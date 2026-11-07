package dev.antite.browser.transfer

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
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
import dev.antite.browser.MainActivity
import dev.antite.browser.ui.AntiteTheme
import dev.antite.browser.ui.LocalPalette
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.concurrent.thread

class TransferActivity : ComponentActivity() {
    companion object {
        private const val EXTRA_EXPORT = "export"
        private const val EXTRA_PASSWORD = "password"

        fun intent(context: Context, export: Boolean, uri: Uri, password: CharArray): Intent =
            Intent(context, TransferActivity::class.java)
                .setData(uri)
                .putExtra(EXTRA_EXPORT, export)
                .putExtra(EXTRA_PASSWORD, password)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
    }

    private var status by mutableStateOf("Ждём закрытия браузера…")
    private var fraction by mutableStateOf<Float?>(null)
    private var finished by mutableStateOf(false)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (finished) openBrowser()
            }
        })
        setContent {
            AntiteTheme(isSystemInDarkTheme()) {
                val p = LocalPalette.current
                Column(
                    Modifier.fillMaxSize().background(p.background).padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(if (intent.getBooleanExtra(EXTRA_EXPORT, true)) "Экспорт данных" else "Импорт данных",
                        color = p.text, fontSize = 22.sp)
                    Text(status, color = p.textSecondary, fontSize = 16.sp, modifier = Modifier.padding(vertical = 16.dp))
                    if (!finished) {
                        val f = fraction
                        if (f == null) {
                            LinearProgressIndicator(Modifier.fillMaxWidth(), color = p.accent)
                        } else {
                            LinearProgressIndicator({ f }, Modifier.fillMaxWidth(), color = p.accent)
                        }
                    } else {
                        TextButton(onClick = ::openBrowser) { Text("Открыть браузер", color = p.accent, fontSize = 16.sp) }
                    }
                }
            }
        }
        if (state == null) thread(name = "transfer") { run() }
    }

    private fun run() {
        val export = intent.getBooleanExtra(EXTRA_EXPORT, true)
        val uri = intent.data
        val password = intent.getCharArrayExtra(EXTRA_PASSWORD)
        intent.removeExtra(EXTRA_PASSWORD)
        val result = try {
            if (uri == null || password == null) throw IOException("no file")
            stopOtherProcesses()
            status = if (export) "Сохраняем данные…" else "Читаем файл…"
            var last = 0L
            val report = { p: Progress ->
                val now = SystemClock.elapsedRealtime()
                if (now - last > 100 && p.total > 0) {
                    last = now
                    runOnUiThread { fraction = (p.done.toFloat() / p.total).coerceIn(0f, 1f) }
                }
            }
            if (export) {
                val out = contentResolver.openOutputStream(uri, "wt") ?: throw FileNotFoundException()
                out.use { DataTransfer.export(this, dataDir, it, password, report) }
                "Готово. Все данные сохранены в файл."
            } else {
                val size = contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getLong(0) else -1L } ?: -1L
                val input = contentResolver.openInputStream(uri) ?: throw FileNotFoundException()
                input.use { DataTransfer.import(this, dataDir, it, size, password, report) }
                "Готово. Данные восстановлены."
            }
        } catch (e: WrongPasswordException) {
            "Неверный пароль или файл повреждён. Данные браузера не изменены."
        } catch (e: Exception) {
            Log.w("Antite", "transfer failed", e)
            if (export) "Не удалось сохранить данные: ${e.message}" else "Не удалось восстановить данные: ${e.message}. Данные браузера не изменены."
        } finally {
            password?.fill(' ')
        }
        runOnUiThread {
            status = result
            finished = true
        }
    }

    private fun stopOtherProcesses() {
        val am = getSystemService(ActivityManager::class.java)
        val me = Process.myPid()
        val uid = Process.myUid()
        fun others() = am.runningAppProcesses.orEmpty().filter { it.uid == uid && it.pid != me }
        val deadline = SystemClock.elapsedRealtime() + 3000
        while (others().any { it.processName == packageName } && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
        repeat(50) {
            val list = others()
            if (list.isEmpty()) return
            list.forEach { Process.killProcess(it.pid) }
            Thread.sleep(100)
        }
        throw IOException("не удалось остановить процессы браузера")
    }

    private fun openBrowser() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finishAndRemoveTask()
        Thread { Thread.sleep(300); Process.killProcess(Process.myPid()) }.start()
    }
}
