package com.smartcalc.app.data.library.source

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream
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

/** Originals are addressed only through catalog UUIDs, never through caller-supplied paths. */
class LibrarySourceStore(private val root: File) {
    companion object { const val MAX_SOURCE_BYTES = 100L * 1024 * 1024 }
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val catalogFile = File(root, "sources.json")
    private val mutex = Mutex()
    @Serializable private data class CatalogFile(val schemaVersion: Int = 1, val sources: List<LibrarySource>)
    private val mutable = MutableStateFlow(load())
    val state: StateFlow<SourceCatalog> = mutable

    private fun load(): SourceCatalog = try {
        if (!catalogFile.exists()) SourceCatalog() else {
            require(catalogFile.length() <= 8 * 1024 * 1024) { "Каталог источников слишком большой" }
            val stored = json.decodeFromString<CatalogFile>(catalogFile.readText())
            require(stored.schemaVersion == 1)
            require(stored.sources.map { it.id }.distinct().size == stored.sources.size)
            stored.sources.forEach { source ->
                require(UUID.fromString(source.id).toString() == source.id)
                require(source.relativePath == "${source.id}/original")
                require(source.sha256.matches(Regex("[0-9a-f]{64}")))
                require(source.sizeBytes in 1..MAX_SOURCE_BYTES)
                require(source.segmentCount == null || source.segmentCount > 0)
                contained(source.relativePath)
            }
            SourceCatalog(stored.sources)
        }
    } catch (_: Exception) { SourceCatalog(error = "Не удалось прочитать каталог источников. Изменения заблокированы.") }

    private fun writable() { check(mutable.value.error == null) { mutable.value.error.orEmpty() } }
    private fun contained(path: String): File {
        val file = File(root, path).canonicalFile
        require(file.toPath().startsWith(root.canonicalFile.toPath()) && file != root.canonicalFile)
        return file
    }
    fun originalFile(id: String): File {
        check(mutable.value.error == null) { mutable.value.error.orEmpty() }
        val source = mutable.value.sources.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Источник не найден")
        check(source.status != SourceStatus.DELETED) { "Оригинал удалён" }
        return contained(source.relativePath).also { check(it.isFile) { "Оригинал недоступен" } }
    }

    suspend fun copy(input: SourceInput): SourceCopyResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            writable()
            root.mkdirs()
            val temporary = contained(".${UUID.randomUUID()}.tmp")
            var created: File? = null
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                var size = 0L
                input.open().use { stream ->
                    FileOutputStream(temporary).use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            size += count
                            require(size <= MAX_SOURCE_BYTES) { "Файл больше 100 МиБ" }
                            digest.update(buffer, 0, count); output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                }
                require(size > 0) { "Файл пустой" }
                val format = sniff(temporary, input)
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                val previous = mutable.value.sources.firstOrNull { it.sha256 == hash }
                val id = previous?.id ?: UUID.randomUUID().toString()
                val target = contained("$id/original")
                val intact = target.isFile && target.length() == size && sha256(target) == hash
                val source = previous?.copy(status = if (previous.status == SourceStatus.DELETED) SourceStatus.ACTIVE else previous.status)
                    ?: LibrarySource(id, input.name.take(250), format, size, hash, "$id/original")
                // A complete original must exist before the catalog can reference it.
                withContext(NonCancellable) {
                    if (!intact) {
                        target.parentFile!!.mkdirs()
                        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                        if (previous == null) created = target
                    }
                    publish(mutable.value.sources.filterNot { it.id == id } + source)
                }
                SourceCopyResult(source, previous != null)
            } catch (error: Exception) {
                created?.delete()
                throw error
            } finally { temporary.delete() }
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = update(id) { source ->
        check(source.status != SourceStatus.DELETED) { "Сначала загрузите оригинал снова" }
        source.copy(status = if (enabled) SourceStatus.ACTIVE else SourceStatus.DISABLED)
    }
    suspend fun setSegmentCount(id: String, count: Int) = update(id) { require(count > 0); it.copy(segmentCount = count) }
    suspend fun deleteOriginal(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            writable()
            val source = mutable.value.sources.first { it.id == id }
            withContext(NonCancellable) {
                // Tombstone first: a crash cannot leave a searchable source with deleted bytes.
                publish(mutable.value.sources.map { if (it.id == id) it.copy(status = SourceStatus.DELETED) else it })
                contained(source.relativePath).delete()
            }
        }
    }
    private suspend fun update(id: String, transform: (LibrarySource) -> LibrarySource) = withContext(Dispatchers.IO) {
        mutex.withLock {
            writable(); require(mutable.value.sources.any { it.id == id })
            withContext(NonCancellable) { publish(mutable.value.sources.map { if (it.id == id) transform(it) else it }) }
        }
    }
    private fun publish(sources: List<LibrarySource>) {
        val temporary = contained(".catalog-${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { out ->
                out.write(json.encodeToString(CatalogFile(sources = sources)).toByteArray()); out.fd.sync()
            }
            Files.move(temporary.toPath(), catalogFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            mutable.value = SourceCatalog(sources)
        } finally { temporary.delete() }
    }
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(8192)
            while (true) { val n = stream.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun sniff(file: File, input: SourceInput): SourceFormat {
        val header = file.inputStream().use { stream ->
            val bytes = ByteArray(16)
            var total = 0
            while (total < bytes.size) { val n = stream.read(bytes, total, bytes.size - total); if (n < 0) break; total += n }
            bytes.copyOf(total)
        }
        val extension = input.name.substringAfterLast('.', "").lowercase()
        require(extension != "doc" && !(header.size >= 2 && header[0] == 0xd0.toByte() && header[1] == 0xcf.toByte())) {
            "Старый DOC не поддерживается. Сохраните документ как DOCX или PDF."
        }
        if (header.take(5).toByteArray().toString(Charsets.US_ASCII) == "%PDF-") return SourceFormat.PDF
        if (header.size >= 3 && header[0] == 0xff.toByte() && header[1] == 0xd8.toByte() && header[2] == 0xff.toByte()) return SourceFormat.JPEG
        if (header.take(8).toByteArray().contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 13, 10, 26, 10))) return SourceFormat.PNG
        if (extension == "docx" || header.take(2).toByteArray().contentEquals(byteArrayOf(0x50, 0x4b))) {
            var hasDocument = false; var hasTypes = false; var total = 0L
            try {
                ZipInputStream(file.inputStream()).use { zip ->
                    val seen = mutableSetOf<String>(); val buffer = ByteArray(8192)
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        require(seen.add(entry.name) && !entry.name.startsWith('/') && entry.name.split('/').none { it == ".." })
                        hasDocument = hasDocument || entry.name == "word/document.xml"
                        hasTypes = hasTypes || entry.name == "[Content_Types].xml"
                        var part = 0L
                        while (true) {
                            val n = zip.read(buffer); if (n < 0) break
                            total += n; part += n
                            require(total <= 64L * 1024 * 1024 && (!entry.name.endsWith(".xml") || part <= 8L * 1024 * 1024))
                        }
                    }
                }
            } catch (error: Exception) { throw IllegalArgumentException("Повреждённый или слишком большой DOCX", error) }
            require(hasDocument && hasTypes) { "Не удалось прочитать DOCX" }
            return SourceFormat.DOCX
        }
        require(extension !in setOf("pdf", "jpg", "jpeg", "png", "docx")) { "Формат файла не соответствует содержимому" }
        require(extension in setOf("txt", "md") || input.mime?.startsWith("text/") == true) { "Подойдут PDF, DOCX, TXT, Markdown, JPEG и PNG" }
        require(header.none { it == 0.toByte() }) { "Не удалось прочитать текстовый файл" }
        return if (extension == "md") SourceFormat.MARKDOWN else SourceFormat.TEXT
    }
}
