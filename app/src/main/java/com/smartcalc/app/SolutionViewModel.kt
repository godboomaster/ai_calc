package com.smartcalc.app

import android.app.Application
import android.net.Uri
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smartcalc.app.data.DocumentException
import com.smartcalc.app.data.DocumentReader
import com.smartcalc.app.data.ImageUtils
import com.smartcalc.app.data.api.ApiException
import com.smartcalc.app.data.api.OpenAiClient
import com.smartcalc.app.data.api.StepPayload
import com.smartcalc.app.data.api.TemplatePrompt
import com.smartcalc.app.data.template.ExampleDraft
import com.smartcalc.app.data.template.TaskTemplate
import com.smartcalc.app.data.template.TemplateStore
import com.smartcalc.app.data.format.FormatStore
import com.smartcalc.app.data.format.SolutionFormat
import com.smartcalc.app.data.history.FollowUp
import com.smartcalc.app.data.history.HistoryStore
import com.smartcalc.app.data.history.Solution
import com.smartcalc.app.data.history.SolutionStep
import com.smartcalc.app.data.settings.ApiSettings
import com.smartcalc.app.data.settings.SettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.ConnectException
import kotlin.coroutines.cancellation.CancellationException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class SolutionViewModel(app: Application) : AndroidViewModel(app) {

    val settingsRepo = SettingsRepository(app)
    private val historyStore = HistoryStore(app)
    private val formatStore = FormatStore(app)
    private val templateStore = TemplateStore(app)
    private val client = OpenAiClient()

    val history = historyStore.items
    val format: StateFlow<SolutionFormat> = formatStore.format
    val templates: StateFlow<List<TaskTemplate>> = templateStore.items

    /**
     * Шаблон, выбранный для следующей задачи. Действует на одну задачу: после ответа снимается,
     * после ошибки остаётся — повтор пойдёт с ним же.
     */
    private val armedId = MutableStateFlow<String?>(null)
    val armedTemplate: StateFlow<TaskTemplate?> = combine(templateStore.items, armedId) { list, id ->
        list.firstOrNull { it.id == id }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun armTemplate(template: TaskTemplate) {
        armedId.value = template.id
    }

    fun disarmTemplate() {
        armedId.value = null
    }

    suspend fun saveTemplate(id: String?, name: String, requirements: String, examples: List<ExampleDraft>) =
        templateStore.save(id, name, requirements, examples)

    fun deleteTemplate(id: String) {
        viewModelScope.launch { templateStore.delete(id) }
    }
    val settings = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, ApiSettings())

    private val _current = MutableStateFlow<Solution?>(null)
    val current: StateFlow<Solution?> = _current

    private val _solving = MutableStateFlow(false)
    val solving: StateFlow<Boolean> = _solving

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    /** Подпись того, что сейчас решается: текст задачи, «Задача с фото», «Документ «…»». */
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending

    /** Текст последней текстовой задачи — чтобы после ошибки отправить её снова, не набирая заново. */
    var retryText: String? = null
        private set

    /** Последний вопрос был дополнением к текущему решению — и повтор после ошибки им же и будет. */
    var retryIsFollowUp = false
        private set

    /**
     * Показывает ли калькулятор на дисплее работу ИИ (ход решения, ответ или ошибку) вместо своих
     * вычислений. Любое нажатие клавиши калькулятора возвращает его к обычному счёту.
     */
    private val assistantDisplay = AssistantDisplay()
    internal val display = assistantDisplay.state

    /**
     * Растёт, когда на дисплей выводится новая задача (отправлена или открыта из истории): калькулятор
     * по нему возвращает цифры, даже если дисплей и до этого показывал ответ ИИ.
     */
    private val _shown = MutableStateFlow(0)
    val shown: StateFlow<Int> = _shown

    /** Что открыть в строке ввода калькулятора: условие, чтобы решить заново, или пустое дополнение. */
    data class EditRequest(val text: String, val followUp: Boolean)

    /** Кнопки на экране решения («изменить условие», «дополнить»): калькулятор забирает запрос себе. */
    var editRequest by mutableStateOf<EditRequest?>(null)

    private var job: Job? = null

    /** Что уходит модели: текст и/или картинки (фото, страницы PDF). */
    private class Request(val text: String?, val images: List<ByteArray>)

    fun solveText(problem: String) = solveNew(problem, retry = problem) { Request(problem, emptyList()) }

    fun solveImage(jpeg: ByteArray) = solveNew("Задача с фото") { Request(null, listOf(jpeg)) }

    fun solveGalleryImage(uri: Uri) = solveNew("Задача с фото") {
        Request(null, listOf(ImageUtils.compressUri(getApplication(), uri)))
    }

    /** Документ читается уже внутри решения: на дисплее в это время «Читаю документ…». */
    fun solveDocument(uri: Uri) = solveNew("Читаю документ…") {
        val doc = DocumentReader.read(getApplication(), uri)
        _pending.value = "Документ «${doc.name}»"
        Request(doc.text?.let { "Задача из документа «${doc.name}»:\n$it" }, doc.images)
    }

    /**
     * Вопрос после ответа: модель видит исходную задачу, свои ответы и прежние дополнения, а ответ на
     * новый вопрос дописывается в то же решение — его показывает дисплей, его шаги открывают цифры.
     */
    fun askFollowUp(question: String) {
        val base = _current.value ?: return solveText(question)
        val template = followUpTemplate(base)
        launchSolve(question, retry = question, followUp = true) {
            val payload = client.followUp(settings.value, base, question, template?.let { templatePrompt(it) })
            val updated = base.copy(
                followUps = base.followUps + FollowUp(
                    question,
                    payload.answer,
                    payload.steps.map { it.toStep() },
                    template = template?.name,
                ),
                // Шаблон, выбранный для дополнения, дальше идёт со всей цепочкой.
                templateId = template?.id ?: base.templateId,
            )
            historyStore.update(updated)
            if (template != null && armedId.value == template.id) armedId.value = null
            updated
        }
    }

    /**
     * Шаблон для дополнения: выбранный сейчас, а если не выбран — тот, по которому решалась задача
     * (если его ещё не удалили). Поправки к ответу по шаблону тоже делаются по шаблону.
     */
    fun followUpTemplate(base: Solution?): TaskTemplate? =
        armedTemplate.value ?: base?.templateId?.let { id -> templates.value.firstOrNull { it.id == id } }

    fun hideFromDisplay() {
        assistantDisplay.hide()
    }

    /** Передумали дописывать дополнение — на дисплей возвращается прежний ответ (или ошибка). */
    fun showOnDisplay() {
        if (_solving.value || _current.value != null || _error.value != null) assistantDisplay.show()
    }

    fun markViewed() { assistantDisplay.markViewed() }

    private fun StepPayload.toStep() = SolutionStep(title, content, plot, svg)

    private fun solveNew(caption: String, retry: String? = null, load: suspend () -> Request) {
        val template = armedTemplate.value
        launchSolve(caption, retry, followUp = false) {
            val request = load()
            val format = formatStore.format.value
            val sampleBase64 = formatStore.readSample()?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
            val payload = client.solve(
                settings.value,
                request.images.map { Base64.encodeToString(it, Base64.NO_WRAP) },
                request.text,
                formatText = format.text.takeIf { it.isNotBlank() },
                formatSampleBase64 = sampleBase64,
                template = template?.let { templatePrompt(it) },
            )
            // Миниатюра в истории — фото или первая страница документа.
            val imagePath = request.images.firstOrNull()?.let { historyStore.saveImage(it) }
            val solution = Solution(
                problem = payload.problem,
                answer = payload.answer,
                steps = payload.steps.map { it.toStep() },
                imagePath = imagePath,
                format = format.summary.takeIf { format.isSet },
                template = template?.name,
                templateId = template?.id,
            )
            historyStore.add(solution)
            // Шаблон отработал — следующая задача снова обычная.
            if (template != null && armedId.value == template.id) armedId.value = null
            solution
        }
    }

    /** Шаблон для запроса: картинки примеров читаются из файлов в base64. */
    private suspend fun templatePrompt(t: TaskTemplate): TemplatePrompt {
        suspend fun load(paths: List<String>) =
            paths.mapNotNull { templateStore.readImage(it)?.let { b -> Base64.encodeToString(b, Base64.NO_WRAP) } }
        return TemplatePrompt(
            t.name,
            t.requirements,
            t.examples.map { TemplatePrompt.Example(it.condition, load(it.conditionImages), it.solution, load(it.solutionImages)) },
        )
    }

    private fun launchSolve(caption: String, retry: String?, followUp: Boolean, work: suspend () -> Solution) {
        // Новая задача заменяет недорешённую: ответ на старую уже никто не ждёт.
        job?.cancel()
        // Дополнение решается поверх прежнего ответа: если не получится, к нему можно вернуться.
        if (!followUp) _current.value = null
        _error.value = null
        _pending.value = caption
        retryText = retry
        retryIsFollowUp = followUp
        _solving.value = true
        assistantDisplay.start()
        _shown.value++
        job = viewModelScope.launch {
            try {
                _current.value = work()
            } catch (e: CancellationException) {
                throw e
            } catch (e: DocumentException) {
                _error.value = e.message
            } catch (e: ApiException) {
                _error.value = e.message
            } catch (e: UnknownHostException) {
                _error.value = "Нет соединения с интернетом"
            } catch (e: ConnectException) {
                _error.value = "Не удалось подключиться к серверу. Проверьте base URL в настройках."
            } catch (e: SocketTimeoutException) {
                _error.value = "Сервер не отвечает (тайм-аут)"
            } catch (e: Exception) {
                _error.value = "Ошибка: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                // Отменённое решение уже заменено новым — его состояние не трогаем.
                if (isActive) {
                    _solving.value = false
                    // Экран не перехватываем; до просмотра результата остаётся только метка на ✦.
                    assistantDisplay.complete()
                }
            }
        }
    }

    fun open(solution: Solution) {
        job?.cancel()
        _solving.value = false
        _current.value = solution
        _error.value = null
        assistantDisplay.start()
        _shown.value++
    }

    fun deleteSolution(id: String) {
        viewModelScope.launch { historyStore.delete(id) }
    }

    suspend fun saveFormat(text: String, newSample: ByteArray?, keepSample: Boolean) =
        formatStore.save(text, newSample, keepSample)

    fun clearFormat() {
        viewModelScope.launch { formatStore.clear() }
    }

    suspend fun testConnection(s: ApiSettings): String = client.testConnection(s)
}
