package com.smartcalc.app

import android.app.Application

class SmartCalcApplication : Application() {
    val dependencies by lazy { AppDependencies(this) }

    // Compatibility accessors keep existing screens/services on the same process-owned instances.
    val sessionStore get() = dependencies.sessionStore
    val solutionSession get() = dependencies.solutionSession
    val historyStore get() = dependencies.historyStore
    val settingsRepository get() = dependencies.settingsRepository
    val formatStore get() = dependencies.formatStore
    val templateStore get() = dependencies.templateStore
    val libraryStore get() = dependencies.libraryStore
    val generation get() = dependencies.generation
    val librarySources get() = dependencies.librarySources
    val libraryImports get() = dependencies.libraryImports
    val libraryExtractor get() = dependencies.libraryExtractor
    val libraryImport get() = dependencies.libraryImport
    val libraryWorkScope get() = dependencies.libraryWorkScope
}
