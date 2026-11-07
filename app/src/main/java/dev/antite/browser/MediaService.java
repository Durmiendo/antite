package dev.antite.browser;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.PlaybackState;
import android.os.IBinder;

import org.mozilla.geckoview.MediaSession;

public class MediaService extends Service {
    private static final String CHANNEL = "media";
    private static final int ID = 7;
    private static final String ACTION_UPDATE = "update";
    private static final String ACTION_PLAY = "play";
    private static final String ACTION_PAUSE = "pause";
    private static final String ACTION_NEXT = "next";
    private static final String ACTION_PREV = "prev";
    private static final String ACTION_STOP = "stop";

    static MediaSession gecko;
    static String title = "";
    static String artist = "";
    static Bitmap art;
    static boolean playing;
    static long features;

    private android.media.session.MediaSession session;

    static void update(Context c) {
        c.startForegroundService(new Intent(c, MediaService.class).setAction(ACTION_UPDATE));
    }

    static void stop(Context c) {
        gecko = null;
        playing = false;
        c.stopService(new Intent(c, MediaService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Воспроизведение", NotificationManager.IMPORTANCE_LOW));
        session = new android.media.session.MediaSession(this, "Antite");
        session.setCallback(new android.media.session.MediaSession.Callback() {
            @Override
            public void onPlay() {
                if (gecko != null) gecko.play();
            }

            @Override
            public void onPause() {
                if (gecko != null) gecko.pause();
            }

            @Override
            public void onSkipToNext() {
                if (gecko != null) gecko.nextTrack();
            }

            @Override
            public void onSkipToPrevious() {
                if (gecko != null) gecko.previousTrack();
            }

            @Override
            public void onSeekTo(long pos) {
                if (gecko != null) gecko.seekTo(pos / 1000.0, false);
            }

            @Override
            public void onStop() {
                if (gecko != null) gecko.stop();
            }
        });
        session.setActive(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_UPDATE : intent.getAction();
        if (gecko != null && action != null) {
            switch (action) {
                case ACTION_PLAY: gecko.play(); break;
                case ACTION_PAUSE: gecko.pause(); break;
                case ACTION_NEXT: gecko.nextTrack(); break;
                case ACTION_PREV: gecko.previousTrack(); break;
                case ACTION_STOP: gecko.stop(); break;
                default: break;
            }
        }
        if (gecko == null || ACTION_STOP.equals(action)) {
            startForeground(ID, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(ID, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        if (!playing) stopForeground(STOP_FOREGROUND_DETACH);
        return START_NOT_STICKY;
    }

    private PendingIntent action(String a, int code) {
        return PendingIntent.getService(this, code, new Intent(this, MediaService.class).setAction(a),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private android.graphics.drawable.Icon icon(int res) {
        return android.graphics.drawable.Icon.createWithResource(this, res);
    }

    private Notification build() {
        MediaMetadata.Builder md = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist);
        if (art != null) md.putBitmap(MediaMetadata.METADATA_KEY_ART, art);
        session.setMetadata(md.build());
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_STOP | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS;
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(actions)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build());

        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title.isEmpty() ? "Воспроизведение" : title)
                .setContentText(artist)
                .setLargeIcon(art)
                .setOngoing(playing)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE))
                .setDeleteIntent(action(ACTION_STOP, 5))
                .addAction(new Notification.Action.Builder(icon(android.R.drawable.ic_media_previous), "Назад", action(ACTION_PREV, 1)).build())
                .addAction(playing
                        ? new Notification.Action.Builder(icon(android.R.drawable.ic_media_pause), "Пауза", action(ACTION_PAUSE, 2)).build()
                        : new Notification.Action.Builder(icon(android.R.drawable.ic_media_play), "Играть", action(ACTION_PLAY, 3)).build())
                .addAction(new Notification.Action.Builder(icon(android.R.drawable.ic_media_next), "Вперёд", action(ACTION_NEXT, 4)).build())
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));
        return b.build();
    }

    @Override
    public void onDestroy() {
        session.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
