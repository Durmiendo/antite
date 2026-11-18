package dev.antite.browser.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import dev.antite.engine.EngineView

// A page under a full-screen page of the browser or under a lock stays out of the accessibility tree: TalkBack must
// not reach a site's content under the secure storage or a locked tab. With the keyboard up Gecko's tree showed
// through the storage (tools/e2e/a11y_covered.py).
@Composable
fun HideCoveredPage(page: EngineView, hidden: Boolean) {
    SideEffect { page.setAccessibilityHidden(hidden) }
}
