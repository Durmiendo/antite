package dev.antite.browser.data

import dev.antite.browser.data.MediaVault.Folder
import dev.antite.browser.data.MediaVault.Item
import dev.antite.keys.Cipher256
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer

// The secure storage's index without the disk: what goes in it, and the folder tree.
object VaultIndex {
    // keys: per file, the box of its key under the vault's data key (version 3), or, from a version 2 index, the key
    // itself (raw), which the caller must put into the core and wipe at once.
    class Index(val items: List<Item>, val folders: List<Folder>, val keys: Map<VaultFileId, ByteArray>, val raw: Boolean = false)

    private const val VERSION = 3

    // The length of the JSON, the JSON, then id, length and box of the key of every file. Keys stay out of the JSON.
    fun encode(items: List<Item>, folders: List<Folder>, boxes: Map<VaultFileId, ByteArray>): ByteArray {
        val json = JSONObject().put("v", VERSION).put("items", itemsJson(items)).put("folders", foldersJson(folders)).toString().toByteArray()
        val kept = items.mapNotNull { i -> boxes[i.id]?.let { i.id to it } }
        val out = ByteBuffer.allocate(4 + json.size + kept.sumOf { 8 + 2 + it.second.size }).putInt(json.size).put(json)
        kept.forEach { (id, box) -> out.putLong(id.value).putShort(box.size.toShort()).put(box) }
        json.fill(0)
        return out.array()
    }

    fun decode(plain: ByteArray): Index {
        val buf = ByteBuffer.wrap(plain)
        val json = ByteArray(buf.int).also { buf.get(it) }
        val o = JSONObject(String(json))
        json.fill(0)
        val keys = HashMap<VaultFileId, ByteArray>()
        val raw = o.optInt("v", 2) < VERSION
        if (raw) {
            while (buf.remaining() >= 8 + Cipher256.KEY) keys[VaultFileId(buf.long)] = ByteArray(Cipher256.KEY).also { buf.get(it) }
        } else {
            while (buf.remaining() >= 8 + 2) {
                val id = VaultFileId(buf.long)
                val n = buf.short.toInt() and 0xffff
                if (n > buf.remaining()) throw java.io.IOException("vault index cut short")
                keys[id] = ByteArray(n).also { buf.get(it) }
            }
        }
        return Index(parseItems(o.getJSONArray("items")), parseFolders(o.getJSONArray("folders")), keys, raw)
    }

    // The JSON of a version 1 index: no keys in it.
    fun decodeV1(json: String): Index {
        val o = JSONObject(json)
        return Index(parseItems(o.getJSONArray("items")), parseFolders(o.getJSONArray("folders")), emptyMap())
    }

    private fun itemsJson(items: List<Item>) = JSONArray().apply {
        items.forEach { i ->
            put(JSONObject()
                .put("id", i.id.value).put("name", i.name).put("mime", i.mime)
                .put("size", i.size).put("time", i.time).put("source", i.source)
                .apply { i.folder?.let { put("folder", it.value) } })
        }
    }

    private fun foldersJson(folders: List<Folder>) = JSONArray().apply {
        folders.forEach { put(JSONObject().put("id", it.id.value).put("name", it.name).apply { it.parent?.let { p -> put("parent", p.value) } }) }
    }

    fun parseItems(a: JSONArray): List<Item> = (0 until a.length()).map { i ->
        val o = a.getJSONObject(i)
        Item(VaultFileId(o.getLong("id")), o.getString("name"), o.getString("mime"), o.getLong("size"),
            o.getLong("time"), o.optString("source"), if (o.has("folder")) VaultFolderId(o.getLong("folder")) else null)
    }

    private fun parseFolders(a: JSONArray): List<Folder> = (0 until a.length()).map { i ->
        a.getJSONObject(i).let { Folder(VaultFolderId(it.getLong("id")), it.getString("name"), if (it.has("parent")) VaultFolderId(it.getLong("parent")) else null) }
    }

    // A deleted folder's files and folders stay, one level up.
    fun deleteFolder(folders: List<Folder>, items: List<Item>, f: Folder): Pair<List<Folder>, List<Item>> =
        folders.filter { it.id != f.id }.map { if (it.parent == f.id) it.copy(parent = f.parent) else it } to
            items.map { if (it.folder == f.id) it.copy(folder = f.parent) else it }

    // Not into itself or one of its own folders.
    fun canMove(folders: List<Folder>, f: Folder, parent: VaultFolderId?): Boolean {
        var p = parent
        val seen = HashSet<VaultFolderId>()
        while (p != null && seen.add(p)) {
            if (p == f.id) return false
            p = folders.firstOrNull { it.id == p }?.parent
        }
        return true
    }

    // The folder and its parents up to the top, for a path line.
    fun path(folders: List<Folder>, id: VaultFolderId?): List<Folder> {
        val out = ArrayList<Folder>()
        var cur = id
        while (cur != null && out.size < 64) {
            val f = folders.firstOrNull { it.id == cur } ?: break
            out.add(0, f)
            cur = f.parent
        }
        return out
    }
}
