package dev.antite.browser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.antite.browser.Browser
import dev.antite.browser.Overlay
import dev.antite.browser.R
import dev.antite.browser.Urls
import kotlinx.coroutines.delay

@Composable
fun BrowserScreen(b: Browser) {
    val p = LocalPalette.current
    val density = LocalDensity.current
    val toolbarPx = with(density) { 56.dp.toPx() }
    b.toolbarHeightPx = toolbarPx
    val t = b.current
    val bar = if (t?.incognito == true) p.toolbarIncognito else p.toolbar
    val navInset = WindowInsets.navigationBars.getBottom(density)
    val imeInset = WindowInsets.ime.getBottom(density)

    Box(
        Modifier.fillMaxSize().background(bar)
            .then(if (b.fullscreen) Modifier else Modifier.windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top)))
            .windowInsetsPadding(WindowInsets.ime.only(WindowInsetsSides.Bottom)),
    ) {
        val view = b.engineView
        if (view != null) {
            val bottomGap = if (b.fullscreen || imeInset > 0) 0 else navInset
            AndroidView(
                factory = { view.view },
                modifier = Modifier.fillMaxSize()
                    .padding(bottom = with(density) { bottomGap.toDp() })
                    .graphicsLayer {
                        translationY = if (b.fullscreen) 0f else toolbarPx - b.hidden
                    },
            )
            SideEffect {
                view.setToolbarMaxHeight(toolbarPx.toInt())
                view.setVerticalClipping(if (b.fullscreen) 0 else -b.hidden.toInt())
            }
        }
        if (t != null && Urls.isBlank(t.url) && b.overlay == Overlay.NONE) {
            NewTabPage(b, Modifier.padding(top = 56.dp).fillMaxSize().background(p.background))
        }
        if (!b.fullscreen) {
            Box(Modifier.fillMaxWidth().clipToBounds()) {
                Toolbar(b, Modifier.offset { IntOffset(0, -b.hidden.toInt()) })
            }
            if (b.urlFocused) Suggestions(b, Modifier.padding(top = 56.dp).fillMaxSize().background(bar))
            if (b.findVisible) FindBar(b)
        }
        b.translateOffer?.let { TranslateBar(b, it, Modifier.align(Alignment.BottomCenter).padding(bottom = with(density) { navInset.toDp() })) }
        when (b.overlay) {
            Overlay.SWITCHER -> TabSwitcher(b)
            Overlay.SETTINGS -> SettingsScreen(b)
            Overlay.LIBRARY -> LibraryScreen(b)
            Overlay.NONE -> {}
        }
        if (b.menuOpen) BrowserMenu(b)
        b.extensionPopup?.let { ExtensionPopup(b, it) }
        DialogHost(b)
        Messages(b, Modifier.align(Alignment.BottomCenter).padding(bottom = with(density) { navInset.toDp() } + 12.dp))
    }

    val hideNav = !b.fullscreen && b.hidden >= toolbarPx && b.overlay == Overlay.NONE && !b.urlFocused
    LaunchedEffect(hideNav) { b.activity.setNavigationBarHidden(hideNav) }
}

@Composable
private fun Messages(b: Browser, modifier: Modifier) {
    val text = b.messages.firstOrNull() ?: return
    LaunchedEffect(text, b.messages.size) {
        delay(2500)
        if (b.messages.firstOrNull() == text) b.messages.removeAt(0)
    }
    val p = LocalPalette.current
    Snackbar(modifier.padding(horizontal = 16.dp), containerColor = if (p.dark) p.card else p.text,
        contentColor = if (p.dark) p.text else p.page) { Text(text) }
}

@Composable
private fun Toolbar(b: Browser, modifier: Modifier) {
    val p = LocalPalette.current
    val t = b.current
    val inc = t?.incognito == true
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var field by remember { mutableStateOf(TextFieldValue("")) }
    var hadFocus by remember { mutableStateOf(false) }

    LaunchedEffect(b.urlFocused) {
        if (b.urlFocused) {
            val url = t?.url.orEmpty().let { if (it == "about:blank") "" else it }
            field = TextFieldValue(url, TextRange(0, url.length))
            b.suggestQuery = ""
            runCatching { focus.requestFocus() }
            keyboard?.show()
        } else {
            focusManager.clearFocus()
            keyboard?.hide()
        }
    }

    LaunchedEffect(b.suggestFill) {
        val f = b.suggestFill ?: return@LaunchedEffect
        field = TextFieldValue(f, TextRange(f.length))
        b.suggestQuery = f
        b.suggestFill = null
    }

    Column(modifier.fillMaxWidth().height(56.dp).background(if (inc) p.toolbarIncognito else p.toolbar)) {
        Row(Modifier.fillMaxWidth().weight(1f).padding(start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (b.prefs.bool("homeButton", false) && !b.urlFocused) IconBtn(R.drawable.ic_home, "Главная страница") { b.goHome() }
            Row(
                Modifier.weight(1f).height(40.dp).background(if (inc) p.pillIncognito else p.pill, RoundedCornerShape(20.dp))
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!b.urlFocused) {
                    Box(Modifier.size(36.dp).clickable { b.pageInfo() }, contentAlignment = Alignment.Center) {
                        Ico(if (inc) R.drawable.ic_incognito else R.drawable.ic_tune, size = 20)
                    }
                }
                Box(Modifier.weight(1f).padding(start = if (b.urlFocused) 12.dp else 4.dp, end = 4.dp)) {
                    BasicTextField(
                        value = field,
                        onValueChange = {
                            field = it
                            b.suggestQuery = it.text
                        },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(color = p.text, fontSize = 16.sp),
                        cursorBrush = SolidColor(p.accent),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { b.navigate(field.text) }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged {
                            if (it.isFocused) {
                                hadFocus = true
                                if (!b.urlFocused) b.urlFocused = true
                            } else if (hadFocus) {
                                hadFocus = false
                                b.urlFocused = false
                            }
                        },
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (b.urlFocused) {
                                    if (field.text.isEmpty()) Text("Введите запрос или URL", color = p.textSecondary, fontSize = 16.sp)
                                    inner()
                                } else {
                                    UrlText(t?.url.orEmpty())
                                }
                            }
                        },
                    )
                }
                if (b.urlFocused && field.text.isNotEmpty()) {
                    Box(Modifier.size(36.dp).clickable {
                        field = TextFieldValue("")
                        b.suggestQuery = ""
                    }, contentAlignment = Alignment.Center) { Ico(R.drawable.ic_close, size = 20) }
                }
            }
            if (!b.urlFocused) {
                IconBtn(R.drawable.ic_add, "Новая вкладка") { b.newTabAndFocus(inc) }
                val n = b.incognitoCount(inc)
                Box(Modifier.size(48.dp).clickable { b.showSwitcher() }, contentAlignment = Alignment.Center) {
                    Box(Modifier.size(22.dp).border(2.dp, p.text, RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
                        Text(if (n >= 100) ":D" else n.toString(), color = p.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Box(Modifier.width(40.dp).height(48.dp).clickable { b.menuOpen = true }, contentAlignment = Alignment.Center) {
                    Ico(R.drawable.ic_more_vert)
                }
            }
        }
        if (t != null && t.loading) {
            LinearProgressIndicator(
                progress = { maxOf(5, t.progress) / 100f },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = p.accent,
                trackColor = if (inc) p.toolbarIncognito else p.toolbar,
                drawStopIndicator = {},
            )
        } else {
            Box(Modifier.height(2.dp))
        }
    }
}

@Composable
private fun UrlText(url: String) {
    val p = LocalPalette.current
    if (Urls.isBlank(url)) {
        Text("Введите запрос или URL", color = p.textSecondary, fontSize = 16.sp, maxLines = 1)
        return
    }
    val s = buildAnnotatedString {
        when {
            url.startsWith("https://") -> {
                withStyle(SpanStyle(color = p.secure)) { append("https") }
                withStyle(SpanStyle(color = p.textSecondary)) { append("://") }
                withStyle(SpanStyle(color = p.text)) { append(url.substring(8)) }
            }
            url.startsWith("http://") -> {
                withStyle(SpanStyle(color = p.textSecondary)) { append("http://") }
                withStyle(SpanStyle(color = p.text)) { append(url.substring(7)) }
            }
            else -> withStyle(SpanStyle(color = p.text)) { append(url) }
        }
    }
    Text(s, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Clip)
}

@Composable
private fun FindBar(b: Browser) {
    val p = LocalPalette.current
    val focus = remember { FocusRequester() }
    var text by remember { mutableStateOf("") }
    Row(
        Modifier.fillMaxWidth().height(56.dp).background(if (b.current?.incognito == true) p.toolbarIncognito else p.toolbar)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = text,
            onValueChange = {
                text = it
                b.find(it)
            },
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = p.text, fontSize = 16.sp),
            cursorBrush = SolidColor(p.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { b.find(null) }),
            modifier = Modifier.weight(1f).focusRequester(focus),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (text.isEmpty()) Text("Найти на странице", color = p.textSecondary, fontSize = 16.sp)
                    inner()
                }
            },
        )
        Text(b.findResult, color = p.textSecondary, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 8.dp))
        IconBtn(R.drawable.ic_keyboard_arrow_up, "Назад") { b.find(null, backwards = true) }
        IconBtn(R.drawable.ic_keyboard_arrow_down, "Вперёд") { b.find(null) }
        IconBtn(R.drawable.ic_close, "Закрыть") { b.hideFind() }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

@Composable
private fun TranslateBar(b: Browser, text: String, modifier: Modifier) {
    val p = LocalPalette.current
    Row(
        modifier.fillMaxWidth().background(p.menu).padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Ico(R.drawable.ic_translate)
        Text(text, color = p.text, fontSize = 14.sp, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
        Text("Перевести", color = p.accent, fontWeight = FontWeight.Bold, fontSize = 14.sp,
            modifier = Modifier.clickable {
                b.translateOffer = null
                b.current?.let(b::translate)
            }.padding(12.dp))
        IconBtn(R.drawable.ic_close, "Закрыть") { b.translateOffer = null }
    }
}

@Composable
private fun ExtensionPopup(b: Browser, tab: dev.antite.engine.EngineTab) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val view = remember(tab) { b.engine.createView(context).also { it.show(tab) } }
    Dialog(onDismissRequest = { b.closePopup(tab) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxWidth().height(520.dp).background(LocalPalette.current.page, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))) {
            AndroidView(factory = { view.view }, modifier = Modifier.fillMaxSize())
        }
    }
}
