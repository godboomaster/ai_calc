package com.smartcalc.app.data.template

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class TemplateStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val photo = byteArrayOf(1, 2, 3, 4, 5)

    private fun store() = TemplateStore(tmp.newFolder())

    private fun TemplateStore.addSample() = runBlocking {
        save(
            null,
            "Гурвиц",
            "Вывод в конце",
            listOf(ExampleDraft("Условие", emptyList(), "Решение", listOf(ImageDraft(bytes = photo)))),
        )
        items.value.single()
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            entries.forEach { (name, bytes) ->
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun expectImportError(store: TemplateStore, bytes: ByteArray, message: String) {
        try {
            runBlocking { store.import(ByteArrayInputStream(bytes)) }
            fail("импорт должен был не пройти")
        } catch (e: TemplateImportException) {
            assertTrue("«${e.message}»", e.message!!.startsWith(message))
        }
    }

    private fun TemplateStore.exportBytes(format: TemplateFormat) = runBlocking {
        ByteArrayOutputStream().also { export(null, it, format) }.toByteArray()
    }

    @Test
    fun attachCopiesTasksWithPhotosOnce() = runBlocking {
        val templates = store()
        val template = templates.addSample()
        val library = store()
        library.save(
            null,
            "Кинематика",
            "",
            listOf(ExampleDraft("Найти скорость", emptyList(), "Ответ: 5", listOf(ImageDraft(bytes = byteArrayOf(9, 9))))),
        )
        val tasks = library.items.value.single().examples
        assertEquals(1, templates.attach(template.id, tasks))
        // Те же задачи второй раз не добавляются.
        assertEquals(0, templates.attach(template.id, tasks))
        val attached = templates.items.value.single().examples.last()
        assertEquals("Найти скорость", attached.condition)
        // Фото — своя копия: удаление раздела базы шаблон не трогает.
        library.delete(library.items.value.single().id)
        assertArrayEquals(byteArrayOf(9, 9), File(attached.solutionImages.single()).readBytes())
        assertEquals(2, templates.items.value.single().examples.size)
    }

    @Test
    fun attachTreatsSameLengthDifferentImageContentAsDifferentTask() = runBlocking {
        val templates = store()
        val template = templates.addSample()
        val different = tmp.newFile("different-same-length.jpg").apply { writeBytes(byteArrayOf(9, 8, 7, 6, 5)) }
        val candidate = TemplateExample(
            condition = "Условие",
            solution = "Решение",
            solutionImages = listOf(different.absolutePath),
        )

        assertEquals(1, templates.attach(template.id, listOf(candidate)))

        assertEquals(2, templates.items.value.single().examples.size)
        assertArrayEquals(different.readBytes(), File(templates.items.value.single().examples.last().solutionImages.single()).readBytes())
    }

    @Test
    fun attachDeduplicatesSameImageContentFromDifferentPaths() = runBlocking {
        val templates = store()
        val template = templates.addSample()
        val sameContent = tmp.newFile("same-content.jpg").apply { writeBytes(photo) }
        val candidate = TemplateExample(
            condition = "Условие",
            solution = "Решение",
            solutionImages = listOf(sameContent.absolutePath),
        )

        assertEquals(0, templates.attach(template.id, listOf(candidate)))

        assertEquals(1, templates.items.value.single().examples.size)
        assertEquals(1, File(template.examples.single().solutionImages.single()).parentFile!!.listFiles()!!.size)
    }

    @Test
    fun attachMissingReferencedImageFailsWithoutChangingTemplate() = runBlocking {
        val root = tmp.newFolder("attach-missing")
        val templates = TemplateStore(root)
        val original = templates.addSample()
        val originalJson = File(root, "templates.json").readText()
        val originalImages = File(root, "templates").listFiles()!!.map { it.name }.sorted()
        val missing = File(root, "does-not-exist.jpg")
        val candidate = TemplateExample("new", listOf(missing.absolutePath), "solution")

        val failure = runCatching { templates.attach(original.id, listOf(candidate)) }.exceptionOrNull()

        assertNotNull("A missing attached image must fail instead of being silently dropped", failure)
        assertEquals(listOf(original), templates.items.value)
        assertEquals(originalJson, File(root, "templates.json").readText())
        assertEquals(originalImages, File(root, "templates").listFiles()!!.map { it.name }.sorted())
    }

    @Test
    fun failedAttachPublicationCleansStagedPhotosAndKeepsMemory() = runBlocking {
        val root = tmp.newFolder("failed-attach")
        val templates = TemplateStore(root)
        val original = templates.addSample()
        val originalImages = File(root, "templates").listFiles()!!.map { it.name }.sorted()
        val metadata = File(root, "templates.json")
        val previousMetadata = File(root, "templates.previous")
        assertTrue(metadata.renameTo(previousMetadata))
        assertTrue(metadata.mkdir())
        val source = tmp.newFile("attach-source.jpg").apply { writeBytes(byteArrayOf(9, 9, 9)) }
        val candidate = TemplateExample("new", emptyList(), "solution", listOf(source.absolutePath))

        val failure = runCatching { templates.attach(original.id, listOf(candidate)) }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals(listOf(original), templates.items.value)
        assertEquals(originalImages, File(root, "templates").listFiles()!!.map { it.name }.sorted())
        assertTrue(previousMetadata.readText().contains(original.id))
        assertTrue(source.exists())
    }

    @Test
    fun importReportsLoadedItems() = runBlocking {
        // «## Задача» — как пишут файл для базы задач; текст без «### Условие» — условие.
        val md = "# Кинематика\n## Задача 1\n### Условие\nНайти v\n### Решение\nОтвет: 5\n## Задача 2\nНайти t\n"
        val result = store().import(ByteArrayInputStream(md.toByteArray()))
        assertEquals(1, result.added)
        assertEquals(listOf("Найти v", "Найти t"), result.items.single().examples.map { it.condition })
        assertEquals("Ответ: 5", result.items.single().examples.first().solution)
    }

    @Test
    fun exportThenImportOnAnotherPhone() = runBlocking {
        val source = store()
        val original = source.addSample()
        val file = ByteArrayOutputStream()
        assertEquals(1, source.export(null, file))

        val target = store()
        assertEquals(ImportResult(added = 1, updated = 0), target.import(ByteArrayInputStream(file.toByteArray())))
        val copy = target.items.value.single()
        assertEquals(original.id, copy.id)
        assertEquals("Гурвиц", copy.name)
        assertEquals("Вывод в конце", copy.requirements)
        assertEquals("Решение", copy.examples.single().solution)
        // Картинка — уже свой файл на этом «телефоне», с тем же содержимым.
        val image = File(copy.examples.single().solutionImages.single())
        assertTrue(image.exists())
        assertArrayEquals(photo, image.readBytes())

        // Повторный импорт того же файла обновляет шаблон, а не дублирует его.
        assertEquals(ImportResult(added = 0, updated = 1), target.import(ByteArrayInputStream(file.toByteArray())))
        assertEquals(1, target.items.value.size)
        assertTrue(!image.exists())
    }

    @Test
    fun exportsOnlyChosenTemplates() = runBlocking {
        val source = store()
        val first = source.addSample()
        source.save(null, "Второй", "", emptyList())
        val file = ByteArrayOutputStream()
        assertEquals(1, source.export(listOf(first.id), file))
        val target = store()
        target.import(ByteArrayInputStream(file.toByteArray()))
        assertEquals(listOf("Гурвиц"), target.items.value.map { it.name })
    }

    @Test
    fun jsonKeepsPhotos() = runBlocking {
        val source = store()
        source.addSample()
        val json = source.exportBytes(TemplateFormat.JSON)
        assertTrue(json.decodeToString().contains("data:image/jpeg;base64,"))

        val target = store()
        assertEquals(ImportResult(added = 1, updated = 0), target.import(ByteArrayInputStream(json)))
        val image = File(target.items.value.single().examples.single().solutionImages.single())
        assertArrayEquals(photo, image.readBytes())
    }

    @Test
    fun markdownKeepsTextButNotPhotos() = runBlocking {
        val source = store()
        val original = source.addSample()
        val md = source.exportBytes(TemplateFormat.MARKDOWN)
        val text = md.decodeToString()
        assertTrue(text.startsWith("# Шаблон: Гурвиц"))
        assertTrue(text.contains("### Решение\n\nРешение"))

        val target = store()
        target.import(ByteArrayInputStream(md))
        val copy = target.items.value.single()
        assertEquals(original.id, copy.id)
        assertEquals("Вывод в конце", copy.requirements)
        assertEquals("Условие", copy.examples.single().condition)
        assertTrue(copy.examples.single().solutionImages.isEmpty())
        // id в комментарии: повторный импорт обновляет шаблон.
        assertEquals(ImportResult(added = 0, updated = 1), target.import(ByteArrayInputStream(md)))
    }

    @Test
    fun repositoryExamplesImport() = runBlocking {
        // Образцы из examples/ — их скачивают и берут за основу, поэтому они должны импортироваться.
        val examples = File("../examples").takeIf { it.exists() } ?: File("examples")
        val fromZip = store()
        assertEquals(ImportResult(added = 1, updated = 0), fromZip.import(File(examples, "example-template.zip").inputStream()))
        val zipped = fromZip.items.value.single()
        assertEquals("Устойчивость по Гурвицу", zipped.name)
        assertEquals(2, zipped.examples.size)
        assertEquals(1, zipped.examples[0].solutionImages.size)

        // Markdown-образец — тот же шаблон, только без фото.
        val fromMd = store()
        fromMd.import(File(examples, "example-template.md").inputStream())
        val md = fromMd.items.value.single()
        assertEquals(zipped.name, md.name)
        assertEquals(zipped.requirements, md.requirements)
        assertEquals(zipped.examples.map { it.condition }, md.examples.map { it.condition })
        assertEquals(zipped.examples.map { it.solution }, md.examples.map { it.solution })
    }

    @Test
    fun everyRepositoryExampleImports() = runBlocking {
        val examples = File("../examples").takeIf { it.exists() } ?: File("examples")
        suspend fun import(name: String): List<TaskTemplate> {
            val target = store()
            target.import(File(examples, name).inputStream())
            return target.items.value
        }
        // JSON с фото внутри — фото на месте.
        val json = import("example-template.json").single()
        assertEquals(1, json.examples[0].solutionImages.size)
        // JSON без фото.
        val text = import("example-template-text.json").single()
        assertEquals("Устойчивость по Гурвицу (без фото)", text.name)
        assertEquals(2, text.examples.size)
        // Два шаблона в свободном Markdown.
        val two = import("two-templates.md")
        assertEquals(listOf("Критерий Рауса", "Квадратное уравнение"), two.map { it.name })
        assertTrue(two[0].requirements.startsWith("Строить таблицу Рауса"))
        assertTrue(two[0].examples.single().condition.startsWith("Исследовать устойчивость"))
        assertTrue(two[0].examples.single().solution.contains("| \$s^3\$ | 1 | 3 |"))
        assertEquals("Решите уравнение \$x^2 - 5x + 6 = 0\$.", two[1].examples.single().condition)
    }

    @Test
    fun manifestAloneImportsAsJson() = runBlocking {
        // manifest.json без архива — это JSON-файл шаблонов; фото по ссылке images/… в нём нет — пропускается.
        val examples = File("../examples").takeIf { it.exists() } ?: File("examples")
        val target = store()
        assertEquals(ImportResult(added = 1, updated = 0), target.import(File(examples, "template-zip/manifest.json").inputStream()))
        val t = target.items.value.single()
        assertEquals("Устойчивость по Гурвицу", t.name)
        assertEquals(2, t.examples.size)
        assertTrue(t.examples.all { it.solutionImages.isEmpty() && it.conditionImages.isEmpty() })
    }

    @Test
    fun foreignAndBrokenFilesAreRejected() {
        val target = store()
        expectImportError(target, "просто текст".toByteArray(), "Это не файл шаблонов SmartCalc")
        expectImportError(target, zip("manifest.json" to """{"format":"other","version":1,"templates":[]}""".toByteArray()), "Это не файл шаблонов SmartCalc")
        expectImportError(target, zip("manifest.json" to "{не json".toByteArray()), "Не удалось прочитать файл шаблонов")
        assertTrue(target.items.value.isEmpty())
    }

    @Test
    fun archiveCannotWriteOutsideTemplatesFolder() = runBlocking {
        val root = tmp.newFolder()
        val target = TemplateStore(root)
        val manifest = """{"format":"smartcalc-templates","version":1,"templates":[{"id":"t","name":"x",
            "examples":[{"solutionImages":["../../evil.jpg","images/ok.jpg"]}]}]}""".toByteArray()
        target.import(ByteArrayInputStream(zip("manifest.json" to manifest, "../../evil.jpg" to photo, "images/ok.jpg" to photo)))
        // Запись с «../» пропущена, а картинка из images/ сохранена под новым именем внутри templates/.
        val images = target.items.value.single().examples.single().solutionImages
        assertEquals(1, images.size)
        assertEquals(File(root, "templates").canonicalPath, File(images.single()).parentFile!!.canonicalPath)
        assertTrue(!File(root.parentFile, "evil.jpg").exists())
    }

    private fun sharedPhotoBundle(): ByteArray = zip(
        "manifest.json" to """{"format":"smartcalc-templates","version":1,"templates":[
            {"id":"a","name":"A","examples":[{"solutionImages":["images/common.jpg"]}]},
            {"id":"b","name":"B","examples":[{"solutionImages":["images/common.jpg"]}]}]}""".toByteArray(),
        "images/common.jpg" to photo,
    )

    @Test fun deletingTemplatePreservesAnotherTemplatesSharedPhoto() = runBlocking {
        val target = store()
        target.import(ByteArrayInputStream(sharedPhotoBundle()))
        target.delete("a")
        val path = target.items.value.single().examples.single().solutionImages.single()
        assertArrayEquals(photo, target.readImage(path))
        val copy = store()
        copy.import(ByteArrayInputStream(target.exportBytes(TemplateFormat.ZIP)))
        assertEquals(1, copy.items.value.single().examples.single().solutionImages.size)
    }

    @Test fun editingTemplatePreservesAnotherTemplatesSharedPhoto() = runBlocking {
        val target = store()
        target.import(ByteArrayInputStream(sharedPhotoBundle()))
        target.save("a", "A changed", "", emptyList())
        val path = target.items.value.first { it.id == "b" }.examples.single().solutionImages.single()
        assertArrayEquals(photo, target.readImage(path))
    }

    @Test fun replacingOneTemplatePreservesAnotherTemplatesSharedPhoto() = runBlocking {
        val target = store()
        target.import(ByteArrayInputStream(sharedPhotoBundle()))
        target.import(ByteArrayInputStream("""{"format":"smartcalc-templates","version":1,
            "templates":[{"id":"a","name":"A changed"}]}""".toByteArray()))
        val path = target.items.value.first { it.id == "b" }.examples.single().solutionImages.single()
        assertArrayEquals(photo, target.readImage(path))
    }

    @Test fun failedPublicationPreservesMetadataPhotosAndMemory() = runBlocking {
        for (operation in listOf("save", "delete", "import")) {
            val root = tmp.newFolder()
            val target = TemplateStore(root)
            val original = target.addSample()
            val previousJson = File(root, "templates.json").readText()
            val previousPhoto = File(original.examples.single().solutionImages.single())
            // A filesystem failure opening the staging file must not damage the live library.
            File(root, "templates.json.tmp").mkdir()
            assertThrows(IOException::class.java) {
                runBlocking {
                    when (operation) {
                        "save" -> target.save(original.id, "changed", "", emptyList())
                        "delete" -> target.delete(original.id)
                        else -> target.import(ByteArrayInputStream("""{"format":"smartcalc-templates","version":1,
                            "templates":[{"id":"${original.id}","name":"changed",
                            "examples":[{"solutionImages":["data:image/jpeg;base64,AQID"]}]}]}""".toByteArray()))
                    }
                }
            }
            assertEquals(listOf(original), target.items.value)
            assertEquals(previousJson, File(root, "templates.json").readText())
            assertEquals(listOf(original), TemplateStore(root).items.value)
            assertArrayEquals(photo, previousPhoto.readBytes())
            assertEquals(1, File(root, "templates").listFiles()!!.size)
        }
    }

    @Test fun duplicateIdsDoNotLeaveUnusedPhotos() = runBlocking {
        val root = tmp.newFolder()
        val target = TemplateStore(root)
        val bundle = zip("manifest.json" to """{"format":"smartcalc-templates","version":1,"templates":[
            {"id":"a","name":"first","examples":[{"solutionImages":["images/first.jpg"]}]},
            {"id":"a","name":"duplicate","examples":[{"solutionImages":["images/second.jpg"]}]}]}""".toByteArray(),
            "images/first.jpg" to photo, "images/second.jpg" to byteArrayOf(6, 7, 8))
        target.import(ByteArrayInputStream(bundle))
        assertEquals("first", target.items.value.single().name)
        assertEquals(1, File(root, "templates").listFiles()!!.size)
    }

    @Test fun unsupportedVersionDoesNotReplaceExistingTemplates() = runBlocking {
        val target = store()
        val original = target.addSample()
        assertThrows(TemplateImportException::class.java) {
            runBlocking {
                target.import(ByteArrayInputStream("""{"format":"smartcalc-templates","version":2,
                    "templates":[{"id":"${original.id}","name":"unsupported"}]}""".toByteArray()))
            }
        }
        assertEquals(listOf(original), target.items.value)
        assertArrayEquals(photo, target.readImage(original.examples.single().solutionImages.single()))
    }
}
