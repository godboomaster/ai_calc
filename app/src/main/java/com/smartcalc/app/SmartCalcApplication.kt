package com.smartcalc.app

import android.app.Application
import com.smartcalc.app.data.session.SessionStore
import com.smartcalc.app.data.history.HistoryStore
import com.smartcalc.app.data.settings.SettingsRepository
import com.smartcalc.app.data.format.FormatStore
import com.smartcalc.app.data.template.TemplateStore
import com.smartcalc.app.data.template.TemplateKind
import com.smartcalc.app.data.request.GenerationController
import com.smartcalc.app.data.request.active
import com.smartcalc.app.data.library.source.LibrarySourceStore
import com.smartcalc.app.data.library.agent.LibraryCatalog
import com.smartcalc.app.data.library.importer.*
import com.smartcalc.app.data.library.extract.DocumentExtractor
import com.smartcalc.app.data.api.DocumentRecognizer
import com.smartcalc.app.data.api.DocumentRecognitionTransport
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.*

class SmartCalcApplication : Application() {
    // Один писатель на процесс: закрытие Activity не отменяет сохранение и не создаёт гонку файлов.
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val sessionStore by lazy {
        SessionStore(File(noBackupFilesDir, "session.json"), sessionScope)
    }
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val libraryWorkScope get() = applicationScope
    val historyStore by lazy { HistoryStore(this) }
    val settingsRepository by lazy { SettingsRepository(this) }
    val formatStore by lazy { FormatStore(this) }
    val templateStore by lazy { TemplateStore(filesDir) }
    /** База решённых задач для поиска: тот же формат, что у шаблонов, но в своей папке. */
    val libraryStore by lazy { TemplateStore(File(filesDir, "library").apply { mkdirs() }, TemplateKind.LIBRARY) }
    val generation by lazy {
        GenerationController(this, applicationScope, historyStore, sessionStore,
            formatStore, templateStore,
            { LibraryCatalog(libraryStore.items.value, librarySources.state.value, libraryStore.loadError.value) })
    }
    val librarySources by lazy { LibrarySourceStore(File(filesDir, "library/sources")) }
    val libraryImports by lazy { LibraryImportJournal(File(filesDir, "library/imports")) }
    val libraryExtractor by lazy { DocumentExtractor(librarySources) }
    val libraryImport by lazy {
        LibraryImportController(librarySources, libraryImports, libraryExtractor, DocumentRecognizer(DocumentRecognitionTransport()),
            TemplateImportPublisher(libraryStore, librarySources), { settingsRepository.settings.first() }, applicationScope,
            solutionActive = generation.state.map { it?.active == true }.stateIn(applicationScope, SharingStarted.Eagerly, generation.state.value?.active == true),
            startForeground = { LibraryImportService.start(this) }, stopForeground = { LibraryImportService.stop(this) },
            sectionSnapshot = { id -> libraryStore.items.value.firstOrNull { it.id == id } })
            .also { controller -> applicationScope.launch(Dispatchers.IO) {
                try { controller.recover() } catch (_: Exception) { libraryImports.reportStorageFailure() }
            } }
    }
}
