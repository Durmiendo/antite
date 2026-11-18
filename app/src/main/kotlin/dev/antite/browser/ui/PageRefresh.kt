package dev.antite.browser.ui

import android.content.Context
import android.view.MotionEvent
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

    // On the finger down the layout must always note where the touch started: there it never takes the touch, and
    // the engine has not answered for this touch yet (its answer is still the last touch's). If the layout said "can
    // scroll up" on the down, it would drop the whole gesture even after the engine allowed the pull.
    private var down = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        down = ev.actionMasked == MotionEvent.ACTION_DOWN
        try {
            return super.onInterceptTouchEvent(ev)
        } finally {
            down = false
        }
    }

    override fun canChildScrollUp() = !down && !atTop()

    // The page view is created once and is placed into a new wrapper when the screen is rebuilt.
    fun setPage(v: View) {
        (v.parent as? ViewGroup)?.removeView(v)
        addView(v, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
}
