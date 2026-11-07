package dev.antite.browser;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

final class TabSwitcher extends LinearLayout {
    interface Host {
        List<Tab> tabs();

        Tab current();

        void select(Tab t);

        void close(Tab t);

        void newTab(boolean incognito);

        void moved(Tab t, Tab before);

        Bitmap thumb(Tab t);

        Bitmap icon(Tab t);

        int tabColor(Tab t);

        boolean listLayout();
    }

    private final Activity a;
    private final Host host;
    private final RecyclerView grid;
    private final Adapter adapter = new Adapter();
    private final TextView normalToggle;
    private final ImageView incognitoToggle;
    private final List<Tab> shown = new ArrayList<>();
    private boolean incognito;
    private boolean list;

    TabSwitcher(Activity a, Host host) {
        super(a);
        this.a = a;
        this.host = host;
        setOrientation(VERTICAL);
        setBackgroundColor(Ui.SWITCHER_BG);
        setClickable(true);

        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(a, 12), 0, Ui.dp(a, 4), 0);

        LinearLayout seg = new LinearLayout(a);
        seg.setOrientation(HORIZONTAL);
        seg.setBackground(Ui.round(Ui.PILL, Ui.dp(a, 20)));
        seg.setPadding(Ui.dp(a, 4), Ui.dp(a, 4), Ui.dp(a, 4), Ui.dp(a, 4));

        normalToggle = new TextView(a);
        normalToggle.setGravity(Gravity.CENTER);
        normalToggle.setTextSize(14);
        normalToggle.setTypeface(Typeface.DEFAULT_BOLD);
        normalToggle.setPadding(Ui.dp(a, 16), 0, Ui.dp(a, 16), 0);
        normalToggle.setOnClickListener(v -> setIncognito(false));
        seg.addView(normalToggle, new LayoutParams(-2, Ui.dp(a, 32)));

        incognitoToggle = new ImageView(a);
        incognitoToggle.setImageResource(R.drawable.ic_incognito);
        incognitoToggle.setScaleType(ImageView.ScaleType.CENTER);
        incognitoToggle.setOnClickListener(v -> setIncognito(true));
        seg.addView(incognitoToggle, new LayoutParams(Ui.dp(a, 52), Ui.dp(a, 32)));

        bar.addView(seg);
        bar.addView(new View(a), new LayoutParams(0, 1, 1f));

        ImageView add = Ui.iconButton(a, R.drawable.ic_add, 48);
        add.setContentDescription("Новая вкладка");
        add.setOnClickListener(v -> host.newTab(incognito));
        bar.addView(add, new LayoutParams(Ui.dp(a, 48), Ui.dp(a, 48)));

        addView(bar, new LayoutParams(-1, Ui.dp(a, 56)));

        grid = new RecyclerView(a);
        grid.setAdapter(adapter);
        grid.setPadding(Ui.dp(a, 8), 0, Ui.dp(a, 8), Ui.dp(a, 16));
        grid.setClipToPadding(false);
        addView(grid, new LayoutParams(-1, 0, 1f));

        new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN | ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT,
                ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
            @Override
            public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder from,
                                  @NonNull RecyclerView.ViewHolder to) {
                int f = from.getBindingAdapterPosition();
                int t = to.getBindingAdapterPosition();
                if (f < 0 || t < 0) return false;
                Tab moving = shown.remove(f);
                shown.add(t, moving);
                adapter.notifyItemMoved(f, t);
                host.moved(moving, t + 1 < shown.size() ? shown.get(t + 1) : null);
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int dir) {
                int p = vh.getBindingAdapterPosition();
                if (p < 0) return;
                Tab t = shown.remove(p);
                adapter.notifyItemRemoved(p);
                host.close(t);
                updateHeader();
            }
        }).attachToRecyclerView(grid);
    }

    void open(boolean incognitoMode) {
        incognito = incognitoMode;
        boolean wantList = host.listLayout();
        if (wantList != list || grid.getLayoutManager() == null) {
            list = wantList;
            grid.setLayoutManager(list ? new androidx.recyclerview.widget.LinearLayoutManager(a)
                    : new GridLayoutManager(a, 2));
            grid.setPadding(list ? 0 : Ui.dp(a, 8), 0, list ? 0 : Ui.dp(a, 8), Ui.dp(a, 16));
            grid.setAdapter(adapter);
        }
        refresh();
        int idx = shown.indexOf(host.current());
        if (idx >= 0) grid.scrollToPosition(idx);
    }

    void setIncognito(boolean on) {
        incognito = on;
        refresh();
    }

    void refresh() {
        shown.clear();
        for (Tab t : host.tabs()) {
            if (t.incognito == incognito) shown.add(t);
        }
        adapter.notifyDataSetChanged();
        updateHeader();
    }

    private void updateHeader() {
        int normal = 0;
        for (Tab t : host.tabs()) {
            if (!t.incognito) normal++;
        }
        normalToggle.setText(normal >= 100 ? ":D" : String.valueOf(normal));
        normalToggle.setTextColor(incognito ? Ui.ICON : Ui.ON_ACCENT);
        normalToggle.setBackground(incognito ? null : Ui.round(Ui.ACCENT, Ui.dp(a, 16)));
        Ui.tint(incognitoToggle, incognito ? Ui.ON_ACCENT : Ui.ICON);
        incognitoToggle.setBackground(incognito ? Ui.round(Ui.ACCENT, Ui.dp(a, 16)) : null);
    }

    private final class Holder extends RecyclerView.ViewHolder {
        final LinearLayout card;
        final TextView title;
        final ImageView thumb;
        final ImageView close;
        ImageView globe;
        TextView site;
        View mark;

        Holder(View item, LinearLayout card, TextView title, ImageView thumb, ImageView close) {
            super(item);
            this.card = card;
            this.title = title;
            this.thumb = thumb;
            this.close = close;
        }
    }

    private Holder createRow() {
        FrameLayout outer = new FrameLayout(a);
        outer.setLayoutParams(new RecyclerView.LayoutParams(-1, Ui.dp(a, 124)));
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(HORIZONTAL);
        row.setPadding(Ui.dp(a, 16), Ui.dp(a, 12), Ui.dp(a, 40), Ui.dp(a, 12));
        outer.addView(row, new FrameLayout.LayoutParams(-1, -1));

        ImageView thumb = new ImageView(a);
        thumb.setScaleType(ImageView.ScaleType.MATRIX);
        thumb.setBackground(Ui.round(0xFFFFFFFF, Ui.dp(a, 4)));
        thumb.setClipToOutline(true);
        thumb.setElevation(Ui.dp(a, 2));
        row.addView(thumb, new LayoutParams(0, -1, 0.42f));

        LinearLayout texts = new LinearLayout(a);
        texts.setOrientation(VERTICAL);
        texts.setGravity(Gravity.CENTER_VERTICAL);
        texts.setPadding(Ui.dp(a, 16), 0, 0, 0);
        TextView title = new TextView(a);
        title.setTextSize(18);
        title.setMaxLines(3);
        title.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(title);
        TextView site = new TextView(a);
        site.setTextSize(14);
        site.setSingleLine(true);
        site.setEllipsize(TextUtils.TruncateAt.END);
        site.setPadding(0, Ui.dp(a, 6), 0, 0);
        texts.addView(site);
        row.addView(texts, new LayoutParams(0, -1, 0.58f));

        ImageView close = Ui.iconButton(a, R.drawable.ic_close, 40);
        close.setContentDescription("Закрыть вкладку");
        outer.addView(close, new FrameLayout.LayoutParams(Ui.dp(a, 40), Ui.dp(a, 40), Gravity.TOP | Gravity.END));

        View mark = new View(a);
        mark.setBackgroundColor(Ui.ACCENT);
        outer.addView(mark, new FrameLayout.LayoutParams(Ui.dp(a, 4), -1, Gravity.START));

        View divider = new View(a);
        divider.setBackgroundColor(0x33000000);
        outer.addView(divider, new FrameLayout.LayoutParams(-1, 1, Gravity.BOTTOM));

        LinearLayout card = new LinearLayout(a);
        Holder h = new Holder(outer, card, title, thumb, close);
        h.site = site;
        h.mark = mark;
        return h;
    }

    private void bindRow(Holder h, Tab t) {
        int color = host.tabColor(t);
        if (color == 0) color = t.incognito ? Ui.TOOLBAR_INCOGNITO : Ui.CARD;
        h.itemView.setBackgroundColor(color);
        boolean light = android.graphics.Color.luminance(color) > 0.55f;
        int text = light ? 0xDE000000 : 0xFFFFFFFF;
        h.title.setTextColor(text);
        h.site.setTextColor(light ? 0x8A000000 : 0xB3FFFFFF);
        Ui.tint(h.close, light ? 0x8A000000 : 0xCCFFFFFF);
        h.title.setText(t.label());
        String u = android.net.Uri.parse(t.url).getHost();
        h.site.setText(u == null ? "" : u);
        h.mark.setVisibility(t == host.current() ? View.VISIBLE : View.INVISIBLE);
        Bitmap b = host.thumb(t);
        h.thumb.setImageBitmap(b);
        if (b != null) {
            h.thumb.post(() -> {
                float sc = h.thumb.getWidth() / (float) b.getWidth();
                android.graphics.Matrix mx = new android.graphics.Matrix();
                mx.setScale(sc, sc);
                h.thumb.setImageMatrix(mx);
            });
        }
    }

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        @Override
        public int getItemViewType(int position) {
            return list ? 1 : 0;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            if (type == 1) return createRow();
            int w = parent.getWidth() > 0
                    ? (parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight()) / 2
                    : Ui.dp(a, 170);
            FrameLayout outer = new FrameLayout(a);
            LinearLayout card = new LinearLayout(a);
            card.setOrientation(VERTICAL);
            card.setClipToOutline(true);

            LinearLayout head = new LinearLayout(a);
            head.setOrientation(HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.setPadding(Ui.dp(a, 10), 0, 0, 0);

            ImageView globe = new ImageView(a);
            globe.setImageResource(R.drawable.ic_public);
            Ui.tint(globe, Ui.ICON);
            head.addView(globe, new LayoutParams(Ui.dp(a, 16), Ui.dp(a, 16)));
            ImageView globeRef = globe;

            TextView title = new TextView(a);
            title.setTextColor(Ui.TEXT);
            title.setTextSize(13);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            LayoutParams tlp = new LayoutParams(0, -2, 1f);
            tlp.setMarginStart(Ui.dp(a, 8));
            head.addView(title, tlp);

            ImageView close = Ui.iconButton(a, R.drawable.ic_close, 36);
            close.setContentDescription("Закрыть вкладку");
            head.addView(close, new LayoutParams(Ui.dp(a, 36), Ui.dp(a, 36)));

            card.addView(head, new LayoutParams(-1, Ui.dp(a, 36)));

            ImageView thumb = new ImageView(a);
            thumb.setScaleType(ImageView.ScaleType.MATRIX);
            thumb.setBackgroundColor(Ui.PAGE);
            card.addView(thumb, new LayoutParams(-1, 0, 1f));

            int m = Ui.dp(a, 6);
            FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-1, -1);
            clp.setMargins(m, m, m, m);
            outer.addView(card, clp);
            outer.setLayoutParams(new RecyclerView.LayoutParams(-1, Math.round(w * 1.25f)));
            Holder holder = new Holder(outer, card, title, thumb, close);
            holder.globe = globeRef;
            return holder;
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int pos) {
            Tab t = shown.get(pos);
            if (getItemViewType(pos) == 1) {
                bindRow(h, t);
                h.itemView.setOnClickListener(v -> host.select(t));
                h.close.setOnClickListener(v -> {
                    int p = shown.indexOf(t);
                    if (p < 0) return;
                    shown.remove(p);
                    notifyItemRemoved(p);
                    host.close(t);
                    updateHeader();
                });
                return;
            }
            boolean selected = t == host.current();
            GradientDrawable bg = Ui.round(Ui.CARD, Ui.dp(a, 16));
            if (selected) bg.setStroke(Ui.dp(a, 3), Ui.ACCENT);
            h.card.setBackground(bg);
            int pad = selected ? Ui.dp(a, 3) : 0;
            h.card.setPadding(pad, 0, pad, pad);
            h.title.setText(t.label());
            Bitmap ic = host.icon(t);
            if (ic != null) {
                h.globe.setImageTintList(null);
                h.globe.setImageBitmap(ic);
            } else {
                h.globe.setImageResource(R.drawable.ic_public);
                Ui.tint(h.globe, Ui.ICON);
            }
            Bitmap b = host.thumb(t);
            h.thumb.setImageBitmap(b);
            if (b != null) {
                h.thumb.post(() -> {
                    float s = h.thumb.getWidth() / (float) b.getWidth();
                    android.graphics.Matrix mx = new android.graphics.Matrix();
                    mx.setScale(s, s);
                    h.thumb.setImageMatrix(mx);
                });
            }
            h.card.setOnClickListener(v -> host.select(t));
            h.close.setOnClickListener(v -> {
                int p = shown.indexOf(t);
                if (p < 0) return;
                shown.remove(p);
                notifyItemRemoved(p);
                host.close(t);
                updateHeader();
            });
        }

        @Override
        public int getItemCount() {
            return shown.size();
        }
    }
}
