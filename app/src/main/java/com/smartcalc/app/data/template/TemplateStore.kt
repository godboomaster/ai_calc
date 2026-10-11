package com.smartcalc.app.data.template

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
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
)

class TemplateImportException(message: String) : Exception(message)

/** Итог импорта: сколько шаблонов новых, сколько заменили уже бывшие (тот же шаблон, импорт повторно). */
data class ImportResult(val added: Int, val updated: Int) {
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
class TemplateStore(private val root: File) {

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

    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<TaskTemplate>> = _items

    private fun load(): List<TaskTemplate> = try {
        if (file.exists()) json.decodeFromString<List<TaskTemplate>>(file.readText()) else emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * Сохраняет шаблон из редактора: новые картинки пишутся в файлы, картинки, убранные из шаблона,
     * удаляются. [id] — null для нового шаблона.
     */
    suspend fun save(id: String?, name: String, requirements: String, examples: List<ExampleDraft>) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
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
                    val template = TaskTemplate(
                        id = id ?: UUID.randomUUID().toString(),
                        name = name.trim(),
                        requirements = requirements.trim(),
                        examples = examples.map {
                            TemplateExample(it.condition.trim(), store(it.conditionImages), it.solution.trim(), store(it.solutionImages))
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

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
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
                        ex.copy(conditionImages = refs(ex.conditionImages), solutionImages = refs(ex.solutionImages))
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
    suspend fun import(input: InputStream): ImportResult = withContext(Dispatchers.IO) {
        mutex.withLock {
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

    /**
     * Дописывает к шаблону [templateId] задачи из другого хранилища (из базы задач). Фото копируются в
     * свои файлы: шаблон не зависит от того, удалят ли задачу из базы. Задача, которая в шаблоне уже
     * есть, второй раз не добавляется. Возвращает число добавленных задач.
     */
    suspend fun attach(templateId: String, examples: List<TemplateExample>): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
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
                    it.copy(conditionImages = copy(it.conditionImages), solutionImages = copy(it.solutionImages))
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
        val bundle = json.decodeFromString<TemplateBundle>(text).takeIf { it.format == BUNDLE_FORMAT }
            ?: throw TemplateImportException("Это не файл шаблонов SmartCalc")
        if (bundle.version != 1) throw TemplateImportException("Эта версия файла шаблонов не поддерживается")
        return bundle
    }

    /** Фото из ссылок шаблона в свои файлы; [load] возвращает байты по ссылке или null. */
    private fun localize(
        templates: List<TaskTemplate>,
        created: MutableList<File>,
        load: (String) -> ByteArray?,
    ): List<TaskTemplate> {
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
        return templates.distinctBy { it.id }.map { t ->
            t.copy(
                examples = t.examples.map { ex ->
                    ex.copy(conditionImages = local(ex.conditionImages), solutionImages = local(ex.solutionImages))
                },
            )
        }
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
        return templates
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

    private fun imagesOf(t: TaskTemplate) = t.examples.flatMap { it.conditionImages + it.solutionImages }.toSet()

    /** Publish metadata first. Shared images belong to the whole library, not one template. */
    private fun publish(updated: List<TaskTemplate>, created: List<File> = emptyList()) {
        val candidates = _items.value.flatMap(::imagesOf) + created.map { it.absolutePath }
        val keep = updated.flatMap(::imagesOf).toSet()
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

    private fun writeImage(target: File, bytes: ByteArray) {
        FileOutputStream(target).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
    }
}
