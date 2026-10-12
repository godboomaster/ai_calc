package com.smartcalc.app.ui.library

import android.content.Context
import android.content.Intent
import android.content.ClipData
import androidx.core.content.FileProvider
import com.smartcalc.app.data.library.source.SourceFormat
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun openLibraryOriginal(context: Context, vm: LibraryImportViewModel, sourceId: String) {
    val source = checkNotNull(vm.source(sourceId))
    val (extension, mime) = when (source.format) {
        SourceFormat.PDF -> "pdf" to "application/pdf"
        SourceFormat.DOCX -> "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        SourceFormat.PNG -> "png" to "image/png"
        SourceFormat.JPEG -> "jpg" to "image/jpeg"
        else -> "txt" to "text/plain"
    }
    val shared = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "shared/${UUID.randomUUID()}").apply { mkdirs() }
        File(directory, "original.$extension").also { vm.originalFile(sourceId).copyTo(it, overwrite = false) }
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", shared)
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).also { it.clipData = ClipData.newRawUri("Оригинал", uri) }
    context.startActivity(intent)
}
