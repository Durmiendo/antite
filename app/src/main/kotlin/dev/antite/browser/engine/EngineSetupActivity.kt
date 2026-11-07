package dev.antite.browser.engine

import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
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
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class EngineSetupActivity : ComponentActivity() {
    private sealed interface State {
        data object Checking : State
        class Ready(val entry: EngineEntry, val abi: String, val serial: Long) : State
        class Downloading(val fraction: Float) : State
        class Failed(val message: String) : State
    }

    private var state by mutableStateOf<State>(State.Checking)

    private val indexUrl: String
        get() {
            val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
            return intent.getStringExtra("indexUrl")?.takeIf { debuggable } ?: BuildConfig.ENGINE_INDEX_URL
        }

    private val fetcher = Fetcher { url ->
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        if (c.responseCode != 200) throw java.io.IOException("$url: HTTP ${c.responseCode}")
        c.inputStream
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
                            Info("Gecko ${s.entry.version}, ${mb(s.entry.adapter.size + s.entry.upstream.getValue(s.abi).size)} МБ\nИсточник: ${Uri.parse(indexUrl).host}")
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

    private fun prefs() = getSharedPreferences("engines", Context.MODE_PRIVATE)

    private fun check() {
        state = State.Checking
        thread(name = "engine-index") {
            state = try {
                val json = fetcher.open(indexUrl).use(InputStream::readBytes)
                val sig = fetcher.open("$indexUrl.sig").use(InputStream::readBytes)
                val index = EngineIndex.verify(json, sig, EngineIndex.publicKey(BuildConfig.PUBLISHER_KEY), prefs().getLong("serial", 0))
                val api = dev.antite.engine.BuildConfig.ENGINE_API
                val pick = Build.SUPPORTED_ABIS.firstNotNullOfOrNull { abi ->
                    index.choose(abi, api, BuildConfig.VERSION_CODE)?.let { it to abi }
                }
                if (pick == null) State.Failed("В источнике нет движка для этого устройства и этой версии браузера.")
                else State.Ready(pick.first, pick.second, index.serial)
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
                val store = Engines.store(this)
                store.dir.mkdirs()
                var last = 0L
                val dir = EngineInstaller(store.dir, fetcher).install(ready.entry, ready.abi) { done, total ->
                    val now = System.currentTimeMillis()
                    if (now - last > 100) {
                        last = now
                        state = State.Downloading(done.toFloat() / total)
                    }
                }
                store.activate(dir)
                prefs().edit().putLong("serial", ready.serial).commit()
                Engines.restart(this)
            } catch (e: Exception) {
                state = State.Failed("Не удалось скачать движок: ${e.message}")
            }
        }
    }
}
