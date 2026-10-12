@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.smartcalc.app.data.template

import com.smartcalc.app.data.library.LIBRARY_METADATA_SCHEMA_VERSION
import com.smartcalc.app.data.library.LibraryMetadata
import com.smartcalc.app.data.library.LibraryOrigin
import com.smartcalc.app.data.library.LibraryOriginKind
import com.smartcalc.app.data.library.LibraryRevision
import com.smartcalc.app.data.library.LibraryVerification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Решённая задача-образец: условие и решение — текстом и/или картинками (пути к файлам). */
@Serializable
data class TemplateExample(
    val condition: String = "",
    val conditionImages: List<String> = emptyList(),
    val solution: String = "",
    val solutionImages: List<String> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val library: LibraryMetadata? = null,
) {
    val isEmpty: Boolean
        get() = condition.isBlank() && solution.isBlank() && conditionImages.isEmpty() && solutionImages.isEmpty()
}

/**
 * Шаблон заданий: требования к задачам одного типа и примеры их решения. ИИ получает его вместе
 * с новой задачей и решает её так же.
 */
@Serializable
data class TaskTemplate(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val requirements: String = "",
    val examples: List<TemplateExample> = emptyList(),
    val updated: Long = System.currentTimeMillis(),
)

/** Картинка в редакторе шаблона: уже сохранённая ([path]) или только что выбранная ([bytes]). */
class ImageDraft(val path: String? = null, val bytes: ByteArray? = null)

class ExampleDraft(
    val condition: String,
    val conditionImages: List<ImageDraft>,
    val solution: String,
    val solutionImages: List<ImageDraft>,
    val libraryId: String? = null,
    val expectedVersion: Int? = null,
    val verification: LibraryVerification? = null,
    val enabled: Boolean? = null,
)

class TemplateImportException(message: String) : Exception(message)

/** Итог импорта: сколько шаблонов новых, сколько заменили уже бывшие (тот же шаблон, импорт повторно). */
data class ImportResult(val added: Int, val updated: Int, val unchanged: Int = 0) {
    /** Сами загруженные шаблоны — уже с путями к своим фото. В сравнении итогов не участвуют. */
    var items: List<TaskTemplate> = emptyList()
        internal set
}

/**
 * Список шаблонов в файле. В ZIP это manifest.json, а пути к фото в нём — имена внутри архива
 * (images/…); в JSON фото лежат прямо в ссылках как data URI.
 */
@Serializable
private data class TemplateBundle(val format: String, val version: Int, val templates: List<TaskTemplate>)

/** Import wire types deliberately have no library field: untrusted metadata is never decoded. */
@Serializable
private data class ImportedBundle(val format: String, val version: Int, val templates: List<ImportedTemplate>)

@Serializable
private data class ImportedTemplate(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val requirements: String = "",
    val examples: List<ImportedExample> = emptyList(),
    val updated: Long = System.currentTimeMillis(),
) {
    fun local() = TaskTemplate(
        id = id,
        name = name,
        requirements = requirements,
        examples = examples.map(ImportedExample::local),
        updated = updated,
    )
}

@Serializable
private data class ImportedExample(
    val condition: String = "",
    val conditionImages: List<String> = emptyList(),
    val solution: String = "",
    val solutionImages: List<String> = emptyList(),
) {
    fun local() = TemplateExample(condition, conditionImages, solution, solutionImages)
}

private const val BUNDLE_FORMAT = "smartcalc-templates"
private const val MANIFEST = "manifest.json"
private val IMAGE_ENTRY = Regex("""images/[A-Za-z0-9-]{1,64}\.jpg""")

// Пределы для чужих файлов: архив-бомба не должна заполнить память или диск.
private const val MAX_MANIFEST_BYTES = 2L * 1024 * 1024
private const val MAX_IMAGE_BYTES = 15L * 1024 * 1024
private const val MAX_TOTAL_BYTES = 100L * 1024 * 1024
// JSON читается в память целиком (фото в нём — base64), поэтому предел скромнее.
private const val MAX_TEXT_BYTES = 60L * 1024 * 1024

/** В каком виде сохранить шаблоны: [mime] — для системного выбора файла и «Поделиться». */
enum class TemplateFormat(val extension: String, val mime: String) {
    ZIP("zip", "application/zip"),
    JSON("json", "application/json"),
    MARKDOWN("md", "text/markdown"),
}

/**
 * Что хранит [TemplateStore]: шаблоны заданий для ИИ или базу решённых задач для поиска. Файлы у них
 * одного формата: раздел базы — это тип задач (название) и сами задачи (примеры).
 */
enum class TemplateKind { TEMPLATES, LIBRARY }

/** Шаблоны хранятся в [root]: список в templates.json, картинки в templates/. */
class TemplateStore(private val root: File, val kind: TemplateKind = TemplateKind.TEMPLATES) {

    // Time-dependent defaults must be written too: omitting updated changes it on every reload.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val exportJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }
    private val file get() = File(root, "templates.json")
    private val imagesDir get() = File(root, "templates").apply { mkdirs() }
    private val mutex = Mutex()

    private data class Loaded(val items: List<TaskTemplate>, val error: String? = null)
    private class UnsupportedLibrarySchema : Exception()

    private val loaded = load()
    private val _items = MutableStateFlow(loaded.items)
    val items: StateFlow<List<TaskTemplate>> = _items
    private val _loadError = MutableStateFlow(loaded.error)
    val loadError: StateFlow<String?> = _loadError

    private fun load(): Loaded {
        if (!file.exists()) return Loaded(emptyList())
        return try {
            val decoded = json.decodeFromString<List<TaskTemplate>>(file.readText())
            if (kind != TemplateKind.LIBRARY) return Loaded(decoded)
            decoded.flatMap { it.examples }.mapNotNull { it.library }.forEach { metadata ->
                if (metadata.schemaVersion != LIBRARY_METADATA_SCHEMA_VERSION) throw UnsupportedLibrarySchema()
            }
            validateLibraryMetadata(decoded)
            var migrated = false
            val library = decoded.map { template ->
                template.copy(examples = template.examples.map { example ->
                    if (example.library != null) example else {
                        migrated = true
                        example.copy(library = newMetadata(LibraryOriginKind.LEGACY))
                    }
                })
            }
            // A caller never observes generated IDs until the exact snapshot containing them is durable.
            if (migrated) writeSnapshot(library)
            Loaded(library)
        } catch (_: UnsupportedLibrarySchema) {
            Loaded(emptyList(), "Версия метаданных библиотеки не поддерживается")
        } catch (_: Exception) {
            Loaded(emptyList(), "Не удалось прочитать или обновить библиотеку. Изменения заблокированы")
        }
    }

    private fun ensureWritable() {
        check(_loadError.value == null) { _loadError.value ?: "Изменения библиотеки заблокированы" }
    }

    private fun validateLibraryMetadata(templates: List<TaskTemplate>) {
        val ids = mutableSetOf<String>()
        templates.flatMap { it.examples }.mapNotNull { it.library }.forEach { metadata ->
            val uuid = UUID.fromString(metadata.id)
            require(uuid.toString() == metadata.id.lowercase())
            require(ids.add(uuid.toString()))
            require(metadata.version >= 1)
            require(metadata.previousVersions.size == metadata.version - 1)
            require(metadata.previousVersions.withIndex().all { (index, revision) ->
                revision.version == index + 1
            })
        }
    }

    private fun newMetadata(kind: LibraryOriginKind, name: String? = null) = LibraryMetadata(
        id = UUID.randomUUID().toString(),
        origin = LibraryOrigin(kind, name),
    )

    /**
     * Сохраняет шаблон из редактора: новые картинки пишутся в файлы, картинки, убранные из шаблона,
     * удаляются. [id] — null для нового шаблона.
     */
    suspend fun save(
        id: String?,
        name: String,
        requirements: String,
        examples: List<ExampleDraft>,
        expectedSection: TaskTemplate? = null,
    ) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                ensureWritable()
                val previous = _items.value.firstOrNull { it.id == id }
                if (kind == TemplateKind.LIBRARY) {
                    if (id != null && previous == null) {
                        throw IllegalStateException("Раздел библиотеки больше не существует")
                    }
                    if (previous != null && expectedSection != previous) {
                        throw IllegalStateException("Раздел библиотеки изменился. Обновите его и повторите сохранение")
                    }
                    validateDraftVersions(previous, examples)
                }
                val created = mutableListOf<File>()
                try {
                    fun store(images: List<ImageDraft>) = images.mapNotNull { img ->
                        img.path ?: img.bytes?.let { bytes ->
                            File(imagesDir, "${UUID.randomUUID()}.jpg").also {
                                created += it
                                writeImage(it, bytes)
                            }.absolutePath
                        }
                    }
                    val oldById = previous?.examples.orEmpty().mapNotNull { old ->
                        old.library?.id?.let { it to old }
                    }.toMap()
                    val template = TaskTemplate(
                        id = id ?: UUID.randomUUID().toString(),
                        name = name.trim(),
                        requirements = requirements.trim(),
                        examples = examples.map { draft ->
                            val current = TemplateExample(
                                condition = draft.condition.trim(),
                                conditionImages = store(draft.conditionImages),
                                solution = draft.solution.trim(),
                                solutionImages = store(draft.solutionImages),
                            )
                            if (kind != TemplateKind.LIBRARY) current else {
                                val old = draft.libraryId?.let(oldById::get)
                                current.copy(library = metadataForSave(old, current, draft))
                            }
                        }.filterNot { it.isEmpty },
                    )
                    val updated = listOf(template) + _items.value.filterNot { it.id == template.id }
                    publish(updated, created)
                } catch (error: Exception) {
                    created.forEach { it.delete() }
                    throw error
                }
            }
        }

    private fun validateDraftVersions(previous: TaskTemplate?, drafts: List<ExampleDraft>) {
        val oldById = previous?.examples.orEmpty().mapNotNull { example ->
            example.library?.id?.let { it to example.library }
        }.toMap()
        val ids = drafts.mapNotNull { it.libraryId }
        require(ids.size == ids.distinct().size) { "Один пример библиотеки указан несколько раз" }
        drafts.forEach { draft ->
            val libraryId = draft.libraryId ?: return@forEach
            val current = oldById[libraryId]
                ?: throw IllegalStateException("Пример библиотеки больше не существует")
            if (draft.expectedVersion == null || draft.expectedVersion != current.version) {
                throw IllegalStateException("Версия примера изменилась. Обновите раздел и повторите сохранение")
            }
        }
    }

    private fun metadataForSave(
        old: TemplateExample?,
        current: TemplateExample,
        draft: ExampleDraft,
    ): LibraryMetadata {
        val previous = old?.library ?: return newMetadata(LibraryOriginKind.MANUAL).copy(
            verification = draft.verification ?: LibraryVerification.UNVERIFIED,
            enabled = draft.enabled ?: true,
        )
        if (sameContent(old, current)) {
            return previous.copy(
                verification = draft.verification ?: previous.verification,
                enabled = draft.enabled ?: previous.enabled,
            )
        }
        val revision = LibraryRevision(
            version = previous.version,
            condition = old.condition,
            conditionImages = old.conditionImages,
            solution = old.solution,
            solutionImages = old.solutionImages,
            verification = previous.verification,
        )
        return previous.copy(
            version = previous.version + 1,
            verification = draft.verification ?: LibraryVerification.UNVERIFIED,
            enabled = draft.enabled ?: previous.enabled,
            previousVersions = previous.previousVersions + revision,
        )
    }

    private fun sameContent(first: TemplateExample, second: TemplateExample): Boolean =
        first.condition.trim() == second.condition.trim() &&
            first.solution.trim() == second.solution.trim() &&
            sameImages(first.conditionImages, second.conditionImages) &&
            sameImages(first.solutionImages, second.solutionImages)

    private fun sameImages(first: List<String>, second: List<String>): Boolean =
        first.size == second.size && first.zip(second).all { (left, right) ->
            left == right || runCatching { File(left).readBytes().contentEquals(File(right).readBytes()) }.getOrDefault(false)
        }

    suspend fun delete(id: String, expectedSection: TaskTemplate? = null) = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureWritable()
            if (kind == TemplateKind.LIBRARY) {
                val current = _items.value.firstOrNull { it.id == id }
                    ?: throw IllegalStateException("Раздел библиотеки больше не существует")
                if (expectedSection != current) {
                    throw IllegalStateException("Раздел библиотеки изменился. Обновите его и повторите удаление")
                }
            }
            val updated = _items.value.filterNot { it.id == id }
            publish(updated)
        }
    }

    /**
     * Пишет в [out] файл шаблонов с [ids] (null — все) в формате [format]; возвращает число шаблонов.
     * ZIP и JSON — с фото, Markdown — только текст.
     */
    suspend fun export(
        ids: Collection<String>?,
        out: OutputStream,
        format: TemplateFormat = TemplateFormat.ZIP,
    ): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            val chosen = _items.value.filter { ids == null || it.id in ids }
            when (format) {
                TemplateFormat.ZIP -> exportZip(chosen, out)
                TemplateFormat.JSON -> out.write(exportJson.encodeToString(withImages(chosen) { dataUri(it) }).toByteArray())
                TemplateFormat.MARKDOWN -> out.write(TemplateMarkdown.write(chosen).toByteArray())
            }
            out.flush()
            chosen.size
        }
    }

    /** Те же шаблоны, но вместо путей к фото — то, что вернёт [ref] (имя в архиве, data URI); нет файла — фото пропадает. */
    private fun withImages(templates: List<TaskTemplate>, ref: (File) -> String?): TemplateBundle {
        fun refs(paths: List<String>) = paths.mapNotNull { p -> File(p).takeIf { it.exists() }?.let(ref) }
        return TemplateBundle(
            BUNDLE_FORMAT,
            1,
            templates.map { t ->
                t.copy(
                    examples = t.examples.map { ex ->
                        ex.copy(
                            conditionImages = refs(ex.conditionImages),
                            solutionImages = refs(ex.solutionImages),
                            library = null,
                        )
                    },
                )
            },
        )
    }

    private fun dataUri(f: File) = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(f.readBytes())

    private fun exportZip(templates: List<TaskTemplate>, out: OutputStream) {
        val entries = linkedMapOf<File, String>()
        val bundle = withImages(templates) { f ->
            entries.getOrPut(f) { "images/${f.nameWithoutExtension.filter { it.isLetterOrDigit() || it == '-' }.take(64)}.jpg" }
        }
        // Поток не закрываем: его закрывает тот, кто открыл.
        val zip = ZipOutputStream(out)
        zip.putNextEntry(ZipEntry(MANIFEST))
        zip.write(json.encodeToString(bundle).toByteArray())
        zip.closeEntry()
        entries.forEach { (file, name) ->
            zip.putNextEntry(ZipEntry(name))
            file.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
        zip.finish()
    }

    /**
     * Добавляет шаблоны из файла: ZIP или JSON из экспорта SmartCalc либо Markdown. Формат
     * определяется по содержимому, а не по расширению — мессенджеры его нередко теряют. Шаблон с тем
     * же id (тот же файл импортирован повторно) заменяется, а не дублируется. Фото получают новые
     * имена: имена из файла на диск не попадают.
     */
    suspend fun import(input: InputStream, sourceName: String? = null): ImportResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureWritable()
            val created = mutableListOf<File>()
            val imported = try {
                val stream = input.buffered()
                stream.mark(4)
                val head = ByteArray(2).also { stream.read(it) }
                stream.reset()
                if (head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) {
                    importZip(stream, created)
                } else {
                    val text = readLimited(stream, MAX_TEXT_BYTES).decodeToString().removePrefix("\uFEFF")
                    if (text.trimStart().startsWith("{")) importJson(text, created) else importMarkdown(text)
                }
            } catch (e: TemplateImportException) {
                created.forEach { it.delete() }
                throw e
            } catch (e: Exception) {
                // Битый архив, обрыв потока, неверный JSON — для пользователя это одно: файл не тот.
                created.forEach { it.delete() }
                throw TemplateImportException("Не удалось прочитать файл шаблонов")
            }
            if (imported.isEmpty()) {
                created.forEach { it.delete() }
                throw TemplateImportException("В файле нет шаблонов")
            }
            val unique = imported.distinctBy { it.id }
            if (kind == TemplateKind.LIBRARY) {
                return@withLock importLibrary(unique, created, sourceName)
            }
            val replaced = _items.value.filter { old -> unique.any { it.id == old.id } }
            val updated = unique + _items.value.filterNot { old -> unique.any { it.id == old.id } }
            try {
                publish(updated, created)
            } catch (error: Exception) {
                created.forEach { it.delete() }
                throw error
            }
            ImportResult(added = unique.size - replaced.size, updated = replaced.size).apply { items = unique }
        }
    }

    private fun importLibrary(
        imported: List<TaskTemplate>,
        created: MutableList<File>,
        sourceName: String?,
    ): ImportResult {
        val existing = _items.value.associateBy { it.id }
        val conflicts = imported.filter { candidate ->
            existing[candidate.id]?.let { current -> !sameSection(current, candidate) } == true
        }
        if (conflicts.isNotEmpty()) {
            created.forEach { it.delete() }
            throw TemplateImportException("Конфликт раздела библиотеки: локальные данные не изменены")
        }
        val additions = imported.filterNot { it.id in existing }.map { template ->
            template.copy(examples = template.examples.map { example ->
                example.copy(library = newMetadata(LibraryOriginKind.FILE_IMPORT, sourceName))
            })
        }
        val unchanged = imported.size - additions.size
        val updated = additions + _items.value
        try {
            publish(updated, created)
        } catch (error: Exception) {
            created.forEach { it.delete() }
            throw error
        }
        val additionsById = additions.associateBy { it.id }
        return ImportResult(added = additions.size, updated = 0, unchanged = unchanged).apply {
            items = imported.map { additionsById[it.id] ?: existing.getValue(it.id) }
        }
    }

    private fun sameSection(first: TaskTemplate, second: TaskTemplate): Boolean =
        first.name.trim() == second.name.trim() &&
            first.requirements.trim() == second.requirements.trim() &&
            first.examples.size == second.examples.size &&
            first.examples.zip(second.examples).all { (left, right) -> sameContent(left, right) }

    /**
     * Дописывает к шаблону [templateId] задачи из другого хранилища (из базы задач). Фото копируются в
     * свои файлы: шаблон не зависит от того, удалят ли задачу из базы. Задача, которая в шаблоне уже
     * есть, второй раз не добавляется. Возвращает число добавленных задач.
     */
    suspend fun attach(templateId: String, examples: List<TemplateExample>): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureWritable()
            val template = _items.value.firstOrNull { it.id == templateId } ?: return@withLock 0
            val references = examples.flatMap { it.conditionImages + it.solutionImages }
            references.forEach { path ->
                require(File(path).isFile) { "Referenced image does not exist: $path" }
            }
            val known = template.examples.mapTo(mutableSetOf(), ExampleFingerprint::key)
            val created = mutableListOf<File>()
            val copies = mutableMapOf<File, String>()
            fun copy(paths: List<String>) = paths.map { path ->
                val source = File(path).canonicalFile
                copies.getOrPut(source) {
                    File(imagesDir, "${UUID.randomUUID()}.jpg").also { target ->
                        created += target
                        writeImage(target, source.readBytes())
                    }.absolutePath
                }
            }
            try {
                val added = examples.filter { !it.isEmpty && known.add(ExampleFingerprint.key(it)) }.map {
                    it.copy(
                        conditionImages = copy(it.conditionImages),
                        solutionImages = copy(it.solutionImages),
                        library = if (kind == TemplateKind.LIBRARY) newMetadata(LibraryOriginKind.MANUAL) else null,
                    )
                }
                if (added.isEmpty()) return@withLock 0
                val changed = template.copy(examples = template.examples + added, updated = System.currentTimeMillis())
                val updated = listOf(changed) + _items.value.filterNot { it.id == templateId }
                publish(updated, created)
                added.size
            } catch (error: Exception) {
                created.forEach { it.delete() }
                throw error
            }
        }
    }

    private fun bundleOf(text: String): TemplateBundle {
        val imported = json.decodeFromString<ImportedBundle>(text).takeIf { it.format == BUNDLE_FORMAT }
            ?: throw TemplateImportException("Это не файл шаблонов SmartCalc")
        if (imported.version != 1) throw TemplateImportException("Эта версия файла шаблонов не поддерживается")
        return TemplateBundle(imported.format, imported.version, imported.templates.map(ImportedTemplate::local))
    }

    /** Фото из ссылок шаблона в свои файлы; [load] возвращает байты по ссылке или null. */
    private fun localize(
        templates: List<TaskTemplate>,
        created: MutableList<File>,
        load: (String) -> ByteArray?,
    ): List<TaskTemplate> {
        val unique = if (kind == TemplateKind.LIBRARY) {
            deduplicateLibraryImport(templates, load)
        } else {
            templates.distinctBy { it.id }
        }
        val saved = mutableMapOf<String, String>()
        fun local(refs: List<String>) = refs.mapNotNull { ref ->
            saved[ref] ?: load(ref)?.let { bytes ->
                if (bytes.size > MAX_IMAGE_BYTES) throw TemplateImportException("Фото в файле шаблонов слишком большое")
                val f = File(imagesDir, "${UUID.randomUUID()}.jpg")
                created += f
                writeImage(f, bytes)
                f.absolutePath.also { saved[ref] = it }
            }
        }
        return unique.map { t ->
            t.copy(
                examples = t.examples.map { ex ->
                        ex.copy(
                            conditionImages = local(ex.conditionImages),
                            solutionImages = local(ex.solutionImages),
                            // Every import is foreign input, including another SmartCalc installation.
                            library = null,
                        )
                },
            )
        }
    }

    /** Validate duplicate IDs against source bytes before any imported photo is written to disk. */
    private fun deduplicateLibraryImport(
        templates: List<TaskTemplate>,
        load: (String) -> ByteArray?,
    ): List<TaskTemplate> {
        val unique = linkedMapOf<String, TaskTemplate>()
        templates.forEach { candidate ->
            val first = unique[candidate.id]
            if (first == null) {
                unique[candidate.id] = candidate
            } else if (!sameImportedSection(first, candidate, load)) {
                throw TemplateImportException("Конфликт повторяющегося раздела библиотеки: импорт отменён")
            }
        }
        return unique.values.toList()
    }

    private fun sameImportedSection(
        first: TaskTemplate,
        second: TaskTemplate,
        load: (String) -> ByteArray?,
    ): Boolean =
        first.name.trim() == second.name.trim() &&
            first.requirements.trim() == second.requirements.trim() &&
            first.examples.size == second.examples.size &&
            first.examples.zip(second.examples).all { (left, right) ->
                left.condition.trim() == right.condition.trim() &&
                    left.solution.trim() == right.solution.trim() &&
                    sameImportedImages(left.conditionImages, right.conditionImages, load) &&
                    sameImportedImages(left.solutionImages, right.solutionImages, load)
            }

    private fun sameImportedImages(
        first: List<String>,
        second: List<String>,
        load: (String) -> ByteArray?,
    ): Boolean = first.size == second.size && first.zip(second).all { (left, right) ->
        val leftBytes = load(left)
        val rightBytes = load(right)
        leftBytes == null && rightBytes == null ||
            leftBytes != null && rightBytes != null && leftBytes.contentEquals(rightBytes)
    }

    private fun importZip(input: InputStream, created: MutableList<File>): List<TaskTemplate> {
        var bundle: TemplateBundle? = null
        val images = mutableMapOf<String, ByteArray>()
        var total = 0L
        ZipInputStream(input).use { zip ->
            generateSequence { zip.nextEntry }.forEach { e ->
                when {
                    e.name == MANIFEST -> bundle = bundleOf(readLimited(zip, MAX_MANIFEST_BYTES).decodeToString())
                    // Только фото из images/ с простым именем: «../» и прочие пути пропускаются.
                    IMAGE_ENTRY.matches(e.name) && e.name !in images -> {
                        val bytes = readLimited(zip, MAX_IMAGE_BYTES)
                        total += bytes.size
                        if (total > MAX_TOTAL_BYTES) throw TemplateImportException("Файл шаблонов слишком большой")
                        images[e.name] = bytes
                    }
                }
            }
        }
        val b = bundle ?: throw TemplateImportException("Это не файл шаблонов SmartCalc")
        return localize(b.templates, created) { images[it] }
    }

    private fun importJson(text: String, created: MutableList<File>): List<TaskTemplate> =
        localize(bundleOf(text).templates, created) { ref ->
            // Фото — data URI или просто base64; нечитаемое фото пропускаем, а не роняем весь импорт.
            runCatching { Base64.getDecoder().decode(ref.substringAfter("base64,").trim()) }.getOrNull()
        }

    private fun importMarkdown(text: String): List<TaskTemplate> {
        val templates = TemplateMarkdown.parse(text)
        if (templates.isEmpty()) {
            throw TemplateImportException(
                "Это не файл шаблонов SmartCalc. В Markdown шаблон начинается с заголовка «# Название», " +
                    "дальше «## Требования», «## Пример 1», «### Условие», «### Решение».",
            )
        }
        return if (kind == TemplateKind.LIBRARY) {
            deduplicateLibraryImport(templates) { null }
        } else {
            templates
        }
    }

    private fun readLimited(input: InputStream, limit: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var read = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            read += n
            if (read > limit) throw TemplateImportException("Файл шаблонов слишком большой")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    suspend fun readImage(path: String): ByteArray? = withContext(Dispatchers.IO) {
        File(path).takeIf { it.exists() }?.readBytes()
    }

    private fun imagesOf(t: TaskTemplate) = t.examples.flatMap { example ->
        val archived = example.library?.previousVersions.orEmpty().flatMap {
            it.conditionImages + it.solutionImages
        }
        example.conditionImages + example.solutionImages + archived
    }.toSet()

    /** Publish metadata first. Shared images belong to the whole library, not one template. */
    private fun publish(updated: List<TaskTemplate>, created: List<File> = emptyList()) {
        val candidates = _items.value.flatMap(::imagesOf) + created.map { it.absolutePath }
        val keep = updated.flatMap(::imagesOf).toSet()
        writeSnapshot(updated)
        _items.value = updated
        // Cleanup failure must never roll back a published library or delete its staged photos.
        runCatching {
            val directory = imagesDir.canonicalFile
            val referenced = keep.map { File(it).canonicalFile }.toSet()
            candidates.map { File(it).canonicalFile }.distinct().filter {
                it.parentFile == directory && it !in referenced
            }.forEach { it.delete() }
        }
    }

    private fun writeSnapshot(updated: List<TaskTemplate>) {
        root.mkdirs()
        val temporary = File(root, "templates.json.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(json.encodeToString(updated).toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            Files.move(
                temporary.toPath(), file.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            if (temporary.isFile) temporary.delete()
        }
    }

    private fun writeImage(target: File, bytes: ByteArray) {
        FileOutputStream(target).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
    }
}
