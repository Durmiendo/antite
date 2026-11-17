package dev.antite.browser.data

import dev.antite.browser.data.HistoryIndex.Event
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import java.io.File

// The history of each category in its own encrypted log, files/history/<name>.log, with "-" for tabs outside any
// category. A log is read once, into its index; every change is appended, and the log is written again when the
// events far outnumber the pages.
@OptIn(ExperimentalSerializationApi::class)
class HistoryStore(
    private val dir: File,
    private val keyOf: (name: String) -> ByteArray,
    private val cipher: () -> Cipher256,
    private val now: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val NONE = "-"

        fun nameOf(category: String?) = category ?: NONE
    }

    @Serializable
    private class Rec(
        @ProtoNumber(1) val kind: Int,
        @ProtoNumber(2) val url: String = "",
        @ProtoNumber(3) val title: String? = null,
        @ProtoNumber(4) val at: Long = 0,
        @ProtoNumber(5) val visits: Int = 0,
    )

    private class Book(val log: EncryptedLog, val index: HistoryIndex)

    private val books = HashMap<String, Book>()

    private fun encode(e: Event): ByteArray = ProtoBuf.encodeToByteArray(when (e) {
        is Event.Visit -> Rec(1, e.url, e.title, e.at)
        is Event.Title -> Rec(2, e.url, e.title)
        is Event.Delete -> Rec(3, e.url)
        Event.Clear -> Rec(4)
        is Event.Page -> Rec(5, e.url, e.title, e.last, e.visits)
    })

    private fun decode(b: ByteArray): Event? = ProtoBuf.decodeFromByteArray<Rec>(b).let { r ->
        when (r.kind) {
            1 -> Event.Visit(r.url, r.title, r.at)
            2 -> Event.Title(r.url, r.title ?: "")
            3 -> Event.Delete(r.url)
            4 -> Event.Clear
            5 -> Event.Page(r.url, r.title, r.visits, r.at)
            else -> null
        }
    }

    private fun file(name: String) = File(dir, "$name.log")

    private fun book(name: String): Book = books.getOrPut(name) {
        val log = EncryptedLog(file(name), keyOf(name), cipher)
        val index = HistoryIndex(now)
        log.read().forEach { r -> decode(r)?.let(index::apply) }
        Book(log, index)
    }

    private fun write(name: String, e: Event) {
        val b = book(name)
        b.log.append(encode(e))
        b.index.apply(e)
        if (b.index.worthCompacting) {
            b.log.rewrite(b.index.snapshot().map(::encode))
            b.index.compacted()
        }
    }

    @Synchronized
    fun visit(category: String?, url: String, title: String?) = write(nameOf(category), Event.Visit(url, title, now()))

    @Synchronized
    fun title(category: String?, url: String, title: String) {
        if (title.isNotEmpty()) write(nameOf(category), Event.Title(url, title))
    }

    @Synchronized
    fun visited(category: String?, urls: List<String>) = book(nameOf(category)).index.visited(urls)

    // Entries of several categories together, newest first; extra holds the category, for deleting one.
    @Synchronized
    fun list(categories: Collection<String?>, filter: String?, limit: Int): List<Entry> =
        categories.map(::nameOf).distinct().flatMap { n -> book(n).index.list(filter, limit).map { it.copy(extra = n) } }
            .sortedByDescending { it.time }.take(limit)

    @Synchronized
    fun delete(e: Entry) {
        val url = e.url ?: return
        write(e.extra ?: NONE, Event.Delete(url))
    }

    @Synchronized
    fun clear(categories: Collection<String?>) = categories.map(::nameOf).distinct().forEach { write(it, Event.Clear) }

    @Synchronized
    fun suggest(category: String?, text: String, limit: Int) = book(nameOf(category)).index.suggest(text, limit)

    @Synchronized
    fun frequent(category: String?, skipHosts: Set<String>, limit: Int) = book(nameOf(category)).index.frequent(skipHosts, limit)

    // Pages from before this store, put into a category's log at once (an empty log only).
    @Synchronized
    fun import(category: String?, pages: List<Event.Page>) {
        val name = nameOf(category)
        val b = book(name)
        if (b.index.size > 0 || pages.isEmpty()) return
        b.log.rewrite(pages.map(::encode))
        pages.forEach(b.index::apply)
        b.index.compacted()
    }

    // The category is gone: its log is deleted (its key goes with the category's key file).
    @Synchronized
    fun forget(category: String?) {
        val name = nameOf(category)
        books.remove(name)
        file(name).delete()
    }

    // Logs stay on the disk; they are read again, with their keys, on next use.
    @Synchronized
    fun close() = books.clear()
}
