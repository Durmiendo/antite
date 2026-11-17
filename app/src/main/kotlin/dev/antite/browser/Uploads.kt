package dev.antite.browser

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dev.antite.browser.data.MediaVault
import dev.antite.keys.VaultFile
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

    // A storage file given to a site: decrypted into the cache only for as long as the app is on screen; wipe()
    // overwrites and deletes these copies when it leaves.
    suspend fun fromVault(context: Context, vault: MediaVault, items: List<MediaVault.Item>): List<Uri> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        items.mapIndexedNotNull { i, item ->
            runCatching {
                val dir = File(File(context.cacheDir, VAULT_DIR), "$now-$i").apply { mkdirs() }
                val file = File(dir, item.name.substringAfterLast('/').ifBlank { "file" })
                vault.reader(item).stream().use { input -> file.outputStream().use { input.copyTo(it) } }
                Uri.fromFile(file)
            }.getOrNull()
        }
    }

    fun wipe(context: Context) {
        File(context.cacheDir, VAULT_DIR).walkBottomUp().forEach { f -> if (f.isFile) VaultFile.wipe(f) else f.delete() }
    }

    private const val VAULT_DIR = "uploads-vault"

    private fun name(context: Context, uri: Uri): String {
        val shown = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment
        return shown?.substringAfterLast('/')?.takeIf { it.isNotBlank() && it != "." && it != ".." } ?: "file"
    }
}
