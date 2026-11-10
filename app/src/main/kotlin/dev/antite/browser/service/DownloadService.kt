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

// Downloads run here so they go on while the browser is in the background. A task either copies a response body into
// a file or does its own work (the engine, a new Antite build) and reports progress; only the former is listed in the
// downloads list, the latter has a negative id.
class DownloadService : Service() {
    class Task(
        val id: Long,
        val name: String,
        val mime: String,
        @Volatile var total: Long,
        val target: Uri?,
        val body: InputStream?,
        val work: ((Task) -> Unit)? = null,
    ) {
        @Volatile
        var cancelled = false

        @Volatile
        var done = 0L

        // Bytes per second, smoothed so the numbers do not jump.
        @Volatile
        var speed = 0.0

        @Volatile
        var error: Throwable? = null

        // Main thread; status is DONE, FAILED or CANCELLED.
        var onDone: (status: String, error: Throwable?) -> Unit = { _, _ -> }

        internal var tick: () -> Unit = {}

        // Called by work as bytes arrive; throws once the user has cancelled, which ends the work.
        fun report(done: Long, total: Long) {
            if (cancelled) throw java.io.IOException("cancelled")
            this.total = total
            this.done = done
            tick()
        }
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

        private val nextJob = java.util.concurrent.atomic.AtomicLong(-1)

        fun job(name: String, work: (Task) -> Unit) =
            Task(nextJob.getAndDecrement(), name, "application/octet-stream", -1, null, null, work)

        // For callers that may not start a foreground service (a background job): the same notifications, this thread.
        fun runHere(context: Context, task: Task): String {
            tasks[task.id] = task
            val r = Runner(context).run(task)
            tasks.remove(task.id)
            return r.first
        }

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
    private lateinit var runner: Runner
    private val running = mutableSetOf<Long>()
    private var foreground: Long? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        runner = Runner(this)
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
        startForeground(notificationId(t.id), runner.progressNotification(t), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    // Detaching keeps the last progress notification so the result can replace it in place.
    private fun stop(remove: Boolean = false) {
        stopForeground(if (remove) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH)
        foreground = null
        stopSelf()
    }

    private fun run(t: Task) {
        val (status, uri) = runner.run(t, finishNotification = false)
        main.post {
            tasks.remove(t.id)
            running.remove(t.id)
            if (foreground == t.id) {
                foreground = null
                val next = tasks.values.firstOrNull()
                if (next != null) promote(next) else stop()
            }
            runner.resultNotification(t, status, uri)
        }
    }

    class Runner(private val context: Context) {
        private val nm = context.getSystemService(NotificationManager::class.java).apply {
            createNotificationChannel(NotificationChannel(CHANNEL, "Загрузки", NotificationManager.IMPORTANCE_LOW))
        }

        private fun mb(bytes: Long) = Formatter.formatShortFileSize(context, bytes)

        fun progressNotification(t: Task): Notification {
            val cancel = PendingIntent.getService(context, notificationId(t.id),
                Intent(context, DownloadService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_ID, t.id), PendingIntent.FLAG_IMMUTABLE)
            val known = t.total > 0
            return Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(t.name)
                .setContentText(progressText(t, t.done))
                .setProgress(100, if (known) (t.done * 100 / t.total).toInt() else 0, !known)
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

        fun resultNotification(t: Task, status: String, uri: Uri?) {
            if (status == CANCELLED) return nm.cancel(notificationId(t.id))
            val b = Notification.Builder(context, CHANNEL)
                .setSmallIcon(if (status == DONE) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
                .setContentTitle(if (status == DONE) "Скачано" else "Не удалось скачать")
                .setContentText(t.name)
                .setAutoCancel(true)
            if (status == DONE && uri != null) {
                val open = Intent(Intent.ACTION_VIEW).setDataAndType(uri, t.mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                b.setContentIntent(PendingIntent.getActivity(context, notificationId(t.id), open, PendingIntent.FLAG_IMMUTABLE))
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

        // Runs the task on this thread with a progress notification; the result notification is left to the caller
        // when it must first give up the foreground.
        fun run(t: Task, finishNotification: Boolean = true): Pair<String, Uri?> {
            var last = System.currentTimeMillis()
            var lastDone = 0L
            t.tick = {
                val now = System.currentTimeMillis()
                if (now - last >= UPDATE_MS) {
                    val current = (t.done - lastDone) * 1000.0 / (now - last)
                    t.speed = if (t.speed == 0.0) current else t.speed * 0.7 + current * 0.3
                    last = now
                    lastDone = t.done
                    notify(notificationId(t.id), progressNotification(t))
                    val share = if (t.total > 0) (t.done.toFloat() / t.total).coerceAtMost(1f) else -1f
                    main.post { if (progress.containsKey(t.id)) progress[t.id] = share }
                }
            }
            notify(notificationId(t.id), progressNotification(t))
            var error: Throwable? = null
            var target: Uri? = null
            val status = try {
                val work = t.work
                if (work != null) work(t) else target = copy(t)
                if (t.cancelled) CANCELLED else DONE
            } catch (e: Exception) {
                Log.w("Antite", "download failed: ${t.name}", e)
                error = e
                t.error = e
                runCatching { t.body?.close() }
                if (t.cancelled) CANCELLED else FAILED
            }
            val cr = context.contentResolver
            if (t.work == null && t.target == null && target != null) {
                if (status == DONE) cr.update(target, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                else cr.delete(target, null, null)
            }
            val uri = if (status == DONE) (target ?: t.target) else null
            if (t.id >= 0) Db.get(context).finishDownload(t.id, uri?.toString(), status)
            main.post {
                progress.remove(t.id)
                t.onDone(status, error)
                if (t.id >= 0) onFinished(t.id)
            }
            if (finishNotification) resultNotification(t, status, uri)
            return status to uri
        }

        private fun copy(t: Task): Uri {
            val cr = context.contentResolver
            val target = t.target ?: cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, t.name)
                put(MediaStore.Downloads.MIME_TYPE, t.mime)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }) ?: throw java.io.IOException("cannot create ${t.name}")
            try {
                t.body!!.use { input ->
                    cr.openOutputStream(target)!!.use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (!t.cancelled) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            t.done = done
                            t.tick()
                        }
                    }
                }
            } catch (e: Exception) {
                if (t.target == null) cr.delete(target, null, null)
                throw e
            }
            return target
        }
    }
}
