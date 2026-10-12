package com.smartcalc.app.data.library.extract

import org.junit.Assert.*
import org.junit.Test

class PageSelectionTest {
    @Test fun permitsOneHundredPagesButRejectsLargerOrInvalidSelections() {
        assertEquals(100, PageSelection((1..100).toList()).ordinals.size)
        for (invalid in listOf(emptyList(), listOf(0), listOf(2, 1), listOf(1, 1), (1..101).toList())) {
            try { PageSelection(invalid); fail("invalid selection") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun dimensionsRespectPixelBudgetForWideTallAndOrdinaryPages() {
        for ((width, height) in listOf(595 to 842, 10000 to 10000, 1 to 100000, 100000 to 1)) {
            val (w, h) = rasterDimensions(width, height)
            assertTrue(w > 0 && h > 0)
            assertTrue(w.toLong() * h <= 4_000_000)
        }
    }
}
