package dev.antite.browser

import android.animation.ValueAnimator
import android.os.Handler
import android.os.Looper
import android.view.animation.LinearInterpolator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.antite.browser.ui.Motion
import kotlin.math.abs

// Chrome's browser controls (cc/input/browser_controls_offset_manager.cc): the toolbar follows the scroll; when the
// gesture and its fling are over, a partly shown toolbar finishes showing or hiding, whichever is nearer.
// Under the finger the toolbar follows the finger itself, not the page scroll: the page view moves with the toolbar,
// so a toolbar moved by the scroll takes only half of the finger's travel.
class ToolbarMotion(private val b: Browser) {
    companion object {
        private const val SHOW_THRESHOLD = 0.5f
        private const val HIDE_THRESHOLD = 0.5f

        // Gecko reports scroll every frame while a fling runs, so a pause this long means it has stopped.
        private const val IDLE_MS = 120L
    }

    private val main = Handler(Looper.getMainLooper())

    // Observable, so the pull to refresh circle can wait for the finger to leave the screen.
    var touching by mutableStateOf(false)
        private set
    private var lastDelta = 0f
    private var animator: ValueAnimator? = null
    private val settle = Runnable { settle() }

    // The finger's position in the window while one finger is down, and whether this touch moves the toolbar.
    private var fingerY: Float? = null
    private var following = false

    fun onTouch(down: Boolean) {
        if (down == touching) return
        touching = down
        if (down) stop() else {
            following = false
            schedule()
        }
    }

    // Every pointer event over the window; y is null when no finger or more than one is down.
    fun onFinger(y: Float?) {
        val last = fingerY
        fingerY = y
        if (y == null) {
            following = false
            return
        }
        if (!following || !touching || last == null || y == last) return
        lastDelta = last - y
        b.moveToolbar(b.hidden + (last - y))
    }

    // False when the scroll must not move the toolbar: the finger does. A touch starts moving the toolbar once it
    // scrolls the page itself, so a swipe over a carousel or an inner list leaves the toolbar alone.
    fun onScrolled(dy: Float): Boolean {
        if (dy != 0f) lastDelta = dy
        stop()
        schedule()
        if (!touching || fingerY == null) return true
        if (dy != 0f) following = true
        return false
    }

    fun show() = animateTo(0f)

    fun stop() {
        animator?.cancel()
        animator = null
    }

    private fun schedule() {
        main.removeCallbacks(settle)
        if (!touching) main.postDelayed(settle, IDLE_MS)
    }

    private fun settle() {
        val height = b.toolbarHeightPx
        if (height <= 0f || touching) return
        val shown = 1f - b.hidden / height
        if (shown <= 0f || shown >= 1f) return
        val show = when {
            shown >= 1f - HIDE_THRESHOLD -> true
            shown <= SHOW_THRESHOLD -> false
            else -> lastDelta < 0
        }
        animateTo(if (show) 0f else height)
    }

    private fun animateTo(target: Float) {
        main.removeCallbacks(settle)
        stop()
        val height = b.toolbarHeightPx
        val from = b.hidden
        if (height <= 0f || from == target) return b.moveToolbar(target)
        animator = ValueAnimator.ofFloat(from, target).apply {
            duration = (Motion.ms(Motion.TOOLBAR_SETTLE) * abs(target - from) / height).toLong()
            interpolator = LinearInterpolator()
            addUpdateListener { b.moveToolbar(it.animatedValue as Float) }
            start()
        }
    }
}

// The navigation bar hides with the fully hidden toolbar and comes back only after the toolbar has come out by deadZone,
// so a small scroll up does not pop it up; in between it stays as it was.
fun navBarHidden(toolbarHidden: Float, toolbarHeight: Float, deadZone: Float, wasHidden: Boolean, hideBar: Boolean): Boolean = when {
    toolbarHidden >= toolbarHeight -> hideBar
    toolbarHidden <= toolbarHeight - deadZone -> false
    else -> wasHidden
}
