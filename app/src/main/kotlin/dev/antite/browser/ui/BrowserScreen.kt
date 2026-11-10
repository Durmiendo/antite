package dev.antite.browser.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationSource
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
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
import dev.antite.browser.closePopup
import dev.antite.browser.data.Options
import dev.antite.browser.find
import dev.antite.browser.hideFind
import dev.antite.browser.l10n.T
import dev.antite.browser.pageInfo
import dev.antite.browser.translate
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BrowserScreen(b: Browser) {
    val p = LocalPalette.current
    val density = LocalDensity.current
    val toolbarPx = with(density) { 56.dp.toPx() }
    b.toolbarHeightPx = toolbarPx
    b.navDeadZonePx = with(density) { Options.navDeadZones[b.prefs.index("navDeadZone", Options.navDeadZones, 2)].value.dp.toPx() }
    val t = b.current
    val bar = if (t?.incognito == true) p.toolbarIncognito else p.toolbar
    val navInset = WindowInsets.navigationBars.getBottom(density)
    val imeInset = WindowInsets.ime.getBottom(density)
    val navHidden by remember {
        derivedStateOf { !b.fullscreen && b.barsHidden && b.overlay == Overlay.NONE && !b.urlFocused }
    }
    // The page is resized once per keyboard transition, not on every animation frame: each resize makes Gecko lay the
    // page out again and the page jerks. The keyboard counts from where its animation ends when showing, from where it
    // starts when hiding. The navigation bar never resizes the page: Gecko treats it as part of the dynamic toolbar and
    // moves fixed bottom elements with it frame by frame (tools/e2e/fixed_bottom_frames.py).
    val imeTarget = WindowInsets.imeAnimationTarget.getBottom(density)
    val imeSettled = if (imeInset == imeTarget) imeInset else minOf(WindowInsets.imeAnimationSource.getBottom(density), imeTarget)
    val navFull = WindowInsets.navigationBarsIgnoringVisibility.getBottom(density)
    // Back with the keyboard open hides the keyboard; like Firefox, that also ends address editing.
    var imeWasVisible by remember { mutableStateOf(false) }
    LaunchedEffect(imeInset > 0) {
        if (imeWasVisible && imeInset == 0 && b.urlFocused) b.urlFocused = false
        if (imeWasVisible && imeInset == 0) b.dismissLoginOffer()
        imeWasVisible = imeInset > 0
    }

    Box(
        Modifier.fillMaxSize().background(bar)
            .then(if (b.fullscreen) Modifier else Modifier.windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top))),
    ) {
        val view = b.engineView
        if (view != null) {
            val bottomGap = if (b.fullscreen) 0 else imeSettled
            // Under the keyboard the navigation bar is already outside the page, so it counts as gone.
            val navBars = WindowInsets.navigationBars
            val keyboardUp by rememberUpdatedState(imeSettled > 0)
            val navMax by rememberUpdatedState(navFull)
            val clipping = {
                if (b.fullscreen) 0
                else -(b.hidden.toInt() + if (keyboardUp) navMax else navMax - navBars.getBottom(density))
            }
            AndroidView(
                factory = { view.view },
                modifier = Modifier.fillMaxSize()
                    .padding(bottom = with(density) { bottomGap.toDp() })
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val e = awaitPointerEvent(PointerEventPass.Initial)
                                b.toolbarMotion.onTouch(e.changes.any { it.pressed })
                            }
                        }
                    }
                    .graphicsLayer {
                        translationY = if (b.fullscreen) 0f else toolbarPx - b.hidden
                        // Sent from the frame that moves the page, so fixed bottom elements are not a frame behind.
                        view.setVerticalClipping(clipping())
                    },
            )
            // Full screen and PiP have no bars, else Gecko keeps an empty strip for them at the bottom. Gecko resets the
            // clipping to 0 whenever the max height is set, so it is set only when it changes and the clipping is resent.
            val maxHeight = if (b.pip || b.fullscreen) 0 else toolbarPx.toInt() + navFull
            val sentMax = remember(view) { intArrayOf(-1) }
            SideEffect {
                if (sentMax[0] != maxHeight) {
                    sentMax[0] = maxHeight
                    view.setToolbarMaxHeight(maxHeight)
                    view.setVerticalClipping(clipping())
                }
            }
            // Gecko drops the clipping when the view is resized (the keyboard), so it is sent again.
            DisposableEffect(view) {
                val listener = android.view.View.OnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                    if (bottom - top != oldBottom - oldTop) view.setVerticalClipping(clipping())
                }
                view.view.addOnLayoutChangeListener(listener)
                onDispose { view.view.removeOnLayoutChangeListener(listener) }
            }
        }
        // The picture-in-picture window shows the video alone.
        if (!b.pip) Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime.only(WindowInsetsSides.Bottom))) {
            if (t != null && Urls.isBlank(t.url) && b.overlay == Overlay.NONE) {
                NewTabPage(b, Modifier.padding(top = 56.dp).fillMaxSize().background(p.background))
            }
            // Full-screen pages cover the toolbar, so it must not stay focusable or tappable under them.
            if (!b.fullscreen && b.overlay == Overlay.NONE) {
                Box(Modifier.fillMaxWidth().clipToBounds()) {
                    Toolbar(b, Modifier.offset { IntOffset(0, -b.hidden.toInt()) })
                }
                if (b.urlFocused) Suggestions(b, Modifier.padding(top = 56.dp).fillMaxSize().background(bar))
                if (b.findVisible) FindBar(b)
            }
            // The navigation bar is transparent with edge-to-edge; while it is shown the page must not show through it.
            // It stays at the screen bottom under the keyboard: hidden while the keyboard inset was not zero, the page
            // flashed through the bar on the frames where the keyboard window had already gone.
            if (!b.fullscreen && navInset > 0) {
                val ime = WindowInsets.ime
                Box(Modifier.align(Alignment.BottomCenter).offset { IntOffset(0, ime.getBottom(this)) }.fillMaxWidth()
                    .height(with(density) { navInset.toDp() }).background(bar))
            }
            b.translateOffer?.let { TranslateBar(b, it, Modifier.align(Alignment.BottomCenter).padding(bottom = with(density) { navInset.toDp() })) }
            b.loginOffer?.let { if (imeInset > 0 && !b.urlFocused && b.overlay == Overlay.NONE) LoginBar(b, it, Modifier.align(Alignment.BottomCenter)) }
            if (b.defaultBrowserOffer && b.translateOffer == null && !b.fullscreen && !b.urlFocused && b.overlay == Overlay.NONE) {
                DefaultBrowserBar(b, Modifier.align(Alignment.BottomCenter).padding(bottom = with(density) { navInset.toDp() }))
            }
            AnimatedContent(b.overlay, transitionSpec = { overlayTransition(initialState, targetState) }, label = "overlay") { o ->
                if (o != Overlay.NONE) {
                    // Being hit is enough to keep touches from the page below; consuming them would break scrolling.
                    Box(Modifier.fillMaxSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }) {
                        when (o) {
                            Overlay.SWITCHER -> TabSwitcher(b)
                            Overlay.SETTINGS -> SettingsScreen(b)
                            Overlay.LIBRARY -> LibraryScreen(b)
                            Overlay.NONE -> {}
                        }
                    }
                }
            }
            BrowserMenu(b)
            b.extensionPopup?.let { ExtensionPopup(b, it) }
            DialogHost(b)
            Messages(b, Modifier.align(Alignment.BottomCenter).padding(bottom = with(density) { navInset.toDp() } + 12.dp))
        }
    }

    LaunchedEffect(Unit) {
        snapshotFlow { navHidden }.collect { b.activity.setNavigationBarHidden(it) }
    }
}

// Settings and the library open like Chrome's settings activity; the tab switcher fades like Chrome's Hub when it does not
// shrink the tab into the grid.
private fun overlayTransition(from: Overlay, to: Overlay): ContentTransform {
    val pages = setOf(Overlay.SETTINGS, Overlay.LIBRARY)
    if (to in pages && from == Overlay.NONE) return Motion.sharedAxisX(forward = true)
    if (from in pages && to == Overlay.NONE) return Motion.sharedAxisX(forward = false)
    val enter = if (to == Overlay.NONE) EnterTransition.None else fadeIn(Motion.tween(Motion.HUB_FADE, Motion.LinearOutSlowIn))
    val exit = if (from == Overlay.NONE) ExitTransition.None else fadeOut(Motion.tween(Motion.HUB_FADE, Motion.FastOutLinearIn))
    return enter togetherWith exit
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
            if (b.prefs.bool("homeButton", false) && !b.urlFocused) IconBtn(R.drawable.ic_home, T.toolbarHome) { b.goHome() }
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
                                    if (field.text.isEmpty()) Text(T.toolbarHint, color = p.textSecondary, fontSize = 16.sp)
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
                IconBtn(R.drawable.ic_add, T.tabNew) { b.newTabAndFocus(inc) }
                val n = b.incognitoCount(inc)
                Box(Modifier.size(48.dp).clickable { b.showSwitcher() }, contentAlignment = Alignment.Center) {
                    Box(Modifier.size(22.dp).border(2.dp, p.text, RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
                        Text(if (n >= 100) ":D" else n.toString(), color = p.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Box(Modifier.width(40.dp).height(48.dp).clickable(onClickLabel = T.toolbarMenu) { b.menuOpen = true }.semantics { contentDescription = T.toolbarMenu }, contentAlignment = Alignment.Center) {
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
        Text(T.toolbarHint, color = p.textSecondary, fontSize = 16.sp, maxLines = 1)
        return
    }
    val d = remember(url) { Urls.display(url) }
    val s = buildAnnotatedString {
        when (d.scheme) {
            "https://" -> {
                withStyle(SpanStyle(color = p.secure)) { append("https") }
                withStyle(SpanStyle(color = p.textSecondary)) { append("://") }
            }
            else -> withStyle(SpanStyle(color = p.textSecondary)) { append(d.scheme) }
        }
        withStyle(SpanStyle(color = p.text)) { append(d.text.substring(0, d.hostEnd)) }
        withStyle(SpanStyle(color = if (d.hostEnd > 0) p.textSecondary else p.text)) { append(d.text.substring(d.hostEnd)) }
    }
    // Like Chrome's scroll to TLD: when the address does not fit, it is moved left until the end of the host shows,
    // so a.b.c.example.com is read as example.com and not cut off after its subdomains.
    val style = TextStyle(fontSize = 16.sp)
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(Modifier.clipToBounds()) {
        val width = constraints.maxWidth
        val shift = remember(s, width) {
            val layout = measurer.measure(s, style, softWrap = false, maxLines = 1)
            (layout.getHorizontalPosition(d.scheme.length + d.hostEnd, usePrimaryDirection = true) - width).coerceAtLeast(0f)
        }
        Text(s, style = style, maxLines = 1, softWrap = false,
            modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true).offset { IntOffset(-shift.roundToInt(), 0) })
    }
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
                    if (text.isEmpty()) Text(T.findHint, color = p.textSecondary, fontSize = 16.sp)
                    inner()
                }
            },
        )
        Text(b.findResult, color = p.textSecondary, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 8.dp))
        IconBtn(R.drawable.ic_keyboard_arrow_up, T.findPrevious) { b.find(null, backwards = true) }
        IconBtn(R.drawable.ic_keyboard_arrow_down, T.findNext) { b.find(null) }
        IconBtn(R.drawable.ic_close, T.actionClose) { b.hideFind() }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

@Composable
private fun LoginBar(b: Browser, offer: dev.antite.engine.Prompt.SelectLogin, modifier: Modifier) {
    val p = LocalPalette.current
    Row(
        modifier.fillMaxWidth().height(48.dp).background(p.menu).padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            offer.options.forEachIndexed { i, o ->
                Row(
                    Modifier.padding(horizontal = 4.dp).height(36.dp).background(p.pill, RoundedCornerShape(18.dp))
                        .clickable { b.pickLogin(i) }.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Ico(R.drawable.ic_key, size = 18)
                    Text(if (o.generated) T.loginSuggestStrong else o.login.username.ifEmpty { T.passwordNoUsername },
                        color = p.text, fontSize = 14.sp, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
        IconBtn(R.drawable.ic_close, T.actionHide) { b.dismissLoginOffer() }
    }
}

@Composable
private fun DefaultBrowserBar(b: Browser, modifier: Modifier) {
    val p = LocalPalette.current
    Column(modifier.fillMaxWidth().background(p.menu).padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Ico(R.drawable.ic_public)
            Text(T.defaultBrowserQuestion, color = p.text, fontSize = 14.sp,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            listOf(T.actionNo to { b.answerDefaultBrowser(yes = false, later = false) },
                T.actionLater to { b.answerDefaultBrowser(yes = false, later = true) },
                T.actionYes to { b.answerDefaultBrowser(yes = true, later = false) }).forEach { (label, action) ->
                Text(label, color = p.accent, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    modifier = Modifier.clickable(onClick = action).padding(12.dp))
            }
        }
    }
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
        Text(T.translateAction, color = p.accent, fontWeight = FontWeight.Bold, fontSize = 14.sp,
            modifier = Modifier.clickable {
                b.translateOffer = null
                b.current?.let(b::translate)
            }.padding(12.dp))
        IconBtn(R.drawable.ic_close, T.actionClose) { b.translateOffer = null }
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
