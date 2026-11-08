package dev.antite.browser.engine

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.util.Log
import dev.antite.browser.BuildConfig
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class Offer(val entry: EngineEntry, val abi: String) {
    val size: Long get() = entry.adapter.size + entry.upstream.getValue(abi).size
}

object EngineUpdates {
    const val JOB_CHECK = 1
    const val JOB_DOWNLOAD = 2
    const val AUTO = "updatesAuto"
    const val WIFI_ONLY = "updatesWifiOnly"
    private const val DAY_MS = 24L * 3600 * 1000

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

    fun sourceUrl(context: Context): String = state(context).getString("source", null) ?: BuildConfig.ENGINE_INDEX_URL

    fun setSource(context: Context, url: String) {
        state(context).edit().putString("source", url).commit()
    }

    fun fetchOffer(context: Context, url: String = sourceUrl(context)): Offer? {
        val json = fetcher.open(url).use(InputStream::readBytes)
        val sig = fetcher.open("$url.sig").use(InputStream::readBytes)
        val st = state(context)
        val index = EngineIndex.verify(json, sig, EngineIndex.publicKey(BuildConfig.PUBLISHER_KEY), st.getLong("serial", 0))
        st.edit().putLong("serial", index.serial).commit()
        val api = dev.antite.engine.BuildConfig.ENGINE_API
        return Build.SUPPORTED_ABIS.firstNotNullOfOrNull { abi ->
            index.choose(abi, api, BuildConfig.VERSION_CODE)?.let { Offer(it, abi) }
        }
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
        val st = state(context)
        try {
            val offer = fetchOffer(context)
            val installed = listOfNotNull(store.active(), store.pending()).mapNotNull(store::version)
            val result = offer?.takeIf { o -> installed.none { !newer(o.entry.version, it) } }
            st.edit().putLong("lastCheck", System.currentTimeMillis()).remove("lastError")
                .putString("available", result?.entry?.version).commit()
            return result
        } catch (e: Exception) {
            st.edit().putLong("lastCheck", System.currentTimeMillis())
                .putString("lastError", if (e is IndexException) "подпись источника не прошла проверку" else e.message ?: e.javaClass.simpleName)
                .commit()
            throw e
        }
    }

    @Synchronized
    fun install(context: Context, offer: Offer, progress: (done: Long, total: Long) -> Unit = { _, _ -> }): File {
        val store = Engines.store(context)
        store.dir.mkdirs()
        val dir = EngineInstaller(store.dir, fetcher).install(offer.entry, offer.abi, progress)
        if (store.active() == null) store.activate(dir) else store.setPending(dir)
        state(context).edit().remove("available").commit()
        return dir
    }

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
