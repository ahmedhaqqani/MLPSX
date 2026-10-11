package com.goldorb.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.goldorb.container
import com.goldorb.core.OrbUpdater
import com.goldorb.core.Phase
import com.goldorb.data.Format
import com.goldorb.data.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Keeps checking prices in the background. Each tick refreshes all sessions, then sets an
 * alarm for just after the next 5-minute close (or the next opening range when every
 * session has closed). The foreground notification keeps network access while the phone dozes.
 */
class MonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground(summary())
        if (intent?.action == ACTION_STOP) {
            cancelAlarm(this)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (job?.isActive != true) job = scope.launch { tick() }
        return START_STICKY
    }

    private suspend fun tick() {
        val c = container
        if (!c.settings.current().monitoring) {
            cancelAlarm(this)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GoldORB:refresh")
            .apply { acquire(2 * 60_000L) }
        try {
            c.repository.refresh()
        } finally {
            val settings = c.settings.current()
            val next = OrbUpdater.nextWake(
                settings.sessions,
                c.repository.snapshots.value,
                Instant.now(),
                lastRefreshFailed = c.repository.status.value.error != null,
            )
            scheduleAlarm(next)
            NotificationManagerCompat.from(this).notify(Notifier.ID_MONITOR, c.notifier.monitorNotification(summary(next)))
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    private fun summary(next: Instant? = null): String {
        val c = container
        val snaps = c.repository.snapshots.value
        val error = c.repository.status.value.error
        if (error != null) return error
        val live = snaps.values.filter { it.phase == Phase.ACTIVE || it.phase == Phase.FORMING }
        if (live.isEmpty() && next != null) return "All sessions closed · next check ${Format.day(next.epochSecond)} ${Format.time(next.epochSecond)}"
        return snaps.values.sortedBy { it.session.ordinal }
            .joinToString(" · ") { "${it.session.label} ${Format.statusLabel(it)}" }
            .ifEmpty { "Starting…" }
    }

    private fun goForeground(text: String) {
        val n = container.notifier.monitorNotification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(Notifier.ID_MONITOR, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Notifier.ID_MONITOR, n)
        }
    }

    private fun scheduleAlarm(at: Instant) {
        val am = getSystemService(AlarmManager::class.java)
        val pi = tickIntent(this)
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), pi)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_TICK = "com.goldorb.TICK"
        private const val ACTION_STOP = "com.goldorb.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java).setAction(ACTION_TICK))
        }

        fun stop(context: Context) {
            cancelAlarm(context)
            context.stopService(Intent(context, MonitorService::class.java))
        }

        private fun tickIntent(context: Context): PendingIntent = PendingIntent.getForegroundService(
            context, 0,
            Intent(context, MonitorService::class.java).setAction(ACTION_TICK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private fun cancelAlarm(context: Context) {
            context.getSystemService(AlarmManager::class.java).cancel(tickIntent(context))
        }
    }
}
