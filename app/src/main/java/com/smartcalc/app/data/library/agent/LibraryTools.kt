package com.smartcalc.app.data.library.agent

import com.smartcalc.app.data.search.TaskSearch
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** One request owns its allowlist and budget; every operation rechecks current library policy. */
class LibraryTools(private val catalog: suspend () -> LibraryCatalog) {
    private val json = Json
    private val allowed = mutableMapOf<String, Int>()
    private val read = linkedMapOf<Pair<String, Int>, LibraryExampleSnapshot>()
    private var contextSize = 0
    val examples: List<LibraryExampleSnapshot> get() = read.values.toList()

    suspend fun hasExamples(): Boolean = try {
        val current = catalog()
        current.error != null || current.sources.error != null || entries(current).isNotEmpty()
    } catch (e: CancellationException) { throw e } catch (_: Exception) { true }

    private fun entries(current: LibraryCatalog) = TaskSearch.entries(current.sections, emptyList(), emptyList(), current.sources)

    suspend fun execute(name: String, arguments: String): JsonObject {
        if (name !in setOf("search_examples", "read_example")) return status("invalid_tool")
        val args = try { if (arguments.length > 20000) return status("invalid_arguments")
            json.parseToJsonElement(arguments) as? JsonObject ?: return status("invalid_arguments")
        } catch (_: Exception) { return status("invalid_arguments") }
        fun string(key: String) = (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val query = if (name == "search_examples") string("query")?.trim() else null
        val limit = if (args.containsKey("limit")) (args["limit"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull else 5
        val id = if (name == "read_example") string("id") else null
        val version = (args["version"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
        if (name == "search_examples") {
            if (args.keys.any { it !in setOf("query", "limit") } || query.isNullOrBlank() || query.unicodeSize() > 2000 || limit == null || limit !in 1..5)
                return status("invalid_arguments")
        } else {
            if (args.keys != setOf("id", "version") || id == null || version == null || version < 1 ||
                runCatching { UUID.fromString(id).toString() == id.lowercase() }.getOrDefault(false).not()) return status("invalid_arguments")
        }
        val current = try { catalog() } catch (e: CancellationException) { throw e } catch (_: Exception) { return status("unavailable") }
        if (current.error != null || current.sources.error != null) return status("unavailable")
        val indexed = entries(current)
        var pending: LibraryExampleSnapshot? = null
        val newAllowed = mutableMapOf<String, Int>()
        val result = if (name == "search_examples") {
            val hits = TaskSearch.search(indexed, query!!).filter { it.entry.library != null }.take(limit!!)
            buildJsonObject {
                put("status", if (hits.isEmpty()) "no_match" else "matches")
                putJsonArray("matches") { hits.forEach { hit ->
                    val entry = hit.entry; val metadata = entry.library!!
                    newAllowed[metadata.id] = metadata.version
                    add(buildJsonObject {
                        put("id", metadata.id); put("version", metadata.version)
                        put("section", entry.type.orEmpty().unicodePreview(128))
                        put("verification", metadata.verification.name)
                        put("condition_preview", entry.condition.unicodePreview(240))
                        put("preview_truncated", entry.condition.unicodeSize() > 240)
                        put("has_images", entry.conditionImages.isNotEmpty() || entry.solutionImages.isNotEmpty())
                    })
                } }
            }
        } else {
            if (allowed[id] != version) return status("not_allowed")
            val entry = indexed.firstOrNull { it.library?.id == id && it.library?.version == version } ?: return status("stale")
            val key = id!! to version!!
            if (key in read) return status("already_read")
            if (read.size >= 5) return status("read_limit")
            val ex = current.sections.flatMap { it.examples }.firstOrNull { it.library?.id == id && it.library?.version == version }
                ?: return status("stale")
            val metadata = ex.library!!
            val hasImages = ex.conditionImages.isNotEmpty() || ex.solutionImages.isNotEmpty()
            if (ex.condition.isBlank() && ex.solution.isBlank() && ex.answer.isBlank() && hasImages) return status("needs_image")
            val snapshot = LibraryExampleSnapshot(id, version, entry.type.orEmpty(), ex.condition, ex.solution, ex.answer,
                metadata.verification, metadata.origin.kind.name,
                metadata.sourceRefs.map { ref -> LibraryExampleSource(ref.sourceId,
                    current.sources.sources.first { it.id == ref.sourceId }.name, ref.kind, ref.ordinal) }, hasImages)
            val response = buildJsonObject { put("status", "ok"); put("example", json.encodeToJsonElement(LibraryExampleSnapshot.serializer(), snapshot)) }
            if (response.toString().unicodeSize() > 4000) return status("too_large")
            pending = snapshot
            response
        }
        val size = result.toString().unicodeSize()
        if (contextSize + size > 12000) return status("context_limit")
        contextSize += size
        allowed.putAll(newAllowed)
        pending?.let { read[it.id to it.version] = it }
        return result
    }

    companion object {
        fun status(value: String) = buildJsonObject { put("status", value) }
        fun explicitSearch(text: String): Boolean = Regex("(?i)(найд[иь]|найти|поищи|ищи|find|search)").containsMatchIn(text) &&
            Regex("(?i)(похож|пример|образец|similar|example)").containsMatchIn(text)
    }
}
