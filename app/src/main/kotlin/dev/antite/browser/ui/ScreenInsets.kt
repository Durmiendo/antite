package dev.antite.browser.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationSource
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset

// The window insets of the browser screen, read through derived states: an inset animation (the navigation bar
// hiding with the toolbar, the keyboard) changes what the screen shows only when one of these changes, not on every
// frame. Insets that must follow every frame are taken in layout (bottomInset, insetHeight).
@Stable
class ScreenInsets @OptIn(ExperimentalLayoutApi::class) constructor(
    val navBars: WindowInsets, val ime: WindowInsets, imeTarget: WindowInsets, imeSource: WindowInsets,
    navFull: WindowInsets, density: Density,
) {
    val navShown by derivedStateOf { navBars.getBottom(density) > 0 }
    val imeUp by derivedStateOf { ime.getBottom(density) > 0 }

    // The keyboard counts from where its animation ends when showing, from where it starts when hiding: the page is
    // resized once per keyboard transition, not on every animation frame, as each resize makes Gecko lay the page out
    // again and the page jerks.
    val imeSettled by derivedStateOf {
        val now = ime.getBottom(density)
        val target = imeTarget.getBottom(density)
        if (now == target) now else minOf(imeSource.getBottom(density), target)
    }
    val navFull by derivedStateOf { navFull.getBottom(density) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun rememberScreenInsets(): ScreenInsets {
    val density = LocalDensity.current
    val navBars = WindowInsets.navigationBars
    val ime = WindowInsets.ime
    val target = WindowInsets.imeAnimationTarget
    val source = WindowInsets.imeAnimationSource
    val navFull = WindowInsets.navigationBarsIgnoringVisibility
    return remember(density, navBars, ime, target, source, navFull) { ScreenInsets(navBars, ime, target, source, navFull, density) }
}

// Bottom padding of the current inset, taken in layout.
fun Modifier.bottomInset(insets: WindowInsets, extra: Dp = 0.dp) = layout { m, c ->
    val pad = insets.getBottom(this) + extra.roundToPx()
    val placeable = m.measure(c.offset(vertical = -pad))
    layout(placeable.width, placeable.height + pad) { placeable.place(0, 0) }
}

// As tall as the current bottom inset, taken in layout.
fun Modifier.insetHeight(insets: WindowInsets) = layout { m, c ->
    val height = insets.getBottom(this)
    val placeable = m.measure(Constraints.fixed(c.maxWidth, height))
    layout(placeable.width, height) { placeable.place(0, 0) }
}
