package dev.antite.browser;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class NewTabPage extends ScrollView {
    interface Host {
        void openUrl(String url, boolean newTab);
    }

    private static final int FREQUENT = 8;

    private final Activity a;
    private final Db db;
    private final SharedPreferences prefs;
    private final Host host;
    private final LinearLayout content;
    private final IconCache icons;

    NewTabPage(Activity a, Db db, SharedPreferences prefs, IconCache icons, Host host) {
        super(a);
        this.icons = icons;
        this.a = a;
        this.db = db;
        this.prefs = prefs;
        this.host = host;
        setBackgroundColor(Ui.PAGE);
        setFillViewport(true);
        content = new LinearLayout(a);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Ui.dp(a, 12), Ui.dp(a, 8), Ui.dp(a, 12), Ui.dp(a, 24));
        addView(content, new LayoutParams(-1, -2));
    }

    private final Runnable reload = this::load;
    private List<Db.Entry> pins = new ArrayList<>();
    private List<Db.Site> frequent = new ArrayList<>();
    private int generation;

    void refresh() {
        removeCallbacks(reload);
        postDelayed(reload, generation == 0 ? 0 : 150);
    }

    private void load() {
        int gen = ++generation;
        Set<String> hidden = new HashSet<>(prefs.getStringSet("hiddenHosts", new HashSet<>()));
        db.read(() -> {
            List<Db.Entry> p = db.pins();
            Set<String> skip = new HashSet<>(hidden);
            for (Db.Entry e : p) skip.add(Urls.host(e.url));
            return new Object[]{p, db.frequent(skip, FREQUENT)};
        }, r -> {
            if (gen != generation) return;
            @SuppressWarnings("unchecked") List<Db.Entry> p = (List<Db.Entry>) r[0];
            @SuppressWarnings("unchecked") List<Db.Site> f = (List<Db.Site>) r[1];
            pins = p;
            frequent = f;
            redraw();
        });
    }

    void redraw() {
        content.removeAllViews();
        int cols = Math.max(4, Math.min(8, (getWidth() > 0 ? getWidth() : a.getResources().getDisplayMetrics().widthPixels)
                / Ui.dp(a, 88)));
        header("Закреплённые");
        List<android.view.View> tiles = new ArrayList<>();
        for (Db.Entry p : pins) {
            tiles.add(tile(p.title, p.url, () -> host.openUrl(p.url, false), () -> pinActions(p)));
        }
        tiles.add(addTile());
        grid(tiles, cols);
        if (!frequent.isEmpty()) {
            header("Часто посещаемые");
            List<android.view.View> ft = new ArrayList<>();
            for (Db.Site s : frequent) {
                ft.add(tile(s.host, s.url, () -> host.openUrl(s.url, false), () -> frequentActions(s)));
            }
            grid(ft, cols);
        }
    }

    private void change(Runnable write) {
        db.run(write);
        refresh();
    }

    private void header(String text) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextSize(14);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(Ui.TEXT_SECONDARY);
        t.setPadding(Ui.dp(a, 8), Ui.dp(a, 16), Ui.dp(a, 8), Ui.dp(a, 8));
        content.addView(t);
    }

    private void grid(List<android.view.View> tiles, int cols) {
        LinearLayout row = null;
        for (int i = 0; i < tiles.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(a);
                row.setOrientation(LinearLayout.HORIZONTAL);
                content.addView(row, new LinearLayout.LayoutParams(-1, -2));
            }
            row.addView(tiles.get(i), new LinearLayout.LayoutParams(0, -2, 1f));
        }
        if (row != null) {
            for (int k = tiles.size() % cols; k != 0 && k < cols; k++) {
                row.addView(new android.view.View(a), new LinearLayout.LayoutParams(0, 1, 1f));
            }
        }
    }

    private LinearLayout tileFrame() {
        LinearLayout t = new LinearLayout(a);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setPadding(Ui.dp(a, 4), Ui.dp(a, 8), Ui.dp(a, 4), Ui.dp(a, 8));
        t.setBackground(Ui.ripple(null, Ui.round(0xFFFFFFFF, Ui.dp(a, 12))));
        return t;
    }

    private void label(LinearLayout t, String text) {
        TextView l = new TextView(a);
        l.setText(text);
        l.setTextSize(12);
        l.setTextColor(Ui.TEXT);
        l.setGravity(Gravity.CENTER);
        l.setSingleLine(true);
        l.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(a, 6);
        t.addView(l, lp);
    }

    private android.view.View tile(String title, String url, Runnable click, Runnable longClick) {
        LinearLayout t = tileFrame();
        FrameLayout circle = new FrameLayout(a);
        circle.setBackground(Ui.round(Ui.CARD, Ui.dp(a, 28)));
        ImageView icon = new ImageView(a);
        Bitmap b = icons.get(Urls.host(url));
        if (b == null) b = Ui.letterIcon(a, Urls.host(url), 36);
        icon.setImageBitmap(b);
        circle.addView(icon, new FrameLayout.LayoutParams(Ui.dp(a, 36), Ui.dp(a, 36), Gravity.CENTER));
        t.addView(circle, new LinearLayout.LayoutParams(Ui.dp(a, 56), Ui.dp(a, 56)));
        label(t, title == null || title.isEmpty() ? Urls.host(url) : title);
        t.setOnClickListener(v -> click.run());
        t.setOnLongClickListener(v -> {
            longClick.run();
            return true;
        });
        return t;
    }

    private android.view.View addTile() {
        LinearLayout t = tileFrame();
        FrameLayout circle = new FrameLayout(a);
        circle.setBackground(Ui.round(Ui.CARD, Ui.dp(a, 28)));
        ImageView icon = new ImageView(a);
        icon.setImageResource(R.drawable.ic_add);
        Ui.tint(icon, Ui.ICON);
        circle.addView(icon, new FrameLayout.LayoutParams(Ui.dp(a, 28), Ui.dp(a, 28), Gravity.CENTER));
        t.addView(circle, new LinearLayout.LayoutParams(Ui.dp(a, 56), Ui.dp(a, 56)));
        label(t, "Добавить");
        t.setOnClickListener(v -> editPin(null, "", ""));
        return t;
    }

    private void pinActions(Db.Entry p) {
        String[] items = {"Открыть в новой вкладке", "Изменить", "Сдвинуть влево", "Сдвинуть вправо", "Открепить"};
        new Sheet(a)
                .setTitle(p.title == null || p.title.isEmpty() ? Urls.host(p.url) : p.title)
                .setItems(items, (d, w) -> {
                    switch (w) {
                        case 0: host.openUrl(p.url, true); break;
                        case 1: editPin(p, p.title, p.url); break;
                        case 2: change(() -> db.movePin(p.id, -1)); break;
                        case 3: change(() -> db.movePin(p.id, 1)); break;
                        case 4: change(() -> db.deletePin(p.id)); break;
                    }
                })
                .show();
    }

    private void frequentActions(Db.Site s) {
        String[] items = {"Открыть в новой вкладке", "Закрепить", "Убрать из частых"};
        new Sheet(a)
                .setTitle(s.host)
                .setItems(items, (d, w) -> {
                    if (w == 0) {
                        host.openUrl(s.url, true);
                    } else if (w == 1) {
                        change(() -> db.addPin(s.url, s.host));
                    } else {
                        Set<String> hidden = new HashSet<>(prefs.getStringSet("hiddenHosts", new HashSet<>()));
                        hidden.add(s.host);
                        prefs.edit().putStringSet("hiddenHosts", hidden).apply();
                        refresh();
                    }
                })
                .show();
    }

    private void editPin(Db.Entry p, String title, String url) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(a, 20), Ui.dp(a, 8), Ui.dp(a, 20), 0);
        EditText name = new EditText(a);
        name.setHint("Название");
        name.setSingleLine(true);
        name.setText(title);
        box.addView(name);
        EditText addr = new EditText(a);
        addr.setHint("Адрес, например ranobelib.me");
        addr.setSingleLine(true);
        addr.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        addr.setText(url);
        box.addView(addr);
        new Sheet(a)
                .setTitle(p == null ? "Новая плитка" : "Изменить плитку")
                .setView(box)
                .setPositiveButton("Сохранить", (d, w) -> {
                    String u = addr.getText().toString().trim();
                    if (u.isEmpty()) {
                        Toast.makeText(a, "Нужен адрес", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (!u.contains("://")) u = "https://" + u;
                    String n = name.getText().toString().trim();
                    String target = u;
                    String label = n.isEmpty() ? Urls.host(u) : n;
                    change(() -> {
                        if (p == null) {
                            db.addPin(target, label);
                        } else {
                            db.updatePin(p.id, target, label);
                        }
                    });
                })
                .setNegativeButton("Отмена", null)
                .show();
    }
}
