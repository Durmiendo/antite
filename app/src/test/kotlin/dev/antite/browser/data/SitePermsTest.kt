package dev.antite.browser.data

import dev.antite.browser.testing.MapPrefs
import dev.antite.engine.PermissionDefault
import dev.antite.engine.PermissionEntry
import dev.antite.engine.PermissionStore
import dev.antite.engine.PermissionValue
import dev.antite.engine.SitePermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SitePermsTest {
    private class Store : PermissionStore {
        val set = ArrayList<Triple<String, SitePermission, PermissionValue>>()
        override fun list(done: (List<PermissionEntry>) -> Unit) = done(emptyList())
        override fun set(origin: String, permission: SitePermission, value: PermissionValue) {
            set += Triple(origin, permission, value)
        }
        override fun remove(entry: PermissionEntry) {}
    }

    private val store = Store()
    private val prefs = Prefs(MapPrefs()) { false }
    private val perms = SitePerms(MapPrefs(), prefs, store)

    @Test
    fun aSiteExceptionWinsOverTheDefault() {
        assertEquals(SitePerms.ASK, perms.decide(SitePerms.LOCATION, "a.example"))
        perms.setException(SitePerms.LOCATION, "a.example", SitePerms.BLOCK)
        assertEquals(SitePerms.BLOCK, perms.decide(SitePerms.LOCATION, "a.example"))
        assertEquals(SitePerms.ASK, perms.decide(SitePerms.LOCATION, "b.example"))
        assertEquals(SitePerms.ASK, perms.decide(SitePerms.LOCATION, null))
        perms.setException(SitePerms.LOCATION, "a.example", -1)
        assertEquals(SitePerms.ASK, perms.decide(SitePerms.LOCATION, "a.example"))
    }

    @Test
    fun anAnswerIsMirroredIntoTheEngineForBothSchemes() {
        perms.setException(SitePerms.LOCATION, "a.example", SitePerms.ALLOW)
        assertEquals(listOf(
            Triple("https://a.example", SitePermission.LOCATION, PermissionValue.ALLOW),
            Triple("http://a.example", SitePermission.LOCATION, PermissionValue.ALLOW),
        ), store.set)
    }

    @Test
    fun ourOwnKindsStayOutOfTheEngine() {
        perms.setException(SitePerms.JS, "a.example", SitePerms.BLOCK)
        perms.setException(SitePerms.AUTOPLAY, "a.example", SitePerms.BLOCK)
        perms.setException(SitePerms.LOCATION, "", SitePerms.BLOCK)
        assertTrue(store.set.isEmpty())
        assertEquals(SitePerms.BLOCK, perms.decide(SitePerms.JS, "a.example"))
    }

    @Test
    fun defaultsGoToTheEngineByKind() {
        perms.setDefaultIndex(SitePerms.LNA, 1)
        val d = perms.engineDefaults()
        assertEquals(PermissionDefault.BLOCK, d[SitePermission.LOCAL_NETWORK])
        assertEquals(PermissionDefault.BLOCK, d[SitePermission.LOCAL_DEVICE])
        assertEquals(PermissionDefault.ASK, d[SitePermission.LOCATION])
        assertFalse(SitePermission.AUTOPLAY_AUDIBLE in d)
    }

    @Test
    fun autoplay() {
        assertTrue(perms.autoplayAllowed("a.example", audible = false))
        assertFalse("muted only by default", perms.autoplayAllowed("a.example", audible = true))
        perms.setException(SitePerms.AUTOPLAY, "a.example", SitePerms.ALLOW)
        assertTrue(perms.autoplayAllowed("a.example", audible = true))
        perms.setException(SitePerms.AUTOPLAY, "b.example", SitePerms.BLOCK)
        assertFalse(perms.autoplayAllowed("b.example", audible = false))
    }

    @Test
    fun exceptionsAndHostsAreListed() {
        perms.setException(SitePerms.CAMERA, "b.example", SitePerms.ALLOW)
        perms.setException(SitePerms.CAMERA, "a.example", SitePerms.BLOCK)
        perms.setException(SitePerms.MIC, "c.example", SitePerms.BLOCK)
        assertEquals(listOf("a.example", "b.example"), perms.exceptions(SitePerms.CAMERA).keys.toList())
        assertEquals(listOf("a.example", "b.example", "c.example"), perms.hosts())
    }
}
