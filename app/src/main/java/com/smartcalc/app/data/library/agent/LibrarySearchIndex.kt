package com.smartcalc.app.data.library.agent

import com.smartcalc.app.data.search.SearchEntry
import com.smartcalc.app.data.search.TaskSearch
import java.io.File
import java.security.MessageDigest

/**
 * One last index for published, immutable catalog snapshots. The generation controller owns it;
 * request permissions, read snapshots and budgets never enter this cache.
 */
class LibrarySearchIndex {
    private var previous: LibraryCatalog? = null
    private var images: List<Pair<String, List<Byte>?>> = emptyList()
    private var prepared: List<SearchEntry>? = null

    @Synchronized
    internal fun entries(current: LibraryCatalog): List<SearchEntry> {
        if (current.error != null || current.sources.error != null) {
            invalidate()
            return emptyList()
        }
        // Fingerprints used by TaskSearch depend on file contents as well as catalog fields.
        // Keep checking bytes: size/mtime alone miss replacement and change deduplication.
        val currentImages = imageContents(current)
        prepared?.let { if (previous == current && images == currentImages) return it }
        val updated = TaskSearch.entries(current.sections, emptyList(), emptyList(), current.sources)
        previous = current
        images = currentImages
        prepared = updated
        return updated
    }

    @Synchronized
    internal fun invalidate() {
        previous = null
        images = emptyList()
        prepared = null
    }

    private fun imageContents(current: LibraryCatalog): List<Pair<String, List<Byte>?>> {
        val paths = linkedSetOf<String>()
        current.sections.forEach { section -> section.examples.forEach { ex ->
            if (TaskSearch.libraryExampleAvailable(ex, current.sources)) {
                paths.addAll(ex.conditionImages)
                paths.addAll(ex.solutionImages)
            }
        } }
        return paths.map { path -> path to runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            File(path).inputStream().buffered().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            digest.digest().toList()
        }.getOrNull() }
    }
}
