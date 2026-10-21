package com.smartcalc.app.data.request

import com.smartcalc.app.data.api.ApiConfigurationException
import com.smartcalc.app.data.search.LibraryCandidate
import com.smartcalc.app.data.search.SimilarTasks
import com.smartcalc.app.data.search.TaskDescription
import com.smartcalc.app.data.settings.ApiSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * Новая задача ждёт, пока пользователь отметит похожие задачи библиотеки. [prepared] — прочитанная задача:
 * при отправке её не читают второй раз. [recognized] — условие, как его прочитала модель.
 */
data class SimilarChoice(
    val id: Long,
    val input: TaskInput,
    val status: Status = Status.SEARCHING,
    val prepared: PreparedRequest? = null,
    val recognized: String = "",
    val candidates: List<LibraryCandidate> = emptyList(),
    val selected: Set<String> = emptySet(),
    /** Модель не ответила, и задачи подобраны только по словам текста. */
    val approximate: Boolean = false,
    val error: String? = null,
) {
    enum class Status { SEARCHING, READY, FAILED }

    val chosen: List<LibraryCandidate> get() = candidates.filter { it.id in selected }
}

/**
 * Подбор похожих задач до отправки. Живёт, пока открыт экран выбора: после гибели процесса задачу
 * отправляют заново. Сам запрос решения начинает тот, кто забирает выбор через [take].
 */
class SimilarTaskPicker(
    private val scope: CoroutineScope,
    private val load: suspend (TaskInput) -> PreparedRequest,
    private val library: suspend () -> List<LibraryCandidate>,
    private val describe: suspend (ApiSettings, PreparedRequest, List<String>) -> TaskDescription,
    private val describeError: (Throwable) -> String = ::requestError,
    /** Сравнение со всей библиотекой — не на главном потоке. */
    private val compute: CoroutineContext = Dispatchers.Default,
) {
    private val mutable = MutableStateFlow<SimilarChoice?>(null)
    val state: StateFlow<SimilarChoice?> = mutable.asStateFlow()
    private var job: Job? = null
    private var counter = 0L

    fun start(input: TaskInput, settings: ApiSettings?) {
        job?.cancel()
        val choice = SimilarChoice(++counter, input)
        mutable.value = choice
        job = scope.launch { search(choice.id, input, settings) }
    }

    /** Повтор после ошибки: та же задача, тот же экран. */
    fun retry(settings: ApiSettings?) {
        val current = state.value?.takeIf { it.status == SimilarChoice.Status.FAILED } ?: return
        job?.cancel()
        mutable.value = SimilarChoice(current.id, current.input, prepared = current.prepared)
        job = scope.launch { search(current.id, current.input, settings) }
    }

    fun toggle(candidateId: String) = mutable.update { choice ->
        choice?.copy(selected = if (candidateId in choice.selected) choice.selected - candidateId else choice.selected + candidateId)
    }

    /** Забирает задачу — для отправки или отмены; подбор, если ещё идёт, останавливается. */
    fun take(): SimilarChoice? {
        job?.cancel()
        return mutable.value.also { mutable.value = null }
    }

    private suspend fun search(id: Long, input: TaskInput, settings: ApiSettings?) {
        try {
            val prepared = state.value?.prepared ?: load(input)
            update(id) { it.copy(prepared = prepared) }
            val candidates = library()
            if (candidates.isEmpty()) {
                update(id) { it.copy(status = SimilarChoice.Status.READY) }
                return
            }
            var approximate = false
            val description = try {
                describe(settings ?: throw ApiConfigurationException("Настройки ещё загружаются. Повторите запрос."),
                    prepared, SimilarTasks.sectionNames(candidates))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Текст задачи есть и без модели: подбор по его словам лучше, чем никакого.
                val text = prepared.text?.takeIf { it.isNotBlank() } ?: throw e
                approximate = true
                TaskDescription(condition = text)
            }
            val ranked = withContext(compute) { SimilarTasks.rank(candidates, description) }
            update(id) {
                it.copy(status = SimilarChoice.Status.READY, recognized = description.condition,
                    candidates = ranked, approximate = approximate)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            update(id) { it.copy(status = SimilarChoice.Status.FAILED, error = describeError(e)) }
        }
    }

    private fun update(id: Long, change: (SimilarChoice) -> SimilarChoice) = mutable.update { choice ->
        if (choice?.id == id) change(choice) else choice
    }
}
