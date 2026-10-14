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
import com.smartcalc.app.data.template.ImportResult
import com.smartcalc.app.data.template.TaskTemplate
import com.smartcalc.app.data.template.TemplateExample
import com.smartcalc.app.data.template.TemplateFormat
import com.smartcalc.app.data.template.TemplateKind
import com.smartcalc.app.data.search.FoundSolution
import com.smartcalc.app.data.search.SearchEntry
import com.smartcalc.app.data.search.TaskSearch
import com.smartcalc.app.data.format.SolutionFormat
import com.smartcalc.app.data.history.Solution
import com.smartcalc.app.data.settings.ApiSettings
import com.smartcalc.app.data.settings.ConnectionList
import com.smartcalc.app.data.settings.AssistantLock
import com.smartcalc.app.data.session.CalculatorSession
import com.smartcalc.app.data.session.ListPosition
import com.smartcalc.app.data.session.ReadingSession
import com.smartcalc.app.data.session.ReadingTarget
import com.smartcalc.app.data.session.restoredFor
import com.smartcalc.app.data.session.blockIds
import com.smartcalc.app.data.session.forSolution
import com.smartcalc.app.data.request.RequestStatus
import com.smartcalc.app.data.request.active
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class SolutionViewModel(app: Application) : AndroidViewModel(app) {

    private val applicationState = app as SmartCalcApplication
    val settingsRepo = applicationState.settingsRepository
    private val historyStore = applicationState.historyStore
    private val formatStore = applicationState.formatStore
    private val templateStore = applicationState.templateStore
    private val libraryStore = applicationState.libraryStore
    private val generation = applicationState.generation
    val request = generation.state
    private val client = OpenAiClient()

    val history = historyStore.items
    val format: StateFlow<SolutionFormat> = formatStore.format
    val templates: StateFlow<List<TaskTemplate>> = templateStore.items
    /** База решённых задач: разделы по типам задач. */
    val library: StateFlow<List<TaskTemplate>> = libraryStore.items

    /** Всё, по чему ищет поиск; считается, только пока открыт экран поиска. null — ещё не собрано. */
    val searchEntries: StateFlow<List<SearchEntry>?> =
        combine(libraryStore.items, templateStore.items, historyStore.items, applicationState.librarySources.state) { base, templates, solved, sources ->
            TaskSearch.entries(base, templates, solved, sources)
        }
            // Building fingerprints reads local example images; keep that work off the CPU pool.
            .flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val sessionStore = applicationState.sessionStore
    val session = sessionStore.state
    private val restoredSession = session.value.restoredFor(history.value)
    // Новый процесс не восстанавливает открытый экран помощника из Android savedInstanceState.
    val sessionInstanceId: String = UUID.randomUUID().toString()

    init {
        sessionStore.update { restoredSession }
    }

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

    private fun store(kind: TemplateKind) = when (kind) {
        TemplateKind.TEMPLATES -> templateStore
        TemplateKind.LIBRARY -> libraryStore
    }

    fun items(kind: TemplateKind): StateFlow<List<TaskTemplate>> = store(kind).items
    fun storageError(kind: TemplateKind): StateFlow<String?> = store(kind).loadError

    suspend fun saveTemplate(
        id: String?,
        name: String,
        requirements: String,
        examples: List<ExampleDraft>,
        kind: TemplateKind = TemplateKind.TEMPLATES,
        expectedSection: TaskTemplate? = null,
    ) = store(kind).save(id, name, requirements, examples, expectedSection)

    suspend fun deleteTemplate(id: String, kind: TemplateKind = TemplateKind.TEMPLATES, expectedSection: TaskTemplate? = null) =
        store(kind).delete(id, expectedSection)

    /** Файл шаблонов (или разделов базы) с [ids] (null — все) — для сохранения или отправки. */
    suspend fun exportTemplates(
        ids: Collection<String>?,
        out: OutputStream,
        format: TemplateFormat,
        kind: TemplateKind = TemplateKind.TEMPLATES,
    ): Int = store(kind).export(ids, out, format)

    suspend fun importTemplates(input: InputStream, kind: TemplateKind = TemplateKind.TEMPLATES, sourceName: String? = null): ImportResult =
        store(kind).import(input, sourceName)

    /** Задачи из базы дописываются примерами к шаблону: с ним они уйдут ИИ. Возвращает, сколько добавлено. */
    suspend fun attachToTemplate(templateId: String, examples: List<TemplateExample>): Int =
        templateStore.attach(templateId, examples)

    /**
     * Найденная задача — на дисплей калькулятора, как решение из истории. Задача из базы или шаблона
     * сохраняется в историю: к ней можно задать дополнение ИИ, и она переживёт перезапуск. Открытая
     * повторно, она берётся из истории — вместе с дополнениями.
     */
    suspend fun openFound(entry: SearchEntry) {
        entry.history?.let { return open(it) }
        val solution = FoundSolution.of(entry)
        history.value.firstOrNull { it.id == solution.id }?.let { return open(it) }
        // HistoryStore returns the localized, durable snapshot. Do not expose external library paths
        // or show a solution whose metadata write has failed.
        open(historyStore.add(solution))
    }
    val settings = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, ApiSettings())

    /** Сохранённые подключения; null — настройки ещё не прочитаны. */
    val connections: StateFlow<ConnectionList?> =
        settingsRepo.connections.stateIn<ConnectionList?>(viewModelScope, SharingStarted.Eagerly, null)

    /** Следующий запрос, в том числе повтор, уйдёт через это подключение. */
    fun selectConnection(id: String) {
        viewModelScope.launch { settingsRepo.selectConnection(id) }
    }
    val swipeToRestore = settingsRepo.swipeToRestore.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Код кнопки ✦ и её состояние; null — настройки ещё не прочитаны. Состояние переживает перезапуск. */
    val assistantLock: StateFlow<AssistantLock?> =
        settingsRepo.assistantLock.stateIn<AssistantLock?>(viewModelScope, SharingStarted.Eagerly, null)

    /** На калькуляторе набрали код и «=»: ✦ появляется или прячется (ответ с дисплея убирает калькулятор). */
    fun toggleAssistant() {
        viewModelScope.launch { settingsRepo.toggleAssistantRevealed() }
    }

    /** Новый код не прячет кнопку сразу — её скрывает первый набор кода на калькуляторе. */
    suspend fun setAssistantCode(code: String) = settingsRepo.setAssistantCode(code)

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
                    if (record.status == RequestStatus.SUCCEEDED && armedId.value == submittedTemplateId) {
                        armedId.value = null
                    }
                }
            }
        }
    }

    private fun beginRequest(followUp: Boolean = false, send: () -> Unit) {
        if (!followUp) _current.value = null
        rememberSolution(_current.value)
        _error.value = null
        _solving.value = true
        submittedTemplateId = armedTemplate.value?.id
        assistantDisplay.start()
        _shown.value++
        send()
    }

    fun solveText(problem: String) = beginRequest { generation.text(problem, armedTemplate.value) }
    fun solveImage(jpeg: ByteArray) = beginRequest { generation.image(jpeg, armedTemplate.value) }
    fun solveGalleryImage(uri: Uri) = beginRequest { generation.gallery(uri, armedTemplate.value) }
    fun solveDocument(uri: Uri) = beginRequest { generation.document(uri, armedTemplate.value) }

    fun askFollowUp(question: String) {
        val base = _current.value ?: return solveText(question)
        val template = followUpTemplate(base)
        beginRequest(followUp = true) { generation.followUp(question, base, template) }
    }

    fun retryRequest() {
        val record = request.value?.takeIf { !it.active && it.error != null } ?: return
        beginRequest(record.followUp) { generation.retry() }
    }

    fun cancelRequest() = generation.runner.interrupt("Вычисление отменено. Можно повторить запрос.")

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

    fun markViewed() {
        assistantDisplay.markViewed()
        if (!display.value.unread) {
            sessionStore.update { it.copy(unread = false) }
            generation.runner.acknowledge()
        }
    }

    fun saveCalculator(value: CalculatorSession) {
        sessionStore.update { saved ->
            val attached = value.followUpSolutionId != null && value.followUpSolutionId == saved.reading.solutionId
            saved.copy(calculator = if (attached) value else value.copy(followUpDraft = "", followUpSolutionId = null))
        }
    }

    fun setReadingTarget(target: ReadingTarget, step: Int = session.value.reading.selectedStep) {
        val solution = current.value ?: return
        updateReading(solution.id, solution.followUps.size) {
            it.copy(target = target, selectedStep = step.coerceIn(0, solution.latestSteps.size))
        }
    }

    fun saveStepScroll(id: String, revision: Int, index: Int, offset: Int) = updateReading(id, revision) {
        it.copy(stepScroll = it.stepScroll + (index to offset.coerceAtLeast(0)))
    }

    fun saveSolutionScroll(id: String, revision: Int, position: ListPosition) = updateReading(id, revision) {
        it.copy(solutionScroll = position)
    }

    fun setSolutionBlocksCollapsed(id: String, revision: Int, blocks: Set<String>, collapsed: Boolean) {
        val solution = current.value?.takeIf { it.id == id && it.followUps.size == revision } ?: return
        val validBlocks = blocks.intersect(solution.blockIds())
        updateReading(id, revision) {
            it.copy(collapsedBlocks = if (collapsed) it.collapsedBlocks + validBlocks else it.collapsedBlocks - validBlocks)
        }
    }

    fun toggleSolutionBlock(id: String, revision: Int, block: String) {
        val solution = current.value?.takeIf { it.id == id && it.followUps.size == revision } ?: return
        if (block !in solution.blockIds()) return
        updateReading(id, revision) {
            it.copy(collapsedBlocks = if (block in it.collapsedBlocks) it.collapsedBlocks - block else it.collapsedBlocks + block)
        }
    }

    fun saveAnswerScroll(id: String, revision: Int, offset: Int) = updateReading(id, revision) {
        it.copy(answerScroll = offset.coerceAtLeast(0))
    }

    private fun updateReading(id: String, revision: Int, update: (ReadingSession) -> ReadingSession) {
        sessionStore.update { saved ->
            if (saved.reading.solutionId == id && saved.reading.followUpCount == revision) {
                saved.copy(reading = update(saved.reading))
            } else saved // Уходящий экран не должен перезаписывать состояние новой задачи.
        }
    }

    private fun rememberSolution(solution: Solution?) {
        sessionStore.update { saved ->
            saved.copy(
                reading = solution?.let { saved.reading.forSolution(it, resetPosition = true) } ?: ReadingSession(),
                calculator = if (saved.calculator.followUpSolutionId == solution?.id) saved.calculator
                    else saved.calculator.copy(followUpDraft = "", followUpSolutionId = null),
                unread = false,
            )
        }
    }

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
