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
import android.os.Build
import android.util.Log
import dev.antite.browser.BuildConfig
import dev.antite.browser.MainActivity
import dev.antite.browser.R
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class Offer(val entry: EngineEntry, val abi: String, val source: Source) {
    val size: Long get() = entry.adapter.size + entry.upstream.getValue(abi).size
}

object EngineUpdates {
    const val JOB_CHECK = 1
    const val JOB_DOWNLOAD = 2
    const val AUTO = "updatesAuto"
    const val INSTALL = "updatesInstall"
    const val WIFI_ONLY = "updatesWifiOnly"
    const val INSTALL_ASK = 0
    const val INSTALL_AUTO = 1
    private const val DAY_MS = 24L * 3600 * 1000
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

    fun fetchOffer(context: Context, source: Source): Offer? {
        val json = fetcher.open(source.url).use(InputStream::readBytes)
        val sig = fetcher.open("${source.url}.sig").use(InputStream::readBytes)
        val st = state(context)
        val key = "serial.${source.id}"
        val index = EngineIndex.verify(json, sig, EngineIndex.publicKey(source.key), st.getLong(key, 0))
        st.edit().putLong(key, index.serial).commit()
        val api = dev.antite.engine.BuildConfig.ENGINE_API
        return Build.SUPPORTED_ABIS.firstNotNullOfOrNull { abi ->
            index.choose(abi, api, BuildConfig.VERSION_CODE)?.let { Offer(it, abi, source) }
        }
    }

    class Found(val offer: Offer?, val errors: List<String>)

    // The newest engine across enabled sources, skipping engines that already failed here.
    fun best(context: Context): Found {
        val rejected = Engines.store(context).rejected()
        val errors = mutableListOf<String>()
        val offers = Sources.enabled(context).mapNotNull { s ->
            try {
                fetchOffer(context, s)
            } catch (e: IndexException) {
                errors += "${s.name}: подпись не прошла проверку"
                null
            } catch (e: Exception) {
                errors += "${s.name}: ${e.message ?: e.javaClass.simpleName}"
                null
            }
        }.filter { it.entry.dirName !in rejected }
        val best = offers.fold(null as Offer?) { acc, o -> if (acc == null || newer(o.entry.version, acc.entry.version)) o else acc }
        return Found(best, errors)
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

    // An offer newer than both the running and the already downloaded engine, or null.
    fun check(context: Context): Offer? {
        val store = Engines.store(context)
        val found = best(context)
        val installed = listOfNotNull(store.active(), store.pending()).mapNotNull(store::version)
        val result = found.offer?.takeIf { o -> installed.none { !newer(o.entry.version, it) } }
        state(context).edit().putLong("lastCheck", System.currentTimeMillis())
            .putString("lastError", found.errors.joinToString("\n").ifEmpty { null })
            .putString("available", result?.entry?.version).commit()
        if (result == null && found.offer == null && found.errors.isNotEmpty()) throw IOException(found.errors.joinToString("; "))
        return result
    }

    @Synchronized
    fun install(context: Context, offer: Offer, progress: (done: Long, total: Long) -> Unit = { _, _ -> }): File {
        val store = Engines.store(context)
        store.dir.mkdirs()
        val dir = EngineInstaller(store.dir, fetcher).install(offer.entry, offer.abi, progress)
        if (store.active() == null) store.activate(dir) else store.setPending(dir)
        state(context).edit().remove("available").putString("origin.${dir.name}", offer.source.name).commit()
        context.getSystemService(NotificationManager::class.java).cancel(CHANNEL, 0)
        return dir
    }

    fun origin(context: Context, engine: File): String? = state(context).getString("origin.${engine.name}", null)

    fun schedule(context: Context) {
        val js = context.getSystemService(JobScheduler::class.java)
        if (!settings(context).getBoolean(AUTO, true)) {
            js.cancel(JOB_CHECK)
            js.cancel(JOB_DOWNLOAD)
            return
        }
        if (js.getPendingJob(JOB_CHECK) != null) return
        js.schedule(
            JobInfo.Builder(JOB_CHECK, ComponentName(context, UpdateJob::class.java))
                .setPeriodic(DAY_MS)
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

    private fun notifyAvailable(context: Context, offer: Offer) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Обновления браузера", NotificationManager.IMPORTANCE_LOW))
        val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_public)
            .setContentTitle("Доступен новый движок")
            .setContentText("Gecko ${offer.entry.version}. Откройте браузер, чтобы обновить")
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE))
            .build()
        try {
            nm.notify(CHANNEL, 0, n)
        } catch (e: SecurityException) {
            Log.w("Antite", "no permission to notify", e)
        }
    }

    fun runInBackground(context: Context) {
        val store = Engines.store(context)
        if (store.active() == null) return
        store.removeUnused()
        val offer = try {
            check(context)
        } catch (e: Exception) {
            Log.w("Antite", "engine update check failed", e)
            return
        } ?: return
        if (settings(context).getInt(INSTALL, INSTALL_ASK) != INSTALL_AUTO) {
            notifyAvailable(context, offer)
            return
        }
        val metered = context.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered
        if (metered && settings(context).getBoolean(WIFI_ONLY, true)) {
            scheduleDownload(context)
            return
        }
        try {
            install(context, offer)
        } catch (e: Exception) {
            Log.w("Antite", "engine update download failed", e)
            state(context).edit().putString("lastError", "не удалось скачать: ${e.message}").commit()
        }
    }
}
