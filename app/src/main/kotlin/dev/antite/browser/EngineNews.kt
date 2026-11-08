package dev.antite.browser

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.antite.browser.engine.EngineUpdates
import dev.antite.browser.engine.Engines
import dev.antite.browser.engine.Offer
import dev.antite.browser.engine.ShellInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EngineNews(private val b: Browser) {
    companion object {
        private const val POSTPONE_MS = 3L * 24 * 3600 * 1000

        // Per process, not per activity: a recreated activity must not ask again.
        private var asked = false
    }

    private val ctx = b.activity

    // Intervals shorter than JobScheduler allows are kept while the browser is on screen.
    fun pollWhileOpen() = ctx.lifecycleScope.launch {
        ctx.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val minutes = EngineUpdates.intervalMinutes(ctx)
                if (!b.prefs.bool(EngineUpdates.AUTO, true) || minutes >= EngineUpdates.JOB_MIN_MINUTES) {
                    delay(60_000)
                    continue
                }
                delay(minutes * 60_000L)
                if (withContext(Dispatchers.IO) { runCatching { EngineUpdates.check(ctx) }.isSuccess }) onResume()
            }
        }
    }

    fun onResume() = ctx.lifecycleScope.launch {
        val st = EngineUpdates.state(ctx)
        val store = Engines.store(ctx)
        val (notice, active) = withContext(Dispatchers.IO) { store.takeNotice() to store.active()?.let(store::version) }
        val seen = st.getString("seenVersion", null)
        if (active != null && active != seen) st.edit().putString("seenVersion", active).apply()
        val parts = notice?.split(' ')
        if (parts?.getOrNull(0) == "rollback") {
            b.show(UiDialog.Message("Новый движок не запустился",
                "Gecko ${parts.getOrNull(1)} трижды подряд не смог запуститься. Браузер вернул Gecko ${parts.getOrNull(2)}, " +
                    "эта версия больше не будет предлагаться.", buttons = listOf(DialogButton("Понятно"))))
        } else if (seen != null && active != null && active != seen) {
            b.toast("Движок обновлён: Gecko $active")
        }
        val shell = st.getInt("shellAvailable", 0)
        if (!asked && shell > BuildConfig.VERSION_CODE && System.currentTimeMillis() >= st.getLong("postponed.shell$shell", 0)) {
            asked = true
            b.show(UiDialog.Message("Доступна новая версия Antite",
                "Сборка $shell. Скачать и установить? Система попросит подтвердить установку.",
                buttons = listOf(
                    DialogButton("Позже") { st.edit().putLong("postponed.shell$shell", System.currentTimeMillis() + POSTPONE_MS).apply() },
                    DialogButton("Обновить") { updateShell() },
                )))
            return@launch
        }
        val available = st.getString("available", null) ?: return@launch
        if (asked || EngineUpdates.installing != null ||
            b.prefs.int(EngineUpdates.INSTALL, EngineUpdates.INSTALL_ASK) != EngineUpdates.INSTALL_ASK
        ) return@launch
        if (System.currentTimeMillis() < st.getLong("postponed.$available", 0)) return@launch
        asked = true
        b.show(UiDialog.Message("Доступен новый движок",
            "Gecko $available. Скачать и установить? Браузер переключится на него после перезапуска.",
            buttons = listOf(
                DialogButton("Позже") { st.edit().putLong("postponed.$available", System.currentTimeMillis() + POSTPONE_MS).apply() },
                DialogButton("Обновить") { update() },
            )))
    }

    private fun update() = ctx.lifecycleScope.launch {
        b.toast("Скачиваем движок…")
        val offer = withContext(Dispatchers.IO) { runCatching { EngineUpdates.check(ctx).engine }.getOrNull() }
        if (offer == null) return@launch b.toast("Не удалось получить обновление")
        install(offer)
    }

    // Without a progress callback nothing on screen shows the download, so a message does.
    fun updateShell(progress: ((Int) -> Unit)? = null, done: () -> Unit = {}) = ctx.lifecycleScope.launch {
        if (progress == null) b.toast("Скачиваем новую версию Antite…")
        val r = withContext(Dispatchers.IO) {
            runCatching {
                val offer = EngineUpdates.check(ctx).shell ?: throw java.io.IOException("обновления больше нет в источнике")
                var last = -1L
                ShellInstaller.install(ctx, offer) { d, total ->
                    val pct = d * 100 / total
                    if (pct != last) {
                        last = pct
                        ctx.runOnUiThread { progress?.invoke(pct.toInt()) }
                    }
                }
            }
        }
        done()
        r.exceptionOrNull()?.let { b.toast("Не удалось обновить Antite: ${it.message}") }
    }

    fun install(offer: Offer, progress: (Int) -> Unit = {}, done: () -> Unit = {}) = ctx.lifecycleScope.launch {
        val r = withContext(Dispatchers.IO) {
            runCatching {
                var last = -1L
                EngineUpdates.install(ctx, offer) { d, total ->
                    val pct = d * 100 / total
                    if (pct != last) {
                        last = pct
                        ctx.runOnUiThread { progress(pct.toInt()) }
                    }
                }
            }
        }
        done()
        if (r.isFailure) return@launch b.toast("Не удалось скачать движок: ${r.exceptionOrNull()?.message}")
        askRestart(offer.entry.version)
    }

    fun askRestart(version: String) = b.show(UiDialog.Message("Движок скачан",
        "Gecko $version начнёт работать после перезапуска браузера.",
        buttons = listOf(DialogButton("Позже"), DialogButton("Перезапустить") { restart() })))

    fun restart() {
        b.saveNow()
        Engines.restart(ctx)
    }
}
