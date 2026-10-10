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
import com.smartcalc.app.data.format.FormatStore
import com.smartcalc.app.data.format.SolutionFormat
import com.smartcalc.app.data.history.HistoryStore
import com.smartcalc.app.data.history.Solution
import com.smartcalc.app.data.history.SolutionStep
import com.smartcalc.app.data.settings.ApiSettings
import com.smartcalc.app.data.settings.SettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
    private val client = OpenAiClient()

    val history = historyStore.items
    val format: StateFlow<SolutionFormat> = formatStore.format
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

    /**
     * Показывает ли калькулятор на дисплее работу ИИ (ход решения, ответ или ошибку) вместо своих
     * вычислений. Любое нажатие клавиши калькулятора возвращает его к обычному счёту.
     */
    private val _onDisplay = MutableStateFlow(false)
    val onDisplay: StateFlow<Boolean> = _onDisplay

    /**
     * Растёт, когда на дисплей выводится новая задача (отправлена или открыта из истории): калькулятор
     * по нему возвращает цифры, даже если дисплей и до этого показывал ответ ИИ.
     */
    private val _shown = MutableStateFlow(0)
    val shown: StateFlow<Int> = _shown

    /**
     * Условие, которое нужно поправить и решить заново (кнопка «изменить» у решения): калькулятор
     * забирает его в строку ввода и открывает клавиатуру.
     */
    var editRequest by mutableStateOf<String?>(null)

    private var job: Job? = null

    /** Что уходит модели: текст и/или картинки (фото, страницы PDF). */
    private class Request(val text: String?, val images: List<ByteArray>)

    fun solveText(problem: String) = run(problem, retry = problem) { Request(problem, emptyList()) }

    fun solveImage(jpeg: ByteArray) = run("Задача с фото") { Request(null, listOf(jpeg)) }

    fun solveGalleryImage(uri: Uri) = run("Задача с фото") {
        Request(null, listOf(ImageUtils.compressUri(getApplication(), uri)))
    }

    /** Документ читается уже внутри решения: на дисплее в это время «Читаю документ…». */
    fun solveDocument(uri: Uri) = run("Читаю документ…") {
        val doc = DocumentReader.read(getApplication(), uri)
        _pending.value = "Документ «${doc.name}»"
        Request(doc.text?.let { "Задача из документа «${doc.name}»:\n$it" }, doc.images)
    }

    fun hideFromDisplay() {
        _onDisplay.value = false
    }

    private fun run(caption: String, retry: String? = null, load: suspend () -> Request) {
        // Новая задача заменяет недорешённую: ответ на старую уже никто не ждёт.
        job?.cancel()
        _current.value = null
        _error.value = null
        _pending.value = caption
        retryText = retry
        _solving.value = true
        _onDisplay.value = true
        _shown.value++
        job = viewModelScope.launch {
            try {
                val request = load()
                val format = formatStore.format.value
                val sampleBase64 = formatStore.readSample()?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
                val payload = client.solve(
                    settings.value,
                    request.images.map { Base64.encodeToString(it, Base64.NO_WRAP) },
                    request.text,
                    formatText = format.text.takeIf { it.isNotBlank() },
                    formatSampleBase64 = sampleBase64,
                )
                // Миниатюра в истории — фото или первая страница документа.
                val imagePath = request.images.firstOrNull()?.let { historyStore.saveImage(it) }
                val solution = Solution(
                    problem = payload.problem,
                    answer = payload.answer,
                    steps = payload.steps.map { SolutionStep(it.title, it.content, it.plot, it.svg) },
                    imagePath = imagePath,
                    format = format.summary.takeIf { format.isSet },
                )
                historyStore.add(solution)
                _current.value = solution
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
                    // Ответ (или ошибка) возвращается на дисплей, даже если пока ждали, считали на калькуляторе.
                    _onDisplay.value = true
                }
            }
        }
    }

    fun open(solution: Solution) {
        job?.cancel()
        _solving.value = false
        _current.value = solution
        _error.value = null
        _onDisplay.value = true
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
