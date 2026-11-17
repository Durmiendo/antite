package dev.antite.browser

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Test

class IntentRouteTest {
    @Test
    fun webLinksOpen() {
        assertEquals(IntentRoute.Open("https://example.com/", view = true),
            routeIntent(Intent.ACTION_VIEW, "https://example.com/", "https", needTab = false))
        assertEquals(IntentRoute.Open("http://example.com/", view = false),
            routeIntent(null, "http://example.com/", "HTTP", needTab = true))
    }

    @Test
    fun otherSchemesNeverOpen() {
        for (scheme in listOf("javascript", "file", "content", "intent", "data", null)) {
            val r = routeIntent(Intent.ACTION_VIEW, "$scheme:x", scheme, needTab = true)
            assertEquals(IntentRoute.Unsupported(scheme, needTab = true), r)
        }
    }

    @Test
    fun sharedFilesGoToTheStorage() {
        assertEquals(IntentRoute.Share(needTab = false), routeIntent(Intent.ACTION_SEND, null, null, needTab = false))
        assertEquals(IntentRoute.Share(needTab = true), routeIntent(Intent.ACTION_SEND_MULTIPLE, "javascript:x", "javascript", needTab = true))
    }

    @Test
    fun nothingToOpen() {
        assertEquals(IntentRoute.NewTab, routeIntent(Intent.ACTION_MAIN, null, null, needTab = true))
        assertEquals(IntentRoute.Nothing, routeIntent(Intent.ACTION_MAIN, null, null, needTab = false))
        assertEquals(IntentRoute.Nothing, routeIntent("dev.antite.OTHER", "https://example.com/", "https", needTab = false))
    }
}
