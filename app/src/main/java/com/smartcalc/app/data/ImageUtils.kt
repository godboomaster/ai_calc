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
import kotlin.math.max
import kotlin.math.roundToInt

object ImageUtils {

    private const val MAX_SIDE = 1568
    private const val JPEG_QUALITY = 85

    suspend fun compressFile(path: String): ByteArray = withContext(Dispatchers.IO) {
        val bitmap = BitmapFactory.decodeFile(path)
            ?: throw IllegalStateException("Не удалось прочитать фото")
        val rotated = applyExifRotation(path, bitmap)
        compressBitmap(rotated)
    }

    suspend fun compressUri(context: Context, uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it)
        } ?: throw IllegalStateException("Не удалось открыть изображение")
        compressBitmap(bitmap)
    }

    private fun applyExifRotation(path: String, bitmap: Bitmap): Bitmap {
        val rotation = when (
            ExifInterface(path).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        ) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bitmap
        }
        val matrix = Matrix().apply { postRotate(rotation) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /** Уменьшает до [MAX_SIDE] и сжимает в JPEG; вызывать не на главном потоке. */
    fun compressBitmap(bitmap: Bitmap): ByteArray {
        val largest = max(bitmap.width, bitmap.height)
        val scaled = if (largest > MAX_SIDE) {
            val scale = MAX_SIDE.toFloat() / largest
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).roundToInt(),
                (bitmap.height * scale).roundToInt(),
                true,
            )
        } else {
            bitmap
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        return out.toByteArray()
    }
}
