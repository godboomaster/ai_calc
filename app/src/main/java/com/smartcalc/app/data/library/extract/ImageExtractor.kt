package com.smartcalc.app.data.library.extract

import com.smartcalc.app.data.ImageUtils
import com.smartcalc.app.data.library.source.*
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object ImageExtractor {
    suspend fun extract(source: LibrarySource, original: File): ExtractedSegment {
        val file = File(original.parentFile, "prepared.jpg")
        prepare(original, file)
        return ExtractedSegment(SourceSegmentRef(source.id, "${source.id}:image:1", SegmentKind.IMAGE, 1), imagePaths = listOf(file.absolutePath))
    }
    suspend fun prepare(original: File, target: File): String {
        val bytes = ImageUtils.compressFile(original.absolutePath, maxPixels = 4_000_000)
        val temporary = File(target.parentFile, ".${target.name}.${java.util.UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
        return target.absolutePath
    }
}
