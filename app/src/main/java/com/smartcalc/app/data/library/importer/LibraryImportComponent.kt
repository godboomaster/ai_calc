package com.smartcalc.app.data.library.importer

import com.smartcalc.app.data.api.DocumentRecognitionTransport
import com.smartcalc.app.data.api.DocumentRecognizer
import com.smartcalc.app.data.library.extract.DocumentExtractor
import com.smartcalc.app.data.library.source.LibrarySourceStore
import com.smartcalc.app.data.settings.ApiSettings
import com.smartcalc.app.data.template.TemplateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Process-lifetime import wiring. Accessing the controller starts local recovery exactly once. */
class LibraryImportComponent(
    sources: LibrarySourceStore,
    journal: LibraryImportJournal,
    extractor: DocumentExtractor,
    library: TemplateStore,
    settings: suspend () -> ApiSettings,
    scope: CoroutineScope,
    solutionActive: StateFlow<Boolean>,
    startForeground: () -> Unit = {},
    stopForeground: () -> Unit = {},
    recognizer: DocumentRecognizer = DocumentRecognizer(DocumentRecognitionTransport()),
    publisher: ImportPublisher = TemplateImportPublisher(library, sources),
) {
    val controller by lazy {
        LibraryImportController(
            sources = sources, journal = journal, extractor = extractor, recognizer = recognizer,
            publisher = publisher, settings = settings, scope = scope,
            solutionActive = solutionActive,
            startForeground = startForeground, stopForeground = stopForeground,
            sectionSnapshot = { id -> library.items.value.firstOrNull { it.id == id } },
        ).also { controller ->
            scope.launch(Dispatchers.IO) {
                try { controller.recover() }
                catch (_: Exception) { journal.reportStorageFailure() }
            }
        }
    }
}
