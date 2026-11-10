package dev.antite.browser

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import dev.antite.browser.l10n.T
import dev.antite.engine.MediaController
import dev.antite.engine.MediaEvent

// Picture-in-picture for a video the page has put in full screen, entered when the user leaves the app.
class Pip(private val activity: MainActivity) {
    companion object {
        private const val ACTION_TOGGLE = "dev.antite.browser.PIP_TOGGLE"
        private const val MAX_RATIO = 2.38f
    }

    private var video: MediaController? = null
    private var width = 0L
    private var height = 0L
    private val playing = HashSet<MediaController>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val v = video ?: return
            if (v in playing) v.pause() else v.play()
        }
    }

    fun register() {
        activity.registerReceiver(receiver, IntentFilter(ACTION_TOGGLE), Context.RECEIVER_NOT_EXPORTED)
    }

    fun unregister() = activity.unregisterReceiver(receiver)

    fun onMedia(event: MediaEvent, current: Boolean) {
        when (event) {
            is MediaEvent.Playing -> playing += event.controller
            is MediaEvent.Paused -> playing -= event.controller
            is MediaEvent.Stopped -> forget(event.controller)
            is MediaEvent.Deactivated -> forget(event.controller)
            is MediaEvent.Fullscreen -> when {
                event.full && current && event.width > 0 && event.height > 0 -> {
                    video = event.controller
                    width = event.width
                    height = event.height
                }
                !event.full && video === event.controller -> video = null
                else -> return
            }
            else -> return
        }
        update()
    }

    private fun forget(c: MediaController) {
        playing -= c
        if (video === c) video = null
    }

    private fun eligible() = video != null && activity.browser.fullscreen

    private fun isPlaying() = video?.let { it in playing } == true

    fun update() {
        runCatching { activity.setPictureInPictureParams(params()) }
    }

    // Before Android 12 there is no auto-enter, the activity has to ask while it is being left.
    fun onUserLeaveHint() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && eligible() && isPlaying()) {
            runCatching { activity.enterPictureInPictureMode(params()) }
        }
    }

    // Closing the window stops the activity before this is called; expanding it does not.
    fun onModeChanged(inPip: Boolean, stopped: Boolean) {
        activity.browser.pip = inPip
        if (!inPip && stopped) video?.pause()
    }

    private fun params(): PictureInPictureParams {
        val b = PictureInPictureParams.Builder()
        if (eligible()) {
            val r = (width.toFloat() / height).coerceIn(1 / MAX_RATIO, MAX_RATIO)
            b.setAspectRatio(Rational((r * 10000).toInt(), 10000))
            sourceRect(r)?.let { b.setSourceRectHint(it) }
            b.setActions(listOf(toggleAction()))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.setAutoEnterEnabled(eligible() && isPlaying())
            b.setSeamlessResizeEnabled(false)
        }
        return b.build()
    }

    private fun sourceRect(ratio: Float): Rect? {
        val v = activity.window.decorView
        val w = v.width
        val h = v.height
        if (w == 0 || h == 0) return null
        return if (w.toFloat() / h > ratio) {
            val vw = (h * ratio).toInt()
            Rect((w - vw) / 2, 0, (w + vw) / 2, h)
        } else {
            val vh = (w / ratio).toInt()
            Rect(0, (h - vh) / 2, w, (h + vh) / 2)
        }
    }

    private fun toggleAction(): RemoteAction {
        val play = !isPlaying()
        val intent = PendingIntent.getBroadcast(
            activity, 0, Intent(ACTION_TOGGLE).setPackage(activity.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val label = if (play) T.mediaPlay else T.mediaPause
        val icon = if (play) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause
        return RemoteAction(Icon.createWithResource(activity, icon), label, label, intent)
    }
}
