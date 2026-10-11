package com.smartcalc.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.smartcalc.app.data.request.active
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Keeps user-initiated work alive when its Activity is closed or removed from recents. */
class GenerationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val runner get() = (application as SmartCalcApplication).generation.runner
    private var monitor: Job? = null
    private var servedRequestId: String? = null
    private var servedAttempt: Long? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        servedRequestId = runner.state.value?.id
        servedAttempt = runner.state.value?.startedAt
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
            releaseWakeLock()
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmartCalc:request")
                .apply { acquire(10 * 60 * 1000L) }
            monitor?.cancel()
            monitor = scope.launch {
                runner.state.first { it?.active != true }
                if (stopSelfResult(startId)) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    releaseWakeLock()
                }
            }
        } catch (_: Exception) {
            runner.interrupt("Android не разрешил фоновое выполнение. Откройте приложение и повторите запрос.")
            stopSelf(startId)
        }
        // A killed HTTP stream cannot be resumed safely: persist interruption, never silently resend.
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        runner.interrupt("Android остановил фоновое вычисление. Можно повторить запрос.")
        stopForeground(STOP_FOREGROUND_REMOVE)
        releaseWakeLock()
        stopSelf(startId)
    }

    override fun onDestroy() {
        if (runner.state.value?.id == servedRequestId && runner.state.value?.startedAt == servedAttempt) {
            runner.interrupt("Фоновое вычисление прервано. Можно повторить запрос.")
        }
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun notification(): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Фоновые вычисления", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_calculate)
            .setContentTitle("Выполняется вычисление")
            .setContentText("Можно закрыть приложение")
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val CHANNEL = "background_calculation"
        private const val NOTIFICATION_ID = 27
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, GenerationService::class.java))
        }
    }
}
