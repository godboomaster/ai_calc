package com.smartcalc.app.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.abs

class ImageUtilsDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    // Expected clockwise corner ordering is hand-derived, independent of production transforms.
    private val original = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
    private val corners = listOf(
        listOf(0, 1, 2, 3), listOf(1, 0, 3, 2), listOf(3, 2, 1, 0), listOf(2, 3, 0, 1),
        listOf(0, 2, 1, 3), listOf(2, 0, 3, 1), listOf(3, 1, 2, 0), listOf(1, 3, 0, 2),
    )

    @Test fun uriPhotosRespectAllExifOrientations() = checkOrientations(useUri = true)
    @Test fun cameraFilesRespectAllExifOrientations() = checkOrientations(useUri = false)

    private fun checkOrientations(useUri: Boolean) = runBlocking {
        for (orientation in 1..8) {
            val file = File.createTempFile("review-exif-", ".jpg", context.cacheDir)
            val source = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
            try {
                for (y in 0 until 80) for (x in 0 until 120) {
                    source.setPixel(x, y, original[(if (y >= 40) 2 else 0) + (if (x >= 60) 1 else 0)])
                }
                file.outputStream().use { source.compress(Bitmap.CompressFormat.JPEG, 100, it) }
                ExifInterface(file).apply {
                    setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                    saveAttributes()
                }
                val bytes = if (useUri) ImageUtils.compressUri(context, Uri.fromFile(file))
                    else ImageUtils.compressFile(file.absolutePath)
                val result = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                try {
                    assertEquals("width for EXIF $orientation", if (orientation >= 5) 80 else 120, result.width)
                    assertEquals("height for EXIF $orientation", if (orientation >= 5) 120 else 80, result.height)
                    for (corner in 0..3) {
                        val x = if (corner % 2 == 0) result.width / 4 else result.width * 3 / 4
                        val y = if (corner < 2) result.height / 4 else result.height * 3 / 4
                        val actual = result.getPixel(x, y)
                        val expected = original[corners[orientation - 1][corner]]
                        assertTrue("EXIF $orientation corner $corner", abs(Color.red(actual) - Color.red(expected)) < 35 &&
                            abs(Color.green(actual) - Color.green(expected)) < 35 && abs(Color.blue(actual) - Color.blue(expected)) < 35)
                    }
                } finally { result.recycle() }
            } finally { source.recycle(); file.delete() }
        }
    }
}
