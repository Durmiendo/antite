package dev.antite.browser.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.text.format.Formatter
import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import dev.antite.browser.R
import dev.antite.browser.data.Db
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

// Downloads run here so they go on while the browser is in the background.
class DownloadService : Service() {
    class Task(
        val id: Long,
        val name: String,
        val mime: String,
        val total: Long,
        val target: Uri?,
        val body: InputStream,
    ) {
        @Volatile
        var cancelled = false

        @Volatile
        var done = 0L

        // Bytes per second, smoothed so the numbers do not jump.
        @Volatile
        var speed = 0.0
    }

    companion object {
        const val DONE = "done"
        const val FAILED = "failed"
        const val CANCELLED = "cancelled"
        private const val CHANNEL = "downloads"
        private const val ACTION_START = "start"
        private const val ACTION_CANCEL = "cancel"
        private const val EXTRA_ID = "id"
        private const val UPDATE_MS = 500L

        private val tasks = ConcurrentHashMap<Long, Task>()
        private val main = Handler(Looper.getMainLooper())

        // Share of bytes received, -1 when the size is unknown; Compose state, written on the main thread.
        val progress = mutableStateMapOf<Long, Float>()
        var onFinished: (Long) -> Unit = {}

        fun enqueue(context: Context, task: Task) {
            tasks[task.id] = task
            progress[task.id] = if (task.total > 0) 0f else -1f
            context.startForegroundService(Intent(context, DownloadService::class.java).setAction(ACTION_START).putExtra(EXTRA_ID, task.id))
        }

        fun cancel(id: Long) {
            tasks[id]?.cancelled = true
        }

        fun isActive(id: Long) = tasks.containsKey(id)

        fun activeIds(): Set<Long> = tasks.keys.toSet()

        private fun notificationId(id: Long) = (id % 1_000_000).toInt() + 100
    }

    private val io = Executors.newCachedThreadPool()
    private lateinit var nm: NotificationManager
    private val running = mutableSetOf<Long>()
    private var foreground: Long? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Загрузки", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getLongExtra(EXTRA_ID, -1) ?: -1
        when (intent?.action) {
            ACTION_CANCEL -> cancel(id)
            ACTION_START -> {
                val t = tasks[id]
                // A service started in the foreground must call startForeground even if there is nothing to do.
                promote(t ?: tasks.values.firstOrNull())
                if (t != null && running.add(t.id)) io.execute { run(t) }
            }
        }
        if (tasks.isEmpty()) stop(remove = true)
        return START_NOT_STICKY
    }

    // The progress notification of one download doubles as the service's foreground notification.
    private fun promote(t: Task?) {
        if (t == null) {
            if (foreground == null) {
                startForeground(Int.MAX_VALUE, Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_download)
                    .setContentTitle("Загрузки").build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            }
            return
        }
        if (foreground != null && tasks.containsKey(foreground)) return
        foreground = t.id
        startForeground(notificationId(t.id), progressNotification(t, t.done), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    // Detaching keeps the last progress notification so the result can replace it in place.
    private fun stop(remove: Boolean = false) {
        stopForeground(if (remove) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH)
        foreground = null
        stopSelf()
    }

    private fun mb(bytes: Long) = Formatter.formatShortFileSize(this, bytes)

    private fun progressNotification(t: Task, done: Long): Notification {
        val cancel = PendingIntent.getService(this, notificationId(t.id),
            Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_ID, t.id), PendingIntent.FLAG_IMMUTABLE)
        val known = t.total > 0
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(t.name)
            .setContentText(progressText(t, done))
            .setProgress(100, if (known) (done * 100 / t.total).toInt() else 0, !known)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Отмена", cancel).build())
            .build()
    }

    private fun progressText(t: Task, done: Long): String {
        val parts = mutableListOf<String>()
        if (t.total > 0) {
            parts += "${done * 100 / t.total}%"
            parts += "${mb(done)} из ${mb(t.total)}"
        } else {
            parts += mb(done)
        }
        if (t.speed > 0) {
            parts += "${mb(t.speed.toLong())}/с"
            if (t.total > 0) parts += "осталось " + eta(((t.total - done) / t.speed).toLong())
        }
        return parts.joinToString(" · ")
    }

    private fun eta(seconds: Long): String = when {
        seconds < 60 -> "${maxOf(seconds, 1)} с"
        seconds < 3600 -> "${(seconds + 30) / 60} мин"
        else -> "${seconds / 3600} ч ${(seconds % 3600) / 60} мин"
    }

    private fun resultNotification(t: Task, status: String, uri: Uri?) {
        if (status == CANCELLED) return nm.cancel(notificationId(t.id))
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(if (status == DONE) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(if (status == DONE) "Скачано" else "Не удалось скачать")
            .setContentText(t.name)
            .setAutoCancel(true)
        if (status == DONE && uri != null) {
            val open = Intent(Intent.ACTION_VIEW).setDataAndType(uri, t.mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            b.setContentIntent(PendingIntent.getActivity(this, notificationId(t.id), open, PendingIntent.FLAG_IMMUTABLE))
        }
        notify(notificationId(t.id), b.build())
    }

    private fun notify(id: Int, n: Notification) {
        try {
            nm.notify(id, n)
        } catch (e: SecurityException) {
            Log.w("Antite", "no permission to notify", e)
        }
    }

    private fun run(t: Task) {
        val cr = contentResolver
        var target: Uri? = null
        val status = try {
            target = t.target ?: cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, t.name)
                put(MediaStore.Downloads.MIME_TYPE, t.mime)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }) ?: throw java.io.IOException("cannot create ${t.name}")
            var done = 0L
            var last = System.currentTimeMillis()
            var lastDone = 0L
            notify(notificationId(t.id), progressNotification(t, 0))
            t.body.use { input ->
                cr.openOutputStream(target!!)!!.use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (!t.cancelled) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        t.done = done
                        val now = System.currentTimeMillis()
                        if (now - last >= UPDATE_MS) {
                            val current = (done - lastDone) * 1000.0 / (now - last)
                            t.speed = if (t.speed == 0.0) current else t.speed * 0.7 + current * 0.3
                            last = now
                            lastDone = done
                            notify(notificationId(t.id), progressNotification(t, done))
                            val share = if (t.total > 0) (done.toFloat() / t.total).coerceAtMost(1f) else -1f
                            main.post { if (progress.containsKey(t.id)) progress[t.id] = share }
                        }
                    }
                }
            }
            if (t.cancelled) CANCELLED else DONE
        } catch (e: Exception) {
            Log.w("Antite", "download failed: ${t.name}", e)
            runCatching { t.body.close() }
            if (t.cancelled) CANCELLED else FAILED
        }
        if (t.target == null && target != null) {
            if (status == DONE) cr.update(target, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            else cr.delete(target, null, null)
        }
        val uri = if (status == DONE) target else null
        Db.get(this).finishDownload(t.id, uri?.toString(), status)
        main.post {
            tasks.remove(t.id)
            running.remove(t.id)
            progress.remove(t.id)
            if (foreground == t.id) {
                foreground = null
                val next = tasks.values.firstOrNull()
                if (next != null) promote(next) else stop()
            }
            resultNotification(t, status, uri)
            onFinished(t.id)
        }
    }
}
