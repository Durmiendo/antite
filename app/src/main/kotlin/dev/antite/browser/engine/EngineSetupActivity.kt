package dev.antite.browser.engine

import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
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

    private val indexUrl: String
        get() {
            val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
            return intent.getStringExtra("indexUrl")?.takeIf { debuggable } ?: BuildConfig.ENGINE_INDEX_URL
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
                            Info("Gecko ${s.offer.entry.version}, ${mb(s.offer.size)} МБ\nИсточник: ${Uri.parse(indexUrl).host}")
                            TextButton(onClick = { download(s) }) { Text("Скачать", color = p.accent, fontSize = 16.sp) }
                        }
                        is State.Downloading -> {
                            Info("Скачиваем и проверяем…")
                            LinearProgressIndicator({ s.fraction }, Modifier.fillMaxWidth(), color = p.accent)
                        }
                        is State.Failed -> {
                            Info(s.message)
                            TextButton(onClick = ::check) { Text("Повторить", color = p.accent, fontSize = 16.sp) }
                        }
                    }
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
                val offer = EngineUpdates.fetchOffer(this, indexUrl)
                if (offer == null) State.Failed("В источнике нет движка для этого устройства и этой версии браузера.")
                else State.Ready(offer)
            } catch (e: IndexException) {
                State.Failed("Источник обновлений не прошёл проверку подписи: ${e.message}")
            } catch (e: Exception) {
                State.Failed("Не удалось получить список движков. Проверьте подключение к интернету.\n${e.message}")
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
                EngineUpdates.setSource(this, indexUrl)
                Engines.restart(this)
            } catch (e: Exception) {
                state = State.Failed("Не удалось скачать движок: ${e.message}")
            }
        }
    }
}
