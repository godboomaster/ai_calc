package com.smartcalc.app.data.library.agent

import com.smartcalc.app.data.library.LibraryVerification
import com.smartcalc.app.data.library.source.SourceCatalog
import com.smartcalc.app.data.library.source.SegmentKind
import com.smartcalc.app.data.template.TaskTemplate
import kotlinx.serialization.Serializable

data class LibraryCatalog(val sections: List<TaskTemplate>, val sources: SourceCatalog = SourceCatalog(), val error: String? = null)

@Serializable
data class LibraryExampleSource(val sourceId: String, val name: String, val kind: SegmentKind, val ordinal: Int)

/** Textual version handed to the model. No local paths or automatic image/file upload. */
@Serializable
data class LibraryExampleSnapshot(
    val id: String,
    val version: Int,
    val section: String,
    val condition: String,
    val solution: String,
    val answer: String,
    val verification: LibraryVerification,
    val origin: String,
    val sources: List<LibraryExampleSource> = emptyList(),
    val hasImages: Boolean = false,
    val imagesNotSent: Boolean = hasImages,
)

@Serializable data class LibraryToolEvent(val name: String, val status: String)
@Serializable data class LibraryAgentReport(
    val posts: Int = 0,
    val events: List<LibraryToolEvent> = emptyList(),
    val examples: List<LibraryExampleSnapshot> = emptyList(),
)

fun LibraryAgentReport.redact(secret: String): LibraryAgentReport {
    if (secret.isBlank()) return this
    fun clean(value: String) = value.replace(secret, "[скрыто]")
    return copy(examples = examples.map { ex -> ex.copy(section = clean(ex.section),
        condition = clean(ex.condition), solution = clean(ex.solution), answer = clean(ex.answer),
        origin = clean(ex.origin), sources = ex.sources.map { it.copy(name = clean(it.name)) }) })
}

internal fun String.unicodeSize(): Int = codePointCount(0, length)
internal fun String.unicodePreview(limit: Int): String = substring(0, offsetByCodePoints(0, minOf(unicodeSize(), limit)))
