package com.smartcalc.app.data.library.source

import kotlinx.serialization.Serializable

@Serializable enum class SourceFormat { PDF, DOCX, JPEG, PNG, TEXT, MARKDOWN }
@Serializable enum class SourceStatus { ACTIVE, DISABLED, DELETED }
@Serializable enum class SegmentKind { PDF_PAGE, DOCUMENT_BLOCK, IMAGE }
@Serializable data class SourceRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init {
        require(listOf(left, top, right, bottom).all { it.isFinite() && it in 0f..1f })
        require(left < right && top < bottom)
    }
}
@Serializable data class SourceSegmentRef(
    val sourceId: String, val segmentId: String, val kind: SegmentKind,
    val ordinal: Int, val region: SourceRect? = null,
) {
    init { require(ordinal > 0 && segmentId.isNotBlank() && sourceId.isNotBlank()) }
}
@Serializable data class LibrarySource(
    val id: String, val name: String, val format: SourceFormat, val sizeBytes: Long,
    val sha256: String, val relativePath: String, val segmentCount: Int? = null,
    val status: SourceStatus = SourceStatus.ACTIVE,
)
@Serializable data class SourceCatalog(
    val sources: List<LibrarySource> = emptyList(), val error: String? = null,
)
data class SourceCopyResult(val source: LibrarySource, val duplicate: Boolean)
