package com.smartcalc.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class LibraryImportService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controller get() = (application as SmartCalcApplication).libraryImport
    private var monitor: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var expectedStop = false
    override fun onCreate() { super.onCreate(); instance = this }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        expectedStop = false
        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Импорт библиотеки", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat_calculate)
                .setContentTitle("Обрабатывается документ").setContentText("Разбор сохраняется в библиотеке")
                .setContentIntent(open).setOngoing(true).setSilent(true).setVisibility(NotificationCompat.VISIBILITY_SECRET).build()
            ServiceCompat.startForeground(this, ID, notification, if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
            wakeLock?.takeIf { it.isHeld }?.release()
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmartCalc:library-import").apply { acquire(9 * 60 * 1000L) }
            monitor?.cancel()
            monitor = scope.launch {
                controller.foregroundActive.first { !it }
                expectedStop = true
                if (stopSelfResult(startId)) { stopForeground(STOP_FOREGROUND_REMOVE); releaseWakeLock() }
            }
        } catch (_: Exception) {
            (application as SmartCalcApplication).libraryWorkScope.launch(Dispatchers.IO) { controller.interruptActive("Android не разрешил фоновый импорт. Откройте приложение и повторите обработку.") }
            expectedStop = true; stopSelf()
        }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        (application as SmartCalcApplication).libraryWorkScope.launch(Dispatchers.IO) { controller.interruptActive("Android остановил импорт. Сохранённые фрагменты доступны; автоматического повтора нет.") }
        expectedStop = true; stopForeground(STOP_FOREGROUND_REMOVE); releaseWakeLock(); stopSelf(startId)
    }
    override fun onDestroy() {
        if (!expectedStop && controller.foregroundActive.value) {
            (application as SmartCalcApplication).libraryWorkScope.launch { controller.interruptActive("Фоновый импорт прерван. Можно продолжить вручную.") }
        }
        if (instance === this) instance = null
        monitor?.cancel(); scope.cancel(); releaseWakeLock(); super.onDestroy()
    }
    private fun releaseWakeLock() { wakeLock?.takeIf { it.isHeld }?.release(); wakeLock = null }
    companion object {
        private const val CHANNEL = "library_import"
        private const val ID = 29
        @Volatile private var instance: LibraryImportService? = null
        fun start(context: Context) { ContextCompat.startForegroundService(context, Intent(context, LibraryImportService::class.java)) }
        fun stop(context: Context) { instance?.expectedStop = true; context.stopService(Intent(context, LibraryImportService::class.java)) }
    }
}
