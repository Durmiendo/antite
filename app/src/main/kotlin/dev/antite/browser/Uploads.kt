package dev.antite.browser

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import dev.antite.browser.data.MediaVault
import dev.antite.browser.data.Secure
import dev.antite.keys.VaultFile
import dev.antite.keys.deleteTree
import dev.antite.keys.output
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// GeckoView turns a picked content:// file into a storage path the app may not read (scoped storage), so the site
// gets no file; like Fenix, the picked files are copied into the app cache and handed over as file:// instead.
object Uploads {
    private const val KEEP_MS = 24 * 60 * 60 * 1000L

    suspend fun copy(context: Context, uris: List<Uri>): List<Uri> = withContext(Dispatchers.IO) {
        val disk = Secure.disk(context)
        val root = File(context.cacheDir, "uploads")
        val now = System.currentTimeMillis()
        runCatching { disk.children(root).filter { now - disk.modified(it) > KEEP_MS }.forEach { disk.deleteTree(it) } }
            .onFailure { Log.w("Antite", "old upload copies not deleted", it) }
        uris.mapIndexedNotNull { i, uri ->
            runCatching {
                val dir = File(root, "$now-$i").also { disk.mkdirs(it) }
                val file = File(dir, name(context, uri))
                context.contentResolver.openInputStream(uri)!!.use { input ->
                    disk.output(file, sync = false).use { input.copyTo(it) }
                }
                Uri.fromFile(file)
            }.getOrNull()
        }
    }

    // A storage file given to a site: decrypted into the cache only for as long as the app is on screen; wipe()
    // overwrites and deletes these copies when it leaves.
    suspend fun fromVault(context: Context, vault: MediaVault, items: List<MediaVault.Item>): List<Uri> = withContext(Dispatchers.IO) {
        val disk = Secure.disk(context)
        val now = System.currentTimeMillis()
        items.mapIndexedNotNull { i, item ->
            runCatching {
                val dir = File(File(context.cacheDir, VAULT_DIR), "$now-$i").also { disk.mkdirs(it) }
                val file = File(dir, item.name.substringAfterLast('/').ifBlank { "file" })
                vault.reader(item).stream().use { input -> disk.output(file, sync = false).use { input.copyTo(it) } }
                Uri.fromFile(file)
            }.getOrNull()
        }
    }

    fun wipe(context: Context) {
        val disk = Secure.disk(context)
        fun walk(f: File) {
            if (disk.isDirectory(f)) {
                disk.children(f).forEach(::walk)
                runCatching { disk.delete(f) }.onFailure { Log.w("Antite", "upload folder not deleted", it) }
            } else runCatching { VaultFile.wipe(disk, f) }.onFailure { e ->
                Log.w("Antite", "upload copy not wiped", e)
                runCatching { disk.delete(f) }
            }
        }
        walk(File(context.cacheDir, VAULT_DIR))
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
