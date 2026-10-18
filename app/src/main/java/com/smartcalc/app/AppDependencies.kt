package com.smartcalc.app

import android.app.Application
import com.smartcalc.app.data.format.FormatStore
import com.smartcalc.app.data.history.HistoryStore
import com.smartcalc.app.data.library.ExampleWorkspace
import com.smartcalc.app.data.library.agent.LibraryCatalog
import com.smartcalc.app.data.library.extract.DocumentExtractor
import com.smartcalc.app.data.library.importer.LibraryImportComponent
import com.smartcalc.app.data.library.importer.LibraryImportJournal
import com.smartcalc.app.data.library.source.LibrarySourceStore
import com.smartcalc.app.data.request.GenerationController
import com.smartcalc.app.data.request.active
import com.smartcalc.app.data.session.SessionStore
import com.smartcalc.app.data.session.SolutionSession
import com.smartcalc.app.data.settings.SettingsRepository
import com.smartcalc.app.data.template.TemplateKind
import com.smartcalc.app.data.template.TemplateStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Application composition root. File writers and background work outlive every Activity/ViewModel. */
class AppDependencies(private val app: Application) {
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val libraryWorkScope get() = applicationScope
    val sessionStore by lazy { SessionStore(File(app.noBackupFilesDir, "session.json"), sessionScope) }
    val solutionSession by lazy { SolutionSession(sessionStore) }
    val historyStore by lazy { HistoryStore(app) }
    val settingsRepository by lazy { SettingsRepository(app) }
    val formatStore by lazy { FormatStore(app) }
    val templateStore by lazy { TemplateStore(app.filesDir) }
    val libraryStore by lazy {
        TemplateStore(File(app.filesDir, "library").apply { mkdirs() }, TemplateKind.LIBRARY)
    }
    val librarySources by lazy { LibrarySourceStore(File(app.filesDir, "library/sources")) }
    val libraryImports by lazy { LibraryImportJournal(File(app.filesDir, "library/imports")) }
    val libraryExtractor by lazy { DocumentExtractor(librarySources) }

    val generation by lazy {
        GenerationController(app, applicationScope, historyStore, sessionStore, formatStore, templateStore,
            libraryCatalog = { LibraryCatalog(libraryStore.items.value, librarySources.state.value, libraryStore.loadError.value) },
            sessionActions = solutionSession)
    }

    private val libraryImportComponent by lazy {
        LibraryImportComponent(
            sources = librarySources, journal = libraryImports, extractor = libraryExtractor, library = libraryStore,
            settings = { settingsRepository.settings.first() }, scope = applicationScope,
            solutionActive = generation.state.map { it?.active == true }
                .stateIn(applicationScope, SharingStarted.Eagerly, generation.state.value?.active == true),
            startForeground = { LibraryImportService.start(app) },
            stopForeground = { LibraryImportService.stop(app) },
        )
    }
    val libraryImport get() = libraryImportComponent.controller

    /** Selection/search subscriptions belong to the caller's ViewModel, never to the process. */
    fun exampleWorkspace(scope: CoroutineScope) =
        ExampleWorkspace(templateStore, libraryStore, historyStore, librarySources, scope)
}
