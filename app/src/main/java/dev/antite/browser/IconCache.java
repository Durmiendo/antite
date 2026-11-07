package dev.antite.browser;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;

import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoWebExecutor;
import org.mozilla.geckoview.WebRequest;
import org.mozilla.geckoview.WebResponse;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class IconCache {
    interface Listener {
        void onIcon(String host);
    }

    private static final String TAG = "IconCache";
    private static final int SIZE = 96;
    private static final int MAX_BYTES = 512 * 1024;
    private static final long REFRESH_MS = 7L * 24 * 3600 * 1000;
    private static final Bitmap MISSING = Bitmap.createBitmap(1, 1, Bitmap.Config.ALPHA_8);

    private final File dir;
    private final GeckoWebExecutor web;
    private final LruCache<String, Bitmap> mem = new LruCache<>(300);
    private final Set<String> inFlight = new HashSet<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Listener listener;
    private final android.content.SharedPreferences themes;
    private final java.util.Map<String, Integer> iconColors = new java.util.HashMap<>();

    IconCache(Context c, GeckoRuntime runtime) {
        themes = c.getSharedPreferences("themes", Context.MODE_PRIVATE);
        dir = new File(c.getFilesDir(), "icons");
        dir.mkdirs();
        web = new GeckoWebExecutor(runtime);
    }

    void setListener(Listener l) {
        listener = l;
    }

    private File file(String host) {
        return new File(dir, host.replaceAll("[^a-zA-Z0-9.-]", "_") + ".png");
    }

    Bitmap get(String host) {
        if (host == null || host.isEmpty()) return null;
        Bitmap b = mem.get(host);
        if (b == null) {
            mem.put(host, MISSING);
            io.execute(() -> {
                File f = file(host);
                Bitmap d = f.exists() ? BitmapFactory.decodeFile(f.getPath()) : null;
                if (d == null) return;
                main.post(() -> {
                    mem.put(host, d);
                    iconColors.remove(host);
                    if (listener != null) listener.onIcon(host);
                });
            });
            return null;
        }
        return b == MISSING ? null : b;
    }

    void setTheme(String host, int color) {
        if (host == null || host.isEmpty()) return;
        if (themes.getInt(host, 0) == color) return;
        if (color == 0) {
            themes.edit().remove(host).apply();
        } else {
            themes.edit().putInt(host, color).apply();
        }
        if (listener != null) listener.onIcon(host);
    }

    int color(String host) {
        if (host == null || host.isEmpty()) return 0;
        int t = themes.getInt(host, 0);
        if (t != 0) return t;
        Integer c = iconColors.get(host);
        if (c == null) {
            Bitmap b = get(host);
            if (b == null) return 0;
            c = dominant(b);
            iconColors.put(host, c);
        }
        return c;
    }

    private static int dominant(Bitmap b) {
        long r = 0, g = 0, bl = 0, n = 0;
        float[] hsv = new float[3];
        int w = b.getWidth(), h = b.getHeight();
        for (int y = 0; y < h; y += 2) {
            for (int x = 0; x < w; x += 2) {
                int p = b.getPixel(x, y);
                if (android.graphics.Color.alpha(p) < 160) continue;
                android.graphics.Color.colorToHSV(p, hsv);
                if (hsv[1] < 0.3f || hsv[2] < 0.25f) continue;
                r += android.graphics.Color.red(p);
                g += android.graphics.Color.green(p);
                bl += android.graphics.Color.blue(p);
                n++;
            }
        }
        if (n < (w / 2) * (h / 2) / 12) return 0;
        return android.graphics.Color.rgb((int) (r / n), (int) (g / n), (int) (bl / n));
    }

    void onPageIcon(String host, String iconUrl, String origin) {
        if (host == null || host.isEmpty() || iconUrl == null) return;
        io.execute(() -> {
            File f = file(host);
            if (f.exists() && System.currentTimeMillis() - f.lastModified() < REFRESH_MS) return;
            synchronized (inFlight) {
                if (!inFlight.add(host)) return;
            }
            main.post(() -> fetch(host, iconUrl, origin + "/favicon.ico"));
        });
    }

    private void fetch(String host, String url, String fallback) {
        web.fetch(new WebRequest.Builder(url).build()).accept(
                resp -> io.execute(() -> decode(host, resp, url, fallback)),
                e -> retry(host, url, fallback));
    }

    private void retry(String host, String failed, String fallback) {
        if (fallback != null && !fallback.equals(failed)) {
            fetch(host, fallback, null);
        } else {
            synchronized (inFlight) {
                inFlight.remove(host);
            }
        }
    }

    private void decode(String host, WebResponse resp, String url, String fallback) {
        Bitmap bmp = null;
        if (resp != null && resp.statusCode >= 200 && resp.statusCode < 300 && resp.body != null) {
            try (InputStream in = resp.body) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0 && bo.size() <= MAX_BYTES) bo.write(buf, 0, n);
                byte[] data = bo.toByteArray();
                bmp = BitmapFactory.decodeByteArray(data, 0, data.length);
            } catch (Exception e) {
                Log.w(TAG, "icon read failed " + host, e);
            }
        }
        if (bmp == null) {
            main.post(() -> retry(host, url, fallback));
            return;
        }
        Bitmap scaled = Bitmap.createScaledBitmap(bmp, SIZE, SIZE, true);
        try (FileOutputStream out = new FileOutputStream(file(host))) {
            scaled.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (Exception e) {
            Log.w(TAG, "icon write failed " + host, e);
        }
        main.post(() -> {
            synchronized (inFlight) {
                inFlight.remove(host);
            }
            mem.put(host, scaled);
            iconColors.remove(host);
            if (listener != null) listener.onIcon(host);
        });
    }

    void clear() {
        mem.evictAll();
        iconColors.clear();
        themes.edit().clear().apply();
        io.execute(() -> {
            File[] files = dir.listFiles();
            if (files != null) for (File f : files) f.delete();
        });
    }
}
