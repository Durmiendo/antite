package dev.antite.browser.engine

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.antite.browser.BuildConfig
import dev.antite.browser.FormField
import dev.antite.browser.data.Prefs
import dev.antite.browser.l10n.T
import dev.antite.browser.service.DownloadService
import dev.antite.browser.ui.FormDialog
import dev.antite.browser.ui.AntiteTheme
import dev.antite.browser.ui.LocalPalette
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import dev.antite.browser.data.KeyRing
import dev.antite.browser.data.Options
import dev.antite.browser.data.Secure
import dev.antite.browser.l10n.L10n
import dev.antite.browser.toSecretBytes
import dev.antite.browser.ui.Badge
import dev.antite.browser.ui.RadioRow
import dev.antite.browser.ui.SecretField
import dev.antite.browser.ui.SecretInput
import dev.antite.browser.ui.SwitchRow
import kotlin.concurrent.thread

class EngineSetupActivity : ComponentActivity() {
    private sealed interface State {
        data object Checking : State
        class Ready(val offer: Offer) : State
        class Downloading(val fraction: Float) : State
        class Failed(val message: String) : State
    }

    private var state by mutableStateOf<State>(State.Checking)
    private var adding by mutableStateOf(false)

    // Debug builds take a test source from the intent, signed with the built-in key.
    private fun useTestSource() {
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val url = intent.getStringExtra("indexUrl")?.takeIf { debuggable } ?: return
        val existing = Sources.list(this).firstOrNull { it.url == url && it.enabled }
        if (existing == null) Sources.add(this, T.engineTestSource, url, BuildConfig.PUBLISHER_KEY, onlyThis = true)
        else Sources.setUpdateSource(this, existing.id)
    }

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        setContent {
            // Theme and language picked on a step apply at once.
            val rev = settingsRevision
            AntiteTheme(remember(rev) { Prefs(this).isDark() }, remember(rev) { Prefs(this).isOled() }) {
                key(rev) { Wizard() }
                if (adding) {
                    FormDialog(T.engineOtherSource, T.engineOtherSourceWarning, listOf(
                        FormField(T.engineSourceIndexUrl, kind = FormField.Kind.URL),
                        FormField(T.engineSourceKey, kind = FormField.Kind.URL),
                    ), T.actionAdd, { v ->
                        try {
                            Sources.add(this, "", v[0].trim(), v[1], onlyThis = true)
                            check()
                            true
                        } catch (e: SourceException) {
                            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
                            false
                        }
                    }, { adding = false })
                }
            }
        }
        if (saved == null) check()
    }

    private var settingsRevision by mutableIntStateOf(0)
    private var step by mutableIntStateOf(0)
    private var engineReady by mutableStateOf(false)
    private var finished by mutableStateOf(false)
    private var keysApplying = false
    private var keyMode by mutableStateOf(KeyRing.Mode.DEVICE)
    private var keyError by mutableStateOf<String?>(null)
    private val pw1 = SecretInput()
    private val pw2 = SecretInput()

    // The steps of the first start; a new one is one more entry here.
    private class Step(val title: String, val content: @androidx.compose.runtime.Composable () -> Unit)

    private val steps
        @androidx.compose.runtime.Composable get() = listOf(
            Step(T.languagesInterface) {
                val options = listOf("" to T.languagesSystem) + L10n.languages
                val cur = options.indexOfFirst { it.first == Prefs(this).string(L10n.PREF, "") }.coerceAtLeast(0)
                options.forEachIndexed { i, (code, name) ->
                    RadioRow(name, i == cur) {
                        Prefs(this).edit { putString(L10n.PREF, code) }
                        L10n.init(this)
                        settingsRevision++
                    }
                }
            },
            Step(T.settingsTheme) {
                val prefs = Prefs(this)
                val cur = prefs.index("theme", Options.themes, 2)
                Options.themes.forEachIndexed { i, o -> RadioRow(o.label, i == cur) { prefs.edit { putInt("theme", i) }; settingsRevision++ } }
                SwitchRow(T.settingsThemeOled, null, prefs.isOled()) { prefs.edit { putBoolean("oled", it) }; settingsRevision++ }
            },
            Step(T.settingsSearchEngine) {
                val prefs = Prefs(this)
                val cur = prefs.index("engine", Options.engines, 0)
                Options.engines.forEachIndexed { i, o -> RadioRow(o.label, i == cur) { prefs.edit { putInt("engine", i) }; settingsRevision++ } }
            },
            Step(T.keysMode) {
                KeyRing.Mode.entries.forEach { m ->
                    RadioRow(modeName(m), m == keyMode) { keyMode = m }
                }
                Badge(if (keyMode.usesDevice) T.keysEraseYes else T.keysEraseNo, Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                if (keyMode.usesPassword) {
                    Column(Modifier.padding(horizontal = 20.dp)) {
                        SecretField(pw1, T.keysPassword)
                        SecretField(pw2, T.keysPasswordAgain)
                        Badge(T.keysPasswordNewWarningShort, Modifier.padding(top = 4.dp))
                    }
                }
                keyError?.let { Text(it, color = LocalPalette.current.insecure, fontSize = 14.sp, modifier = Modifier.padding(20.dp)) }
            },
        )

    private fun modeName(m: KeyRing.Mode) = when (m) {
        KeyRing.Mode.DEVICE -> T.keysModeDevice
        KeyRing.Mode.PASSWORD -> T.keysModePassword
        KeyRing.Mode.BOTH -> T.keysModeBoth
    }

    @androidx.compose.runtime.Composable
    private fun Wizard() {
        val p = LocalPalette.current
        val all = steps
        Column(Modifier.fillMaxSize().background(p.background).systemBarsPadding().padding(vertical = 24.dp)) {
            Column(Modifier.padding(horizontal = 24.dp)) {
                Text("Antite", color = p.text, fontSize = 26.sp)
                EngineStatus()
                Text(all.indices.joinToString(" ") { if (it == step) "●" else "○" }, color = p.accent, fontSize = 14.sp,
                    modifier = Modifier.padding(top = 16.dp))
                Text(all[step].title, color = p.text, fontSize = 20.sp, modifier = Modifier.padding(vertical = 12.dp))
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { all[step].content() }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { if (step > 0) step-- }, enabled = step > 0) { Text(T.actionBack, fontSize = 16.sp) }
                val last = step == all.lastIndex
                TextButton(onClick = { if (last) finish(all) else step++ }, enabled = !finished) {
                    Text(when {
                        !last -> T.setupNext
                        finished -> T.setupWaitEngine
                        else -> T.setupStart
                    }, color = p.accent, fontSize = 16.sp)
                }
            }
        }
    }

    // The last step applies the key mode, then the browser starts as soon as the engine is in.
    private fun finish(all: List<Step>) {
        if (keyMode.usesPassword) {
            val a = pw1.take()
            val b = pw2.take()
            val same = a.contentEquals(b)
            b.fill('\u0000')
            if (!same || a.isEmpty()) {
                a.fill('\u0000')
                keyError = if (!same) T.keysPasswordMismatch else T.keysPasswordEmpty
                return
            }
            val bytes = a.toSecretBytes()
            finished = true
            keysApplying = true
            keyError = null
            thread(name = "keys") {
                try {
                    Secure.ring(this).setMode(keyMode, bytes)
                    Secure.ring(this).lock()
                } catch (e: Exception) {
                    runOnUiThread {
                        keysApplying = false
                        finished = false
                        keyError = T.keysFailed(reason = e.message ?: "")
                    }
                    return@thread
                }
                runOnUiThread {
                    keysApplying = false
                    if (engineReady) Engines.restart(this)
                }
            }
            return
        }
        finished = true
        if (engineReady) Engines.restart(this)
    }

    @androidx.compose.runtime.Composable
    private fun EngineStatus() {
        val p = LocalPalette.current
        when (val s = state) {
            State.Checking -> {
                Info(T.engineSetupChecking)
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = p.accent)
            }
            is State.Ready -> {
                Info(T.engineSetupOffer(version = s.offer.entry.version, size = mb(s.offer.size), source = s.offer.source.name,
                    host = Uri.parse(s.offer.source.url).host) +
                    if (s.offer.source.builtin) "" else "\n" + T.engineSetupKey(fingerprint = s.offer.source.fingerprint))
                Row {
                    TextButton(onClick = { download(s) }) { Text(T.actionDownload, color = p.accent, fontSize = 16.sp) }
                    TextButton(onClick = { adding = true }) { Text(T.engineOtherSource, color = p.accent, fontSize = 16.sp) }
                }
            }
            is State.Downloading -> {
                Info(if (engineReady) T.setupEngineReady else T.engineSetupDownloading)
                LinearProgressIndicator({ s.fraction }, Modifier.fillMaxWidth(), color = p.accent)
            }
            is State.Failed -> {
                Info(s.message)
                Row {
                    TextButton(onClick = ::check) { Text(T.actionRetry, color = p.accent, fontSize = 16.sp) }
                    TextButton(onClick = { adding = true }) { Text(T.engineOtherSource, color = p.accent, fontSize = 16.sp) }
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Info(text: String) {
        Text(text, color = LocalPalette.current.textSecondary, fontSize = 16.sp, modifier = Modifier.padding(vertical = 12.dp))
    }

    private fun mb(bytes: Long) = (bytes + 512 * 1024) / (1024 * 1024)

    private fun check() {
        state = State.Checking
        thread(name = "engine-index") {
            state = try {
                useTestSource()
                val found = EngineUpdates.best(this)
                when {
                    found.offer != null -> State.Ready(found.offer).also { r -> runOnUiThread { download(r) } }
                    found.errors.isNotEmpty() ->
                        State.Failed(T.engineSetupListFailedOffline + "\n" + found.errors.joinToString("\n"))
                    else -> State.Failed(T.engineSetupNoneForAbi(abi = Abi.current))
                }
            } catch (e: Exception) {
                State.Failed(T.engineSetupListFailed(reason = e.message))
            }
        }
    }

    private var pending: State.Ready? = null
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        pending?.let(::start)
        pending = null
    }

    // The download shows its progress in a notification, so it asks for them first; a refusal does not stop it.
    private fun download(ready: State.Ready) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pending = ready
            return askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        start(ready)
    }

    private fun start(ready: State.Ready) {
        state = State.Downloading(0f)
        UpdateDownloads.engine(this, ready.offer, { state = State.Downloading(it / 100f) }) { status, error ->
            state = when (status) {
                DownloadService.DONE -> {
                    engineReady = true
                    if (finished && !keysApplying) return@engine Engines.restart(this)
                    State.Downloading(1f)
                }
                DownloadService.CANCELLED -> ready
                else -> State.Failed(T.engineDownloadFailed(reason = error?.message))
            }
        }
    }
}
