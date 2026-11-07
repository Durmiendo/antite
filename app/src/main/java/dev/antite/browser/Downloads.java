package dev.antite.browser;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.webkit.URLUtil;
import android.widget.Toast;

import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.WebResponse;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class Downloads {
    static final int REQUEST = 4250;

    private final Activity a;
    private final Db db;
    private final Runnable onFinished;
    private final ExecutorService io = Executors.newCachedThreadPool();
    private final Handler main = new Handler(Looper.getMainLooper());
    private InputStream pendingBody;
    private String pendingName;
    private String pendingMime;

    Downloads(Activity a, Db db, Runnable onFinished) {
        this.a = a;
        this.db = db;
        this.onFinished = onFinished;
    }

    void start(WebResponse response, boolean askTarget) {
        if (response.body == null) return;
        String disposition = header(response, "content-disposition");
        String mime = header(response, "content-type");
        String name = URLUtil.guessFileName(response.uri, disposition, mime);
        String mimeType = mime == null ? "application/octet-stream" : mime.split(";")[0].trim();
        if (!askTarget) {
            save(response.body, name, mimeType, null);
            return;
        }
        closeQuietly(pendingBody);
        pendingBody = response.body;
        pendingName = name;
        pendingMime = mimeType;
        a.startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(mimeType)
                .putExtra(Intent.EXTRA_TITLE, name), REQUEST);
    }

    void onTarget(int result, Intent data) {
        InputStream body = pendingBody;
        pendingBody = null;
        if (body == null) return;
        Uri target = result == Activity.RESULT_OK && data != null ? data.getData() : null;
        if (target == null) {
            closeQuietly(body);
            return;
        }
        save(body, pendingName, pendingMime, target);
    }

    void savePdf(Tab t, GeckoSession s) {
        String base = (t.title.isEmpty() ? Urls.host(t.url) : t.title).replaceAll("[\\\\/:*?\"<>|]", "_");
        if (base.length() > 80) base = base.substring(0, 80);
        String name = base + ".pdf";
        s.saveAsPdf().accept(in -> {
            if (in != null) save(in, name, "application/pdf", null);
        }, e -> Toast.makeText(a, "Не удалось сохранить PDF", Toast.LENGTH_SHORT).show());
    }

    void open(Db.Entry e) {
        if (e.url == null) {
            Toast.makeText(a, "Файл ещё не готов", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            a.startActivity(new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(Uri.parse(e.url), mime(e))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (Exception ex) {
            Toast.makeText(a, "Нет приложения, чтобы открыть этот файл", Toast.LENGTH_SHORT).show();
        }
    }

    void share(Db.Entry e) {
        if (e.url == null) return;
        a.startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND)
                .setType(mime(e))
                .putExtra(Intent.EXTRA_STREAM, Uri.parse(e.url))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null));
    }

    static String status(Db.Entry e) {
        String s = e.extra == null ? "" : e.extra.split("\\|", -1)[0];
        return "done".equals(s) ? "Скачано" : "failed".equals(s) ? "Ошибка" : "Загружается";
    }

    private static String mime(Db.Entry e) {
        String[] parts = e.extra == null ? new String[0] : e.extra.split("\\|", -1);
        return parts.length > 1 && !parts[1].isEmpty() ? parts[1] : "*/*";
    }

    private void save(InputStream body, String name, String mimeType, Uri chosen) {
        Toast.makeText(a, "Загрузка: " + name, Toast.LENGTH_SHORT).show();
        ContentResolver cr = a.getApplicationContext().getContentResolver();
        io.execute(() -> {
            long id = db.addDownload(name, mimeType);
            Uri target = chosen;
            if (target == null) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                v.put(MediaStore.Downloads.MIME_TYPE, mimeType);
                v.put(MediaStore.Downloads.IS_PENDING, 1);
                target = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            }
            boolean ok = false;
            if (target != null) {
                try (InputStream in = body; OutputStream out = cr.openOutputStream(target)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    ok = true;
                } catch (Exception e) {
                    android.util.Log.w("Antite", "download failed: " + name, e);
                }
                if (chosen == null) {
                    if (ok) {
                        ContentValues done = new ContentValues();
                        done.put(MediaStore.Downloads.IS_PENDING, 0);
                        cr.update(target, done, null, null);
                    } else {
                        cr.delete(target, null, null);
                    }
                }
            } else {
                closeQuietly(body);
            }
            db.finishDownload(id, ok ? target.toString() : null, ok);
            String msg = ok ? "Скачано: " + name : "Ошибка загрузки: " + name;
            main.post(() -> {
                Toast.makeText(a.getApplicationContext(), msg, Toast.LENGTH_LONG).show();
                onFinished.run();
            });
        });
    }

    private static String header(WebResponse r, String name) {
        for (java.util.Map.Entry<String, String> e : r.headers.entrySet()) {
            if (name.equalsIgnoreCase(e.getKey())) return e.getValue();
        }
        return null;
    }

    private static void closeQuietly(InputStream in) {
        if (in == null) return;
        try {
            in.close();
        } catch (IOException ignored) {
        }
    }
}
