package com.smartcalc.app

import android.app.Application
import com.smartcalc.app.data.session.SessionStore
import com.smartcalc.app.data.history.HistoryStore
import com.smartcalc.app.data.settings.SettingsRepository
import com.smartcalc.app.data.format.FormatStore
import com.smartcalc.app.data.template.TemplateStore
import com.smartcalc.app.data.request.GenerationController
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class SmartCalcApplication : Application() {
    // Один писатель на процесс: закрытие Activity не отменяет сохранение и не создаёт гонку файлов.
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val sessionStore by lazy {
        SessionStore(File(noBackupFilesDir, "session.json"), sessionScope)
    }
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val historyStore by lazy { HistoryStore(this) }
    val settingsRepository by lazy { SettingsRepository(this) }
    val formatStore by lazy { FormatStore(this) }
    val templateStore by lazy { TemplateStore(filesDir) }
    val generation by lazy {
        GenerationController(this, applicationScope, historyStore, sessionStore,
            settingsRepository, formatStore, templateStore)
    }
}
