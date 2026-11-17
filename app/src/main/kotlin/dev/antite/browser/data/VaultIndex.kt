package dev.antite.browser.data

import dev.antite.browser.data.MediaVault.Folder
import dev.antite.browser.data.MediaVault.Item
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer

// The secure storage's index without the disk: what goes in it, and the folder tree.
object VaultIndex {
    class Index(val items: List<Item>, val folders: List<Folder>, val keys: Map<Long, ByteArray>)

    // The length of the JSON, the JSON, then id and key of every file. Keys stay out of the JSON, so they never become
    // strings that cannot be wiped.
    fun encode(items: List<Item>, folders: List<Folder>, keys: Map<Long, ByteArray>): ByteArray {
        val json = JSONObject().put("items", itemsJson(items)).put("folders", foldersJson(folders)).toString().toByteArray()
        val kept = items.mapNotNull { i -> keys[i.id]?.let { i.id to it } }
        val out = ByteBuffer.allocate(4 + json.size + kept.size * (8 + Cipher256.KEY)).putInt(json.size).put(json)
        kept.forEach { (id, k) -> out.putLong(id).put(k) }
        json.fill(0)
        return out.array()
    }

    fun decode(plain: ByteArray): Index {
        val buf = ByteBuffer.wrap(plain)
        val json = ByteArray(buf.int).also { buf.get(it) }
        val o = JSONObject(String(json))
        json.fill(0)
        val keys = HashMap<Long, ByteArray>()
        while (buf.remaining() >= 8 + Cipher256.KEY) keys[buf.long] = ByteArray(Cipher256.KEY).also { buf.get(it) }
        return Index(parseItems(o.getJSONArray("items")), parseFolders(o.getJSONArray("folders")), keys)
    }

    // The JSON of a version 1 index: no keys in it.
    fun decodeV1(json: String): Index {
        val o = JSONObject(json)
        return Index(parseItems(o.getJSONArray("items")), parseFolders(o.getJSONArray("folders")), emptyMap())
    }

    private fun itemsJson(items: List<Item>) = JSONArray().apply {
        items.forEach { i ->
            put(JSONObject()
                .put("id", i.id).put("name", i.name).put("mime", i.mime)
                .put("size", i.size).put("time", i.time).put("source", i.source)
                .apply { i.folder?.let { put("folder", it) } })
        }
    }

    private fun foldersJson(folders: List<Folder>) = JSONArray().apply {
        folders.forEach { put(JSONObject().put("id", it.id).put("name", it.name).apply { it.parent?.let { p -> put("parent", p) } }) }
    }

    fun parseItems(a: JSONArray): List<Item> = (0 until a.length()).map { i ->
        val o = a.getJSONObject(i)
        Item(o.getLong("id"), o.getString("name"), o.getString("mime"), o.getLong("size"),
            o.getLong("time"), o.optString("source"), if (o.has("folder")) o.getLong("folder") else null)
    }

    private fun parseFolders(a: JSONArray): List<Folder> = (0 until a.length()).map { i ->
        a.getJSONObject(i).let { Folder(it.getLong("id"), it.getString("name"), if (it.has("parent")) it.getLong("parent") else null) }
    }

    // A deleted folder's files and folders stay, one level up.
    fun deleteFolder(folders: List<Folder>, items: List<Item>, f: Folder): Pair<List<Folder>, List<Item>> =
        folders.filter { it.id != f.id }.map { if (it.parent == f.id) it.copy(parent = f.parent) else it } to
            items.map { if (it.folder == f.id) it.copy(folder = f.parent) else it }

    // Not into itself or one of its own folders.
    fun canMove(folders: List<Folder>, f: Folder, parent: Long?): Boolean {
        var p = parent
        val seen = HashSet<Long>()
        while (p != null && seen.add(p)) {
            if (p == f.id) return false
            p = folders.firstOrNull { it.id == p }?.parent
        }
        return true
    }

    // The folder and its parents up to the top, for a path line.
    fun path(folders: List<Folder>, id: Long?): List<Folder> {
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
