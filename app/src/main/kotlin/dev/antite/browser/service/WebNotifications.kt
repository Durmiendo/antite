package dev.antite.browser.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.antite.browser.MainActivity
import dev.antite.browser.R
import dev.antite.browser.l10n.T
import dev.antite.engine.NotificationHandler
import dev.antite.engine.WebNotificationEvent

class WebNotifications(context: Context) : NotificationHandler {
    companion object {
        const val ACTION_CLICK = "dev.antite.browser.WEB_NOTIFICATION"
        private const val EXTRA_ID = "notificationId"
        private const val CHANNEL = "web"
        private const val CHANNEL_SILENT = "web-silent"
        private const val KEEP_CLICKS = 50

        // Click handlers of notifications nobody tapped or closed; the oldest are dropped.
        private val shown = object : LinkedHashMap<Int, () -> Unit>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, () -> Unit>) = size > KEEP_CLICKS
        }

        fun click(intent: Intent) {
            shown.remove(intent.getIntExtra(EXTRA_ID, 0))?.invoke()
        }
    }

    private val app = context.applicationContext
    private val nm = app.getSystemService(NotificationManager::class.java).apply {
        createNotificationChannel(NotificationChannel(CHANNEL, T.notificationsSitesChannel, NotificationManager.IMPORTANCE_DEFAULT))
        createNotificationChannel(NotificationChannel(CHANNEL_SILENT, T.notificationsSitesSilentChannel,
            NotificationManager.IMPORTANCE_LOW))
    }

    override fun onNotification(event: WebNotificationEvent) {
        when (event) {
            is WebNotificationEvent.Show -> {
                val id = event.id.hashCode()
                shown[id] = event.click
                val open = Intent(app, MainActivity::class.java)
                    .setAction(ACTION_CLICK)
                    .putExtra(EXTRA_ID, id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                val channel = if (event.silent) CHANNEL_SILENT else CHANNEL
                val n = Notification.Builder(app, channel)
                    .setSmallIcon(R.drawable.ic_public)
                    .setContentTitle(event.title)
                    .setContentText(event.text)
                    .setStyle(Notification.BigTextStyle().bigText(event.text))
                    .setSubText(event.origin?.let { Uri.parse(it).host })
                    .setAutoCancel(true)
                    .setContentIntent(PendingIntent.getActivity(app, id, open,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                    .build()
                val ok = try {
                    nm.notify(CHANNEL, id, n)
                    nm.areNotificationsEnabled()
                } catch (e: SecurityException) {
                    false
                }
                event.shown(ok)
            }
            is WebNotificationEvent.Close -> {
                val id = event.id.hashCode()
                shown.remove(id)
                nm.cancel(CHANNEL, id)
            }
        }
    }
}
