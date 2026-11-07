package dev.antite.browser;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.widget.Toast;

import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSession.PermissionDelegate;
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission;
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.MediaSource;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

final class SitePrompts {
    static final int ANDROID_REQUEST = 4343;
    private static final int NOTIFICATIONS_REQUEST = 4344;
    private static final int ALLOW_ONCE = 4;

    private final Activity a;
    private final SitePerms perms;
    private PermissionDelegate.Callback pendingAndroid;

    SitePrompts(Activity a, SitePerms perms) {
        this.a = a;
        this.perms = perms;
    }

    PermissionDelegate delegateFor(Tab t) {
        return new PermissionDelegate() {
            @Override
            public void onAndroidPermissionsRequest(GeckoSession s, String[] list, Callback callback) {
                requestAndroid(list, callback);
            }

            @Override
            public void onMediaPermissionRequest(GeckoSession s, String uri, MediaSource[] video,
                                                 MediaSource[] audio, MediaCallback callback) {
                media(t, uri, find(video, MediaSource.SOURCE_CAMERA), find(audio, MediaSource.SOURCE_MICROPHONE),
                        callback);
            }

            @Override
            public GeckoResult<Integer> onContentPermissionRequest(GeckoSession s, ContentPermission perm) {
                return content(t, perm);
            }
        };
    }

    boolean allowPopup(Tab owner) {
        String h = owner == null ? "" : Urls.siteHost(owner.url);
        boolean ok = perms.decide(SitePerms.POPUPS, h) == SitePerms.ALLOW;
        if (!ok) Toast.makeText(a, "Всплывающее окно заблокировано", Toast.LENGTH_SHORT).show();
        return ok;
    }

    void requestNotifications(boolean onlyOnce) {
        if (onlyOnce) {
            android.content.SharedPreferences p = a.getSharedPreferences("antite", Activity.MODE_PRIVATE);
            if (p.getBoolean("askedNotif", false)) return;
            p.edit().putBoolean("askedNotif", true).apply();
        }
        if (a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            a.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATIONS_REQUEST);
        }
    }

    void onAndroidResult(int[] results) {
        PermissionDelegate.Callback cb = pendingAndroid;
        pendingAndroid = null;
        if (cb == null) return;
        boolean ok = results.length > 0;
        for (int r : results) ok &= r == PackageManager.PERMISSION_GRANTED;
        if (ok) {
            cb.grant();
        } else {
            cb.reject();
        }
    }

    private static MediaSource find(MediaSource[] list, int type) {
        if (list == null) return null;
        for (MediaSource m : list) {
            if (m.source == type) return m;
        }
        return null;
    }

    private void media(Tab t, String uri, MediaSource cam, MediaSource mic,
                       PermissionDelegate.MediaCallback callback) {
        String h = Urls.siteHost(uri);
        if (cam != null && perms.decide(SitePerms.CAMERA, h) == SitePerms.BLOCK) cam = null;
        if (mic != null && perms.decide(SitePerms.MIC, h) == SitePerms.BLOCK) mic = null;
        if (cam == null && mic == null) {
            callback.reject();
            return;
        }
        boolean askCam = cam != null && perms.decide(SitePerms.CAMERA, h) == SitePerms.ASK;
        boolean askMic = mic != null && perms.decide(SitePerms.MIC, h) == SitePerms.ASK;
        MediaSource fc = cam;
        MediaSource fm = mic;
        if (!askCam && !askMic) {
            callback.grant(fc, fm);
            return;
        }
        String what = askCam && askMic ? "доступ к камере и микрофону"
                : askCam ? "доступ к камере" : "доступ к микрофону";
        ask(askCam ? R.drawable.ic_videocam : R.drawable.ic_mic, h, what, answer -> {
            if ((answer == SitePerms.ALLOW || answer == SitePerms.BLOCK) && !t.incognito) {
                if (askCam) perms.setException(SitePerms.CAMERA, h, answer);
                if (askMic) perms.setException(SitePerms.MIC, h, answer);
            }
            boolean allow = answer == SitePerms.ALLOW || answer == ALLOW_ONCE;
            MediaSource gc = allow || !askCam ? fc : null;
            MediaSource gm = allow || !askMic ? fm : null;
            if (gc == null && gm == null) {
                callback.reject();
            } else {
                callback.grant(gc, gm);
            }
        });
    }

    private GeckoResult<Integer> content(Tab t, ContentPermission perm) {
        SitePerms.Cat c = SitePerms.forGecko(perm.permission);
        if (c == null) return GeckoResult.fromValue(ContentPermission.VALUE_DENY);
        String h = Urls.siteHost(perm.uri);
        if (c == SitePerms.AUTOPLAY) {
            perms.forgetAuto(perm);
            return GeckoResult.fromValue(perms.autoplay(h, perm.permission));
        }
        boolean fromException = perms.exception(c, h) >= 0;
        int mode = perms.decide(c, h);
        if (mode != SitePerms.ASK) {
            if (!fromException) perms.forgetAuto(perm);
            return GeckoResult.fromValue(mode == SitePerms.ALLOW
                    ? ContentPermission.VALUE_ALLOW : ContentPermission.VALUE_DENY);
        }
        GeckoResult<Integer> r = new GeckoResult<>();
        ask(icon(c), h, c.request, answer -> {
            if ((answer == SitePerms.ALLOW || answer == SitePerms.BLOCK) && !t.incognito) {
                perms.setException(c, h, answer);
            } else {
                perms.forgetAuto(perm);
            }
            boolean allow = answer == SitePerms.ALLOW || answer == ALLOW_ONCE;
            if (allow && c == SitePerms.NOTIFICATIONS) requestNotifications(false);
            r.complete(allow ? ContentPermission.VALUE_ALLOW : ContentPermission.VALUE_DENY);
        });
        return r;
    }

    private void requestAndroid(String[] list, PermissionDelegate.Callback callback) {
        List<String> need = new ArrayList<>();
        if (list != null) {
            for (String p : list) {
                if (a.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
            }
        }
        if (need.isEmpty()) {
            callback.grant();
            return;
        }
        if (pendingAndroid != null) pendingAndroid.reject();
        pendingAndroid = callback;
        a.requestPermissions(need.toArray(new String[0]), ANDROID_REQUEST);
    }

    private void ask(int icon, String host, String what, IntConsumer answer) {
        int[] result = {-1};
        new Sheet(a)
                .setIcon(icon)
                .setTitle(host + " запрашивает " + what)
                .setStacked(true)
                .setPositiveButton("Разрешить при посещении сайта", (d, w) -> result[0] = SitePerms.ALLOW)
                .setNeutralButton("Разрешить в этот раз", (d, w) -> result[0] = ALLOW_ONCE)
                .setNegativeButton("Не разрешать", (d, w) -> result[0] = SitePerms.BLOCK)
                .setOnDismissListener(d -> answer.accept(result[0]))
                .show();
    }

    private static int icon(SitePerms.Cat c) {
        if (c == SitePerms.LOCATION) return R.drawable.ic_location_on;
        if (c == SitePerms.NOTIFICATIONS) return R.drawable.ic_notifications;
        if (c == SitePerms.LNA) return R.drawable.ic_lan;
        if (c == SitePerms.XR) return R.drawable.ic_view_in_ar;
        if (c == SitePerms.STORAGE) return R.drawable.ic_database;
        if (c == SitePerms.DRM) return R.drawable.ic_shield_lock;
        return R.drawable.ic_public;
    }
}
