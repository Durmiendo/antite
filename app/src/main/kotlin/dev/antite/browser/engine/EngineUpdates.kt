package dev.antite.browser.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.util.Log
import dev.antite.browser.BuildConfig
import dev.antite.browser.MainActivity
import dev.antite.browser.R
import dev.antite.browser.service.DownloadService
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class Offer(val entry: EngineEntry, val abi: String, val source: Source) {
    val size: Long get() = entry.adapter.size + entry.upstream.getValue(abi).size
    val dirName: String get() = entry.dirName(abi)
}

class ShellOffer(val version: Int, val apk: Artifact, val source: Source)

class Updates(val engine: Offer?, val shell: ShellOffer?)

object EngineUpdates {
    const val JOB_CHECK = 1
    const val JOB_DOWNLOAD = 2
    const val AUTO = "updatesAuto"
    const val INSTALL = "updatesInstall"
    const val WIFI_ONLY = "updatesWifiOnly"
    const val INTERVAL = "updatesInterval"
    const val DEBUG = "debugInstall"

    // Minutes; JobScheduler runs periodic jobs at most every 15 minutes, so shorter ones run while the browser is open.
    val INTERVALS = listOf(1, 15, 60, 360, 1440)
    const val INTERVAL_DEFAULT = 4
    const val JOB_MIN_MINUTES = 15
    const val INSTALL_ASK = 0
    const val INSTALL_AUTO = 1
    private const val CHANNEL = "updates"

    val fetcher = Fetcher { url ->
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "Antite")
        if (c.responseCode != 200) throw IOException("$url: HTTP ${c.responseCode}")
        c.inputStream
    }

    fun state(context: Context) = context.getSharedPreferences("engines", Context.MODE_PRIVATE)

    private fun settings(context: Context) = context.getSharedPreferences("antite", Context.MODE_PRIVATE)

    private class Fetched(val engine: Offer?, val shell: ShellOffer?)

    private fun fetch(context: Context, source: Source): Fetched {
        val json = fetcher.open(source.url).use(InputStream::readBytes)
        val sig = fetcher.open("${source.url}.sig").use(InputStream::readBytes)
        val st = state(context)
        val key = "serial.${source.id}"
        val index = EngineIndex.verify(json, sig, EngineIndex.publicKey(source.key), st.getLong(key, 0))
        st.edit().putLong(key, index.serial).commit()
        val abi = Abi.current ?: throw IOException("неизвестная архитектура процессора ${System.getProperty("os.arch")}")
        val engine = index.choose(abi, dev.antite.engine.BuildConfig.ENGINE_API, BuildConfig.VERSION_CODE)?.let { Offer(it, abi, source) }
        val shell = index.shell?.let { s -> s.apk[abi]?.let { ShellOffer(s.version, it, source) } }
        return Fetched(engine, shell)
    }

    class Found(val offer: Offer?, val shell: ShellOffer?, val errors: List<String>)

    // What the update source offers, skipping engines that already failed here. Other sources are not asked.
    fun best(context: Context): Found = fromSource(context, Sources.updateSource(context))

    fun fromSource(context: Context, s: Source): Found {
        val fetched = try {
            fetch(context, s)
        } catch (e: IndexExpiredException) {
            val date = java.text.DateFormat.getDateInstance().format(java.util.Date(e.expires * 1000))
            return Found(null, null, listOf("${s.name}: список движков устарел ($date). Проверьте дату на телефоне"))
        } catch (e: IndexException) {
            return Found(null, null, listOf("${s.name}: подпись не прошла проверку"))
        } catch (e: Exception) {
            return Found(null, null, listOf("${s.name}: ${e.message ?: e.javaClass.simpleName}"))
        }
        val rejected = Engines.store(context).rejected()
        val offer = fetched.engine?.takeIf { it.dirName !in rejected }
        val shell = fetched.shell?.takeIf { it.version > BuildConfig.VERSION_CODE }
        return Found(offer, shell, emptyList())
    }

    fun debugEnabled(context: Context) = settings(context).getBoolean(DEBUG, false)

    // Debug mode: what an index at any address offers, without checking its signature; nothing is remembered.
    fun fromAddress(context: Context, url: String): Updates {
        if (!debugEnabled(context)) throw IOException("режим отладки выключен")
        val index = EngineIndex.unsigned(fetcher.open(url).use(InputStream::readBytes))
        val abi = Abi.current ?: throw IOException("неизвестная архитектура процессора ${System.getProperty("os.arch")}")
        val source = Source("debug", "Отладка", url, "", enabled = false)
        val engine = index.choose(abi, dev.antite.engine.BuildConfig.ENGINE_API, BuildConfig.VERSION_CODE)?.let { Offer(it, abi, source) }
        val shell = index.shell?.let { s -> s.apk[abi]?.let { ShellOffer(s.version, it, source) } }
        return Updates(engine, shell)
    }

    fun newer(a: String, b: String): Boolean {
        val x = a.split('.').map { it.toLongOrNull() ?: 0 }
        val y = b.split('.').map { it.toLongOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (d != 0) return d > 0
        }
        return false
    }

    // The offer if it is newer than both the running and the already downloaded engine.
    fun newerThanInstalled(context: Context, offer: Offer?): Offer? {
        val store = Engines.store(context)
        val installed = listOfNotNull(store.active(), store.pending()).mapNotNull(store::version)
        return offer?.takeIf { o -> installed.none { !newer(o.entry.version, it) } }
    }

    // An engine newer than the installed ones and a newer shell, from the update source.
    fun check(context: Context): Updates {
        val found = best(context)
        val result = newerThanInstalled(context, found.offer)
        state(context).edit().putLong("lastCheck", System.currentTimeMillis())
            .putString("lastError", found.errors.joinToString("\n").ifEmpty { null })
            .putString("available", result?.entry?.version)
            .putInt("shellAvailable", found.shell?.version ?: 0).commit()
        if (found.offer == null && found.shell == null && found.errors.isNotEmpty()) throw IOException(found.errors.joinToString("; "))
        return Updates(result, found.shell)
    }

    // The version being downloaded in this process, so it is not offered again meanwhile.
    @Volatile
    var installing: String? = null
        private set

    @Synchronized
    fun install(context: Context, offer: Offer, progress: (done: Long, total: Long) -> Unit = { _, _ -> }): File {
        val store = Engines.store(context)
        store.dir.mkdirs()
        installing = offer.entry.version
        val dir = try {
            EngineInstaller(store.dir, fetcher).install(offer.entry, offer.abi, progress)
        } finally {
            installing = null
        }
        if (store.active() == null) store.activate(dir) else store.setPending(dir)
        state(context).edit().remove("available").putString("origin.${dir.name}", offer.source.name).commit()
        cancelNotification(context, NOTIFY_ENGINE)
        return dir
    }

    fun origin(context: Context, engine: File): String? = state(context).getString("origin.${engine.name}", null)

    fun intervalMinutes(context: Context): Int =
        INTERVALS[settings(context).getInt(INTERVAL, INTERVAL_DEFAULT).coerceIn(0, INTERVALS.lastIndex)]

    fun schedule(context: Context) {
        val js = context.getSystemService(JobScheduler::class.java)
        if (!settings(context).getBoolean(AUTO, true)) {
            js.cancel(JOB_CHECK)
            js.cancel(JOB_DOWNLOAD)
            return
        }
        val period = maxOf(intervalMinutes(context), JOB_MIN_MINUTES) * 60_000L
        if (js.getPendingJob(JOB_CHECK)?.intervalMillis == period) return
        js.schedule(
            JobInfo.Builder(JOB_CHECK, ComponentName(context, UpdateJob::class.java))
                .setPeriodic(period)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build(),
        )
    }

    private fun scheduleDownload(context: Context) {
        context.getSystemService(JobScheduler::class.java).schedule(
            JobInfo.Builder(JOB_DOWNLOAD, ComponentName(context, UpdateJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                .setPersisted(true)
                .build(),
        )
    }

    const val NOTIFY_ENGINE = 0
    const val NOTIFY_SHELL = 1

    fun notify(context: Context, id: Int, title: String, text: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Обновления браузера", NotificationManager.IMPORTANCE_LOW))
        val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_public)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_IMMUTABLE))
            .build()
        try {
            nm.notify(CHANNEL, id, n)
        } catch (e: SecurityException) {
            Log.w("Antite", "no permission to notify", e)
        }
    }

    fun cancelNotification(context: Context, id: Int) =
        context.getSystemService(NotificationManager::class.java).cancel(CHANNEL, id)

    fun runInBackground(context: Context) {
        val store = Engines.store(context)
        if (store.active() == null) return
        store.removeUnused()
        val updates = try {
            check(context)
        } catch (e: Exception) {
            Log.w("Antite", "engine update check failed", e)
            return
        }
        // A shell is installed by the system installer, which always asks the user.
        updates.shell?.let {
            notify(context, NOTIFY_SHELL, "Доступна новая версия Antite", "Сборка ${it.version}. Откройте браузер, чтобы обновить")
        }
        val offer = updates.engine ?: return
        if (settings(context).getInt(INSTALL, INSTALL_ASK) != INSTALL_AUTO) {
            notify(context, NOTIFY_ENGINE, "Доступен новый движок", "Gecko ${offer.entry.version}. Откройте браузер, чтобы обновить")
            return
        }
        val metered = context.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered
        if (metered && settings(context).getBoolean(WIFI_ONLY, true)) {
            scheduleDownload(context)
            return
        }
        val task = UpdateDownloads.engineTask(context, offer)
        if (DownloadService.runHere(context, task) == DownloadService.FAILED) {
            state(context).edit().putString("lastError", "не удалось скачать: ${task.error?.message}").commit()
        }
    }
}
