package com.smartcalc.app.data

import org.junit.Assert.*
import org.junit.Test

class ImageDecodePlanTest {
    @Test fun highResolutionPhotosAreSampledBeforeAllocation() {
        assertEquals(4, imageDecodeSampleSize(12000, 9000, 1568))
        assertEquals(4, imageDecodeSampleSize(9000, 12000, 1568))
        assertEquals(2, imageDecodeSampleSize(4000, 3000, 1568))
        assertEquals(1, imageDecodeSampleSize(800, 600, 1568))
    }

    @Test fun invalidAndUnreasonableDimensionsAreRejected() {
        for ((w, h) in listOf(0 to 100, -1 to -1, Int.MAX_VALUE to 500, 50000 to 50000)) {
            assertThrows(DocumentException::class.java) { imageDecodeSampleSize(w, h, 1568) }
        }
    }
}
