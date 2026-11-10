package dev.antite.browser

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.antite.browser.engine.EngineUpdates
import dev.antite.browser.engine.Engines
import dev.antite.browser.engine.Offer
import dev.antite.browser.engine.ShellOffer
import dev.antite.browser.engine.UpdateDownloads
import dev.antite.browser.l10n.T
import dev.antite.browser.service.DownloadService
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
            b.show(UiDialog.Message(T.engineRollbackTitle,
                T.engineRollbackText(failed = parts.getOrNull(1), restored = parts.getOrNull(2)), buttons = listOf(DialogButton(T.actionOk))))
        } else if (seen != null && active != null && active != seen) {
            b.toast(T.engineUpdated(version = active))
        }
        val shell = st.getInt("shellAvailable", 0)
        if (!asked && shell > BuildConfig.VERSION_CODE && System.currentTimeMillis() >= st.getLong("postponed.shell$shell", 0)) {
            asked = true
            b.show(UiDialog.Message(T.shellOfferTitle,
                T.shellOfferText(version = shell),
                buttons = listOf(
                    DialogButton(T.actionLater) { st.edit().putLong("postponed.shell$shell", System.currentTimeMillis() + POSTPONE_MS).apply() },
                    DialogButton(T.actionUpdate) { updateShell() },
                )))
            return@launch
        }
        val available = st.getString("available", null) ?: return@launch
        if (asked || EngineUpdates.installing != null ||
            b.prefs.int(EngineUpdates.INSTALL, EngineUpdates.INSTALL_ASK) != EngineUpdates.INSTALL_ASK
        ) return@launch
        if (System.currentTimeMillis() < st.getLong("postponed.$available", 0)) return@launch
        asked = true
        b.show(UiDialog.Message(T.engineOfferTitle,
            T.engineOfferText(version = available),
            buttons = listOf(
                DialogButton(T.actionLater) { st.edit().putLong("postponed.$available", System.currentTimeMillis() + POSTPONE_MS).apply() },
                DialogButton(T.actionUpdate) { update() },
            )))
    }

    private fun update() = ctx.lifecycleScope.launch {
        b.toast(T.engineDownloading)
        val offer = withContext(Dispatchers.IO) { runCatching { EngineUpdates.check(ctx).engine }.getOrNull() }
        if (offer == null) return@launch b.toast(T.engineUpdateUnavailable)
        install(offer)
    }

    // Without a progress callback nothing on screen shows the download, so a message does.
    fun updateShell(progress: ((Int) -> Unit)? = null, done: () -> Unit = {}) = ctx.lifecycleScope.launch {
        if (progress == null) b.toast(T.shellDownloading)
        val r = withContext(Dispatchers.IO) { runCatching { EngineUpdates.check(ctx).shell } }
        val offer = r.getOrNull()
        if (offer == null) {
            done()
            return@launch b.toast(T.shellUpdateFailed(reason = r.exceptionOrNull()?.message ?: T.shellUpdateGone))
        }
        installShell(offer, progress ?: {}, done)
    }

    fun installShell(offer: ShellOffer, progress: (Int) -> Unit = {}, done: () -> Unit = {}) =
        UpdateDownloads.shell(ctx, offer, progress) { status, error ->
            done()
            if (status == DownloadService.FAILED) b.toast(T.shellUpdateFailed(reason = error?.message))
        }

    fun install(offer: Offer, progress: (Int) -> Unit = {}, done: () -> Unit = {}) =
        UpdateDownloads.engine(ctx, offer, progress) { status, error ->
            done()
            when (status) {
                DownloadService.DONE -> askRestart(offer.entry.version)
                DownloadService.FAILED -> b.toast(T.engineDownloadFailed(reason = error?.message))
            }
        }

    fun askRestart(version: String) = b.show(UiDialog.Message(T.engineDownloadedTitle,
        T.engineDownloadedText(version = version),
        buttons = listOf(DialogButton(T.actionLater), DialogButton(T.actionRestart) { restart() })))

    fun restart() {
        b.saveNow()
        Engines.restart(ctx)
    }
}
