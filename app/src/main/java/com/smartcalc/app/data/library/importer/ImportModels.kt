package com.smartcalc.app.data.library.importer

import com.smartcalc.app.data.library.extract.ExtractedSegment
import com.smartcalc.app.data.library.extract.ExtractionWarning
import com.smartcalc.app.data.library.source.SourceSegmentRef
import kotlinx.serialization.Serializable

@Serializable data class ImportDraft(
    val id: String, val revision: Int = 1, val condition: String, val solution: String,
    val answer: String = "", val conditionImages: List<String> = emptyList(),
    val solutionImages: List<String> = emptyList(), val refs: List<SourceSegmentRef>,
    val warnings: List<ExtractionWarning> = emptyList(), val excluded: Boolean = false,
)
@Serializable data class RecognitionCarry(val text: String, val refs: List<SourceSegmentRef>)
@Serializable data class RecognitionBatch(val segments: List<ExtractedSegment>, val carry: RecognitionCarry? = null)
@Serializable data class RecognitionResult(val drafts: List<ImportDraft>, val carry: RecognitionCarry? = null,
    val warnings: List<ExtractionWarning> = emptyList())
