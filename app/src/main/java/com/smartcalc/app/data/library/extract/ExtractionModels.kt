package com.smartcalc.app.data.library.extract

import com.smartcalc.app.data.library.source.SourceSegmentRef
import kotlinx.serialization.Serializable

@Serializable data class ExtractionWarning(val code: String, val detail: String, val blocking: Boolean = true)
@Serializable data class ExtractedSegment(
    val ref: SourceSegmentRef, val text: String = "", val imagePaths: List<String> = emptyList(),
    val warnings: List<ExtractionWarning> = emptyList(),
)
data class ExtractionResult(val segments: List<ExtractedSegment>, val totalSegments: Int,
    val remainingOrdinals: List<Int> = emptyList())
data class ParsedTask(val condition: String, val solution: String, val answer: String,
    val refs: List<SourceSegmentRef>, val warnings: List<ExtractionWarning>,
    val conditionImages: List<String> = emptyList(), val solutionImages: List<String> = emptyList())
data class StructuredParseResult(val tasks: List<ParsedTask>, val unresolvedSegmentIds: List<String>)
data class OmmlResult(val text: String, val warnings: List<ExtractionWarning>)
