package dev.antite.browser.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class Entry(val id: Long, val url: String?, val title: String?, val time: Long, val extra: String?)

data class Site(val host: String, val url: String, val title: String?)

class Db private constructor(context: Context) : SQLiteOpenHelper(context, "antite.db", null, 2) {
    companion object {
        private const val HISTORY_KEEP_MS = 90L * 24 * 3600 * 1000

        @Volatile
        private var instance: Db? = null

        fun get(context: Context): Db =
            instance ?: synchronized(this) { instance ?: Db(context.applicationContext).also { instance = it } }

        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
    }

    init {
        setWriteAheadLoggingEnabled(true)
    }

    suspend fun <T> io(block: Db.() -> T): T = withContext(dispatcher) { block() }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE history (id INTEGER PRIMARY KEY, url TEXT UNIQUE NOT NULL, title TEXT, visited INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX history_visited ON history(visited)")
        db.execSQL("CREATE TABLE bookmarks (id INTEGER PRIMARY KEY, url TEXT UNIQUE NOT NULL, title TEXT, created INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE downloads (id INTEGER PRIMARY KEY, name TEXT NOT NULL, uri TEXT, mime TEXT, created INTEGER NOT NULL, status TEXT NOT NULL)")
        db.execSQL("CREATE TABLE closed (id INTEGER PRIMARY KEY, url TEXT NOT NULL, title TEXT, state TEXT, closed INTEGER NOT NULL)")
        onUpgrade(db, 1, 2)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
        if (oldV < 2) {
            db.execSQL("ALTER TABLE history ADD COLUMN visits INTEGER NOT NULL DEFAULT 1")
            db.execSQL("CREATE TABLE pins (id INTEGER PRIMARY KEY, url TEXT NOT NULL, title TEXT, pos INTEGER NOT NULL)")
        }
    }

    private fun Cursor.entries(): List<Entry> = use {
        buildList {
            while (moveToNext()) add(Entry(getLong(0), getString(1), getString(2), getLong(3), getString(4)))
        }
    }

    fun pins(): List<Entry> =
        readableDatabase.rawQuery("SELECT id, url, title, pos, NULL FROM pins ORDER BY pos", null).entries()

    fun addPin(url: String, title: String) {
        val db = writableDatabase
        val pos = db.rawQuery("SELECT IFNULL(MAX(pos), -1) + 1 FROM pins", null).use { it.moveToFirst(); it.getLong(0) }
        db.insert("pins", null, ContentValues().apply {
            put("url", url)
            put("title", title)
            put("pos", pos)
        })
    }

    fun updatePin(id: Long, url: String, title: String) {
        writableDatabase.update("pins", ContentValues().apply {
            put("url", url)
            put("title", title)
        }, "id=?", arrayOf(id.toString()))
    }

    fun deletePin(id: Long) {
        writableDatabase.delete("pins", "id=?", arrayOf(id.toString()))
    }

    fun movePin(id: Long, delta: Int) {
        val list = pins()
        val i = list.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j < 0 || j >= list.size) return
        val a = list[i]
        val b = list[j]
        writableDatabase.execSQL("UPDATE pins SET pos=? WHERE id=?", arrayOf(b.time, a.id))
        writableDatabase.execSQL("UPDATE pins SET pos=? WHERE id=?", arrayOf(a.time, b.id))
    }

    fun frequent(skipHosts: Set<String>, limit: Int): List<Site> {
        val score = LinkedHashMap<String, Int>()
        val titles = HashMap<String, String>()
        val roots = HashMap<String, String>()
        val cutoff = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
        readableDatabase.rawQuery(
            "SELECT url, title, visits FROM history WHERE visited>? ORDER BY visits DESC LIMIT 500",
            arrayOf(cutoff.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val u = Uri.parse(c.getString(0))
                val host = u.host ?: continue
                val h = host.removePrefix("www.")
                if (h in skipHosts) continue
                if (h !in score) roots[h] = "${u.scheme}://$host/"
                score[h] = (score[h] ?: 0) + c.getInt(2)
                if (h !in titles && c.getString(1) != null) titles[h] = c.getString(1)
            }
        }
        return score.entries.sortedByDescending { it.value }.take(limit).map { Site(it.key, roots[it.key]!!, titles[it.key]) }
    }

    fun suggest(text: String, limit: Int): List<Entry> {
        val f = "%$text%"
        val seen = HashSet<String>()
        val out = ArrayList<Entry>()
        readableDatabase.rawQuery(
            "SELECT id, url, title, created, 'b' FROM bookmarks WHERE url LIKE ? OR title LIKE ? LIMIT ?",
            arrayOf(f, f, limit.toString()),
        ).entries().forEach { if (seen.add(it.url ?: "")) out += it }
        readableDatabase.rawQuery(
            "SELECT id, url, title, visited, 'h' FROM history WHERE url LIKE ? OR title LIKE ? " +
                "ORDER BY (url LIKE ?) DESC, visits DESC, visited DESC LIMIT ?",
            arrayOf(f, f, "%://$text%", (limit * 2).toString()),
        ).entries().forEach { if (out.size < limit && seen.add(it.url ?: "")) out += it }
        return out
    }

    fun visit(url: String, title: String?) {
        val db = writableDatabase
        val v = ContentValues().apply {
            put("url", url)
            put("visited", System.currentTimeMillis())
            if (!title.isNullOrEmpty()) put("title", title)
        }
        if (db.update("history", v, "url=?", arrayOf(url)) == 0) {
            db.insert("history", null, v)
        } else {
            db.execSQL("UPDATE history SET visits=visits+1 WHERE url=?", arrayOf(url))
        }
    }

    fun title(url: String, title: String) {
        if (title.isEmpty()) return
        writableDatabase.update("history", ContentValues().apply { put("title", title) }, "url=?", arrayOf(url))
    }

    fun visited(urls: List<String>): BooleanArray {
        val db = readableDatabase
        return BooleanArray(urls.size) { i ->
            db.rawQuery("SELECT 1 FROM history WHERE url=? LIMIT 1", arrayOf(urls[i])).use { it.moveToFirst() }
        }
    }

    fun history(filter: String?, limit: Int): List<Entry> {
        writableDatabase.delete("history", "visited<?",
            arrayOf((System.currentTimeMillis() - HISTORY_KEEP_MS).toString()))
        return query("SELECT id, url, title, visited, NULL FROM history", "visited", filter, limit)
    }

    fun deleteHistory(id: Long) {
        writableDatabase.delete("history", "id=?", arrayOf(id.toString()))
    }

    fun clearHistory() {
        writableDatabase.delete("history", null, null)
    }

    fun isBookmarked(url: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM bookmarks WHERE url=?", arrayOf(url)).use { it.moveToFirst() }

    fun addBookmark(url: String, title: String) {
        writableDatabase.insertWithOnConflict("bookmarks", null, ContentValues().apply {
            put("url", url)
            put("title", title)
            put("created", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun removeBookmark(url: String) {
        writableDatabase.delete("bookmarks", "url=?", arrayOf(url))
    }

    fun deleteBookmark(id: Long) {
        writableDatabase.delete("bookmarks", "id=?", arrayOf(id.toString()))
    }

    fun renameBookmark(id: Long, title: String) {
        writableDatabase.update("bookmarks", ContentValues().apply { put("title", title) }, "id=?", arrayOf(id.toString()))
    }

    fun bookmarks(filter: String?): List<Entry> =
        query("SELECT id, url, title, created, NULL FROM bookmarks", "created", filter, 5000)

    fun addDownload(name: String, mime: String): Long = writableDatabase.insert("downloads", null, ContentValues().apply {
        put("name", name)
        put("mime", mime)
        put("created", System.currentTimeMillis())
        put("status", "loading")
    })

    fun finishDownload(id: Long, uri: String?, ok: Boolean) {
        writableDatabase.update("downloads", ContentValues().apply {
            put("uri", uri)
            put("status", if (ok) "done" else "failed")
        }, "id=?", arrayOf(id.toString()))
    }

    fun downloads(filter: String?): List<Entry> = query(
        "SELECT id, uri, name, created, status || '|' || IFNULL(mime,'') FROM downloads", "created", filter, 1000,
    )

    fun clearDownloads() {
        writableDatabase.delete("downloads", null, null)
    }

    fun deleteDownload(id: Long) {
        writableDatabase.delete("downloads", "id=?", arrayOf(id.toString()))
    }

    fun addClosed(url: String, title: String, state: String?) {
        val db = writableDatabase
        db.insert("closed", null, ContentValues().apply {
            put("url", url)
            put("title", title)
            put("state", state)
            put("closed", System.currentTimeMillis())
        })
        db.execSQL("DELETE FROM closed WHERE id NOT IN (SELECT id FROM closed ORDER BY closed DESC LIMIT 50)")
    }

    fun closed(filter: String?): List<Entry> =
        query("SELECT id, url, title, closed, state FROM closed", "closed", filter, 50)

    fun deleteClosed(id: Long) {
        writableDatabase.delete("closed", "id=?", arrayOf(id.toString()))
    }

    fun clearClosed() {
        writableDatabase.delete("closed", null, null)
    }

    private fun query(base: String, order: String, filter: String?, limit: Int): List<Entry> {
        var sql = base
        var args: Array<String>? = null
        if (!filter.isNullOrEmpty()) {
            val downloads = base.contains("FROM downloads")
            val col = if (downloads) "name" else "title"
            val urlCol = if (downloads) "uri" else "url"
            sql += " WHERE $col LIKE ? OR $urlCol LIKE ?"
            args = arrayOf("%$filter%", "%$filter%")
        }
        sql += " ORDER BY $order DESC LIMIT $limit"
        return readableDatabase.rawQuery(sql, args).entries()
    }
}
