package dev.antite.browser;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class TabStore {
    private static final String TAG = "TabStore";

    private final File file;
    private final File thumbs;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());

    TabStore(Context c) {
        file = new File(c.getFilesDir(), "tabs.json");
        thumbs = new File(c.getFilesDir(), "thumbs");
        thumbs.mkdirs();
    }

    static final class Loaded {
        final List<Tab> tabs = new ArrayList<>();
        long selected;
    }

    Loaded load() {
        Loaded out = new Loaded();
        if (!file.exists()) return out;
        try {
            JSONObject root = new JSONObject(
                    new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            out.selected = root.optLong("selected");
            JSONArray arr = root.getJSONArray("tabs");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Tab t = new Tab(o.getLong("id"), false);
                t.url = o.optString("url");
                t.title = o.optString("title");
                t.state = o.has("state") ? o.getString("state") : null;
                t.desktop = o.optBoolean("desktop");
                t.lastUsed = o.optLong("lastUsed");
                out.tabs.add(t);
            }
        } catch (Exception e) {
            Log.w(TAG, "tabs.json unreadable, starting fresh", e);
        }
        return out;
    }

    void save(List<Tab> tabs, Tab selected) {
        JSONObject root = new JSONObject();
        try {
            JSONArray arr = new JSONArray();
            for (Tab t : tabs) {
                if (t.incognito) continue;
                JSONObject o = new JSONObject();
                o.put("id", t.id);
                o.put("url", t.url);
                o.put("title", t.title);
                if (t.state != null) o.put("state", t.state);
                o.put("desktop", t.desktop);
                o.put("lastUsed", t.lastUsed);
                arr.put(o);
            }
            root.put("tabs", arr);
            root.put("selected", selected != null && !selected.incognito ? selected.id : 0);
        } catch (Exception e) {
            Log.e(TAG, "serialize failed", e);
            return;
        }
        String json = root.toString();
        io.execute(() -> {
            File tmp = new File(file.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            } catch (Exception e) {
                Log.e(TAG, "write failed", e);
                return;
            }
            if (!tmp.renameTo(file)) Log.e(TAG, "rename failed");
        });
    }

    void saveThumb(Tab t) {
        if (t.incognito || t.thumb == null) return;
        Bitmap b = t.thumb;
        long id = t.id;
        io.execute(() -> {
            try (FileOutputStream out = new FileOutputStream(new File(thumbs, id + ".jpg"))) {
                b.compress(Bitmap.CompressFormat.JPEG, 70, out);
            } catch (Exception e) {
                Log.w(TAG, "thumb write failed", e);
            }
        });
    }

    void loadThumb(Tab t, Runnable onLoaded) {
        if (t.incognito || t.thumb != null || t.thumbRequested) return;
        t.thumbRequested = true;
        long id = t.id;
        io.execute(() -> {
            File f = new File(thumbs, id + ".jpg");
            Bitmap b = f.exists() ? BitmapFactory.decodeFile(f.getPath()) : null;
            if (b == null) return;
            main.post(() -> {
                if (t.thumb == null) t.thumb = b;
                onLoaded.run();
            });
        });
    }

    void deleteThumb(long id) {
        io.execute(() -> new File(thumbs, id + ".jpg").delete());
    }

    void flush() {
        try {
            io.submit(() -> { }).get();
        } catch (Exception ignored) {
        }
    }
}
