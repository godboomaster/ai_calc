package com.smartcalc.app.data.request

import com.smartcalc.app.data.library.agent.LibraryCatalog
import com.smartcalc.app.data.library.agent.LibraryTools
import com.smartcalc.app.data.library.agent.LibrarySearchIndex

/** Opt-in gate: disabling it never even loads the library; empty ordinary requests stay legacy. */
internal suspend fun libraryToolsForRequest(
    enabled: Boolean,
    text: String,
    index: LibrarySearchIndex = LibrarySearchIndex(),
    catalog: suspend () -> LibraryCatalog,
): LibraryTools? {
    if (!enabled) return null
    val tools = LibraryTools(index, catalog)
    return tools.takeIf { LibraryTools.explicitSearch(text) || it.hasExamples() }
}
