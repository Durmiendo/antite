package dev.antite.browser;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class Suggestions extends ScrollView {
    interface Host {
        void go(String input);

        void fill(String text);
    }

    private static final String[] SUGGEST_URLS = {
            "https://duckduckgo.com/ac/?type=list&q=",
            "https://suggestqueries.google.com/complete/search?client=firefox&q=",
            "https://suggest.yandex.ru/suggest-ff.cgi?part=",
            "https://api.bing.com/osjson.aspx?query=",
            "https://duckduckgo.com/ac/?type=list&q=",
    };

    private final Activity a;
    private final Db db;
    private final SharedPreferences prefs;
    private final Host host;
    private final LinearLayout list;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private String text = "";
    private int generation;
    private boolean incognito;
    private final IconCache icons;

    Suggestions(Activity a, Db db, SharedPreferences prefs, IconCache icons, Host host) {
        super(a);
        this.icons = icons;
        this.a = a;
        this.db = db;
        this.prefs = prefs;
        this.host = host;
        setBackgroundColor(Ui.TOOLBAR);
        list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        addView(list, new LayoutParams(-1, -2));
    }

    void update(String input, boolean incognitoTab) {
        text = input.trim();
        incognito = incognitoTab;
        int gen = ++generation;
        if (text.isEmpty()) {
            list.removeAllViews();
            setVisibility(GONE);
            return;
        }
        String q = text;
        List<Db.Entry> local = new ArrayList<>();
        List<String> remote = new ArrayList<>();
        if (incognito) {
            render(local, remote);
        } else {
            db.read(() -> db.suggest(q, 5), found -> {
                if (gen != generation) return;
                local.addAll(found);
                render(local, remote);
            });
        }
        if (prefs.getBoolean("searchSuggest", true)) {
            main.postDelayed(() -> {
                if (gen != generation) return;
                net.execute(() -> {
                    List<String> fetched = fetch(q);
                    main.post(() -> {
                        if (gen != generation) return;
                        remote.addAll(fetched);
                        render(local, remote);
                    });
                });
            }, 150);
        }
    }

    private List<String> fetch(String q) {
        List<String> out = new ArrayList<>();
        HttpURLConnection c = null;
        try {
            int engine = Math.min(prefs.getInt("engine", 0), SUGGEST_URLS.length - 1);
            URL url = new URL(SUGGEST_URLS[engine] + URLEncoder.encode(q, "UTF-8"));
            c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(3000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile) Antite");
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                JSONArray arr = new JSONArray(bo.toString(StandardCharsets.UTF_8.name()));
                JSONArray items = arr.getJSONArray(1);
                for (int i = 0; i < items.length() && out.size() < 6; i++) out.add(items.getString(i));
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.disconnect();
        }
        return out;
    }

    private void render(List<Db.Entry> local, List<String> remote) {
        list.removeAllViews();
        boolean looksUrl = !text.contains(" ") && text.contains(".") && !text.endsWith(".");
        if (looksUrl || text.contains("://")) {
            row(R.drawable.ic_public, null, text, "Перейти", () -> host.go(text), null);
        } else {
            row(R.drawable.ic_search, null, text, "Поиск", () -> host.go(text), null);
        }
        for (Db.Entry e : local) {
            String title = e.title == null || e.title.isEmpty() ? e.url : e.title;
            row("b".equals(e.extra) ? R.drawable.ic_star : R.drawable.ic_history,
                    icons.get(Urls.host(e.url)), title, e.url,
                    () -> host.go(e.url), () -> host.fill(e.url));
        }
        for (String s : remote) {
            if (s.equalsIgnoreCase(text)) continue;
            row(R.drawable.ic_search, null, s, null, () -> host.go(s), () -> host.fill(s));
        }
        setVisibility(VISIBLE);
    }

    private void row(int icon, android.graphics.Bitmap image, String title, String sub, Runnable click, Runnable fill) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(Ui.dp(a, 56));
        r.setPadding(Ui.dp(a, 20), Ui.dp(a, 6), Ui.dp(a, 4), Ui.dp(a, 6));
        r.setBackground(Ui.ripple(null, Ui.round(0xFFFFFFFF, 0)));
        ImageView iv = new ImageView(a);
        if (image != null) {
            iv.setImageBitmap(image);
        } else {
            iv.setImageResource(icon);
            Ui.tint(iv, Ui.ICON);
        }
        r.addView(iv, new LinearLayout.LayoutParams(Ui.dp(a, 22), Ui.dp(a, 22)));
        LinearLayout texts = new LinearLayout(a);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(a);
        t.setText(title);
        t.setTextSize(16);
        t.setTextColor(Ui.TEXT);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(t);
        if (sub != null) {
            TextView s = new TextView(a);
            s.setText(sub);
            s.setTextSize(13);
            s.setTextColor(Ui.TEXT_SECONDARY);
            s.setSingleLine(true);
            s.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            texts.addView(s);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.setMarginStart(Ui.dp(a, 16));
        r.addView(texts, lp);
        if (fill != null) {
            ImageView up = Ui.iconButton(a, R.drawable.ic_arrow_back, 44);
            up.setRotation(45);
            up.setContentDescription("Вставить в строку");
            up.setOnClickListener(v -> fill.run());
            r.addView(up, new LinearLayout.LayoutParams(Ui.dp(a, 44), Ui.dp(a, 44)));
        }
        r.setOnClickListener(v -> click.run());
        list.addView(r, new LinearLayout.LayoutParams(-1, -2));
    }
}
