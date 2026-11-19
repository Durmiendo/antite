package dev.antite.browser.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import dev.antite.browser.lang.catching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.FileNotFoundException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

// Files of the secure storage handed to another app: read through a pipe, decrypted as they go, never written out.
// Each link carries a random token of its own and dies when the storage locks, so it cannot be kept and used later.
class VaultShare : ContentProvider() {
    companion object {
        private val links = ConcurrentHashMap<String, VaultFileId>()
        private val random = SecureRandom()

        private fun authority(context: Context) = "${context.packageName}.vaultshare"

        fun uris(context: Context, items: List<MediaVault.Item>): List<Uri> = items.map { item ->
            val token = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
            links[token] = item.id
            Uri.Builder().scheme("content").authority(authority(context)).appendPath(token).appendPath(item.name).build()
        }

        // The chooser with read access to exactly these files.
        fun intent(context: Context, items: List<MediaVault.Item>): Intent {
            val list = uris(context, items)
            val mime = items.map { it.mime }.distinct().singleOrNull() ?: items.map { it.mime.substringBefore('/') }
                .distinct().singleOrNull()?.let { "$it/*" } ?: "*/*"
            val send = if (list.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, list[0])
            else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(list))
            send.type = mime
            send.clipData = android.content.ClipData.newRawUri(null, list[0]).apply { list.drop(1).forEach { addItem(android.content.ClipData.Item(it)) } }
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            return Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        fun revokeAll(context: Context) {
            for (token in links.keys) {
                catching { context.revokeUriPermission(Uri.parse("content://${authority(context)}/$token"), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
            links.clear()
        }
    }

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun item(uri: Uri): MediaVault.Item {
        val id = uri.pathSegments.firstOrNull()?.let(links::get) ?: throw FileNotFoundException("no such link")
        return MediaVault.get(context!!).items.firstOrNull { it.id == id } ?: throw FileNotFoundException("the storage is locked")
    }

    override fun onCreate() = true

    override fun getType(uri: Uri): String? = catching { item(uri).mime }.getOrNull()

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val i = item(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns, 1).apply {
            addRow(columns.map { c -> when (c) { OpenableColumns.DISPLAY_NAME -> i.name; OpenableColumns.SIZE -> i.size; else -> null } })
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read only")
        val vault = MediaVault.get(context!!)
        val i = item(uri)
        val (read, write) = ParcelFileDescriptor.createReliablePipe()
        io.launch {
            try {
                vault.reader(i).stream().use { input -> ParcelFileDescriptor.AutoCloseOutputStream(write).use { input.copyTo(it) } }
            } catch (e: Exception) {
                Log.w("Antite", "vault share of ${i.id} cut short", e)
                catching { write.closeWithError(e.message ?: "read failed") }
            }
        }
        return read
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
}
