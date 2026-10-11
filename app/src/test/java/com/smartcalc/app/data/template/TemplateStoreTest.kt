package com.smartcalc.app.data.template

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
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
}
