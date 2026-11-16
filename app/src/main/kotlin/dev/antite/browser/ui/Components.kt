package dev.antite.browser.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.antite.browser.R
import dev.antite.browser.l10n.T

@Composable
fun Ico(@DrawableRes res: Int, tint: Color = LocalPalette.current.icon, size: Int = 24, modifier: Modifier = Modifier) {
    Icon(painterResource(res), contentDescription = null, tint = tint, modifier = modifier.size(size.dp))
}

@Composable
fun IconBtn(@DrawableRes res: Int, description: String, enabled: Boolean = true, tint: Color? = null, onClick: () -> Unit) {
    val p = LocalPalette.current
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(painterResource(res), contentDescription = description, tint = tint ?: if (enabled) p.icon else p.iconDisabled)
    }
}

// The rounded search row above a list (library pages, secure storage).
@Composable
fun SearchField(value: String, onChange: (String) -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().height(44.dp)
            .background(p.pill, RoundedCornerShape(22.dp)).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Ico(R.drawable.ic_search, size = 20)
        BasicTextField(value, onChange, singleLine = true, cursorBrush = SolidColor(p.accent),
            textStyle = TextStyle(color = p.text, fontSize = 16.sp),
            modifier = Modifier.weight(1f).padding(start = 10.dp),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(T.librarySearch, color = p.textSecondary, fontSize = 16.sp)
                    inner()
                }
            })
    }
}

@Composable
fun PageScaffold(
    title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}, scrollTo: Int = -1, content: LazyListScope.() -> Unit,
) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxSize().background(p.background)) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(start = 4.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBtn(R.drawable.ic_arrow_back, T.actionBack, onClick = onBack)
            Text(
                title, color = p.text, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 12.dp),
            )
            actions()
        }
        // The page runs under the navigation bar, so the last rows must be able to scroll above it.
        val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val state = rememberLazyListState()
        val reset = LocalPageReset.current
        LaunchedEffect(reset) { state.scrollToItem(0) }
        // A row opened from settings search is brought up with a little of the page above it.
        if (scrollTo >= 0) LaunchedEffect(scrollTo) { state.scrollToItem((scrollTo - 2).coerceAtLeast(0)) }
        LazyColumn(Modifier.fillMaxSize(), state, contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp + navBar)) {
            content()
        }
    }
}

// Changed by a kept page when it closes, so it opens at the top again.
val LocalPageReset = compositionLocalOf { 0 }

fun LazyListScope.section(name: String) {
    (this as? SettingsIndex)?.section = name
    sectionItem(name)
}

private fun LazyListScope.sectionItem(name: String) = item(key = "section:$name") {
    Text(
        name, color = LocalPalette.current.accent, fontSize = 14.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 8.dp),
    )
}

fun LazyListScope.note(text: String) = item {
    Text(
        text, color = LocalPalette.current.textSecondary, fontSize = 13.sp,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingRow(
    title: String,
    summary: String? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    toggled: Boolean? = null,
    info: String? = null,
    badge: String? = null,
) {
    val p = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    val click = if (onClick != null || onLongClick != null) {
        Modifier.combinedClickable(onClick = { onClick?.invoke() }, onLongClick = onLongClick)
    } else Modifier
    // A switch row says whether it is on, for screen readers (and e2e scripts reading the screen).
    val mod = if (toggled == null) click else click.semantics {
        role = Role.Switch
        toggleableState = ToggleableState(toggled)
    }
    Row(
        mod.fillMaxWidth().heightIn(min = 64.dp).padding(start = 20.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = p.text, fontSize = 16.sp)
            if (!summary.isNullOrEmpty()) Text(summary, color = p.textSecondary, fontSize = 14.sp)
            if (badge != null) Badge(badge, Modifier.padding(top = 4.dp))
        }
        if (info != null) IconBtn(R.drawable.ic_info, T.keysInfo, tint = if (open) p.accent else p.icon) { open = !open }
        if (trailing != null) trailing()
    }
    if (info != null && open) InfoCard(info)
}

// A short label in a rounded plate, in place of a sentence of text.
@Composable
fun Badge(text: String, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Text(
        text, color = p.textSecondary, fontSize = 12.sp,
        modifier = modifier.background(p.card, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

// The explanation behind an info button, opened under its row.
@Composable
fun InfoCard(text: String) {
    val p = LocalPalette.current
    Text(
        text, color = p.textSecondary, fontSize = 13.sp, lineHeight = 18.sp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            .background(p.card, RoundedCornerShape(12.dp)).padding(12.dp),
    )
}

@Composable
fun SwitchRow(title: String, summary: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    SettingRow(title, summary, onClick = { onChange(!checked) }, toggled = checked, trailing = { SwitchMark(checked) })
}

// The switch drawn at the end of a row; the row itself takes the taps.
@Composable
fun SwitchMark(checked: Boolean) {
    val p = LocalPalette.current
    Switch(
        checked = checked, onCheckedChange = null,
        colors = SwitchDefaults.colors(
            checkedThumbColor = p.onAccent, checkedTrackColor = p.accent,
            uncheckedThumbColor = p.icon, uncheckedTrackColor = p.card, uncheckedBorderColor = p.divider,
        ),
    )
}

@Composable
fun RadioRow(text: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).combinedClickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected, onClick = null,
            colors = RadioButtonDefaults.colors(selectedColor = p.accent, unselectedColor = p.icon),
            modifier = Modifier.padding(12.dp),
        )
        Text(text, color = p.text, fontSize = 16.sp)
    }
}

@Composable
fun Divider() {
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp).background(LocalPalette.current.divider))
}

@Composable
fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

val NoArrangement = Arrangement.Top
