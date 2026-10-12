package com.smartcalc.app.data.library.importer

import com.smartcalc.app.data.api.DocumentRecognizer
import com.smartcalc.app.data.settings.ApiSettings
import com.smartcalc.app.data.library.source.*
import com.smartcalc.app.data.library.extract.*
import com.smartcalc.app.data.template.TaskTemplate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class LibraryImportController(
    private val sources: LibrarySourceStore, private val journal: LibraryImportJournal,
    private val extractor: DocumentExtractor, private val recognizer: DocumentRecognizer,
    private val publisher: ImportPublisher, private val settings: suspend () -> ApiSettings,
    private val scope: CoroutineScope, private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val solutionActive: StateFlow<Boolean> = MutableStateFlow(false),
    private val startForeground: () -> Unit = {}, private val stopForeground: () -> Unit = {},
    private val sectionSnapshot: (String) -> TaskTemplate? = { null },
    private val activeTimeoutMillis: Long = 8 * 60 * 1000,
) {
    init { require(activeTimeoutMillis in 1..8 * 60 * 1000L) }
    val jobs = journal.jobs
    private val mutableForeground = MutableStateFlow(false)
    val foregroundActive: StateFlow<Boolean> = mutableForeground
    private val work = Mutex()
    private val actions = Mutex()
    private val running = ConcurrentHashMap<String, Job>()
    private fun current(id: String) = jobs.value.jobs.first { it.id == id }
    suspend fun create(sourceIds: List<String>, selections: Map<String, PageSelection>): String {
        check(sources.state.value.error == null && journal.jobs.value.error == null) { "Хранилище недоступно" }
        require(sourceIds.isNotEmpty() && sourceIds.distinct().size == sourceIds.size && selections.keys == sourceIds.toSet())
        require(selections.values.sumOf { it.ordinals.size } <= 100) { "Выберите не больше 100 страниц или фрагментов" }
        sourceIds.forEach { id ->
            val source = sources.state.value.sources.first { it.id == id }
            val count = extractor.inspect(source)
            require(selections.getValue(id).ordinals.all { it <= count }) { "Выбранный фрагмент отсутствует" }
        }
        val id = UUID.randomUUID().toString()
        journal.save(ImportJob(id, sourceIds, selections)); return id
    }
    suspend fun process(jobId: String) {
        actions.withLock {
            running[jobId]?.takeIf { it.isActive }?.let { previous ->
                check(!current(jobId).waitingForSolution && current(jobId).status !in setOf(ImportStatus.EXTRACTING, ImportStatus.RECOGNIZING, ImportStatus.PUBLISHING)) { "Этот импорт уже выполняется" }
                previous.join()
            }
            val previousJob = current(jobId)
            val discardCheckpoint = previousJob.status == ImportStatus.FAILED || withContext(Dispatchers.IO) {
                journal.response(previousJob)?.let { it.code !in 200..299 } == true
            }
            val job = journal.update(jobId) { it.copy(attempt = it.attempt + 1, postsUsed = 0, status = ImportStatus.EXTRACTING, error = null, waitingForSolution = false,
                activeBatch = if (discardCheckpoint) null else it.activeBatch,
                inFlightPost = if (discardCheckpoint) null else it.inFlightPost) }
            val task = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                try { work.withLock { execute(jobId, job.attempt) } }
                catch (error: CancellationException) { throw error }
                catch (_: Exception) { journal.reportStorageFailure() }
            }
            running[jobId] = task
            task.invokeOnCompletion { running.remove(jobId, task) }; task.start()
        }
    }
    private suspend fun change(id: String, attempt: Long, transform: (ImportJob) -> ImportJob): ImportJob = journal.update(id) {
        if (it.attempt != attempt || it.status == ImportStatus.CANCELLED) throw CancellationException("Attempt superseded")
        transform(it)
    }
    private suspend fun applyResult(id: String, attempt: Long, batch: RecognitionBatch, result: RecognitionResult) = change(id, attempt) { job ->
        val existing = job.drafts.mapTo(mutableSetOf()) { it.id }
        job.copy(drafts = job.drafts + result.drafts.filter { existing.add(it.id) },
            processedSegmentIds = job.processedSegmentIds + batch.segments.map { it.ref.segmentId },
            carry = result.carry, activeBatch = null, inFlightPost = null)
    }
    private suspend fun savedResponse(id: String, attempt: Long) {
        val job = current(id)
        val response = journal.response(job) ?: return
        val batch = checkNotNull(job.activeBatch).let { it.copy(carry = resolvedCarry(job, it.carry)) }
        require(response.code in 200..299) { "Сохранённая ошибка требует явного повтора обработки" }
        applyResult(id, attempt, batch, recognizer.parseSaved(batch, response))
    }
    private fun resolvedCarry(job: ImportJob, carry: RecognitionCarry? = job.carry): RecognitionCarry? = carry?.let {
        val ids = it.refs.map { ref -> ref.segmentId }.toSet()
        it.copy(segments = (it.segments + job.segments).filter { segment -> segment.ref.segmentId in ids }
            .distinctBy { segment -> segment.ref.segmentId }.map { segment -> segment.copy(text = "") })
    }
    private suspend fun execute(id: String, attempt: Long) {
        var foreground = false
        var deadline = clock() + activeTimeoutMillis
        suspend fun active(phase: ImportStatus) {
            if (solutionActive.value) {
                if (foreground) { mutableForeground.value = false; stopForeground(); foreground = false }
                val waitingAt = clock()
                change(id, attempt) { it.copy(status = ImportStatus.PAUSED, waitingForSolution = true, error = "Ожидает завершения текущего решения") }
                solutionActive.first { !it }; deadline += clock() - waitingAt
            }
            currentCoroutineContext().ensureActive()
            change(id, attempt) { it.copy(status = phase, waitingForSolution = false, error = null) }
            if (!foreground) { foreground = true; mutableForeground.value = true; startForeground() }
            if (clock() >= deadline) throw BudgetReached()
        }
        try {
            savedResponse(id, attempt)
            for (sourceId in current(id).sourceIds) {
                active(ImportStatus.EXTRACTING)
                val source = sources.state.value.sources.first { it.id == sourceId }
                check(source.status != SourceStatus.DELETED) { "Оригинал удалён; загрузите его снова" }
                val existing = current(id).segments.filter { it.ref.sourceId == sourceId }.map { it.ref.ordinal }.toSet()
                val missing = current(id).selections.getValue(sourceId).ordinals.filterNot { it in existing }
                if (missing.isNotEmpty()) extractor.extract(source, PageSelection(missing)) { segment ->
                    currentCoroutineContext().ensureActive()
                    if (clock() >= deadline) throw BudgetReached()
                    change(id, attempt) { it.copy(segments = it.segments.filterNot { old -> old.ref.segmentId == segment.ref.segmentId } + segment, status = ImportStatus.EXTRACTING) }
                }
            }
            var job = current(id)
            // Structured local documents are useful even without model credentials.
            if (job.processedSegmentIds.isEmpty() && job.carry == null) {
                val parsed = StructuredTaskParser.parse(job.segments)
                if (parsed.tasks.isNotEmpty()) {
                    val drafts = parsed.tasks.mapIndexed { index, task ->
                        val unmatched = if (index == 0) job.segments.filter { it.ref.segmentId in parsed.unresolvedSegmentIds } else emptyList()
                        val warnings = task.warnings + if (unmatched.isNotEmpty()) listOf(ExtractionWarning("unassigned", "В документе есть нераспределённые фрагменты. Сверьте границы с оригиналом.")) else emptyList()
                        ImportDraft(UUID.nameUUIDFromBytes("$id:local:$index".toByteArray()).toString(), condition = task.condition,
                            solution = task.solution, answer = task.answer, refs = (task.refs + unmatched.map { it.ref }).distinct(),
                            conditionImages = task.conditionImages, solutionImages = task.solutionImages, warnings = warnings)
                    }
                    change(id, attempt) { it.copy(drafts = drafts, processedSegmentIds = it.segments.map { segment -> segment.ref.segmentId }.toSet(), status = ImportStatus.REVIEW_REQUIRED, error = null) }
                    return
                }
            }
            while (true) {
                job = current(id)
                val pending = job.segments.filterNot { it.ref.segmentId in job.processedSegmentIds }
                if (pending.isEmpty()) {
                    if (job.carry != null) {
                        val carry = resolvedCarry(job)!!
                        val draft = ImportDraft(UUID.nameUUIDFromBytes("$id:unfinished".toByteArray()).toString(), condition = carry.text,
                            solution = "", refs = carry.refs, conditionImages = carry.segments.flatMap { it.imagePaths }.distinct(),
                            warnings = carry.segments.flatMap { it.warnings } + ExtractionWarning("unfinished", "Задача продолжается за выбранным диапазоном. Дополните или разделите её вручную."))
                        change(id, attempt) { it.copy(drafts = it.drafts.filterNot { old -> old.id == draft.id } + draft, carry = null) }
                    }
                    change(id, attempt) { it.copy(status = ImportStatus.REVIEW_REQUIRED, error = null) }; return
                }
                active(ImportStatus.RECOGNIZING)
                val batch = RecognitionBatcher.next(pending, resolvedCarry(job))!!
                change(id, attempt) { it.copy(activeBatch = batch, status = ImportStatus.RECOGNIZING, error = null) }
                val result = withTimeout((deadline - clock()).coerceAtLeast(1)) { recognizer.recognize(settings(), batch, beforePost = {
                    if (clock() >= deadline) throw BudgetReached()
                    change(id, attempt) {
                        if (it.postsUsed >= 10) throw BudgetReached()
                        it.copy(postsUsed = it.postsUsed + 1, inFlightPost = PostAttempt(it.postsUsed + 1, batch.segments.map { segment -> segment.ref.segmentId }))
                    }
                }, afterPost = { response ->
                    currentCoroutineContext().ensureActive()
                    journal.checkpointResponse(id, response, attempt)
                }) }
                applyResult(id, attempt, batch, result)
            }
        } catch (_: RecognitionBatcher.ManualBatchRequired) {
            val job = current(id)
            val next = job.segments.first { it.ref.segmentId !in job.processedSegmentIds }
            val carry = resolvedCarry(job)
            val context = carry?.segments.orEmpty() + next
            val draft = ImportDraft(UUID.nameUUIDFromBytes("$id:manual:${next.ref.segmentId}".toByteArray()).toString(),
                condition = listOfNotNull(carry?.text, next.text).filter { it.isNotBlank() }.joinToString("\n\n"), solution = "",
                refs = (carry?.refs.orEmpty() + next.ref).distinct(), conditionImages = context.flatMap { it.imagePaths }.distinct(),
                warnings = context.flatMap { it.warnings }.distinct() + ExtractionWarning("manual_boundary", "Продолжение превышает пакет распознавания. Полный текст и изображения сохранены; разделите и проверьте задачу вручную."))
            change(id, attempt) { it.copy(drafts = it.drafts.filterNot { old -> old.id == draft.id } + draft,
                processedSegmentIds = it.processedSegmentIds + next.ref.segmentId, carry = null, activeBatch = null, inFlightPost = null,
                status = ImportStatus.REVIEW_REQUIRED, error = "Нужна ручная проверка длинного продолжения. Остаток можно обработать кнопкой «Продолжить».") }
        } catch (_: BudgetReached) {
            change(id, attempt) { it.copy(status = ImportStatus.PAUSED, error = "Лимит этой партии исчерпан. Сохранённый разбор доступен; «Продолжить» запускает следующую партию.") }
        } catch (_: TimeoutCancellationException) {
            change(id, attempt) { it.copy(status = ImportStatus.PAUSED, error = "Время партии истекло. Неизвестный исход запроса не повторяется автоматически; сохранённый разбор доступен.") }
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                if (journal.jobs.value.error == null && current(id).attempt == attempt && current(id).status != ImportStatus.CANCELLED)
                    journal.update(id) { it.copy(status = ImportStatus.INTERRUPTED, error = "Обработка прервана; автоматического повтора запроса нет.") }
            }
            throw error
        } catch (error: Exception) {
            if (journal.jobs.value.error != null) return
            val detail = error.message.orEmpty().take(400)
            change(id, attempt) { it.copy(status = ImportStatus.FAILED,
                error = if (detail.any { ch -> ch in 'А'..'я' }) detail else "Не удалось обработать документ. Оригинал и черновики сохранены.") }
        } finally { if (foreground) { mutableForeground.value = false; stopForeground() } }
    }
    suspend fun recover() {
        journal.recover()
        if (journal.jobs.value.error != null) return
        for (job in journal.jobs.value.jobs.filter { it.status != ImportStatus.CANCELLED }) {
            val receipt = try { job.publication?.let { publisher.receipt(it.commitId) } }
                catch (_: Exception) {
                    journal.update(job.id) { it.copy(status = ImportStatus.INTERRUPTED, error = "Библиотека недоступна; результат публикации не проверен. Данные сохранены.") }
                    continue
                }
            if (receipt != null) {
                journal.update(job.id) { it.copy(status = ImportStatus.READY, receipt = receipt,
                    publishedDraftIds = it.publishedDraftIds + it.publication!!.items.map { item -> item.draftId }, error = null) }
            } else if (job.inFlightPost?.responseRelativePath != null) {
                try {
                    savedResponse(job.id, job.attempt)
                    journal.update(job.id) { it.copy(status = if (it.segments.all { segment -> segment.ref.segmentId in it.processedSegmentIds }) ImportStatus.REVIEW_REQUIRED else ImportStatus.PAUSED, error = null) }
                } catch (_: Exception) { journal.update(job.id) { it.copy(status = ImportStatus.FAILED, error = "Сохранённый ответ не разобран. Можно исправить черновики или повторить обработку вручную.") } }
            } else if (job.status == ImportStatus.PUBLISHING) {
                journal.update(job.id) { it.copy(status = ImportStatus.REVIEW_REQUIRED, error = "Публикация не завершена. Черновики сохранены; подтвердите добавление снова.") }
            }
        }
    }
    suspend fun cancel(jobId: String) {
        actions.withLock {
            journal.update(jobId) { it.copy(attempt = it.attempt + 1, status = ImportStatus.CANCELLED, error = null, waitingForSolution = false) }
            running[jobId]?.cancel()
        }
    }
    suspend fun interruptActive(message: String) {
        for ((id, task) in running) {
            journal.update(id) { current ->
                if (current.status in setOf(ImportStatus.EXTRACTING, ImportStatus.RECOGNIZING) || current.waitingForSolution)
                    current.copy(attempt = current.attempt + 1, status = ImportStatus.INTERRUPTED, waitingForSolution = false, error = message)
                else current
            }
            task.cancel()
        }
    }
    private suspend fun awaitTerminal(jobId: String) {
        running[jobId]?.takeIf { it.isActive }?.let { task ->
            check(!current(jobId).waitingForSolution && current(jobId).status !in setOf(ImportStatus.EXTRACTING, ImportStatus.RECOGNIZING, ImportStatus.PUBLISHING)) { "Дождитесь завершения обработки" }
            task.join()
        }
    }
    suspend fun saveDraft(jobId: String, draft: ImportDraft, expectedRevision: Int): ImportJob {
        awaitTerminal(jobId)
        return journal.update(jobId) { job ->
        val previous = job.drafts.firstOrNull { it.id == draft.id }
        check(previous?.revision == expectedRevision || previous == null && expectedRevision == 0) { "Черновик изменился. Откройте актуальную версию." }
        check(draft.id !in job.publishedDraftIds) { "Эта задача уже добавлена; измените её в библиотеке" }
        require(draft.refs.all { it.sourceId in job.sourceIds && it.ordinal in job.selections.getValue(it.sourceId).ordinals })
        job.copy(drafts = job.drafts.filterNot { it.id == draft.id } + draft.copy(revision = (previous?.revision ?: 0) + 1), status = ImportStatus.REVIEW_REQUIRED)
        }
    }
    suspend fun replaceDrafts(jobId: String, oldIds: List<String>, expectedRevisions: Map<String, Int>, replacements: List<ImportDraft>): ImportJob {
        awaitTerminal(jobId)
        return journal.update(jobId) { job ->
        check(oldIds.none { it in job.publishedDraftIds })
        require(oldIds.all { id -> job.drafts.firstOrNull { it.id == id }?.revision == expectedRevisions[id] })
        val old = job.drafts.filter { it.id in oldIds }
        require(replacements.flatMap { it.refs }.toSet() == old.flatMap { it.refs }.toSet())
        job.copy(drafts = job.drafts.filterNot { it.id in oldIds } + replacements, status = ImportStatus.REVIEW_REQUIRED)
        }
    }
    suspend fun publish(jobId: String, draftIds: List<String>, sectionId: String?, sectionName: String) {
        awaitTerminal(jobId)
        var job = current(jobId)
        job.publication?.let { prior -> publisher.receipt(prior.commitId)?.let { receipt ->
            job = journal.update(jobId) { it.copy(receipt = receipt, publishedDraftIds = it.publishedDraftIds + prior.items.map { item -> item.draftId }, status = ImportStatus.READY) }
        } }
        val selected = job.drafts.filter { it.id in draftIds && it.id !in job.publishedDraftIds }
        require(selected.isNotEmpty() && selected.all { !it.excluded && it.condition.isNotBlank() && (it.solution.isNotBlank() || it.answer.isNotBlank()) && it.refs.isNotEmpty() && it.warnings.none { warning -> warning.blocking } }) { "Исправьте замечания и выберите задачи с условием и решением" }
        require(sectionName.isNotBlank()) { "Укажите название раздела" }
        val target = sectionId ?: UUID.nameUUIDFromBytes("section:$jobId".toByteArray()).toString()
        val items = selected.map { draft -> PublicationItem(UUID.nameUUIDFromBytes("library-import:$jobId:${draft.id}".toByteArray()).toString(), draft.id, draft.revision,
            draft.condition, draft.solution, draft.answer, draft.conditionImages, draft.solutionImages, draft.refs) }
        val old = job.publication
        val snapshot = sectionSnapshot(target)
        require(sectionId == null || snapshot != null) { "Раздел удалён. Выберите актуальный раздел или создайте новый." }
        val intent = if (old != null && old.items == items && old.sectionId == target && old.sectionName == sectionName && old.expectedSection == snapshot) old
            else PublicationIntent(UUID.randomUUID().toString(), jobId, target, sectionName, snapshot, items)
        journal.update(jobId) { it.copy(status = ImportStatus.PUBLISHING, publication = intent, error = null) }
        try {
            val receipt = publisher.publish(intent)
            journal.update(jobId) { it.copy(status = ImportStatus.READY, receipt = receipt, publishedDraftIds = it.publishedDraftIds + selected.map { draft -> draft.id }) }
        } catch (error: Exception) {
            journal.update(jobId) { it.copy(status = ImportStatus.REVIEW_REQUIRED, error = "Не удалось добавить задачи. Черновики сохранены; проверьте доступность оригинала и актуальность раздела.") }
            throw error
        }
    }
    private class BudgetReached : Exception()
}
