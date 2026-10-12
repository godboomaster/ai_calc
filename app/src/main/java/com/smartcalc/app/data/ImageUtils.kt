package com.smartcalc.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.math.max
import kotlin.math.roundToInt

object ImageUtils {

    private const val MAX_SIDE = 1568
    private const val JPEG_QUALITY = 85

    suspend fun compressFile(path: String, maxPixels: Int? = null): ByteArray = withContext(Dispatchers.IO) {
        compressSource(maxPixels) { File(path).inputStream() }
    }

    suspend fun compressUri(context: Context, uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        compressSource {
            context.contentResolver.openInputStream(uri)
                ?: throw DocumentException("Не удалось открыть изображение")
        }
    }

    private fun compressSource(maxPixels: Int? = null, open: () -> InputStream): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        val options = BitmapFactory.Options().apply {
            inSampleSize = imageDecodeSampleSize(bounds.outWidth, bounds.outHeight, MAX_SIDE)
            if (maxPixels != null) {
                require(maxPixels > 0)
                while (((bounds.outWidth + inSampleSize - 1) / inSampleSize).toLong() *
                    ((bounds.outHeight + inSampleSize - 1) / inSampleSize) > maxPixels) inSampleSize *= 2
            }
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val orientation = try {
            open().use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        } catch (_: IOException) {
            // Some decodable formats do not have an EXIF block.
            ExifInterface.ORIENTATION_NORMAL
        }
        val bitmap = open().use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw DocumentException("Не удалось прочитать изображение")
        try {
            val oriented = applyExifOrientation(bitmap, orientation)
            try {
                return compressBitmap(oriented)
            } finally {
                if (oriented !== bitmap) oriented.recycle()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(270f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /** Уменьшает до [MAX_SIDE] и сжимает в JPEG; вызывать не на главном потоке. */
    fun compressBitmap(bitmap: Bitmap): ByteArray {
        val largest = max(bitmap.width, bitmap.height)
        val scaled = if (largest > MAX_SIDE) {
            val scale = MAX_SIDE.toFloat() / largest
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).roundToInt().coerceAtLeast(1),
                (bitmap.height * scale).roundToInt().coerceAtLeast(1),
                true,
            )
        } else {
            bitmap
        }
        try {
            val out = ByteArrayOutputStream()
            if (!scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                throw DocumentException("Не удалось подготовить изображение")
            }
            return out.toByteArray()
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }
}
