package dev.antite.browser.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.antite.browser.l10n.T
import dev.antite.browser.service.DownloadService

// The engine and Antite builds download like files: a notification with progress, speed and cancel, and the download
// goes on when the browser leaves the screen.
object UpdateDownloads {
    private val main = Handler(Looper.getMainLooper())

    fun engineName(offer: Offer) = T.engineDownloadName(version = offer.entry.version)

    fun shellName(offer: ShellOffer) = T.shellDownloadName(version = offer.version)

    fun engineTask(context: Context, offer: Offer) = DownloadService.job(engineName(offer)) { t ->
        EngineUpdates.install(context, offer) { done, total -> t.report(done, total) }
    }

    // progress is a percent on the main thread; done gets DownloadService.DONE, FAILED or CANCELLED.
    fun engine(
        context: Context,
        offer: Offer,
        progress: (Int) -> Unit = {},
        done: (status: String, error: Throwable?) -> Unit,
    ) = start(context, engineName(offer), progress, done) { report ->
        EngineUpdates.install(context, offer, report)
    }

    fun shell(
        context: Context,
        offer: ShellOffer,
        progress: (Int) -> Unit = {},
        done: (status: String, error: Throwable?) -> Unit,
    ) = start(context, shellName(offer), progress, done) { report ->
        ShellInstaller.install(context, offer, report)
    }

    private fun start(
        context: Context,
        name: String,
        progress: (Int) -> Unit,
        done: (String, Throwable?) -> Unit,
        work: (report: (Long, Long) -> Unit) -> Unit,
    ) {
        var shown = -1L
        val task = DownloadService.job(name) { t ->
            work { d, total ->
                t.report(d, total)
                val pct = if (total > 0) d * 100 / total else 0
                if (pct != shown) {
                    shown = pct
                    main.post { progress(pct.toInt()) }
                }
            }
        }
        task.onDone = done
        DownloadService.enqueue(context, task)
    }
}
