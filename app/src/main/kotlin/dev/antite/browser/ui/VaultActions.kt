package dev.antite.browser.ui

import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.antite.browser.Browser
import dev.antite.browser.DialogButton
import dev.antite.browser.UiDialog
import dev.antite.browser.FormField
import dev.antite.browser.data.MediaVault
import dev.antite.browser.data.VaultFolderId
import dev.antite.browser.data.VaultShare
import dev.antite.browser.l10n.T
import dev.antite.browser.lang.catching

// What can be done to files of the storage, from the grid's selection and from the viewer alike. Files leave the
// storage only by "Restore" (back to downloads) and "Share", which streams them decrypted (VaultShare).
fun Browser.vaultMove(items: List<MediaVault.Item>, done: () -> Unit = {}) = pickFolder { target ->
    mediaVault.move(items, target)
    toast(T.vaultMoved(count = items.size))
    done()
}

fun Browser.vaultShare(items: List<MediaVault.Item>) {
    catching { activity.startActivity(VaultShare.intent(activity, items)) }
        .onFailure { android.util.Log.w("Antite", "vault share failed", it) }
}

fun Browser.vaultRestore(items: List<MediaVault.Item>, done: () -> Unit = {}) = mediaVault.restore(items) { n ->
    downloads.revision.intValue++
    toast(if (n == 0) T.vaultRestoreFailed else T.vaultRestoredCount(count = n))
    done()
}

fun Browser.vaultDelete(items: List<MediaVault.Item>, done: () -> Unit = {}) =
    confirm(if (items.size == 1) T.vaultDeleteConfirm else T.vaultDeleteCountConfirm(count = items.size), T.actionDelete) {
        mediaVault.delete(items) { toast(T.vaultDeleted) }
        done()
    }

fun Browser.vaultRename(i: MediaVault.Item, done: () -> Unit = {}) {
    show(UiDialog.Form(T.vaultRename, null, listOf(FormField(T.vaultFileName, i.name, FormField.Kind.NAME)), onConfirm = { v ->
        val name = v[0].trim()
        if (name.isEmpty()) return@Form false
        mediaVault.rename(i, name)
        done()
        true
    }))
}

fun Browser.vaultInfo(i: MediaVault.Item) {
    val folder = mediaVault.path(i.folder).joinToString(" › ") { it.name }.ifEmpty { T.vaultTitle }
    val date = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
        .format(java.util.Date(i.time))
    show(UiDialog.Message(i.name, T.vaultInfoText(type = i.mime,
        size = android.text.format.Formatter.formatShortFileSize(activity, i.size), date = date, folder = folder),
        buttons = listOf(DialogButton(T.actionClose))))
}

// Everything at once, for the viewer's menu and for the selection's "more".
fun Browser.vaultFileMenu(items: List<MediaVault.Item>, done: () -> Unit = {}) {
    val one = items.singleOrNull()
    show(UiDialog.Items(one?.name ?: T.vaultSelected(count = items.size), listOfNotNull(
        DialogButton(T.vaultToFolder) { vaultMove(items, done) },
        DialogButton(T.vaultShare) { vaultShare(items) },
        one?.let { DialogButton(T.vaultRename) { vaultRename(it, done) } },
        one?.let { DialogButton(T.vaultInfo) { vaultInfo(it) } },
        DialogButton(T.vaultRestore) { vaultRestore(items, done) },
        DialogButton(T.actionDelete) { vaultDelete(items, done) },
    )))
}

fun Browser.vaultFolderName(title: String, name: String, done: (String) -> Unit) {
    show(UiDialog.Form(title, null, listOf(FormField(T.vaultFolderName, name, FormField.Kind.NAME)), onConfirm = { v ->
        val entered = v[0].trim()
        if (entered.isEmpty()) return@Form false
        done(entered)
        true
    }))
}

// Every folder by its full path, and the top of the storage.
internal fun Browser.pickFolder(except: VaultFolderId? = null, done: (VaultFolderId?) -> Unit) {
    val list = mediaVault.folders.filter { it.id != except }
        .map { f -> mediaVault.path(f.id).joinToString(" › ") { it.name } to f.id }.sortedBy { it.first.lowercase() }
    show(UiDialog.Items(T.vaultToFolder, listOf(DialogButton(T.vaultNoFolder) { done(null) }) +
        list.map { (name, id) -> DialogButton(name) { done(id) } }))
}

fun Browser.vaultFolderActions(f: MediaVault.Folder) {
    show(UiDialog.Items(f.name, listOf(
        DialogButton(T.vaultFolderRename) { vaultFolderName(T.vaultFolderRename, f.name) { mediaVault.renameFolder(f, it) } },
        DialogButton(T.vaultToFolder) { pickFolder(f.id) { mediaVault.moveFolder(f, it) } },
        DialogButton(T.vaultFolderDelete) { confirm(T.vaultFolderDeleteConfirm(name = f.name), T.actionDelete) { mediaVault.deleteFolder(f) } },
    )))
}

// Files from the system file chooser go into the open folder.
fun Browser.vaultImport(folder: VaultFolderId?) = activity.openDocuments { uris ->
    if (uris.isEmpty()) return@openDocuments
    toast(T.vaultAdding(count = uris.size))
    mediaVault.import(uris, folder, prefs.bool("vaultDeleteOriginal", true)) { added, failed -> vaultImported(added, failed) }
}

fun Browser.vaultImported(added: Int, failed: Int) =
    toast(if (failed == 0) T.vaultAdded(count = added) else T.vaultAddedSome(count = added, failed = failed))

internal fun Browser.vaultSortMenu(current: String, onSort: (String) -> Unit) {
    show(UiDialog.Items(T.vaultSort, listOf("date" to T.vaultSortDate, "name" to T.vaultSortName, "size" to T.vaultSortSize)
        .map { (key, label) -> DialogButton(if (key == current) "✓ $label" else label) { onSort(key) } }))
}

// "+" at the top: a folder or files, both into the open folder.
internal fun Browser.vaultAddMenu(folder: VaultFolderId?, onFolder: (VaultFolderId?) -> Unit) {
    show(UiDialog.Items(T.vaultAdd, listOf(
        DialogButton(T.vaultFolderNew) { vaultFolderName(T.vaultFolderNew, "") { mediaVault.addFolder(it, folder) { f -> onFolder(f.id) } } },
        DialogButton(T.vaultAddFiles) { vaultImport(folder) },
    )))
}
