package dev.antite.browser.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class TabStore(dir: File, private val warn: (String, Throwable?) -> Unit = { m, e -> Log.w("Antite", m, e) }) {
    constructor(context: Context) : this(context.filesDir)

    data class Loaded(val tabs: List<Tab>, val selected: Long)

    private val file = File(dir, "tabs.json")
    private val thumbs = File(dir, "thumbs").apply { mkdirs() }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val io = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + io)

    fun load(): Loaded {
        if (!file.exists()) return Loaded(emptyList(), 0)
        return try {
            val root = JSONObject(file.readText())
            val arr = root.getJSONArray("tabs")
            val tabs = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Tab(o.getLong("id"), false).apply {
                    url = o.optString("url")
                    title = o.optString("title")
                    state = if (o.has("state")) o.getString("state") else null
                    desktop = o.optBoolean("desktop")
                    category = o.optString("category").ifEmpty { null }
                    container = o.optString("container").ifEmpty { null }
                    lastUsed = o.optLong("lastUsed")
                }
            }
            Loaded(tabs, root.optLong("selected"))
        } catch (e: Exception) {
            // Kept aside, so the next save does not overwrite the only copy of the tabs.
            file.renameTo(File(file.path + ".bad"))
            warn("tabs.json unreadable, kept as tabs.json.bad, starting fresh", e)
            Loaded(emptyList(), 0)
        }
    }

    fun save(tabs: List<Tab>, selected: Tab?) {
        val arr = JSONArray()
        for (t in tabs) {
            if (t.incognito) continue
            arr.put(JSONObject().apply {
                put("id", t.id)
                put("url", t.url)
                put("title", t.title)
                t.state?.let { put("state", it) }
                put("desktop", t.desktop)
                t.category?.let { put("category", it) }
                t.container?.let { put("container", it) }
                put("lastUsed", t.lastUsed)
            })
        }
        val json = JSONObject().put("tabs", arr)
            .put("selected", if (selected != null && !selected.incognito) selected.id else 0).toString()
        scope.launch {
            val tmp = File(file.path + ".tmp")
            try {
                FileOutputStream(tmp).use { out ->
                    out.write(json.toByteArray())
                    out.fd.sync()
                }
                if (!tmp.renameTo(file)) warn("tabs.json rename failed", null)
            } catch (e: Exception) {
                warn("tabs.json write failed", e)
            }
        }
    }

    fun saveThumb(t: Tab) {
        val b = t.thumb ?: return
        if (t.incognito) return
        scope.launch {
            runCatching {
                FileOutputStream(File(thumbs, "${t.id}.jpg")).use { b.compress(Bitmap.CompressFormat.JPEG, 70, it) }
            }.onFailure { Log.w("Antite", "thumb write failed", it) }
        }
    }

    fun loadThumb(t: Tab) {
        if (t.incognito || t.thumb != null || t.thumbRequested) return
        t.thumbRequested = true
        scope.launch {
            val f = File(thumbs, "${t.id}.jpg")
            val b = if (f.exists()) BitmapFactory.decodeFile(f.path) else null
            if (b != null) withContext(Dispatchers.Main) { if (t.thumb == null) t.thumb = b }
        }
    }

    fun deleteThumb(id: Long) {
        scope.launch { File(thumbs, "$id.jpg").delete() }
    }

    fun flush() = runBlocking { withContext(io) {} }
}
