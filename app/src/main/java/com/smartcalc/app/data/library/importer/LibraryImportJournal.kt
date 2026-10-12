package com.smartcalc.app.data.library.importer

import com.smartcalc.app.data.api.RecognitionHttpResponse
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class LibraryImportJournal(private val root: File) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    @Serializable private data class JobFile(val schemaVersion: Int = 1, val job: ImportJob)
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(load())
    val jobs: StateFlow<ImportCatalog> = mutable
    fun reportStorageFailure() {
        mutable.value = mutable.value.copy(error = "Не удалось сохранить журнал импорта. Обработка остановлена; перезапустите приложение после восстановления хранилища.")
    }
    private fun folder(id: String): File {
        require(UUID.fromString(id).toString() == id)
        return File(root, id).also { require(it.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) }
    }
    private fun validate(job: ImportJob) {
        folder(job.id)
        require(job.sourceIds.isNotEmpty() && job.sourceIds.distinct().size == job.sourceIds.size)
        job.sourceIds.forEach { require(UUID.fromString(it).toString() == it) }
        require(job.selections.keys == job.sourceIds.toSet() && job.selections.values.sumOf { it.ordinals.size } <= 100)
        require(job.postsUsed in 0..10 && job.attempt >= 0)
        require(job.drafts.size <= 500 && job.drafts.map { it.id }.distinct().size == job.drafts.size)
        val refs = job.segments.map { it.ref } + job.drafts.flatMap { it.refs } + job.carry?.refs.orEmpty()
        refs.forEach { require(it.sourceId in job.sourceIds && it.ordinal in job.selections.getValue(it.sourceId).ordinals) }
        val media = job.segments.flatMap { it.imagePaths } + job.drafts.flatMap { it.conditionImages + it.solutionImages } +
            job.activeBatch?.segments.orEmpty().flatMap { it.imagePaths } + job.carry?.segments.orEmpty().flatMap { it.imagePaths } +
            job.activeBatch?.carry?.segments.orEmpty().flatMap { it.imagePaths }
        val sourcesRoot = File(root.parentFile, "sources").canonicalFile.toPath()
        val jobsRoot = root.canonicalFile.toPath()
        media.forEach { path -> val file = File(path).canonicalFile.toPath(); require(file.startsWith(sourcesRoot) || file.startsWith(jobsRoot)) { "Медиа вне библиотеки" } }
        job.drafts.forEach { require(it.revision > 0 && UUID.fromString(it.id).toString() == it.id) }
        job.inFlightPost?.responseRelativePath?.let { require(it.matches(Regex("response-\\d+-\\d+\\.json"))) }
    }
    private fun load(): ImportCatalog = try {
        val loaded = root.listFiles().orEmpty().filter { it.isDirectory && runCatching { UUID.fromString(it.name) }.isSuccess }
            .mapNotNull { directory ->
                val file = File(directory, "job.json")
                if (!file.exists()) null else {
                    require(file.isFile && file.length() <= 32L * 1024 * 1024)
                    val stored = json.decodeFromString<JobFile>(file.readText())
                    require(stored.schemaVersion == 1 && stored.job.id == directory.name)
                    validate(stored.job); stored.job
                }
            }
        ImportCatalog(loaded)
    } catch (_: Exception) { ImportCatalog(error = "Не удалось прочитать журнал импорта. Изменения заблокированы.") }
    private fun writable() { check(mutable.value.error == null) { mutable.value.error.orEmpty() } }
    private fun atomic(file: File, content: String, limit: Int) {
        val bytes = content.toByteArray(); require(bytes.size <= limit) { "Данные импорта слишком велики" }
        file.parentFile!!.mkdirs(); val temporary = File(file.parentFile, ".${file.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (error: java.io.IOException) { reportStorageFailure(); throw error }
        finally { temporary.delete() }
    }
    private fun persist(job: ImportJob) {
        validate(job)
        atomic(File(folder(job.id), "job.json"), json.encodeToString(JobFile(job = job)), 32 * 1024 * 1024)
        mutable.value = ImportCatalog(mutable.value.jobs.filterNot { it.id == job.id } + job)
    }
    suspend fun save(job: ImportJob) = withContext(Dispatchers.IO) {
        mutex.withLock { writable(); withContext(NonCancellable) { persist(job) } }
    }
    suspend fun update(id: String, transform: (ImportJob) -> ImportJob): ImportJob = withContext(Dispatchers.IO) {
        mutex.withLock {
            writable(); val current = mutable.value.jobs.first { it.id == id }
            val next = transform(current); require(next.id == id)
            withContext(NonCancellable) { persist(next) }; next
        }
    }
    suspend fun checkpointResponse(id: String, response: RecognitionHttpResponse, expectedAttempt: Long? = null) = withContext(Dispatchers.IO) {
        mutex.withLock {
            writable(); val job = mutable.value.jobs.first { it.id == id }
            check(expectedAttempt == null || expectedAttempt == job.attempt) { "Устаревшая попытка" }
            check(job.status == ImportStatus.RECOGNIZING)
            val post = checkNotNull(job.inFlightPost)
            val name = "response-${job.attempt}-${post.number}.json"
            require(response.body.toByteArray().size <= 2 * 1024 * 1024)
            withContext(NonCancellable) {
                atomic(File(folder(id), name), json.encodeToString(response), 4 * 1024 * 1024 + 1024)
                persist(job.copy(inFlightPost = post.copy(responseRelativePath = name)))
            }
        }
    }
    fun response(job: ImportJob): RecognitionHttpResponse? {
        val path = job.inFlightPost?.responseRelativePath ?: return null
        require(path.matches(Regex("response-\\d+-\\d+\\.json")))
        val file = File(folder(job.id), path)
        require(file.isFile && file.length() <= 4L * 1024 * 1024 + 1024)
        return json.decodeFromString<RecognitionHttpResponse>(file.readText())
    }
    suspend fun recover() {
        if (mutable.value.error != null) return
        mutable.value.jobs.filter { it.waitingForSolution || it.status in setOf(ImportStatus.EXTRACTING, ImportStatus.RECOGNIZING) }.forEach { job ->
            update(job.id) { current -> if (!current.waitingForSolution && current.status !in setOf(ImportStatus.EXTRACTING, ImportStatus.RECOGNIZING)) current else current.copy(waitingForSolution = false, status = if (current.inFlightPost?.responseRelativePath != null) ImportStatus.PAUSED else ImportStatus.INTERRUPTED,
                error = if (current.inFlightPost?.responseRelativePath != null) "Ответ сохранён; можно завершить разбор без повторной отправки."
                    else "Обработка прервана. Неизвестный исход запроса не повторяется автоматически.") }
        }
    }
}
