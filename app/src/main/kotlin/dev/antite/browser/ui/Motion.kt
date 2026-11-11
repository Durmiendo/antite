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
import androidx.compose.animation.core.snap
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TransformOrigin
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
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
    // before Chrome's motion; the same with the menu revealed like the first Java build's PopupWindow.
    const val STYLE_PREF = "animStyle"
    const val CHROME = 0
    const val INSTANT = 1
    const val REVEAL_MENU = 2

    private val style = mutableIntStateOf(CHROME)

    fun setStyle(index: Int) {
        style.intValue = index.coerceIn(CHROME, REVEAL_MENU)
    }

    val chrome: Boolean get() = style.intValue == CHROME
    val revealMenu: Boolean get() = style.intValue == REVEAL_MENU

    val off: Boolean get() = speed.floatValue <= 0f

    fun ms(base: Int): Int = speed.floatValue.let { if (it <= 0f) 0 else (base / it).roundToInt() }

    fun <T> tween(base: Int, easing: Easing, delay: Int = 0): FiniteAnimationSpec<T> =
        androidx.compose.animation.core.tween(ms(base), ms(delay), easing)

    // Full-screen pages and settings pages move only in Chrome's style.
    fun pageMs(base: Int): Int = if (chrome) ms(base) else 0

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
    private val AccelerateDecelerate = Easing { x -> (cos((x + 1) * PI) / 2 + 0.5).toFloat() }
    private val DecelerateCubic = Easing { x -> 1f - (1f - x).pow(3) }
    private val DecelerateQuint = Easing { x -> 1f - (1f - x).pow(5) }

    private fun part(ms: Float, of: Int) = (ms / of).coerceIn(0f, 1f)

    // Android's PopupWindow with Animation.Dialog, as the first build showed its menu. The transition
    // (popup_window_enter.xml) reveals the still menu through a rectangle growing from its center in 250 ms while it
    // fades in from 0.1 in 100 ms; the window animation (dialog_enter.xml) zooms it from 0.9 in 220 ms and fades it in
    // 150 ms. Closing fades it in 300 ms (popup_window_exit.xml) and zooms and fades the window away (dialog_exit.xml).
    // [shown] runs linearly over POPUP_ENTER when opening and over DIALOG_WINDOW when closing.
    fun popupReveal(layer: GraphicsLayerScope, opening: Boolean, shown: Float) = with(layer) {
        val scale: Float
        if (opening) {
            val t = shown * POPUP_ENTER
            val reveal = AccelerateDecelerate.transform(part(t, POPUP_ENTER))
            scale = 0.9f + 0.1f * DecelerateQuint.transform(part(t, DIALOG_WINDOW))
            alpha = (0.1f + 0.9f * part(t, POPUP_FADE_IN)) * DecelerateCubic.transform(part(t, DIALOG_WINDOW_FADE))
            clip = reveal < 1f
            shape = GenericShape { size, _ ->
                val w = size.width * reveal
                val h = size.height * reveal
                addRect(Rect((size.width - w) / 2, (size.height - h) / 2, (size.width + w) / 2, (size.height + h) / 2))
            }
        } else {
            val t = (1f - shown) * DIALOG_WINDOW
            scale = 1f - 0.1f * DecelerateQuint.transform(part(t, DIALOG_WINDOW))
            alpha = (1f - DecelerateCubic.transform(part(t, DIALOG_WINDOW_FADE))) *
                (1f - AccelerateDecelerate.transform(part(t, POPUP_EXIT)))
            clip = false
        }
        scaleX = scale
        scaleY = scale
        transformOrigin = TransformOrigin.Center
    }

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
    const val POPUP_ENTER = 250
    const val POPUP_FADE_IN = 100
    const val POPUP_EXIT = 300
    const val DIALOG_WINDOW = 220
    const val DIALOG_WINDOW_FADE = 150
    const val SHARED_AXIS = 366
    const val SHARED_AXIS_FADE_IN = 283
    const val SHARED_AXIS_FADE_OUT = 83
    const val HUB_FADE = 325
    const val TOOLBAR_SETTLE = 200
}
