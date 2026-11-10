package dev.antite.browser

import android.animation.ValueAnimator
import android.os.Handler
import android.os.Looper
import android.view.animation.LinearInterpolator
import kotlin.math.abs

// Chrome's browser controls (cc/input/browser_controls_offset_manager.cc): the toolbar follows the scroll; when the
// gesture and its fling are over, a partly shown toolbar finishes showing or hiding, whichever is nearer.
class ToolbarMotion(private val b: Browser) {
    companion object {
        private const val MAX_DURATION_MS = 200L
        private const val SHOW_THRESHOLD = 0.5f
        private const val HIDE_THRESHOLD = 0.5f

        // Gecko reports scroll every frame while a fling runs, so a pause this long means it has stopped.
        private const val IDLE_MS = 120L
    }

    private val main = Handler(Looper.getMainLooper())
    private var touching = false
    private var lastDelta = 0f
    private var animator: ValueAnimator? = null
    private val settle = Runnable { settle() }

    fun onTouch(down: Boolean) {
        if (down == touching) return
        touching = down
        if (down) stop() else schedule()
    }

    fun onScrolled(dy: Float) {
        if (dy != 0f) lastDelta = dy
        stop()
        schedule()
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
            duration = (MAX_DURATION_MS * abs(target - from) / height).toLong()
            interpolator = LinearInterpolator()
            addUpdateListener { b.moveToolbar(it.animatedValue as Float) }
            start()
        }
    }
}
