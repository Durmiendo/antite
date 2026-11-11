package dev.antite.browser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import dev.antite.browser.Browser
import dev.antite.browser.DialogButton
import dev.antite.browser.FormField
import dev.antite.browser.UiDialog
import dev.antite.browser.data.Categories
import dev.antite.browser.data.TabCategory
import dev.antite.browser.l10n.T
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun categoriesPage(b: Browser) = SettingsPage(T.categoriesMine) { nav ->
    b.categories.list.forEach { c ->
        mark(c.name, null, "category:${c.id}")
        item(key = "category:${c.id}") {
            Marked("category:${c.id}") {
                SettingRow(c.name, T.categoryTabs(count = b.tabs.count { !it.incognito && it.category == c.id }),
                    onClick = { nav.push(c.name) { n -> CategoryPage(b, n, c) } }, leading = { CategoryBadge(b, c) })
            }
        }
    }
    action(T.categoriesAdd, null, "row:addCategory") {
        b.show(UiDialog.Form(T.categoriesAdd, null, listOf(FormField(T.categoryName, "", FormField.Kind.NAME)), onConfirm = { v ->
            val name = v[0].trim()
            if (name.isEmpty()) return@Form false
            val c = b.categories.add(name)
            nav.push(c.name) { n -> CategoryPage(b, n, c) }
            true
        }))
    }
    note(T.categoriesNote)
}

internal fun incognitoPage(b: Browser) = SettingsPage(T.tabsIncognito) {
    toggle(b, T.incognitoSecure, T.incognitoSecureSummary, "incognitoSecure", true, false)
    toggle(b, T.tabsCloseIncognito, null, "closeIncognito", false, false)
    toggle(b, T.incognitoLock, T.incognitoLockSummary, "incognitoLock", false, false)
}

// A round badge in the category's color with its picture, or its first letter.
@Composable
internal fun CategoryBadge(b: Browser, c: TabCategory, size: Int = 32) {
    val color = Color(c.color)
    Box(Modifier.size(size.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        if (b.categories.iconFile(c) != null) CategoryIcon(b, c, size * 3 / 5)
        else Text(c.name.take(1).uppercase(), color = if (color.luminance() > 0.5f) Color(0xDE000000) else Color.White,
            fontSize = (size / 2).sp, fontWeight = FontWeight.Bold)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CategoryPage(b: Browser, nav: Nav, c: TabCategory) {
    val p = LocalPalette.current
    fun pickIcon() = b.activity.openDocument(listOf("image/svg+xml", "image/png", "image/jpeg", "image/webp")) { uri ->
        if (uri == null) return@openDocument
        b.activity.lifecycleScope.launch {
            val name = withContext(Dispatchers.IO) { b.categories.importIcon(c, uri) }
            if (name == null) b.toast(T.categoryIconBad) else b.categories.setIcon(c, name)
        }
    }
    Screen(b, nav, c.name) {
        item(key = "badge") {
            Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) { CategoryBadge(b, c, 72) }
        }
        item(key = "name") {
            SettingRow(T.categoryName, c.name, onClick = {
                b.show(UiDialog.Form(T.categoryName, null, listOf(FormField(T.categoryName, c.name, FormField.Kind.NAME)), onConfirm = { v ->
                    val name = v[0].trim()
                    if (name.isEmpty()) return@Form false
                    c.name = name
                    b.categories.save()
                    true
                }))
            })
        }
        section(T.categoryColor)
        item(key = "colors") {
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Categories.COLORS.forEach { col ->
                    Box(Modifier.size(40.dp).clip(CircleShape).background(Color(col))
                        .then(if (col == c.color) Modifier.border(3.dp, p.text, CircleShape) else Modifier)
                        .clickable {
                            c.color = col
                            b.categories.save()
                        })
                }
            }
        }
        section(T.categoryIcon)
        item(key = "icon") {
            SettingRow(if (c.icon == null) T.categoryIconPick else T.categoryIconReplace, T.categoryIconFormats, onClick = ::pickIcon)
        }
        if (c.icon != null) item(key = "iconRemove") { SettingRow(T.categoryIconRemove, onClick = { b.categories.removeIcon(c) }) }
        section(T.categoryPlace)
        item(key = "left") { SettingRow(T.ntpMoveLeft, onClick = { b.categories.move(c, -1) }) }
        item(key = "right") { SettingRow(T.ntpMoveRight, onClick = { b.categories.move(c, 1) }) }
        item(key = "delete") {
            SettingRow(T.categoryDelete, onClick = {
                b.show(UiDialog.Message(T.categoryDelete, T.categoryDeleteConfirm(name = c.name), buttons = listOf(
                    DialogButton(T.actionCancel) {},
                    DialogButton(T.actionDelete) {
                        b.deleteCategory(c)
                        nav.back()
                    },
                )))
            })
        }
    }
}
