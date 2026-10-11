package com.goldorb.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.goldorb.R
import com.goldorb.core.Alert
import com.goldorb.core.EventType
import com.goldorb.ui.MainActivity

class Notifier(private val context: Context) {

    fun createChannels() {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SIGNALS, "ORB signals", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Breakouts, failed breakouts and re-breaks"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_MONITOR, "Background monitoring", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while the app checks prices every 5 minutes"
                setShowBadge(false)
            }
        )
    }

    fun signal(alert: Alert) {
        val e = alert.event
        val s = alert.snapshot
        val title = "${alert.session.label}: ${e.type.title}"
        val text = when (e.type) {
            EventType.RANGE_SET ->
                "OR ${Format.price(s.orLow)} – ${Format.price(s.orHigh)} · VA ${Format.price(s.valueAreaLow)} – ${Format.price(s.vah)}"
            EventType.BREAKOUT_UP, EventType.REBREAK_UP ->
                "5m close ${Format.price(e.price)} above OR high ${Format.price(s.orHigh)} (${Format.time(e.time + 300)})"
            EventType.BREAKOUT_DOWN, EventType.REBREAK_DOWN ->
                "5m close ${Format.price(e.price)} below OR low ${Format.price(s.orLow)} (${Format.time(e.time + 300)})"
            EventType.FAILED_UP, EventType.FAILED_DOWN ->
                "5m close ${Format.price(e.price)} back inside ${Format.price(s.orLow)} – ${Format.price(s.orHigh)} (${Format.time(e.time + 300)})"
        }
        post((alert.session.ordinal * 1_000_003 + e.time + e.type.ordinal).toInt(), CH_SIGNALS, title, text)
    }

    fun loginNeeded() {
        post(ID_LOGIN, CH_SIGNALS, "TradingView sign-in needed", "Open Gold ORB → Settings to reconnect your TradingView account.")
    }

    fun monitorNotification(text: String) = NotificationCompat.Builder(context, CH_MONITOR)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("Gold ORB is watching")
        .setContentText(text)
        .setOngoing(true)
        .setSilent(true)
        .setContentIntent(openApp())
        .build()

    private fun post(id: Int, channel: String, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        NotificationManagerCompat.from(context).notify(id, n)
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CH_SIGNALS = "signals"
        const val CH_MONITOR = "monitor"
        const val ID_MONITOR = 1
        private const val ID_LOGIN = 2
    }
}
