package dev.antite.browser;

import android.app.Activity;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

final class ListPage extends LinearLayout {
    interface Source {
        String title();

        int icon();

        String emptyText();

        List<Db.Entry> load(String filter);

        String subtitle(Db.Entry e);

        void open(Db.Entry e);

        void longPress(Db.Entry e, Runnable refresh);

        String clearLabel();

        void clearAll(Runnable refresh);

        default android.graphics.Bitmap image(Db.Entry e) {
            return null;
        }
    }

    private final Activity a;
    private final Runnable onClose;
    private final TextView title;
    private final EditText search;
    private final TextView clear;
    private final TextView empty;
    private final RecyclerView list;
    private final List<Db.Entry> items = new ArrayList<>();
    private final Adapter adapter = new Adapter();
    private Source source;

    ListPage(Activity a, Runnable onClose) {
        super(a);
        this.a = a;
        this.onClose = onClose;
        setOrientation(VERTICAL);
        setBackgroundColor(Ui.SWITCHER_BG);
        setClickable(true);

        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(a, 4), 0, Ui.dp(a, 8), 0);
        ImageView back = Ui.iconButton(a, R.drawable.ic_arrow_back, 48);
        back.setContentDescription("Назад");
        back.setOnClickListener(v -> onClose.run());
        bar.addView(back, new LayoutParams(Ui.dp(a, 48), Ui.dp(a, 48)));
        title = new TextView(a);
        title.setTextSize(20);
        title.setTextColor(Ui.TEXT);
        LayoutParams tlp = new LayoutParams(0, -2, 1f);
        tlp.setMarginStart(Ui.dp(a, 12));
        bar.addView(title, tlp);
        clear = new TextView(a);
        clear.setTextColor(Ui.ACCENT);
        clear.setTextSize(14);
        clear.setPadding(Ui.dp(a, 12), Ui.dp(a, 12), Ui.dp(a, 12), Ui.dp(a, 12));
        clear.setBackground(Ui.borderlessRipple());
        clear.setOnClickListener(v -> {
            if (source != null) source.clearAll(this::refresh);
        });
        bar.addView(clear);
        addView(bar, new LayoutParams(-1, Ui.dp(a, 56)));

        LinearLayout searchBox = new LinearLayout(a);
        searchBox.setOrientation(HORIZONTAL);
        searchBox.setGravity(Gravity.CENTER_VERTICAL);
        searchBox.setBackground(Ui.round(Ui.PILL, Ui.dp(a, 20)));
        searchBox.setPadding(Ui.dp(a, 12), 0, Ui.dp(a, 12), 0);
        ImageView glass = new ImageView(a);
        glass.setImageResource(R.drawable.ic_search);
        Ui.tint(glass, Ui.TEXT_SECONDARY);
        searchBox.addView(glass, new LayoutParams(Ui.dp(a, 20), Ui.dp(a, 20)));
        search = new EditText(a);
        search.setBackground(null);
        search.setSingleLine(true);
        search.setTextSize(15);
        search.setTextColor(Ui.TEXT);
        search.setHintTextColor(Ui.TEXT_SECONDARY);
        search.setHint("Поиск");
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int x, int y, int z) {
            }

            public void onTextChanged(CharSequence s, int x, int y, int z) {
            }

            public void afterTextChanged(Editable s) {
                refresh();
            }
        });
        searchBox.addView(search, new LayoutParams(0, -1, 1f));
        LayoutParams slp = new LayoutParams(-1, Ui.dp(a, 40));
        slp.setMargins(Ui.dp(a, 12), 0, Ui.dp(a, 12), Ui.dp(a, 8));
        addView(searchBox, slp);

        empty = new TextView(a);
        empty.setTextColor(Ui.TEXT_SECONDARY);
        empty.setTextSize(15);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(Ui.dp(a, 24), Ui.dp(a, 48), Ui.dp(a, 24), 0);
        addView(empty, new LayoutParams(-1, -2));

        list = new RecyclerView(a);
        list.setLayoutManager(new LinearLayoutManager(a));
        list.setAdapter(adapter);
        addView(list, new LayoutParams(-1, 0, 1f));
    }

    void show(Source s) {
        source = s;
        title.setText(s.title());
        String cl = s.clearLabel();
        clear.setText(cl == null ? "" : cl);
        clear.setVisibility(cl == null ? GONE : VISIBLE);
        search.setText("");
        refresh();
        list.scrollToPosition(0);
        setVisibility(VISIBLE);
    }

    void refresh() {
        if (source == null) return;
        items.clear();
        items.addAll(source.load(search.getText().toString().trim()));
        adapter.notifyDataSetChanged();
        empty.setText(source.emptyText());
        empty.setVisibility(items.isEmpty() ? VISIBLE : GONE);
    }

    static String ago(long time) {
        if (System.currentTimeMillis() - time < DateUtils.MINUTE_IN_MILLIS) return "только что";
        return DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString();
    }

    private final class Holder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView title;
        final TextView sub;

        Holder(View v, ImageView icon, TextView title, TextView sub) {
            super(v);
            this.icon = icon;
            this.title = title;
            this.sub = sub;
        }
    }

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(a, 20), Ui.dp(a, 10), Ui.dp(a, 16), Ui.dp(a, 10));
            row.setMinimumHeight(Ui.dp(a, 64));
            row.setBackground(Ui.ripple(null, Ui.round(0xFFFFFFFF, 0)));
            row.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            ImageView icon = new ImageView(a);
            Ui.tint(icon, Ui.ICON);
            row.addView(icon, new LayoutParams(Ui.dp(a, 24), Ui.dp(a, 24)));
            LinearLayout texts = new LinearLayout(a);
            texts.setOrientation(VERTICAL);
            TextView t = new TextView(a);
            t.setTextSize(16);
            t.setTextColor(Ui.TEXT);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(t);
            TextView s = new TextView(a);
            s.setTextSize(13);
            s.setTextColor(Ui.TEXT_SECONDARY);
            s.setSingleLine(true);
            s.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            texts.addView(s);
            LayoutParams lp = new LayoutParams(0, -2, 1f);
            lp.setMarginStart(Ui.dp(a, 16));
            row.addView(texts, lp);
            return new Holder(row, icon, t, s);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int pos) {
            Db.Entry e = items.get(pos);
            android.graphics.Bitmap img = source.image(e);
            if (img != null) {
                h.icon.setImageTintList(null);
                h.icon.setImageBitmap(img);
            } else {
                h.icon.setImageResource(source.icon());
                Ui.tint(h.icon, Ui.ICON);
            }
            String t = e.title == null || e.title.isEmpty() ? e.url : e.title;
            h.title.setText(t == null ? "" : t);
            h.sub.setText(source.subtitle(e));
            h.itemView.setOnClickListener(v -> source.open(e));
            h.itemView.setOnLongClickListener(v -> {
                source.longPress(e, ListPage.this::refresh);
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }
}
