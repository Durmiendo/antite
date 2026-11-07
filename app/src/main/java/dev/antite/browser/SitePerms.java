package dev.antite.browser;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONException;
import org.json.JSONObject;
import org.mozilla.geckoview.GeckoPreferenceController;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession.PermissionDelegate;
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class SitePerms {
    static final int ASK = 0;
    static final int ALLOW = 1;
    static final int BLOCK = 2;
    static final int MUTED = 3;

    static final class Cat {
        final String key;
        final String title;
        final String[] gecko;
        final String[] labels;
        final int[] values;
        final int def;
        final String request;
        final String note;

        Cat(String key, String title, String[] gecko, String[] labels, int[] values, int def,
            String request, String note) {
            this.key = key;
            this.title = title;
            this.gecko = gecko;
            this.labels = labels;
            this.values = values;
            this.def = def;
            this.request = request;
            this.note = note;
        }

        boolean perSite() {
            return note == null;
        }
    }

    private static final String[] ASK_BLOCK = {"Спрашивать", "Блокировать"};
    private static final int[] V_ASK_BLOCK = {ASK, BLOCK};
    private static final String[] ALLOW_BLOCK = {"Разрешать", "Блокировать"};
    private static final int[] V_ALLOW_BLOCK = {ALLOW, BLOCK};
    private static final String[] ALLOW_ASK_BLOCK = {"Разрешать", "Спрашивать", "Блокировать"};
    private static final int[] V_ALLOW_ASK_BLOCK = {ALLOW, ASK, BLOCK};

    static final Cat LOCATION = new Cat("location", "Местоположение", new String[]{"geolocation"},
            ASK_BLOCK, V_ASK_BLOCK, 0, "ваше местоположение", null);
    static final Cat CAMERA = new Cat("camera", "Камера", null,
            ASK_BLOCK, V_ASK_BLOCK, 0, "доступ к камере", null);
    static final Cat MIC = new Cat("mic", "Микрофон", null,
            ASK_BLOCK, V_ASK_BLOCK, 0, "доступ к микрофону", null);
    static final Cat NOTIFICATIONS = new Cat("notifications", "Уведомления", new String[]{"desktop-notification"},
            ASK_BLOCK, V_ASK_BLOCK, 0, "разрешение показывать уведомления", null);
    static final Cat JS = new Cat("js", "JavaScript", null,
            ALLOW_BLOCK, V_ALLOW_BLOCK, 0, null, null);
    static final Cat POPUPS = new Cat("popups", "Всплывающие окна", null,
            ALLOW_BLOCK, V_ALLOW_BLOCK, 1, null, null);
    static final Cat AUTOPLAY = new Cat("autoplay", "Автовоспроизведение",
            new String[]{"autoplay-media-audible", "autoplay-media-inaudible"},
            new String[]{"Разрешать", "Только без звука", "Блокировать"}, new int[]{ALLOW, MUTED, BLOCK}, 1,
            null, null);
    static final Cat DRM = new Cat("drm", "Защищённый контент", new String[]{"media-key-system-access"},
            ALLOW_ASK_BLOCK, V_ALLOW_ASK_BLOCK, 0, "воспроизведение защищённого контента (DRM)", null);
    static final Cat STORAGE = new Cat("storage", "Постоянное хранилище", new String[]{"persistent-storage"},
            ALLOW_ASK_BLOCK, V_ALLOW_ASK_BLOCK, 0, "постоянное хранилище данных", null);
    static final Cat LNA = new Cat("lna", "Доступ к локальной сети", new String[]{"local-network", "loopback-network"},
            ASK_BLOCK, V_ASK_BLOCK, 0, "доступ к устройствам в локальной сети", null);
    static final Cat XR = new Cat("xr", "Виртуальная реальность", new String[]{"xr"},
            ASK_BLOCK, V_ASK_BLOCK, 0, "доступ к VR-устройствам", null);
    static final Cat SENSORS = new Cat("sensors", "Датчики движения", null,
            ALLOW_BLOCK, V_ALLOW_BLOCK, 0, null, "Движок применяет это сразу ко всем сайтам, исключений нет");

    static final Cat[] ALL = {LOCATION, CAMERA, MIC, NOTIFICATIONS, JS, POPUPS, AUTOPLAY, DRM, STORAGE,
            LNA, XR, SENSORS};

    private final SharedPreferences prefs;
    private final SharedPreferences sites;
    private final Handler main = new Handler(Looper.getMainLooper());
    private GeckoRuntime runtime;

    SitePerms(Context c, SharedPreferences prefs) {
        this.prefs = prefs;
        this.sites = c.getSharedPreferences("siteperms", Context.MODE_PRIVATE);
        migrate();
    }

    void attach(GeckoRuntime runtime) {
        this.runtime = runtime;
        applyDefaults();
        if (!prefs.getBoolean("permsWiped", false)) {
            runtime.getStorageController().getAllPermissions().accept(list -> {
                if (list != null) {
                    for (ContentPermission p : list) {
                        if (p.permission != PermissionDelegate.PERMISSION_TRACKING) {
                            runtime.getStorageController().setPermission(p, 0);
                        }
                    }
                }
                prefs.edit().putBoolean("permsWiped", true).apply();
            }, e -> { });
        }
    }

    private void migrate() {
        SharedPreferences.Editor e = prefs.edit();
        if (prefs.contains("js")) {
            if (!prefs.getBoolean("js", true)) e.putInt("cat_js", 1);
            e.remove("js");
        }
        if (prefs.contains("autoplay")) {
            e.putInt("cat_autoplay", prefs.getInt("autoplay", 1));
            e.remove("autoplay");
        }
        e.apply();
    }

    int defaultIndex(Cat c) {
        return Math.min(prefs.getInt("cat_" + c.key, c.def), c.values.length - 1);
    }

    int defaultValue(Cat c) {
        return c.values[defaultIndex(c)];
    }

    void setDefaultIndex(Cat c, int index) {
        prefs.edit().putInt("cat_" + c.key, index).apply();
        applyDefaults();
    }

    void applyDefaults() {
        if (runtime == null) return;
        for (Cat c : ALL) {
            if (c.gecko == null || c == AUTOPLAY) continue;
            int v = defaultValue(c);
            for (String type : c.gecko) {
                GeckoPreferenceController.setGeckoPref("permissions.default." + type, v,
                        GeckoPreferenceController.PREF_BRANCH_USER);
            }
        }
        boolean sensors = defaultValue(SENSORS) == ALLOW;
        GeckoPreferenceController.setGeckoPref("device.sensors.motion.enabled", sensors,
                GeckoPreferenceController.PREF_BRANCH_USER);
        GeckoPreferenceController.setGeckoPref("device.sensors.orientation.enabled", sensors,
                GeckoPreferenceController.PREF_BRANCH_USER);
    }

    int exception(Cat c, String host) {
        if (host == null || host.isEmpty()) return -1;
        return sites.getInt(c.key + "|" + host, -1);
    }

    int decide(Cat c, String host) {
        int e = exception(c, host);
        return e >= 0 ? e : defaultValue(c);
    }

    void setException(Cat c, String host, int value) {
        if (host == null || host.isEmpty()) return;
        String k = c.key + "|" + host;
        if (value < 0) {
            sites.edit().remove(k).apply();
        } else {
            sites.edit().putInt(k, value).apply();
        }
        if (c.gecko != null && c != AUTOPLAY && runtime != null) {
            int gv = value == ALLOW ? ContentPermission.VALUE_ALLOW : value == BLOCK ? ContentPermission.VALUE_DENY : 0;
            runtime.getStorageController().getAllPermissions().accept(list -> {
                if (list != null) {
                    for (ContentPermission p : list) {
                        if (p.privateMode || forGecko(p.permission) != c) continue;
                        String h = android.net.Uri.parse(p.uri).getHost();
                        if (host.equalsIgnoreCase(h)) runtime.getStorageController().setPermission(p, gv);
                    }
                }
                if (gv == 0) return;
                for (String type : c.gecko) {
                    for (String scheme : new String[]{"https://", "http://"}) {
                        ContentPermission p = make(scheme + host, type);
                        if (p != null) runtime.getStorageController().setPermission(p, gv);
                    }
                }
            }, e -> { });
        }
    }

    void forgetAuto(ContentPermission p) {
        if (runtime == null) return;
        main.postDelayed(() -> runtime.getStorageController().setPermission(p, 0), 1500);
    }

    Map<String, Integer> exceptions(Cat c) {
        Map<String, Integer> out = new TreeMap<>();
        String prefix = c.key + "|";
        for (Map.Entry<String, ?> e : sites.getAll().entrySet()) {
            if (e.getKey().startsWith(prefix) && e.getValue() instanceof Integer) {
                out.put(e.getKey().substring(prefix.length()), (Integer) e.getValue());
            }
        }
        return out;
    }

    List<String> hosts() {
        TreeMap<String, Boolean> set = new TreeMap<>();
        for (String k : sites.getAll().keySet()) {
            int i = k.indexOf('|');
            if (i > 0) set.put(k.substring(i + 1), true);
        }
        return new ArrayList<>(set.keySet());
    }

    void forgetSite(String host) {
        SharedPreferences.Editor e = sites.edit();
        for (Cat c : ALL) e.remove(c.key + "|" + host);
        e.apply();
        if (runtime == null) return;
        runtime.getStorageController().getAllPermissions().accept(list -> {
            if (list == null) return;
            for (ContentPermission p : list) {
                if (host.equalsIgnoreCase(android.net.Uri.parse(p.uri).getHost())) {
                    runtime.getStorageController().setPermission(p, 0);
                }
            }
        }, err -> { });
    }

    static ContentPermission make(String origin, String type) {
        try {
            String principal = new JSONObject().put("1", new JSONObject().put("0", origin + "/")).toString();
            JSONObject j = new JSONObject()
                    .put("uri", origin + "/")
                    .put("principal", principal)
                    .put("privateMode", false)
                    .put("perm", type)
                    .put("value", ContentPermission.VALUE_PROMPT);
            return ContentPermission.fromJson(j);
        } catch (JSONException e) {
            return null;
        }
    }

    String label(Cat c, int value) {
        for (int i = 0; i < c.values.length; i++) {
            if (c.values[i] == value) return c.labels[i];
        }
        return value == ALLOW ? "Разрешать" : value == BLOCK ? "Блокировать" : "Спрашивать";
    }

    static Cat forGecko(int permission) {
        switch (permission) {
            case PermissionDelegate.PERMISSION_GEOLOCATION:
                return LOCATION;
            case PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION:
                return NOTIFICATIONS;
            case PermissionDelegate.PERMISSION_PERSISTENT_STORAGE:
                return STORAGE;
            case PermissionDelegate.PERMISSION_XR:
                return XR;
            case PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE:
            case PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE:
                return AUTOPLAY;
            case PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS:
                return DRM;
            case PermissionDelegate.PERMISSION_LOCAL_DEVICE_ACCESS:
            case PermissionDelegate.PERMISSION_LOCAL_NETWORK_ACCESS:
                return LNA;
            default:
                return null;
        }
    }
}
