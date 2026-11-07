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
import dev.antite.engine.NotificationHandler
import dev.antite.engine.WebNotificationEvent

class WebNotifications(context: Context) : NotificationHandler {
    companion object {
        const val ACTION_CLICK = "dev.antite.browser.WEB_NOTIFICATION"
        private const val EXTRA_ID = "notificationId"
        private const val CHANNEL = "web"
        private val shown = HashMap<Int, () -> Unit>()

        fun click(intent: Intent) {
            shown.remove(intent.getIntExtra(EXTRA_ID, 0))?.invoke()
        }
    }

    private val app = context.applicationContext
    private val nm = app.getSystemService(NotificationManager::class.java).apply {
        createNotificationChannel(NotificationChannel(CHANNEL, "Уведомления сайтов", NotificationManager.IMPORTANCE_DEFAULT))
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
                val n = Notification.Builder(app, CHANNEL)
                    .setSmallIcon(R.drawable.ic_public)
                    .setContentTitle(event.title)
                    .setContentText(event.text)
                    .setStyle(Notification.BigTextStyle().bigText(event.text))
                    .setSubText(event.origin?.let { Uri.parse(it).host })
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(event.silent)
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
