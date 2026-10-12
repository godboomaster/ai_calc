package com.smartcalc.app.ui.library

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import com.smartcalc.app.SmartCalcApplication
import com.smartcalc.app.data.library.source.*
import com.smartcalc.app.data.library.extract.*
import com.smartcalc.app.data.library.importer.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class LibraryImportViewModel(app: Application, private val saved: SavedStateHandle) : AndroidViewModel(app) {
    private val application = app as SmartCalcApplication
    val sources = application.librarySources.state
    val jobs = application.libraryImport.jobs
    val library = application.libraryStore.items
    val settings = application.settingsRepository.settings
    val pendingSourceIds = saved.getStateFlow("libraryPendingSources", emptyList<String>())
    private val mutableEdits = MutableStateFlow<Map<String, ImportDraft>>(emptyMap())
    val edits: StateFlow<Map<String, ImportDraft>> = mutableEdits
    val error = MutableStateFlow<String?>(null)
    val copying = MutableStateFlow(false)
    val actionBusy = MutableStateFlow(false)
    private val actions = Mutex()
    private val writers = Mutex()
    private val delayed = mutableMapOf<String, Job>()
    fun clearError() { error.value = null }
    fun source(id: String) = sources.value.sources.firstOrNull { it.id == id }
    fun job(id: String) = jobs.value.jobs.firstOrNull { it.id == id }
    private fun failure() { error.value = "Не удалось выполнить действие. Исходники и сохранённые черновики остались доступны." }
    suspend fun copyInputs(inputs: List<SourceInput>): List<String> = application.libraryWorkScope.async(Dispatchers.IO) {
        copying.value = true
        try {
            val ids = inputs.map { application.librarySources.copy(it).source.id }.distinct()
            withContext(Dispatchers.Main.immediate) { saved["libraryPendingSources"] = ids }
            ids
        } catch (e: Exception) { error.value = e.message?.take(250) ?: "Не удалось сохранить оригинал"; throw e }
        finally { copying.value = false }
    }.await()
    suspend fun copyUris(uris: List<Uri>): List<String> {
        val context = getApplication<Application>()
        val inputs = uris.map { uri ->
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: "Файл"
            SourceInput(name, context.contentResolver.getType(uri)) {
                context.contentResolver.openInputStream(uri) ?: throw IllegalArgumentException("Не удалось открыть выбранный файл")
            }
        }
        return copyInputs(inputs)
    }
    fun selectSources(ids: List<String>) { saved["libraryPendingSources"] = ids }
    suspend fun inspect(id: String) = application.libraryExtractor.inspect(checkNotNull(source(id)))
    suspend fun createAndProcess(ids: List<String>, selections: Map<String, PageSelection>): String =
        application.libraryWorkScope.async(Dispatchers.IO) {
            val id = application.libraryImport.create(ids, selections)
            application.libraryImport.process(id); id
        }.await()
    fun process(id: String) = command { application.libraryImport.process(id) }
    fun cancel(id: String) = command { application.libraryImport.cancel(id) }
    fun enableSource(id: String, enabled: Boolean) = command { application.librarySources.setEnabled(id, enabled) }
    fun deleteSource(id: String) = command { application.librarySources.deleteOriginal(id) }
    private fun command(block: suspend () -> Unit) = application.libraryWorkScope.launch(Dispatchers.IO) {
        actions.withLock {
            actionBusy.value = true
            try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) { failure() }
            finally { actionBusy.value = false }
        }
    }
    fun edit(jobId: String, draft: ImportDraft) {
        mutableEdits.update { it + (draft.id to draft) }
        delayed.remove(jobId)?.cancel()
        delayed[jobId] = application.libraryWorkScope.launch(Dispatchers.IO) { delay(300); flush(jobId) }
    }
    suspend fun flush(jobId: String) = writers.withLock {
        val currentJob = job(jobId) ?: return@withLock
        val pending = mutableEdits.value.filterKeys { id -> currentJob.drafts.any { it.id == id } }
        pending.forEach { (id, draft) ->
            val latest = job(jobId)?.drafts?.firstOrNull { it.id == id } ?: return@forEach
            try {
                application.libraryImport.saveDraft(jobId, draft, latest.revision)
                mutableEdits.update { map -> if (map[id] == draft) map - id else map }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { failure() }
        }
    }
    fun flushInBackground(jobId: String) = command { flush(jobId) }
    fun addDraft(jobId: String) = command {
        val job = checkNotNull(job(jobId))
        val refs = job.segments.map { it.ref }.distinct()
        require(refs.isNotEmpty()) { "Сначала подготовьте фрагменты оригинала" }
        application.libraryImport.saveDraft(jobId, ImportDraft(UUID.randomUUID().toString(), condition = "", solution = "", refs = refs), 0)
    }
    fun merge(jobId: String, ids: List<String>) = command {
        flush(jobId)
        val drafts = checkNotNull(job(jobId)).drafts.filter { it.id in ids }
        val merged = ImportDraftEditor.merge(drafts)
        application.libraryImport.replaceDrafts(jobId, ids, drafts.associate { it.id to it.revision }, listOf(merged))
    }
    fun split(jobId: String, draftId: String) = command {
        flush(jobId)
        val draft = checkNotNull(job(jobId)?.drafts?.first { it.id == draftId })
        val parts = ImportDraftEditor.split(draft, listOf(draft.copy(id = UUID.randomUUID().toString()),
            draft.copy(id = UUID.randomUUID().toString(), condition = "", solution = "", answer = "", conditionImages = emptyList(), solutionImages = emptyList())))
        application.libraryImport.replaceDrafts(jobId, listOf(draftId), mapOf(draftId to draft.revision), parts)
    }
    fun publish(jobId: String, ids: List<String>, sectionId: String?, sectionName: String) = command {
        flush(jobId)
        require(mutableEdits.value.keys.none { id -> job(jobId)?.drafts?.any { it.id == id } == true }) { "Сначала сохраните правки" }
        application.libraryImport.publish(jobId, ids, sectionId, sectionName)
    }
    suspend fun preview(sourceId: String, ordinal: Int): ExtractedSegment = withContext(Dispatchers.IO) {
        val source = checkNotNull(source(sourceId))
        application.libraryExtractor.extract(source, PageSelection(listOf(ordinal))) { }.segments.single()
    }
    fun originalFile(sourceId: String): File = application.librarySources.originalFile(sourceId)
}
