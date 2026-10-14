package com.smartcalc.app.data.request

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.smartcalc.app.GenerationService
import com.smartcalc.app.data.DocumentException
import com.smartcalc.app.data.DocumentReader
import com.smartcalc.app.data.ImageUtils
import com.smartcalc.app.data.api.ApiException
import com.smartcalc.app.data.api.ApiConfigurationException
import com.smartcalc.app.data.api.LibraryAgentException
import com.smartcalc.app.data.library.agent.LibraryCatalog
import com.smartcalc.app.data.library.agent.LibraryAgentReport
import com.smartcalc.app.data.api.OpenAiClient
import com.smartcalc.app.data.api.generationTransport
import com.smartcalc.app.data.api.TemplatePrompt
import com.smartcalc.app.data.format.FormatStore
import com.smartcalc.app.data.history.FollowUp
import com.smartcalc.app.data.history.HistoryStore
import com.smartcalc.app.data.history.Solution
import com.smartcalc.app.data.history.SolutionStep
import com.smartcalc.app.data.session.ReadingSession
import com.smartcalc.app.data.session.SessionStore
import com.smartcalc.app.data.session.forSolution
import com.smartcalc.app.data.settings.SettingsRepository
import com.smartcalc.app.data.template.TaskTemplate
import com.smartcalc.app.data.template.TemplateStore
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Application-owned input preparation, execution and recovery. Credentials never enter the journal. */
class GenerationController(
    private val context: Context,
    scope: CoroutineScope,
    private val history: HistoryStore,
    private val sessions: SessionStore,
    private val settings: SettingsRepository,
    private val formats: FormatStore,
    private val templates: TemplateStore,
    private val libraryCatalog: suspend () -> LibraryCatalog,
) {
    private val client = OpenAiClient(generationTransport())
    private val files = RequestFiles(File(context.noBackupFilesDir, "request"))
    private val journal = RequestJournal(File(context.noBackupFilesDir, "request.json"))
    val runner = RequestRunner<Solution>(scope, journal,
        commit = { id, solution ->
            files.saveResult(id, solution)
            publishResult(id, solution)
        },
        recoverResult = { record ->
            files.readResult(record.id)?.takeIf {
                !record.followUp || history.items.value.any { it.id == record.baseSolutionId }
            }?.let { publishResult(record.id, it) }
        },
        describeError = ::requestError,
        cleanup = files::clear,
    )
    val state = runner.state

    init {
        scope.launch {
            state.collect { record ->
                if (record != null) {
                    val id = if (record.status == RequestStatus.SUCCEEDED) record.resultSolutionId else record.baseSolutionId
                    val solution = history.items.value.firstOrNull { it.id == id }
                    sessions.update { saved ->
                        saved.copy(
                            reading = solution?.let { saved.reading.forSolution(it) } ?: ReadingSession(),
                            unread = record.unread,
                        )
                    }
                }
            }
        }
    }

    fun text(problem: String, template: TaskTemplate?) = submit(problem, problem, template = template) {
        PreparedRequest(text = problem)
    }

    fun image(bytes: ByteArray, template: TaskTemplate?) = submit("Задача с фото", template = template) {
        PreparedRequest(images = listOf(encode(bytes)))
    }

    fun gallery(uri: Uri, template: TaskTemplate?) = submit("Задача с фото", template = template) {
        PreparedRequest(images = listOf(encode(ImageUtils.compressUri(context, uri))))
    }

    fun document(uri: Uri, template: TaskTemplate?) = submit("Задача из документа", template = template) {
        val document = DocumentReader.read(context, uri)
        PreparedRequest(text = document.text?.let { "Задача из документа «${document.name}»:\n$it" },
            images = document.images.map(::encode))
    }

    fun followUp(question: String, base: Solution, template: TaskTemplate?) =
        submit(question, question, base, template) { PreparedRequest(text = question, base = base) }

    private fun submit(
        caption: String,
        retry: String? = null,
        base: Solution? = null,
        template: TaskTemplate? = null,
        load: suspend () -> PreparedRequest,
    ) {
        val format = formats.format.value
        val record = RequestRecord(UUID.randomUUID().toString(), RequestStatus.PREPARING, caption,
            retryText = retry, followUp = base != null, baseSolutionId = base?.id)
        runner.start(record, { GenerationService.start(context) }) {
            val input = withContext(Dispatchers.IO) {
                load().copy(
                    template = template?.let { templatePrompt(it) }, templateId = template?.id,
                    formatText = format.text.takeIf { it.isNotBlank() },
                    formatSample = if (base == null) formats.readSample()?.let(::encode) else null,
                    formatSummary = format.summary.takeIf { format.isSet },
                )
            }
            files.saveInput(record.id, input)
            execute(record.id, input)
        }
    }

    fun retry() {
        val previous = state.value?.takeIf { !it.active && it.error != null } ?: return
        // Keep the logical ID so a process death cannot strand an older payload/checkpoint.
        val record = previous.copy(status = RequestStatus.PREPARING, error = null,
            resultSolutionId = null, unread = false,
            startedAt = maxOf(System.currentTimeMillis(), previous.startedAt + 1))
        runner.start(record, { GenerationService.start(context) }) {
            // A server response may already be checkpointed even if saving history failed.
            // Finish local publication instead of sending another paid POST.
            val checkpoint = files.readResult(previous.id)
            if (checkpoint != null) {
                files.readInput(previous.id)?.let { files.saveInput(record.id, it) }
                return@start checkpoint
            }
            val input = files.readInput(previous.id) ?: previous.retryText?.let { text ->
                val base = previous.baseSolutionId?.let { id -> history.items.value.firstOrNull { it.id == id } }
                if (previous.followUp && base == null) throw DocumentException("Исходное решение удалено. Создайте новую задачу.")
                PreparedRequest(text = text, base = base)
            } ?: throw DocumentException("Не удалось сохранить вложение. Выберите фото или документ заново.")
            if (input.base != null && history.items.value.none { it.id == input.base.id }) {
                throw DocumentException("Исходное решение удалено. Создайте новую задачу.")
            }
            files.saveInput(record.id, input)
            execute(record.id, input)
        }
    }

    private suspend fun execute(id: String, input: PreparedRequest): Solution {
        // Read credentials only when sending; they remain in the existing settings repository.
        val config = settings.settings.first()
        val base = input.base
        val tools = withContext(Dispatchers.IO) { libraryToolsForRequest(config.libraryToolsEnabled, input.text.orEmpty(), libraryCatalog) }
        files.saveAgentReport(id, LibraryAgentReport())
        val progress: suspend (LibraryAgentReport) -> Unit = { files.saveAgentReport(id, it) }
        val payload = if (base != null) client.followUp(config, base, input.text.orEmpty(), input.template, tools, progress)
        else client.solve(config, input.images, input.text, input.formatText, input.formatSample, input.template, tools, progress)
        val steps = payload.steps.map { SolutionStep(it.title, it.content, it.plot, it.svg) }
        return if (base != null) {
            if (history.items.value.none { it.id == base.id }) throw DocumentException("Исходное решение удалено.")
            base.copy(followUps = base.followUps + FollowUp(input.text.orEmpty(), payload.answer, steps,
                template = input.template?.name, agentReport = payload.agentReport), templateId = input.templateId ?: base.templateId)
        } else Solution(id = id, problem = payload.problem, answer = payload.answer, steps = steps,
            format = input.formatSummary, template = input.template?.name, templateId = input.templateId, agentReport = payload.agentReport)
    }

    private suspend fun publishResult(id: String, solution: Solution): String {
        val ready = if (solution.imagePath == null && solution.followUps.isEmpty()) {
            val image = files.readInput(id)?.images?.firstOrNull()
            if (image == null) solution else solution.copy(imagePath =
                history.saveImage(Base64.decode(image, Base64.NO_WRAP), stableId = solution.id))
        } else solution
        try {
            if (!history.update(ready, requireExisting = ready.followUps.isNotEmpty())) {
                throw DocumentException("Исходное решение удалено. Создайте новую задачу.")
            }
        } catch (error: Exception) {
            // Input + response remain checkpointed, so an unreferenced thumbnail is reproducible.
            if (solution.imagePath == null) ready.imagePath?.let { history.removeImageIfUnreferenced(it) }
            throw error
        }
        return ready.id
    }

    private suspend fun templatePrompt(template: TaskTemplate): TemplatePrompt {
        suspend fun load(paths: List<String>) = paths.mapNotNull { templates.readImage(it)?.let(::encode) }
        return TemplatePrompt(template.name, template.requirements, template.examples.map {
            TemplatePrompt.Example(it.condition, load(it.conditionImages), exampleSolutionText(it), load(it.solutionImages))
        })
    }

    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
}

internal fun exampleSolutionText(example: com.smartcalc.app.data.template.TemplateExample): String =
    listOf(example.solution, example.answer.takeIf { it.isNotBlank() }?.let { "Ответ: $it" }.orEmpty())
        .filter { it.isNotBlank() }.joinToString("\n\n")

internal fun requestError(error: Throwable): String = when (error) {
    is DocumentException -> error.message ?: "Не удалось прочитать вложение"
    is ApiConfigurationException -> error.message ?: "Проверьте настройки API."
    is LibraryAgentException -> error.message ?: "Не удалось выполнить поиск агентом. Проверьте поддержку инструментов API."
    is ApiException -> when (error.httpCode) {
        401, 403 -> "Сервер отклонил доступ. Проверьте API-ключ и выбранную модель в настройках."
        429 -> "Лимит запросов исчерпан. Повторите позже."
        null -> "Не удалось получить корректный ответ модели. Проверьте настройки и повторите запрос."
        else -> "Ошибка сервера (HTTP ${error.httpCode}). Можно повторить запрос."
    }
    is UnknownHostException -> "Нет соединения с интернетом"
    is ConnectException -> "Не удалось подключиться к серверу. Проверьте base URL в настройках."
    is SocketTimeoutException -> "Сервер не отвечает (тайм-аут). Можно повторить запрос."
    else -> "Не удалось выполнить запрос. Проверьте соединение и свободное место."
}
