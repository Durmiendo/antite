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
import androidx.compose.foundation.isSystemInDarkTheme
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
import dev.antite.browser.service.DownloadService
import dev.antite.browser.ui.FormDialog
import dev.antite.browser.ui.AntiteTheme
import dev.antite.browser.ui.LocalPalette
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
        if (existing == null) Sources.add(this, "Тестовый", url, BuildConfig.PUBLISHER_KEY, onlyThis = true)
        else Sources.setUpdateSource(this, existing.id)
    }

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        setContent {
            AntiteTheme(isSystemInDarkTheme()) {
                val p = LocalPalette.current
                Column(
                    Modifier.fillMaxSize().background(p.background).padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("Движок браузера", color = p.text, fontSize = 22.sp)
                    when (val s = this@EngineSetupActivity.state) {
                        State.Checking -> {
                            Info("Проверяем источник обновлений…")
                            LinearProgressIndicator(Modifier.fillMaxWidth(), color = p.accent)
                        }
                        is State.Ready -> {
                            Info("Для работы браузеру нужен движок. Он скачивается один раз и потом обновляется отдельно.")
                            Info("Gecko ${s.offer.entry.version}, ${mb(s.offer.size)} МБ\nИсточник: ${s.offer.source.name} (${Uri.parse(s.offer.source.url).host})" +
                                if (s.offer.source.builtin) "" else "\nКлюч SHA-256: ${s.offer.source.fingerprint}")
                            TextButton(onClick = { download(s) }) { Text("Скачать", color = p.accent, fontSize = 16.sp) }
                            TextButton(onClick = { adding = true }) { Text("Другой источник", color = p.accent, fontSize = 16.sp) }
                        }
                        is State.Downloading -> {
                            Info("Скачиваем и проверяем…")
                            LinearProgressIndicator({ s.fraction }, Modifier.fillMaxWidth(), color = p.accent)
                        }
                        is State.Failed -> {
                            Info(s.message)
                            TextButton(onClick = ::check) { Text("Повторить", color = p.accent, fontSize = 16.sp) }
                            TextButton(onClick = { adding = true }) { Text("Другой источник", color = p.accent, fontSize = 16.sp) }
                        }
                    }
                }
                if (adding) {
                    FormDialog("Другой источник", "Источник сможет устанавливать код, который выполняется в браузере. " +
                        "Добавляйте только тот, чьему издателю доверяете", listOf(
                        FormField("Адрес индекса (https://…/engines.json)", kind = FormField.Kind.URL),
                        FormField("Открытый ключ издателя", kind = FormField.Kind.URL),
                    ), "Добавить", { v ->
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
                    found.offer != null -> State.Ready(found.offer)
                    found.errors.isNotEmpty() ->
                        State.Failed("Не удалось получить список движков. Проверьте подключение к интернету.\n" + found.errors.joinToString("\n"))
                    else -> State.Failed("В источнике нет движка для архитектуры ${Abi.current} и этой версии браузера.")
                }
            } catch (e: Exception) {
                State.Failed("Не удалось получить список движков: ${e.message}")
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
                DownloadService.DONE -> return@engine Engines.restart(this)
                DownloadService.CANCELLED -> ready
                else -> State.Failed("Не удалось скачать движок: ${error?.message}")
            }
        }
    }
}
