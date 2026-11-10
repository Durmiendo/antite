package dev.antite.browser.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class Palette(
    val dark: Boolean,
    val toolbar: Color,
    val toolbarIncognito: Color,
    val pill: Color,
    val pillIncognito: Color,
    val page: Color,
    val surface: Color,
    val menu: Color,
    val divider: Color,
    val iconCircle: Color,
    val icon: Color,
    val iconDisabled: Color,
    val text: Color,
    val textSecondary: Color,
    val textDisabled: Color,
    val accent: Color,
    val onAccent: Color,
    val secure: Color,
    val insecure: Color,
    val card: Color,
    val background: Color,
)

val DarkPalette = Palette(
    dark = true,
    toolbar = Color(0xFF131313),
    toolbarIncognito = Color(0xFF1F2023),
    pill = Color(0xFF343635),
    pillIncognito = Color(0xFF3C4043),
    page = Color(0xFF0A0A0A),
    surface = Color(0xFF131313),
    menu = Color(0xFF393939),
    divider = Color(0xFF414141),
    iconCircle = Color(0xFF2C2C2C),
    icon = Color(0xFFC6C6C6),
    iconDisabled = Color(0xFF6E6E6E),
    text = Color(0xFFE5E5E5),
    textSecondary = Color(0xFFA8A8A8),
    textDisabled = Color(0xFF7A7A7A),
    accent = Color(0xFFFCB4B8),
    onAccent = Color(0xFF561D24),
    secure = Color(0xFF81C995),
    insecure = Color(0xFFF28B82),
    card = Color(0xFF2A2A2A),
    background = Color(0xFF131313),
)

val LightPalette = Palette(
    dark = false,
    toolbar = Color(0xFFFFFFFF),
    toolbarIncognito = Color(0xFF3C4043),
    pill = Color(0xFFEDEFF2),
    pillIncognito = Color(0xFF5F6368),
    page = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    menu = Color(0xFFFFFFFF),
    divider = Color(0xFFE3E3E3),
    iconCircle = Color(0xFFEDEFF2),
    icon = Color(0xFF444746),
    iconDisabled = Color(0xFFB0B3B8),
    text = Color(0xFF1F1F1F),
    textSecondary = Color(0xFF5F6368),
    textDisabled = Color(0xFF9AA0A6),
    accent = Color(0xFF9C4049),
    onAccent = Color(0xFFFFFFFF),
    secure = Color(0xFF188038),
    insecure = Color(0xFFD93025),
    card = Color(0xFFF1F3F4),
    background = Color(0xFFF6F7F8),
)

val LocalPalette = staticCompositionLocalOf { DarkPalette }

@Composable
fun AntiteTheme(dark: Boolean, content: @Composable () -> Unit) {
    val p = if (dark) DarkPalette else LightPalette
    val scheme = if (dark) {
        darkColorScheme(
            primary = p.accent, onPrimary = p.onAccent, surface = p.surface, onSurface = p.text,
            surfaceContainerHigh = p.menu, surfaceContainer = p.menu, background = p.background,
            onSurfaceVariant = p.textSecondary, outline = p.divider, secondary = p.accent,
        )
    } else {
        lightColorScheme(
            primary = p.accent, onPrimary = p.onAccent, surface = p.surface, onSurface = p.text,
            surfaceContainerHigh = p.menu, surfaceContainer = p.menu, background = p.background,
            onSurfaceVariant = p.textSecondary, outline = p.divider, secondary = p.accent,
        )
    }
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
