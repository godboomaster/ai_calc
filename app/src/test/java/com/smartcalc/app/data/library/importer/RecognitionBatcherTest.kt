package com.smartcalc.app.data.library.importer

import com.smartcalc.app.data.library.extract.*
import com.smartcalc.app.data.library.source.*
import org.junit.Assert.*
import org.junit.Test

class RecognitionBatcherTest {
    private fun segment(n: Int, text: String = "", visual: Boolean = false) = ExtractedSegment(
        SourceSegmentRef("source", "s$n", SegmentKind.PDF_PAGE, n), text,
        if (visual) listOf("image$n") else emptyList())
    @Test fun keepsTwoPagesAndIncludesCarryInBudget() {
        val batch = RecognitionBatcher.next((1..3).map { segment(it, visual = true) }, null)!!
        assertEquals(2, batch.segments.size)
        val text = RecognitionBatcher.next(listOf(segment(1, "a".repeat(11999))), RecognitionCarry("b", emptyList()))!!
        assertEquals(12000, text.segments.sumOf { it.text.length } + text.carry!!.text.length)
    }
    @Test fun doesNotSilentlyTruncateOversizedConditionOrCarry() {
        try { RecognitionBatcher.next(listOf(segment(1, "x".repeat(12001))), null); fail("oversized") }
        catch (_: IllegalArgumentException) { }
        try { RecognitionBatcher.next(listOf(segment(1, "x")), RecognitionCarry("a".repeat(12000), emptyList())); fail("carry") }
        catch (_: IllegalArgumentException) { }
    }
}
