package dev.antite.browser.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import dev.antite.browser.MainActivity
import dev.antite.engine.MediaController
import dev.antite.engine.MediaEvent

class MediaPlayback(private val context: Context) {
    fun onEvent(event: MediaEvent, tabTitle: String, tabHost: String, icon: Bitmap?, onFirstPlay: () -> Unit) {
        val s = MediaService.Companion
        when (event) {
            is MediaEvent.Activated -> {
                s.controller = event.controller
                s.title = tabTitle
                s.artist = tabHost
                s.art = icon
            }
            is MediaEvent.Deactivated -> if (s.controller === event.controller) MediaService.stop(context)
            is MediaEvent.Metadata -> if (s.controller === event.controller) {
                s.title = event.metadata.title?.takeIf { it.isNotEmpty() } ?: tabTitle
                s.artist = event.metadata.artist?.takeIf { it.isNotEmpty() } ?: tabHost
                if (s.playing) MediaService.update(context)
            }
            is MediaEvent.Artwork -> if (s.controller === event.controller) {
                s.art = event.image
                MediaService.update(context)
            }
            is MediaEvent.Playing -> {
                s.controller = event.controller
                s.playing = true
                onFirstPlay()
                MediaService.update(context)
            }
            is MediaEvent.Paused -> if (s.controller === event.controller) {
                s.playing = false
                MediaService.update(context)
            }
            is MediaEvent.Stopped -> if (s.controller === event.controller) MediaService.stop(context)
            is MediaEvent.Fullscreen -> {}
        }
    }
}

class MediaService : Service() {
    companion object {
        private const val CHANNEL = "media"
        private const val ID = 7
        private const val ACTION_UPDATE = "update"
        private const val ACTION_PLAY = "play"
        private const val ACTION_PAUSE = "pause"
        private const val ACTION_NEXT = "next"
        private const val ACTION_PREV = "prev"
        private const val ACTION_STOP = "stop"

        var controller: MediaController? = null
        var title = ""
        var artist = ""
        var art: Bitmap? = null
        var playing = false

        fun update(c: Context) {
            c.startForegroundService(Intent(c, MediaService::class.java).setAction(ACTION_UPDATE))
        }

        fun stop(c: Context) {
            controller = null
            playing = false
            c.stopService(Intent(c, MediaService::class.java))
        }
    }

    private lateinit var session: MediaSession

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Воспроизведение", NotificationManager.IMPORTANCE_LOW),
        )
        session = MediaSession(this, "Antite").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { Companion.controller?.play() }
                override fun onPause() { Companion.controller?.pause() }
                override fun onSkipToNext() { Companion.controller?.next() }
                override fun onSkipToPrevious() { Companion.controller?.previous() }
                override fun onSeekTo(pos: Long) { Companion.controller?.seekTo(pos / 1000.0) }
                override fun onStop() { Companion.controller?.stop() }
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_UPDATE
        controller?.let { c ->
            when (action) {
                ACTION_PLAY -> c.play()
                ACTION_PAUSE -> c.pause()
                ACTION_NEXT -> c.next()
                ACTION_PREV -> c.previous()
                ACTION_STOP -> c.stop()
            }
        }
        startForeground(ID, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        if (controller == null || action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!playing) stopForeground(STOP_FOREGROUND_DETACH)
        return START_NOT_STICKY
    }

    private fun action(a: String, code: Int) = PendingIntent.getService(
        this, code, Intent(this, MediaService::class.java).setAction(a),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun button(res: Int, label: String, a: String, code: Int) =
        Notification.Action.Builder(Icon.createWithResource(this, res), label, action(a, code)).build()

    private fun build(): Notification {
        session.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
            .apply { art?.let { putBitmap(MediaMetadata.METADATA_KEY_ART, it) } }
            .build())
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
            .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
            .build())
        val open = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title.ifEmpty { "Воспроизведение" })
            .setContentText(artist)
            .setLargeIcon(art)
            .setOngoing(playing)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE))
            .setDeleteIntent(action(ACTION_STOP, 5))
            .addAction(button(android.R.drawable.ic_media_previous, "Назад", ACTION_PREV, 1))
            .addAction(if (playing) button(android.R.drawable.ic_media_pause, "Пауза", ACTION_PAUSE, 2)
            else button(android.R.drawable.ic_media_play, "Играть", ACTION_PLAY, 3))
            .addAction(button(android.R.drawable.ic_media_next, "Вперёд", ACTION_NEXT, 4))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    override fun onDestroy() {
        session.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
