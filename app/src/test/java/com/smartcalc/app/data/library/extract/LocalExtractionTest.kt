package com.smartcalc.app.data.library.extract

import com.smartcalc.app.data.library.source.*
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import java.nio.charset.Charset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry

class LocalExtractionTest {
    @get:Rule val temp = TemporaryFolder()
    private fun source(format: SourceFormat = SourceFormat.TEXT) = LibrarySource(
        UUID.randomUUID().toString(), "fixture", format, 1, "0".repeat(64), "unused/original")
    private fun fixture(name: String): File = File("../docs/library-quality/fixtures", name)
        .takeIf { it.exists() } ?: File("docs/library-quality/fixtures", name)

    @Test fun textEncodingsPreserveModulusAndReferenceEveryBlock() {
        val src = source()
        val result = TextExtractor.extract(src, ByteArrayInputStream("Задача 1\nУсловие\n|x-4|=5".toByteArray(Charset.forName("windows-1251"))))
        assertTrue(result.segments.joinToString(" ") { it.text }.contains("|x-4|=5"))
        assertEquals(listOf(1, 2, 3), result.segments.map { it.ref.ordinal })
        assertTrue(result.segments.all { it.ref.sourceId == src.id })
    }
    @Test fun structuredTasksDoNotMergeDifferentTargetsAndKeepSeparateAnswers() {
        val result = TextExtractor.extract(source(), fixture("i01-utf8.txt").inputStream())
        val parsed = StructuredTaskParser.parse(result.segments)
        assertEquals(2, parsed.tasks.size)
        assertTrue(parsed.tasks[0].condition.contains("АЧХ"))
        assertTrue(parsed.tasks[1].condition.contains("ступень"))
        assertTrue(parsed.tasks[1].answer.contains("exp(-2*t)"))
        assertTrue(parsed.tasks.all { it.refs.isNotEmpty() })
    }
    @Test fun unstructuredTextIsNotSilentlyPublished() {
        val result = TextExtractor.extract(source(), ByteArrayInputStream("Обычный текст без границ задач".toByteArray()))
        val parsed = StructuredTaskParser.parse(result.segments)
        assertTrue(parsed.tasks.isEmpty())
        assertEquals(1, parsed.unresolvedSegmentIds.size)
    }
    @Test fun sameLineTaskHeadingKeepsTheRequestedQuantityInTheCondition() {
        val result = TextExtractor.extract(source(), "Задача 1. Найти силу тока.\nR=10, U=20\nРешение\nI=U/R\nОтвет\n2 A".byteInputStream())
        val parsed = StructuredTaskParser.parse(result.segments)
        assertEquals("Найти силу тока.\nR=10, U=20", parsed.tasks.single().condition)
        assertEquals("I=U/R", parsed.tasks.single().solution)
        assertEquals("2 A", parsed.tasks.single().answer)
        assertTrue(parsed.unresolvedSegmentIds.isEmpty())
    }
    @Test fun numberedTitleAndContinuedHeadingRetainTheirMeaningWithoutSplittingTask() {
        val result = TextExtractor.extract(source(), "Пример 2: Закон Ома\nУсловие\nНайти I\nРешение\nI=U/R\nПример 2 (продолжение): U=20, R=10\nОтвет\n2 A".byteInputStream())
        val task = StructuredTaskParser.parse(result.segments).tasks.single()
        assertEquals("Закон Ома\nНайти I", task.condition)
        assertEquals("I=U/R\nU=20, R=10", task.solution)
        assertEquals(result.segments.map { it.ref }, task.refs)
    }
    @Test fun numberOnlyHeadingsKeepSeparateTasksWithoutAddingLabelsToConditions() {
        val result = TextExtractor.extract(source(), "Задача 1.\nУсловие\nFind I\nОтвет\n2\nЗадача E02\nУсловие\nFind U\nОтвет\n20".byteInputStream())
        val parsed = StructuredTaskParser.parse(result.segments)
        assertEquals(listOf("Find I", "Find U"), parsed.tasks.map { it.condition })
        assertEquals(listOf("2", "20"), parsed.tasks.map { it.answer })
    }
    @Test fun imageOnlyBlocksStayAssignedToTheirConditionOrSolutionWithWarnings() {
        val src = source(SourceFormat.DOCX)
        fun ref(n: Int) = SourceSegmentRef(src.id, "${src.id}:block:$n", SegmentKind.DOCUMENT_BLOCK, n)
        val warning = ExtractionWarning("unknown", "Проверьте рисунок")
        val parsed = StructuredTaskParser.parse(listOf(
            ExtractedSegment(ref(1), "Задача 1\nУсловие\nFind x"),
            ExtractedSegment(ref(2), imagePaths = listOf("condition.jpg"), warnings = listOf(warning)),
            ExtractedSegment(ref(3), "Решение\nAuthor solution"),
            ExtractedSegment(ref(4), imagePaths = listOf("solution.jpg")),
            ExtractedSegment(ref(5), "Ответ\n2")))
        assertEquals((1..5).map { ref(it) }, parsed.tasks.single().refs)
        assertEquals(listOf("condition.jpg"), parsed.tasks.single().conditionImages)
        assertEquals(listOf("solution.jpg"), parsed.tasks.single().solutionImages)
        assertTrue(warning in parsed.tasks.single().warnings)
    }
    @Test fun textLimitRejectsRatherThanTruncating() {
        try { TextExtractor.extract(source(), ByteArrayInputStream(ByteArray(2 * 1024 * 1024 + 1) { 65 })); fail("limit") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun docxReadsActualMathTableAndStableBlockIds() {
        val src = source(SourceFormat.DOCX)
        val result = DocxExtractor.extract(src, fixture("i05-formulas.docx"), temp.newFolder())
        val text = result.segments.joinToString("\n") { it.text }
        assertTrue(text.contains("\\frac"))
        assertTrue(text.contains("\\sqrt"))
        assertTrue(text.contains("(6-K)/2"))
        assertTrue(result.segments.all { it.ref.kind == SegmentKind.DOCUMENT_BLOCK })
        val repeated = DocxExtractor.extract(src, fixture("i05-formulas.docx"), temp.newFolder())
        assertEquals(result.segments.map { it.ref.segmentId }, repeated.segments.map { it.ref.segmentId })
    }
    @Test fun unknownMathOperatorProducesBlockingWarning() {
        val unknown = "<m:oMath xmlns:m='http://schemas.openxmlformats.org/officeDocument/2006/math'><m:nary><m:e><m:r><m:t>x</m:t></m:r></m:e></m:nary></m:oMath>"
        assertTrue(OmmlText.convert(unknown).warnings.any { it.blocking })
    }
    @Test fun externalEntitiesAreRejectedWithoutOpeningFiles() {
        try { OmmlText.convert("<!DOCTYPE a [<!ENTITY evil SYSTEM 'file:///never-read'>]><a>&evil;</a>"); fail("DTD") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun tableAndMathWarningsCarryReferencesThroughTaskParsing() {
        val src = source()
        val ref = SourceSegmentRef(src.id, "${src.id}:block:1", SegmentKind.DOCUMENT_BLOCK, 1)
        val warning = ExtractionWarning("unclear", "Нечитаемый знак", true)
        val parsed = StructuredTaskParser.parse(listOf(ExtractedSegment(ref,
            "Задача 1\nУсловие\nx ? 1\nРешение\nИсходное решение\nОтвет\n2", emptyList(), listOf(warning))))
        assertEquals("2", parsed.tasks.single().answer)
        assertTrue(parsed.tasks.single().warnings.any { it.blocking })
        assertEquals(listOf(ref), parsed.tasks.single().refs)
    }
    @Test fun zipTraversalAndExcessiveXmlDepthAreRejected() {
        val archive = temp.newFile("bad.docx")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("../outside")); zip.write("bad".toByteArray()); zip.closeEntry()
        }
        try { DocxExtractor.extract(source(SourceFormat.DOCX), archive, temp.newFolder()); fail("path") }
        catch (_: IllegalArgumentException) { }
        try { OmmlText.convert("<a>".repeat(65) + "x" + "</a>".repeat(65)); fail("depth") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun unusedZipPartsAlsoConsumeUnpackedBudget() {
        val archive = temp.newFile("bomb.docx")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("unused.bin"))
            val chunk = ByteArray(8192) { 65 }
            repeat(64 * 1024 * 1024 / chunk.size) { zip.write(chunk) }; zip.write(65); zip.closeEntry()
        }
        try { DocxExtractor.extract(source(SourceFormat.DOCX), archive, temp.newFolder()); fail("64 MiB") }
        catch (_: IllegalArgumentException) { }
    }
}
