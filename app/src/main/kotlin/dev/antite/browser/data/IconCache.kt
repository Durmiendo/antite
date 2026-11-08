package dev.antite.browser.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.LruCache
import androidx.compose.runtime.mutableIntStateOf
import dev.antite.engine.Engine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class IconCache(context: Context, private val engine: Engine, private val download: () -> Boolean) {
    companion object {
        private const val SIZE = 96
        private const val MAX_BYTES = 512 * 1024
        private const val REFRESH_MS = 7L * 24 * 3600 * 1000
        private val MISSING: Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ALPHA_8)
    }

    val version = mutableIntStateOf(0)

    private val dir = File(context.filesDir, "icons").apply { mkdirs() }
    private val themes = context.getSharedPreferences("themes", Context.MODE_PRIVATE)
    private val mem = LruCache<String, Bitmap>(300)
    private val colors = HashMap<String, Int>()
    private val inFlight = HashSet<String>()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private fun file(host: String) = File(dir, host.replace(Regex("[^a-zA-Z0-9.-]"), "_") + ".png")

    private fun changed() {
        version.intValue++
    }

    fun get(host: String?): Bitmap? {
        version.intValue
        if (host.isNullOrEmpty()) return null
        val b = mem.get(host)
        if (b == null) {
            mem.put(host, MISSING)
            scope.launch {
                val f = file(host)
                val d = if (f.exists()) BitmapFactory.decodeFile(f.path) else null
                if (d != null) withContext(Dispatchers.Main) {
                    mem.put(host, d)
                    colors.remove(host)
                    changed()
                }
            }
            return null
        }
        return if (b === MISSING) null else b
    }

    fun setTheme(host: String, color: Int?) {
        val c = color ?: 0
        if (themes.getInt(host, 0) == c) return
        themes.edit().apply { if (c == 0) remove(host) else putInt(host, c) }.apply()
        changed()
    }

    fun color(host: String?): Int {
        if (host.isNullOrEmpty()) return 0
        val t = themes.getInt(host, 0)
        if (t != 0) return t
        colors[host]?.let { return it }
        val b = get(host) ?: return 0
        return dominant(b).also { colors[host] = it }
    }

    private fun dominant(b: Bitmap): Int {
        var r = 0L
        var g = 0L
        var bl = 0L
        var n = 0
        val hsv = FloatArray(3)
        for (y in 0 until b.height step 2) for (x in 0 until b.width step 2) {
            val p = b.getPixel(x, y)
            if (Color.alpha(p) < 160) continue
            Color.colorToHSV(p, hsv)
            if (hsv[1] < 0.3f || hsv[2] < 0.25f) continue
            r += Color.red(p)
            g += Color.green(p)
            bl += Color.blue(p)
            n++
        }
        if (n < (b.width / 2) * (b.height / 2) / 12) return 0
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (bl / n).toInt())
    }

    fun onPageIcon(host: String, iconUrl: String?, origin: String) {
        if (host.isEmpty() || iconUrl == null || !download()) return
        scope.launch {
            val f = file(host)
            if (f.exists() && System.currentTimeMillis() - f.lastModified() < REFRESH_MS) return@launch
            withContext(Dispatchers.Main) {
                if (inFlight.add(host)) fetch(host, iconUrl, "$origin/favicon.ico")
            }
        }
    }

    private fun fetch(host: String, url: String, fallback: String?) {
        engine.fetch(url, MAX_BYTES) { bytes ->
            scope.launch {
                val bmp = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                if (bmp == null) {
                    withContext(Dispatchers.Main) {
                        if (fallback != null && fallback != url) fetch(host, fallback, null) else inFlight.remove(host)
                    }
                    return@launch
                }
                val scaled = Bitmap.createScaledBitmap(bmp, SIZE, SIZE, true)
                runCatching { FileOutputStream(file(host)).use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                withContext(Dispatchers.Main) {
                    inFlight.remove(host)
                    mem.put(host, scaled)
                    colors.remove(host)
                    changed()
                }
            }
        }
    }

    fun clear() {
        mem.evictAll()
        colors.clear()
        themes.edit().clear().apply()
        scope.launch { dir.listFiles()?.forEach { it.delete() } }
        changed()
    }
}
