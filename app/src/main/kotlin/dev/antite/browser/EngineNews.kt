package dev.antite.browser

import androidx.lifecycle.lifecycleScope
import dev.antite.browser.engine.EngineUpdates
import dev.antite.browser.engine.Engines
import dev.antite.browser.engine.Offer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EngineNews(private val b: Browser) {
    companion object {
        private const val POSTPONE_MS = 3L * 24 * 3600 * 1000
    }

    private val ctx = b.activity
    private var asked = false

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
        val available = st.getString("available", null) ?: return@launch
        if (asked || b.prefs.int(EngineUpdates.INSTALL, EngineUpdates.INSTALL_ASK) != EngineUpdates.INSTALL_ASK) return@launch
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
        val offer = withContext(Dispatchers.IO) { runCatching { EngineUpdates.check(ctx) }.getOrNull() }
        if (offer == null) return@launch b.toast("Не удалось получить обновление")
        install(offer)
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
