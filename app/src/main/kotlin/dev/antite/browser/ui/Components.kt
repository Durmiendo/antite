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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
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
fun IconBtn(@DrawableRes res: Int, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    val p = LocalPalette.current
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(painterResource(res), contentDescription = description, tint = if (enabled) p.icon else p.iconDisabled)
    }
}

@Composable
fun PageScaffold(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}, content: LazyListScope.() -> Unit) {
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
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp + navBar)) {
            content()
        }
    }
}

fun LazyListScope.section(name: String) = item(key = "section:$name") {
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
) {
    val p = LocalPalette.current
    val mod = if (onClick != null || onLongClick != null) {
        Modifier.combinedClickable(onClick = { onClick?.invoke() }, onLongClick = onLongClick)
    } else Modifier
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
        }
        if (trailing != null) trailing()
    }
}

@Composable
fun SwitchRow(title: String, summary: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    SettingRow(title, summary, onClick = { onChange(!checked) }, trailing = {
        Switch(
            checked = checked, onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = p.onAccent, checkedTrackColor = p.accent,
                uncheckedThumbColor = p.icon, uncheckedTrackColor = p.card, uncheckedBorderColor = p.divider,
            ),
        )
    })
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
