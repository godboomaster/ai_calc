package com.smartcalc.app

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smartcalc.app.data.api.OpenAiClient
import com.smartcalc.app.data.template.ExampleDraft
import com.smartcalc.app.data.template.TaskTemplate
import com.smartcalc.app.data.template.TemplateExample
import com.smartcalc.app.data.template.TemplateFormat
import com.smartcalc.app.data.template.TemplateKind
import com.smartcalc.app.data.search.SearchEntry
import com.smartcalc.app.data.format.SolutionFormat
import com.smartcalc.app.data.history.Solution
import com.smartcalc.app.data.settings.ApiSettings
import com.smartcalc.app.data.settings.ConnectionList
import com.smartcalc.app.data.settings.AssistantLock
import com.smartcalc.app.data.session.CalculatorSession
import com.smartcalc.app.data.session.ListPosition
import com.smartcalc.app.data.session.ReadingTarget
import com.smartcalc.app.data.request.RequestStatus
import com.smartcalc.app.data.request.SimilarChoice
import com.smartcalc.app.data.request.SimilarTaskPicker
import com.smartcalc.app.data.request.TaskInput
import com.smartcalc.app.data.search.SimilarTasks
import com.smartcalc.app.data.request.active
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class SolutionViewModel(app: Application) : AndroidViewModel(app) {

    private val applicationState = (app as SmartCalcApplication).dependencies
    val settingsRepo = applicationState.settingsRepository
    private val historyStore = applicationState.historyStore
    private val formatStore = applicationState.formatStore
    private val generation = applicationState.generation
    val request = generation.state
    private val client = OpenAiClient()

    val history = historyStore.items
    val format: StateFlow<SolutionFormat> = formatStore.format
    private val examples = applicationState.exampleWorkspace(viewModelScope)
    val templates: StateFlow<List<TaskTemplate>> = examples.templates
    /** База решённых задач: разделы по типам задач. */
    val library: StateFlow<List<TaskTemplate>> = examples.library

    val searchEntries = examples.searchEntries
    val armedTemplate = examples.armedTemplate

    private val sessionActions = applicationState.solutionSession
    val session = sessionActions.state
    private val restoredSession = sessionActions.restoreFor(history.value)
    // Новый процесс не восстанавливает открытый экран помощника из Android savedInstanceState.
    val sessionInstanceId: String = UUID.randomUUID().toString()

    fun armTemplate(template: TaskTemplate) = examples.armTemplate(template)
    fun disarmTemplate() = examples.disarmTemplate()
    fun items(kind: TemplateKind) = examples.items(kind)
    fun storageError(kind: TemplateKind) = examples.storageError(kind)
    suspend fun saveTemplate(id: String?, name: String, requirements: String, examples: List<ExampleDraft>,
        kind: TemplateKind = TemplateKind.TEMPLATES, expectedSection: TaskTemplate? = null) =
        this.examples.saveTemplate(id, name, requirements, examples, kind, expectedSection)
    suspend fun deleteTemplate(id: String, kind: TemplateKind = TemplateKind.TEMPLATES, expectedSection: TaskTemplate? = null) =
        examples.deleteTemplate(id, kind, expectedSection)
    suspend fun exportTemplates(ids: Collection<String>?, out: OutputStream, format: TemplateFormat,
        kind: TemplateKind = TemplateKind.TEMPLATES) = examples.exportTemplates(ids, out, format, kind)
    suspend fun importTemplates(input: InputStream, kind: TemplateKind = TemplateKind.TEMPLATES, sourceName: String? = null) =
        examples.importTemplates(input, kind, sourceName)
    suspend fun attachToTemplate(templateId: String, examples: List<TemplateExample>) =
        this.examples.attachToTemplate(templateId, examples)
    suspend fun openFound(entry: SearchEntry) = open(examples.resolveFound(entry))

    // Updated before publishing the UI value; null means the stored settings are not loaded yet.
    private var requestSettings: ApiSettings? = null
    val settings = settingsRepo.settings.onEach { requestSettings = it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ApiSettings())

    /** Сохранённые подключения; null — настройки ещё не прочитаны. */
    val connections: StateFlow<ConnectionList?> =
        settingsRepo.connections.stateIn<ConnectionList?>(viewModelScope, SharingStarted.Eagerly, null)

    /** Следующий запрос, в том числе повтор, уйдёт через это подключение. */
    fun selectConnection(id: String) {
        viewModelScope.launch { settingsRepo.selectConnection(id) }
    }
    val swipeToRestore = settingsRepo.swipeToRestore.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // Экстренное скрытие действует до записи в настройки: ✦ не должен мелькнуть, пока она идёт.
    private val concealedNow = MutableStateFlow(false)

    /** Код кнопки ✦ и её состояние; null — настройки ещё не прочитаны. Состояние переживает перезапуск. */
    val assistantLock: StateFlow<AssistantLock?> =
        combine(settingsRepo.assistantLock, concealedNow) { lock, concealed ->
            if (concealed) lock.copy(revealed = false) else lock
        }.stateIn<AssistantLock?>(viewModelScope, SharingStarted.Eagerly, null)

    /** На калькуляторе набрали код и «=»: ✦ появляется или прячется (ответ с дисплея убирает калькулятор). */
    fun toggleAssistant() {
        viewModelScope.launch {
            settingsRepo.toggleAssistantRevealed()
            concealedNow.value = false
        }
    }

    /** Экстренное скрытие: ✦ и ответ пропадают сразу, вернуть помощника можно только кодом. */
    fun concealAssistant() {
        concealedNow.value = true
        // Задача, ждущая выбора примеров, пропадает вместе с помощником.
        similar.take()
        assistantDisplay.hide()
        viewModelScope.launch { settingsRepo.concealAssistant() }
    }

    /** Новый код не прячет кнопку сразу — её скрывает первый набор кода на калькуляторе. */
    suspend fun setAssistantCode(code: String) {
        settingsRepo.setAssistantCode(code)
        concealedNow.value = false
    }

    private val _current = MutableStateFlow(history.value.firstOrNull { it.id == restoredSession.reading.solutionId })
    val current: StateFlow<Solution?> = _current

    private val _solving = MutableStateFlow(request.value?.active == true)
    val solving: StateFlow<Boolean> = _solving

    private val _error = MutableStateFlow(request.value?.error)
    val error: StateFlow<String?> = _error

    /** Подпись того, что сейчас решается: текст задачи, «Задача с фото», «Документ «…»». */
    private val _pending = MutableStateFlow(request.value?.caption)
    val pending: StateFlow<String?> = _pending

    /** Текст последней текстовой задачи — чтобы после ошибки отправить её снова, не набирая заново. */
    val retryText: String? get() = request.value?.retryText

    /** Последний вопрос был дополнением к текущему решению — и повтор после ошибки им же и будет. */
    val retryIsFollowUp: Boolean get() = request.value?.followUp == true

    /**
     * Показывает ли калькулятор на дисплее работу ИИ (ход решения, ответ или ошибку) вместо своих
     * вычислений. Любое нажатие клавиши калькулятора возвращает его к обычному счёту.
     */
    private val assistantDisplay = AssistantDisplay(initialUnread = request.value?.unread ?: restoredSession.unread)
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

    private var submittedTemplateId: String? = null

    init {
        viewModelScope.launch {
            request.collect { record ->
                if (record != null) {
                    _solving.value = record.active
                    _error.value = record.error
                    _pending.value = record.caption
                    val selected = if (record.status == RequestStatus.SUCCEEDED) record.resultSolutionId else record.baseSolutionId
                    _current.value = history.value.firstOrNull { it.id == selected }
                    if (record.unread) assistantDisplay.complete()
                    if (record.status == RequestStatus.SUCCEEDED) examples.disarmIfSelected(submittedTemplateId)
                }
            }
        }
    }

    private fun beginRequest(followUp: Boolean = false, send: (ApiSettings?) -> Unit) {
        val attemptSettings = requestSettings
        if (!followUp) _current.value = null
        rememberSolution(_current.value)
        _error.value = null
        _solving.value = true
        submittedTemplateId = armedTemplate.value?.id
        assistantDisplay.start()
        _shown.value++
        send(attemptSettings)
    }

    fun solveText(problem: String) = solve(TaskInput.Text(problem))
    fun solveImage(jpeg: ByteArray) = solve(TaskInput.Image(jpeg))
    fun solveGalleryImage(uri: Uri) = solve(TaskInput.Gallery(uri))
    fun solveDocument(uri: Uri) = solve(TaskInput.Document(uri))

    /**
     * Похожие задачи библиотеки, которые можно отметить примерами перед отправкой. Пока подбор
     * не закончен или не выбран, задача никуда, кроме короткого запроса подбора, не отправлена.
     */
    private val similar = SimilarTaskPicker(viewModelScope, generation::load, examples::libraryCandidates,
        { settings, prepared, sections -> client.describeTask(settings, prepared.images, prepared.text, sections) })
    val similarChoice: StateFlow<SimilarChoice?> = similar.state

    init {
        // Похожих задач нет — выбирать нечего, задача уходит сразу, без примеров.
        viewModelScope.launch {
            similar.state.collect { if (it?.status == SimilarChoice.Status.READY && it.candidates.isEmpty()) sendSimilar(false) }
        }
    }

    /** Выбранный образец уже задаёт способ решения — тогда подбирать нечего. */
    private fun solve(input: TaskInput) {
        val settings = requestSettings
        val library = examples.library.value.any { section ->
            section.examples.any { !it.isEmpty && it.library?.enabled != false }
        }
        if (settings?.similarTasksEnabled != false && armedTemplate.value == null && library) {
            similar.start(input, settings)
        } else {
            beginRequest { generation.solve(input, armedTemplate.value, it) }
        }
    }

    fun toggleSimilar(candidateId: String) = similar.toggle(candidateId)

    fun retrySimilar() = similar.retry(requestSettings)

    /** Отправка с отмеченными задачами (они уходят одноразовым образцом) или без примеров. */
    fun sendSimilar(withExamples: Boolean) {
        val choice = similar.take() ?: return
        val template = choice.chosen.takeIf { withExamples && it.isNotEmpty() }?.let(SimilarTasks::template)
        beginRequest { generation.solve(choice.input, template, it, choice.prepared) }
    }

    /** Передумали отправлять: текст задачи возвращается в строку ввода, фото просто отпускается. */
    fun cancelSimilar() {
        val input = similar.take()?.input
        if (input is TaskInput.Text) editRequest = EditRequest(input.problem, followUp = false)
    }

    fun askFollowUp(question: String) {
        val base = _current.value ?: return solveText(question)
        val template = followUpTemplate(base)
        beginRequest(followUp = true) { generation.followUp(question, base, template, it) }
    }

    fun retryRequest() {
        val record = request.value?.takeIf { !it.active && it.error != null } ?: return
        beginRequest(record.followUp) { generation.retry(it) }
    }

    fun cancelRequest() = generation.runner.interrupt("Вычисление отменено. Можно повторить запрос.")

    /**
     * Шаблон для дополнения: выбранный сейчас, а если не выбран — тот, по которому решалась задача
     * (если его ещё не удалили). Поправки к ответу по шаблону тоже делаются по шаблону.
     */
    fun followUpTemplate(base: Solution?): TaskTemplate? = examples.followUpTemplate(base?.templateId)

    fun hideFromDisplay() {
        assistantDisplay.hide()
    }

    /** Передумали дописывать дополнение — на дисплей возвращается прежний ответ (или ошибка). */
    fun showOnDisplay() {
        if (_solving.value || _current.value != null || _error.value != null) assistantDisplay.show()
    }

    fun markViewed() {
        assistantDisplay.markViewed()
        if (!display.value.unread) {
            sessionActions.markViewed()
            generation.runner.acknowledge()
        }
    }

    fun saveCalculator(value: CalculatorSession) = sessionActions.saveCalculator(value)

    fun setReadingTarget(target: ReadingTarget, step: Int = session.value.reading.selectedStep) =
        sessionActions.setReadingTarget(current.value, target, step)

    fun saveStepScroll(id: String, revision: Int, index: Int, offset: Int) =
        sessionActions.saveStepScroll(id, revision, index, offset)

    fun saveSolutionScroll(id: String, revision: Int, position: ListPosition) =
        sessionActions.saveSolutionScroll(id, revision, position)

    fun setSolutionBlocksCollapsed(id: String, revision: Int, blocks: Set<String>, collapsed: Boolean) =
        sessionActions.setSolutionBlocksCollapsed(current.value, id, revision, blocks, collapsed)

    fun toggleSolutionBlock(id: String, revision: Int, block: String) =
        sessionActions.toggleSolutionBlock(current.value, id, revision, block)

    fun saveAnswerScroll(id: String, revision: Int, offset: Int) =
        sessionActions.saveAnswerScroll(id, revision, offset)

    private fun rememberSolution(solution: Solution?) = sessionActions.rememberSolution(solution)

    fun open(solution: Solution) {
        generation.runner.dismiss()
        _solving.value = false
        _current.value = solution
        rememberSolution(solution)
        _error.value = null
        assistantDisplay.start()
        _shown.value++
    }

    fun deleteSolution(id: String) {
        viewModelScope.launch {
            val selected = _current.value?.id == id || request.value?.id == id ||
                request.value?.baseSolutionId == id || request.value?.resultSolutionId == id
            if (selected) {
                // Finish any local response commit before deleting, so it cannot resurrect the entry.
                generation.runner.dismiss().join()
                _solving.value = false
                _error.value = null
                _current.value = null
                assistantDisplay.hide()
                rememberSolution(null)
            }
            historyStore.delete(id)
        }
    }

    suspend fun saveFormat(text: String, newSample: ByteArray?, keepSample: Boolean) =
        formatStore.save(text, newSample, keepSample)

    fun clearFormat() {
        viewModelScope.launch { formatStore.clear() }
    }

    suspend fun testConnection(s: ApiSettings): String = client.testConnection(s)
}
