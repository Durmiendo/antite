package dev.antite.browser.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.caverock.androidsvg.SVG
import dev.antite.browser.lang.catching
import dev.antite.browser.lang.decodedOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class TabCategory(val id: String, name: String, color: Int, icon: String?, protectDownloads: Boolean = false, locked: Boolean = false) {
    var name by mutableStateOf(name)
    var color by mutableStateOf(color)
    // File name under files/categories, or null for no icon.
    var icon by mutableStateOf(icon)
    // Files downloaded from its tabs go straight to the secure storage and never show in downloads.
    var protectDownloads by mutableStateOf(protectDownloads)
    // Its tabs show only after the device unlock, like locked incognito tabs.
    var locked by mutableStateOf(locked)
    // A container id: new tabs of the category open in it.
    var container by mutableStateOf<String?>(null)
}

// The user's tab categories, in strip order. "All" and incognito are not stored: they always exist.
class Categories(private val context: Context, private val prefs: Prefs) {
    companion object {
        const val PREF = "tabCategories"
        const val ALL = "all"
        const val INCOGNITO = "incognito"
        private const val ICON_PX = 96
        val COLORS = listOf(
            0xFF8AB4F8.toInt(), 0xFF81C995.toInt(), 0xFFFDD663.toInt(), 0xFFF28B82.toInt(), 0xFFFCAD70.toInt(),
            0xFFFF8BCB.toInt(), 0xFFC58AF9.toInt(), 0xFF78D9EC.toInt(), 0xFFE6C9A8.toInt(), 0xFFBDC1C6.toInt(),
        )
    }

    private val dir = File(context.filesDir, "categories")
    val list = mutableStateListOf<TabCategory>()

    init {
        catching {
            val arr = JSONArray(prefs.string(PREF, "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list += TabCategory(o.getString("id"), o.optString("name"), o.optInt("color", COLORS[0]),
                    o.optString("icon").ifEmpty { null }, o.optBoolean("protect"), o.optBoolean("lock"))
                    .apply { container = o.optString("container").ifEmpty { null } }
            }
        }.onFailure { Log.w("Antite", "tab categories unreadable", it) }
    }

    fun byId(id: String?) = list.firstOrNull { it.id == id }

    fun save() {
        val arr = JSONArray()
        list.forEach { c ->
            arr.put(JSONObject().put("id", c.id).put("name", c.name).put("color", c.color).put("protect", c.protectDownloads).put("lock", c.locked)
                .apply { c.icon?.let { put("icon", it) } }
                .apply { c.container?.let { put("container", it) } })
        }
        prefs.edit { putString(PREF, arr.toString()) }
    }

    fun add(name: String): TabCategory {
        val c = TabCategory(UUID.randomUUID().toString(), name, COLORS[list.size % COLORS.size], null)
        list += c
        save()
        return c
    }

    fun delete(c: TabCategory) {
        list.remove(c)
        c.icon?.let { File(dir, it).delete() }
        save()
    }

    fun move(c: TabCategory, delta: Int) {
        val i = list.indexOf(c)
        val j = i + delta
        if (i < 0 || j !in list.indices) return
        list.removeAt(i)
        list.add(j, c)
        save()
    }

    // Copies the picked image next to the browser's data and returns its file name; runs off the main thread.
    fun importIcon(c: TabCategory, uri: Uri): String? {
        val mime = context.contentResolver.getType(uri) ?: ""
        val ext = when {
            "svg" in mime -> "svg"
            else -> MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: uri.lastPathSegment?.substringAfterLast('.', "") ?: ""
        }.lowercase()
        if (ext !in setOf("svg", "png", "jpg", "jpeg", "webp")) return null
        dir.mkdirs()
        val name = "${c.id}-${System.currentTimeMillis()}.$ext"
        val file = File(dir, name)
        catching { context.contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } } }
            .onFailure { file.delete(); return null }
        if (bitmap(file) == null) {
            file.delete()
            return null
        }
        return name
    }

    fun setIcon(c: TabCategory, name: String) {
        c.icon?.let { File(dir, it).delete() }
        c.icon = name
        save()
    }

    fun removeIcon(c: TabCategory) {
        c.icon?.let { File(dir, it).delete() }
        c.icon = null
        save()
    }

    fun iconFile(c: TabCategory) = c.icon?.let { File(dir, it) }

    private val cache = ConcurrentHashMap<String, Bitmap>()

    // A square bitmap of the icon; svg is drawn by AndroidSVG, the rest decoded by Android. Runs off the main thread.
    fun bitmap(file: File): Bitmap? = cache[file.path] ?: decode(file)?.also { cache[file.path] = it }

    private fun decode(file: File): Bitmap? = decodedOrNull {
        if (file.extension == "svg") {
            val svg = file.inputStream().use { SVG.getFromInputStream(it) }
            if (svg.documentViewBox == null && svg.documentWidth > 0 && svg.documentHeight > 0) {
                svg.setDocumentViewBox(0f, 0f, svg.documentWidth, svg.documentHeight)
            }
            svg.setDocumentWidth("100%")
            svg.setDocumentHeight("100%")
            Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888).also { svg.renderToCanvas(Canvas(it)) }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= ICON_PX && bounds.outHeight / (sample * 2) >= ICON_PX) sample *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
}
