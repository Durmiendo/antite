package dev.antite.browser.data

import java.net.URI

// The history of one category in memory, built from the events of its log, answering what the interface asks: the
// history list, the address bar, the frequent sites, the visited links.
class HistoryIndex(private val now: () -> Long = System::currentTimeMillis) {
    companion object {
        const val KEEP_MS = 90L * 24 * 3600 * 1000
        const val FREQUENT_MS = 30L * 24 * 3600 * 1000
    }

    sealed interface Event {
        data class Visit(val url: String, val title: String?, val at: Long) : Event
        data class Title(val url: String, val title: String) : Event
        data class Delete(val url: String) : Event
        // One page as a whole: what a compacted log holds.
        data class Page(val url: String, val title: String?, val visits: Int, val last: Long) : Event
    }

    private class Page(val id: Long, val url: String, var title: String?, var visits: Int, var last: Long)

    private val pages = LinkedHashMap<String, Page>()
    private var nextId = 1L

    // Events applied over the pages they describe: once there are many more, the log is worth writing again.
    var events = 0
        private set

    val size get() = pages.size

    val worthCompacting get() = events > 2 * pages.size + 200

    fun apply(e: Event) {
        events++
        when (e) {
            is Event.Visit -> {
                val p = pages[e.url]
                if (p == null) pages[e.url] = Page(nextId++, e.url, e.title?.ifEmpty { null }, 1, e.at)
                else {
                    p.visits++
                    p.last = maxOf(p.last, e.at)
                    if (!e.title.isNullOrEmpty()) p.title = e.title
                }
            }
            is Event.Title -> if (e.title.isNotEmpty()) pages[e.url]?.title = e.title
            is Event.Delete -> pages.remove(e.url)
            is Event.Page -> pages[e.url] = Page(nextId++, e.url, e.title, e.visits, e.last)
        }
    }

    private fun live() = (now() - KEEP_MS).let { cutoff -> pages.values.filter { it.last >= cutoff } }

    fun has(url: String) = pages.containsKey(url)

    fun urlOf(id: Long): String? = pages.values.firstOrNull { it.id == id }?.url

    fun visited(urls: List<String>) = BooleanArray(urls.size) { pages.containsKey(urls[it]) }

    // Newest first; the filter looks in addresses and titles.
    fun list(filter: String?, limit: Int): List<Entry> {
        val f = filter?.trim()?.lowercase().orEmpty()
        return live().asSequence()
            .filter { f.isEmpty() || f in it.url.lowercase() || f in (it.title?.lowercase() ?: "") }
            .sortedByDescending { it.last }.take(limit).map { Entry(it.id, it.url, it.title, it.last, null) }.toList()
    }

    // Pages whose host starts with the text first, then by visits and recency.
    fun suggest(text: String, limit: Int): List<Entry> {
        val t = text.trim().lowercase()
        if (t.isEmpty()) return emptyList()
        return live().asSequence()
            .filter { t in it.url.lowercase() || t in (it.title?.lowercase() ?: "") }
            .sortedWith(compareByDescending<Page> { "://$t" in it.url.lowercase() }.thenByDescending { it.visits }.thenByDescending { it.last })
            .take(limit).map { Entry(it.id, it.url, it.title, it.last, "h") }.toList()
    }

    // Sites by visits over the last 30 days, one per host, opened at their root.
    fun frequent(skipHosts: Set<String>, limit: Int): List<Site> {
        val cutoff = now() - FREQUENT_MS
        val score = LinkedHashMap<String, Int>()
        val titles = HashMap<String, String>()
        val roots = HashMap<String, String>()
        for (p in pages.values.filter { it.last > cutoff }.sortedByDescending { it.visits }) {
            val u = runCatching { URI(p.url) }.getOrNull() ?: continue
            val host = u.host ?: continue
            val h = host.removePrefix("www.")
            if (h in skipHosts) continue
            if (h !in score) roots[h] = "${u.scheme}://$host/"
            score[h] = (score[h] ?: 0) + p.visits
            if (h !in titles) p.title?.let { titles[h] = it }
        }
        return score.entries.sortedByDescending { it.value }.take(limit).map { Site(it.key, roots[it.key]!!, titles[it.key]) }
    }

    // What a compacted log holds: one event per page still kept.
    fun snapshot(): List<Event> = live().map { Event.Page(it.url, it.title, it.visits, it.last) }

    fun compacted() {
        val keep = live().map { it.url }.toSet()
        pages.keys.retainAll(keep)
        events = pages.size
    }
}
