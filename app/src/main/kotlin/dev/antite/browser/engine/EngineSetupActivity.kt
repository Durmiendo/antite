package dev.antite.browser.engine

import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
        if (Sources.list(this).none { it.url == url && it.enabled }) {
            Sources.add(this, "Тестовый", url, BuildConfig.PUBLISHER_KEY, onlyThis = true)
        }
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

    private fun download(ready: State.Ready) {
        state = State.Downloading(0f)
        thread(name = "engine-download") {
            try {
                var last = 0L
                EngineUpdates.install(this, ready.offer) { done, total ->
                    val now = System.currentTimeMillis()
                    if (now - last > 100) {
                        last = now
                        state = State.Downloading(done.toFloat() / total)
                    }
                }
                Engines.restart(this)
            } catch (e: Exception) {
                state = State.Failed("Не удалось скачать движок: ${e.message}")
            }
        }
    }
}
