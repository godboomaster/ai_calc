package com.smartcalc.app

import android.app.Application
import com.smartcalc.app.data.session.SessionStore
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
}
