package dev.antite.browser;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import org.mozilla.geckoview.WebNotification;
import org.mozilla.geckoview.WebNotificationDelegate;

import java.util.HashMap;
import java.util.Map;

final class WebNotifications implements WebNotificationDelegate {
    static final String ACTION_CLICK = "dev.antite.browser.WEB_NOTIFICATION";
    static final String EXTRA_ID = "notificationId";
    private static final String CHANNEL = "web";
    private static final Map<Integer, WebNotification> shown = new HashMap<>();

    private final Context app;
    private final NotificationManager nm;

    WebNotifications(Context app) {
        this.app = app;
        nm = app.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Уведомления сайтов",
                NotificationManager.IMPORTANCE_DEFAULT));
    }

    private static int id(WebNotification n) {
        return (n.origin + "|" + n.tag).hashCode();
    }

    @Override
    public void onShowNotification(WebNotification n) {
        int id = id(n);
        shown.put(id, n);
        Intent open = new Intent(app, MainActivity.class)
                .setAction(ACTION_CLICK)
                .putExtra(EXTRA_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        String site = n.origin == null ? null : Uri.parse(n.origin).getHost();
        Notification.Builder b = new Notification.Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.ic_public)
                .setContentTitle(n.title)
                .setContentText(n.text)
                .setStyle(new Notification.BigTextStyle().bigText(n.text))
                .setSubText(site)
                .setAutoCancel(true)
                .setOnlyAlertOnce(n.silent)
                .setContentIntent(PendingIntent.getActivity(app, id, open,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        try {
            nm.notify(CHANNEL, id, b.build());
        } catch (SecurityException e) {
            android.util.Log.w("Antite", "notification denied", e);
        }
    }

    @Override
    public void onCloseNotification(WebNotification n) {
        int id = id(n);
        shown.remove(id);
        nm.cancel(CHANNEL, id);
    }

    static void click(Intent intent) {
        WebNotification n = shown.remove(intent.getIntExtra(EXTRA_ID, 0));
        if (n != null) n.click();
    }
}
