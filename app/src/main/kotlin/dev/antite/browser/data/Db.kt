package dev.antite.browser.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class Entry(val id: Long, val url: String?, val title: String?, val time: Long, val extra: String?)

data class Site(val host: String, val url: String, val title: String?)

class Db private constructor(context: Context) : SQLiteOpenHelper(context, "antite.db", null, VERSION) {
    companion object {
        const val VERSION = 4

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
        onUpgrade(db, 1, 4)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
        if (oldV < 2) {
            db.execSQL("ALTER TABLE history ADD COLUMN visits INTEGER NOT NULL DEFAULT 1")
            db.execSQL("CREATE TABLE pins (id INTEGER PRIMARY KEY, url TEXT NOT NULL, title TEXT, pos INTEGER NOT NULL)")
        }
        if (oldV < 3) {
            db.execSQL("ALTER TABLE downloads ADD COLUMN source TEXT")
            db.execSQL("ALTER TABLE downloads ADD COLUMN size INTEGER")
        }
        if (oldV < 4) db.execSQL("ALTER TABLE pins ADD COLUMN category TEXT")
    }

    private fun Cursor.entries(): List<Entry> = use {
        buildList {
            while (moveToNext()) add(Entry(getLong(0), getString(1), getString(2), getLong(3), getString(4)))
        }
    }

    // Entry.extra is the pin's tab category id.
    fun pins(): List<Entry> =
        readableDatabase.rawQuery("SELECT id, url, title, pos, category FROM pins ORDER BY pos", null).entries()

    fun addPin(url: String, title: String, category: String? = null) {
        val db = writableDatabase
        val pos = db.rawQuery("SELECT IFNULL(MAX(pos), -1) + 1 FROM pins", null).use { it.moveToFirst(); it.getLong(0) }
        db.insert("pins", null, ContentValues().apply {
            put("url", url)
            put("title", title)
            put("pos", pos)
            put("category", category)
        })
    }

    fun updatePin(id: Long, url: String, title: String) {
        writableDatabase.update("pins", ContentValues().apply {
            put("url", url)
            put("title", title)
        }, "id=?", arrayOf(id.toString()))
    }

    fun setPinCategory(id: Long, category: String?) {
        writableDatabase.update("pins", ContentValues().apply { put("category", category) }, "id=?", arrayOf(id.toString()))
    }

    // Pins of a deleted category open in "All" again.
    fun clearPinCategory(category: String) {
        writableDatabase.update("pins", ContentValues().apply { putNull("category") }, "category=?", arrayOf(category))
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

    fun bookmarkSuggestions(text: String, limit: Int): List<Entry> {
        val f = "%$text%"
        return readableDatabase.rawQuery(
            "SELECT id, url, title, created, 'b' FROM bookmarks WHERE url LIKE ? OR title LIKE ? LIMIT ?",
            arrayOf(f, f, limit.toString()),
        ).entries()
    }

    // The history table of the time before the encrypted history, for moving it over.
    fun oldHistory(): List<HistoryIndex.Event.Page> =
        readableDatabase.rawQuery("SELECT url, title, visits, visited FROM history", null).use { c ->
            buildList { while (c.moveToNext()) add(HistoryIndex.Event.Page(c.getString(0), c.getString(1), c.getInt(2), c.getLong(3))) }
        }

    // Emptied, and the file rewritten, so the addresses do not linger in its free pages.
    fun dropOldHistory() {
        writableDatabase.delete("history", null, null)
        writableDatabase.execSQL("VACUUM")
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

    fun addDownload(name: String, mime: String, source: String): Long = writableDatabase.insert("downloads", null, ContentValues().apply {
        put("name", name)
        put("mime", mime)
        put("source", source)
        put("created", System.currentTimeMillis())
        put("status", "loading")
    })

    fun finishDownload(id: Long, uri: String?, status: String, size: Long) {
        writableDatabase.update("downloads", ContentValues().apply {
            put("uri", uri)
            put("status", status)
            put("size", size)
        }, "id=?", arrayOf(id.toString()))
    }

    // Downloads cut off by a killed process stay "loading" forever otherwise.
    fun failStaleDownloads(active: Set<Long>) {
        val keep = if (active.isEmpty()) "" else " AND id NOT IN (${active.joinToString(",")})"
        writableDatabase.execSQL("UPDATE downloads SET status='failed' WHERE status='loading'$keep")
    }

    fun downloads(filter: String?): List<Entry> = query(
        "SELECT id, uri, name, created, status || '|' || IFNULL(mime,'') || '|' || IFNULL(source,'') || '|' || IFNULL(size,-1) FROM downloads", "created", filter, 1000,
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
