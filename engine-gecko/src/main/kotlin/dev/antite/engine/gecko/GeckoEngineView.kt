package dev.antite.engine.gecko

import android.content.Context
import android.graphics.Bitmap
import android.view.MotionEvent
import android.view.View
import dev.antite.engine.EngineTab
import dev.antite.engine.EngineView
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.PanZoomController

// Asks Gecko on each finger down who takes the touch and where the content under it can still scroll, as Fenix does
// for pull to refresh; the answer comes asynchronously, before the first moves of the gesture.
internal class TouchGeckoView(context: Context) : GeckoView(context) {
    @Volatile
    var pullAllowed: Boolean? = null
        private set

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_DOWN) return super.onTouchEvent(event)
        pullAllowed = null
        onTouchEventForDetailResult(event).accept({ r ->
            if (r == null) return@accept
            val byPage = r.handledResult() == PanZoomController.INPUT_RESULT_HANDLED_CONTENT ||
                r.handledResult() == PanZoomController.INPUT_RESULT_IGNORED
            val canScrollUp = r.scrollableDirections() and PanZoomController.SCROLLABLE_FLAG_TOP != 0
            val canOverscroll = r.overscrollDirections() and PanZoomController.OVERSCROLL_FLAG_VERTICAL != 0
            pullAllowed = !byPage && !canScrollUp && canOverscroll
        }, { pullAllowed = false })
        return true
    }
}

internal class GeckoEngineView(private val gecko: TouchGeckoView) : EngineView {
    private var cover: Int? = null

    override val view: View get() = gecko

    override fun show(tab: EngineTab?) {
        val session = (tab as GeckoTab?)?.session
        if (gecko.session === session) return
        cover?.let(gecko::coverUntilFirstPaint)
        gecko.releaseSession()
        if (session != null) gecko.setSession(session)
    }

    override fun setCover(argb: Int) {
        cover = argb
    }

    override fun capture(done: (Bitmap?) -> Unit) {
        try {
            gecko.capturePixels().accept({ done(it) }, { done(null) })
        } catch (e: Exception) {
            done(null)
        }
    }

    override fun setToolbarMaxHeight(px: Int) = gecko.setDynamicToolbarMaxHeight(px)

    override fun setVerticalClipping(px: Int) = gecko.setVerticalClipping(px)

    override fun canPullToRefresh(): Boolean? = gecko.pullAllowed
}
