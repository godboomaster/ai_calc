package com.smartcalc.app.ui.library

import com.smartcalc.app.data.library.importer.ImportDraft
import com.smartcalc.app.data.library.extract.ExtractionWarning
import com.smartcalc.app.data.library.source.*
import com.smartcalc.app.data.template.TemplateExample
import com.smartcalc.app.data.library.LibraryMetadata
import com.smartcalc.app.data.library.LibraryVerification
import com.smartcalc.app.ui.template.ExampleEditState
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class ImportReviewStateTest {
    private val ref = SourceSegmentRef(UUID.randomUUID().toString(), "page", SegmentKind.PDF_PAGE, 1)
    private val draft = ImportDraft(UUID.randomUUID().toString(), condition = "Condition", solution = "Solution", answer = "42", refs = listOf(ref))
    @Test fun unresolvedWarningExclusionAndMissingFieldsPreventPublishing() {
        assertTrue(ImportReviewState(listOf(draft), setOf(draft.id)).canPublish)
        assertFalse(ImportReviewState(listOf(draft.copy(warnings = listOf(ExtractionWarning("unclear", "Знак")))), setOf(draft.id)).canPublish)
        assertFalse(ImportReviewState(listOf(draft.copy(excluded = true)), setOf(draft.id)).canPublish)
        assertFalse(ImportReviewState(listOf(draft.copy(condition = "")), setOf(draft.id)).canPublish)
        assertFalse(ImportReviewState(listOf(draft), setOf(draft.id), publishedIds = setOf(draft.id)).canPublish)
    }
    @Test fun repeatedSourceRequiresComparisonAcknowledgementAndKeepsLocalEditsVisible() {
        val previous = TemplateExample("Locally corrected", solution = "Local solution", answer = "43",
            library = LibraryMetadata(id = UUID.randomUUID().toString(), sourceRefs = listOf(ref)))
        val section = com.smartcalc.app.data.template.TaskTemplate(name = "Previous", examples = listOf(previous))
        assertEquals(previous, comparableExamples(draft, listOf(section), "new-job").single().example)
        assertFalse(ImportReviewState(listOf(draft), setOf(draft.id), repeatRequired = true).canPublish)
        assertTrue(ImportReviewState(listOf(draft), setOf(draft.id), repeatRequired = true, repeatConfirmed = true).canPublish)
        assertTrue(comparableExamples(draft.copy(refs = listOf(ref.copy(sourceId = UUID.randomUUID().toString()))), listOf(section), "new-job").isEmpty())
    }
    @Test fun processedOrdinalSummaryPreservesGaps() {
        assertEquals("1-3, 6, 8-9", formatOrdinalRanges(listOf(9, 1, 3, 2, 6, 8, 2)))
    }
    @Test fun answerEditorPreservesValueAndResetsVerificationWhenChanged() {
        val editor = ExampleEditState(TemplateExample("Condition", solution = "Solution", answer = "42",
            library = LibraryMetadata(id = UUID.randomUUID().toString(), verification = LibraryVerification.VERIFIED_BY_USER)))
        assertEquals("42", editor.toDraft().answer)
        editor.answer.updateText("43")
        assertEquals(LibraryVerification.UNVERIFIED, editor.verification)
        assertEquals("43", editor.toDraft().answer)
    }
    @Test fun pageRangeRejectsGapsOutsideDocumentAndMoreThanHundredSelections() {
        assertEquals(listOf(1, 2, 3, 6), parsePageSelection("1-3,6", 6).ordinals)
        for (bad in listOf("0", "7", "3-1", "1-101", "a")) {
            try { parsePageSelection(bad, 6); fail("invalid range") } catch (_: IllegalArgumentException) { }
        }
    }
}
