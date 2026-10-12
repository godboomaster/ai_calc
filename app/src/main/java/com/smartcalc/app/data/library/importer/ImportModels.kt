package com.smartcalc.app.data.library.importer

import com.smartcalc.app.data.library.extract.ExtractedSegment
import com.smartcalc.app.data.library.extract.ExtractionWarning
import com.smartcalc.app.data.library.source.SourceSegmentRef
import kotlinx.serialization.Serializable
import com.smartcalc.app.data.library.extract.PageSelection
import com.smartcalc.app.data.template.TaskTemplate

@Serializable data class ImportDraft(
    val id: String, val revision: Int = 1, val condition: String, val solution: String,
    val answer: String = "", val conditionImages: List<String> = emptyList(),
    val solutionImages: List<String> = emptyList(), val refs: List<SourceSegmentRef>,
    val warnings: List<ExtractionWarning> = emptyList(), val excluded: Boolean = false,
)
@Serializable data class RecognitionCarry(val text: String, val refs: List<SourceSegmentRef>,
    val segments: List<ExtractedSegment> = emptyList())
@Serializable data class RecognitionBatch(val segments: List<ExtractedSegment>, val carry: RecognitionCarry? = null)
@Serializable data class RecognitionResult(val drafts: List<ImportDraft>, val carry: RecognitionCarry? = null,
    val warnings: List<ExtractionWarning> = emptyList())

@Serializable enum class ImportStatus { COPIED, EXTRACTING, RECOGNIZING, REVIEW_REQUIRED, PUBLISHING, READY, PAUSED, FAILED, INTERRUPTED, CANCELLED }
@Serializable data class PostAttempt(val number: Int, val segmentIds: List<String>, val responseRelativePath: String? = null)
@Serializable data class PublicationItem(val exampleId: String, val draftId: String, val draftRevision: Int,
    val condition: String, val solution: String, val answer: String,
    val conditionImages: List<String>, val solutionImages: List<String>, val refs: List<SourceSegmentRef>)
@Serializable data class PublicationIntent(val commitId: String, val jobId: String, val sectionId: String,
    val sectionName: String, val expectedSection: TaskTemplate? = null, val items: List<PublicationItem>)
@Serializable data class ImportReceipt(val commitId: String, val intentSha256: String, val sectionId: String,
    val exampleIds: List<String>, val descriptorSha256: String = "", val mediaSha256: Map<String, String> = emptyMap())
@Serializable data class ImportJob(
    val id: String, val sourceIds: List<String>, val selections: Map<String, PageSelection>,
    val status: ImportStatus = ImportStatus.COPIED, val attempt: Long = 0, val postsUsed: Int = 0,
    val segments: List<ExtractedSegment> = emptyList(), val processedSegmentIds: Set<String> = emptySet(),
    val drafts: List<ImportDraft> = emptyList(), val carry: RecognitionCarry? = null,
    val activeBatch: RecognitionBatch? = null, val inFlightPost: PostAttempt? = null,
    val publication: PublicationIntent? = null, val receipt: ImportReceipt? = null,
    val publishedDraftIds: Set<String> = emptySet(), val error: String? = null,
    val waitingForSolution: Boolean = false,
)
@Serializable data class ImportCatalog(val jobs: List<ImportJob> = emptyList(), val error: String? = null)
