package dev.antite.browser.ui

import android.graphics.Path
import android.view.animation.PathInterpolator
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import kotlin.math.roundToInt

// Chrome's motion: durations and curves from Chromium (ui/android/java/res/anim, Interpolators.java,
// HubAnimationConstants.java, shared_x_axis_*.xml), scaled by the animation speed setting.
object Motion {
    const val PREF = "animSpeed"

    // Speeds offered in settings; 0 turns animations off.
    val speeds = listOf(0f, 0.5f, 1f, 1.5f, 2f)
    const val DEFAULT_SPEED = 2

    private val speed = mutableFloatStateOf(1f)

    fun setSpeed(index: Int) {
        speed.floatValue = speeds[index.coerceIn(0, speeds.lastIndex)]
    }

    // Styles offered in settings: Chrome's motion; full-screen pages, settings pages and the menu at once, as Antite was
    // before Chrome's motion; Chrome's motion with full-screen pages shown through a window that grows from the top left
    // corner, as the build of 10.11.2026 did by accident (AnimatedContent's default SizeTransform from an empty overlay).
    const val STYLE_PREF = "animStyle"
    const val CHROME = 0
    const val INSTANT = 1
    const val WINDOW = 2

    private val style = mutableIntStateOf(CHROME)

    fun setStyle(index: Int) {
        style.intValue = index.coerceIn(CHROME, WINDOW)
    }

    val chrome: Boolean get() = style.intValue != INSTANT
    val window: Boolean get() = style.intValue == WINDOW

    val off: Boolean get() = speed.floatValue <= 0f

    fun ms(base: Int): Int = speed.floatValue.let { if (it <= 0f) 0 else (base / it).roundToInt() }

    fun <T> tween(base: Int, easing: Easing, delay: Int = 0): FiniteAnimationSpec<T> =
        androidx.compose.animation.core.tween(ms(base), ms(delay), easing)

    // Full-screen pages and settings pages move only in Chrome's styles.
    fun pageMs(base: Int): Int = if (chrome) ms(base) else 0

    // How long a full-screen page takes to go away, the window included.
    fun closeMs(): Int = if (window) maxOf(pageMs(SHARED_AXIS), ms(WINDOW_SETTLE)) else pageMs(SHARED_AXIS)

    // AnimatedContent's default SizeTransform: spring(StiffnessMediumLow); a spring's time goes as 1/sqrt(stiffness), so
    // the speed setting scales the stiffness by its square.
    fun <T> windowSpec(): FiniteAnimationSpec<T> = speed.floatValue.let {
        if (it <= 0f || !window) snap() else spring(stiffness = Spring.StiffnessMediumLow * it * it, visibilityThreshold = null)
    }

    fun <T> pageTween(base: Int, easing: Easing, delay: Int = 0): FiniteAnimationSpec<T> =
        if (chrome) tween(base, easing, delay) else snap()

    // ui/android/java/res/anim/emphasized.xml, also Material 3 emphasized.
    val Emphasized: Easing = PathInterpolator(Path().apply {
        moveTo(0f, 0f)
        cubicTo(0.05f, 0f, 0.133333f, 0.06f, 0.166666f, 0.4f)
        cubicTo(0.208333f, 0.82f, 0.25f, 1f, 1f, 1f)
    }).let { Easing { x -> it.getInterpolation(x) } }
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val StandardDecelerate = CubicBezierEasing(0f, 0f, 0f, 1f)
    val StandardAccelerate = CubicBezierEasing(0.3f, 0f, 1f, 1f)
    val LinearOutSlowIn = CubicBezierEasing(0f, 0f, 0.2f, 1f)
    val FastOutLinearIn = CubicBezierEasing(0.4f, 0f, 1f, 1f)
    // Chrome settings pages and activities (shared_x_axis_*.xml): the new page comes 25% from the side while the old one
    // fades out first and leaves the other way.
    fun sharedAxisX(forward: Boolean): ContentTransform {
        if (!chrome) return EnterTransition.None togetherWith ExitTransition.None
        val sign = if (forward) 1 else -1
        return (slideInHorizontally(tween(SHARED_AXIS, Emphasized)) { sign * it / 4 } +
            fadeIn(tween(SHARED_AXIS_FADE_IN, StandardDecelerate, SHARED_AXIS_FADE_OUT))) togetherWith
            (slideOutHorizontally(tween(SHARED_AXIS, Emphasized)) { -sign * it / 4 } +
                fadeOut(tween(SHARED_AXIS_FADE_OUT, StandardAccelerate)))
    }

    const val MENU_ENTER = 250
    const val MENU_EXIT = 150
    const val DIALOG = 200
    const val SHARED_AXIS = 366
    const val SHARED_AXIS_FADE_IN = 283
    const val SHARED_AXIS_FADE_OUT = 83
    const val HUB_FADE = 325
    const val TOOLBAR_SETTLE = 200
    // Roughly when the window spring settles at the normal speed.
    const val WINDOW_SETTLE = 600
}
