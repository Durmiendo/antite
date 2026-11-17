package dev.antite.browser.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TabStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val warnings = ArrayList<String>()
    private fun store() = TabStore(tmp.root) { m, _ -> warnings += m }

    @Test
    fun tabsComeBackAsTheyWere() {
        val a = Tab.create(false).apply {
            url = "https://a.example/"; title = "A"; state = "{s}"; desktop = true; category = "work"; container = "c1"; lastUsed = 5
        }
        val b = Tab.create(false).apply { url = "https://b.example/" }
        val secret = Tab.create(true).apply { url = "https://private.example/" }
        store().apply { save(listOf(a, secret, b), b); flush() }
        val loaded = store().load()
        assertEquals(listOf(a.id, b.id), loaded.tabs.map { it.id })
        assertEquals(b.id, loaded.selected)
        val back = loaded.tabs[0]
        assertEquals(listOf("https://a.example/", "A", "{s}", true, "work", "c1", 5L),
            listOf(back.url, back.title, back.state, back.desktop, back.category, back.container, back.lastUsed))
        assertEquals(null, loaded.tabs[1].category)
    }

    @Test
    fun anIncognitoTabIsNeverTheSelectedOneOnDisk() {
        val n = Tab.create(false)
        val i = Tab.create(true)
        store().apply { save(listOf(n, i), i); flush() }
        assertEquals(0L, store().load().selected)
    }

    // A file that cannot be read is kept aside: starting fresh must not overwrite the only copy of the user's tabs.
    @Test
    fun anUnreadableFileIsKeptAside() {
        val broken = "{\"tabs\": [{\"id\": 1, \"url\": \"https://a.exa"
        File(tmp.root, "tabs.json").writeText(broken)
        val s = store()
        assertTrue(s.load().tabs.isEmpty())
        assertEquals(1, warnings.size)
        s.save(listOf(Tab.create(false)), null)
        s.flush()
        assertEquals(broken, File(tmp.root, "tabs.json.bad").readText())
    }
}
