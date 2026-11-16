package dev.antite.browser.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

// Pull down to refresh, like Chrome. SwipeRefreshLayout intercepts the drag from GeckoView once nothing under the
// finger can scroll up (atTop) and shows the circle; the intercepted gesture reaches the page as a cancel, so nothing scrolls under it.
class PageRefreshLayout(
    context: Context,
    private val atTop: () -> Boolean,
    onRefresh: () -> Unit,
) : SwipeRefreshLayout(context) {
    init {
        setOnRefreshListener(onRefresh)
    }

    override fun canChildScrollUp() = !atTop()

    // The page view is created once and is placed into a new wrapper when the screen is rebuilt.
    fun setPage(v: View) {
        (v.parent as? ViewGroup)?.removeView(v)
        addView(v, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
}
