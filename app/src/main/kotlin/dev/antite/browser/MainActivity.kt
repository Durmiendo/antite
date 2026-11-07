package dev.antite.browser

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.os.PersistableBundle
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import dev.antite.browser.data.Tab
import dev.antite.browser.ui.AntiteTheme
import dev.antite.browser.ui.BrowserScreen
import dev.antite.browser.ui.LocalBrowser
import dev.antite.engine.gecko.GeckoEngine

class MainActivity : ComponentActivity() {
    lateinit var browser: Browser
        private set

    private var filesResult: ((List<Uri>?) -> Unit)? = null
    private var createResult: ((Uri?) -> Unit)? = null
    private var openResult: ((Uri?) -> Unit)? = null
    private var permissionsResult: ((Boolean) -> Unit)? = null
    private var navHidden = false

    private val pickOne = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        filesResult?.invoke(uri?.let { listOf(it) })
        filesResult = null
    }
    private val pickMany = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        filesResult?.invoke(uris)
        filesResult = null
    }
    private val create = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        createResult?.invoke(if (r.resultCode == RESULT_OK) r.data?.data else null)
        createResult = null
    }
    private val open = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        openResult?.invoke(uri)
        openResult = null
    }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        permissionsResult?.invoke(r.isNotEmpty() && r.values.all { it })
        permissionsResult = null
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        StrictModeSetup.enableIfDebuggable(this)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        browser = Browser(this, GeckoEngine.FACTORY.create(this))
        browser.attachView(browser.engine.createView(this))
        browser.restore()
        if (state == null) {
            browser.handleIntent(intent, browser.current == null || browser.prefs.int("startup", 0) == 1)
        } else if (browser.current == null) {
            browser.newTabAndFocus(false)
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!browser.onBack()) moveTaskToBack(true)
            }
        })
        setContent {
            val dark = browser.prefs.isDark()
            AntiteTheme(dark) {
                CompositionLocalProvider(LocalBrowser provides browser) { BrowserScreen(browser) }
            }
            window.insetsController?.setSystemBarsAppearance(
                if (dark) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        browser.handleIntent(intent, false)
    }

    override fun onPause() {
        super.onPause()
        browser.captureThumb(browser.current)
        browser.saveNow()
    }

    override fun onStop() {
        super.onStop()
        browser.onStop()
    }

    fun pickFiles(mimeTypes: List<String>, multiple: Boolean, done: (List<Uri>?) -> Unit) {
        filesResult = done
        val mime = mimeTypes.singleOrNull()?.takeIf { '/' in it } ?: "*/*"
        if (multiple) pickMany.launch(mime) else pickOne.launch(mime)
    }

    fun createDocument(name: String, mime: String, done: (Uri?) -> Unit) {
        createResult = done
        create.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime)
            .putExtra(Intent.EXTRA_TITLE, name))
    }

    fun openDocument(mimeTypes: List<String>, done: (Uri?) -> Unit) {
        openResult = done
        open.launch(arrayOf("*/*") + mimeTypes)
    }

    fun requestPermissions(list: List<String>, done: (Boolean) -> Unit) {
        permissionsResult?.invoke(false)
        permissionsResult = done
        permissions.launch(list.toTypedArray())
    }

    fun requestNotificationPermission(onlyOnce: Boolean) {
        if (onlyOnce) {
            if (browser.prefs.sp.getBoolean("askedNotif", false)) return
            browser.prefs.edit { putBoolean("askedNotif", true) }
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(listOf(Manifest.permission.POST_NOTIFICATIONS)) {}
        }
    }

    fun setFullscreen(full: Boolean) {
        browser.fullscreen = full
        val c = window.insetsController ?: return
        if (full) {
            c.hide(WindowInsets.Type.systemBars())
            c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            c.show(WindowInsets.Type.systemBars())
            navHidden = false
        }
    }

    fun setNavigationBarHidden(hide: Boolean) {
        val c = window.insetsController ?: return
        if (browser.fullscreen || hide == navHidden) return
        navHidden = hide
        if (hide) {
            c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsets.Type.navigationBars())
        } else {
            c.show(WindowInsets.Type.navigationBars())
        }
    }

    fun copy(text: String, sensitive: Boolean = false) {
        val clip = ClipData.newPlainText(if (sensitive) "password" else "url", text)
        if (sensitive) clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        browser.toast("Скопировано")
    }

    fun share(url: String, title: String?) {
        if (url.isEmpty()) return
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
        if (!title.isNullOrEmpty()) i.putExtra(Intent.EXTRA_SUBJECT, title)
        startActivity(Intent.createChooser(i, null))
    }

    fun addShortcut(t: Tab, icon: Bitmap?) {
        if (!Urls.isWeb(t.url)) return browser.toast("Ярлык можно создать только для сайта")
        val sm = getSystemService(ShortcutManager::class.java)
        if (sm == null || !sm.isRequestPinShortcutSupported) return browser.toast("Рабочий стол не поддерживает ярлыки")
        val host = Urls.host(t.url)
        val label = t.title.ifEmpty { host }
        sm.requestPinShortcut(ShortcutInfo.Builder(this, "site-" + t.url.hashCode())
            .setShortLabel(label.take(24))
            .setLongLabel(label)
            .setIcon(Icon.createWithBitmap(icon ?: letterIcon(host)))
            .setIntent(Intent(Intent.ACTION_VIEW, Uri.parse(t.url)).setClass(this, MainActivity::class.java))
            .build(), null)
    }

    private fun letterIcon(host: String): Bitmap {
        val size = (48 * resources.displayMetrics.density).toInt()
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = android.graphics.Color.HSVToColor(floatArrayOf(Math.abs(host.hashCode() % 360).toFloat(), 0.45f, 0.75f))
        c.drawCircle(size / 2f, size / 2f, size / 2f, p)
        p.color = android.graphics.Color.WHITE
        p.textSize = size * 0.5f
        p.textAlign = Paint.Align.CENTER
        p.typeface = Typeface.DEFAULT_BOLD
        c.drawText(host.take(1).uppercase(), size / 2f, size / 2f + size * 0.18f, p)
        return b
    }
}
