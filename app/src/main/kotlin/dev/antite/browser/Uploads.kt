package dev.antite.browser

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// GeckoView turns a picked content:// file into a storage path the app may not read (scoped storage), so the site
// gets no file; like Fenix, the picked files are copied into the app cache and handed over as file:// instead.
object Uploads {
    private const val KEEP_MS = 24 * 60 * 60 * 1000L

    suspend fun copy(context: Context, uris: List<Uri>): List<Uri> = withContext(Dispatchers.IO) {
        val root = File(context.cacheDir, "uploads")
        val now = System.currentTimeMillis()
        root.listFiles()?.filter { now - it.lastModified() > KEEP_MS }?.forEach { it.deleteRecursively() }
        uris.mapIndexedNotNull { i, uri ->
            runCatching {
                val dir = File(root, "$now-$i").apply { mkdirs() }
                val file = File(dir, name(context, uri))
                context.contentResolver.openInputStream(uri)!!.use { input ->
                    file.outputStream().use { input.copyTo(it) }
                }
                Uri.fromFile(file)
            }.getOrNull()
        }
    }

    private fun name(context: Context, uri: Uri): String {
        val shown = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment
        return shown?.substringAfterLast('/')?.takeIf { it.isNotBlank() && it != "." && it != ".." } ?: "file"
    }
}
