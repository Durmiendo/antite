package dev.antite.browser;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

final class AppMenu {
    static final class IconAction {
        final int icon;
        final String label;
        final boolean enabled;
        final Runnable action;

        IconAction(int icon, String label, boolean enabled, Runnable action) {
            this.icon = icon;
            this.label = label;
            this.enabled = enabled;
            this.action = action;
        }
    }

    static final class Item {
        final int icon;
        final String text;
        final boolean enabled;
        final Boolean checked;
        final boolean submenu;
        final Runnable action;

        Item(int icon, String text, boolean enabled, Boolean checked, boolean submenu, Runnable action) {
            this.icon = icon;
            this.text = text;
            this.enabled = enabled;
            this.checked = checked;
            this.submenu = submenu;
            this.action = action;
        }
    }

    private static final Item DIVIDER = new Item(0, null, false, null, false, null);

    private final List<IconAction> row = new ArrayList<>();
    private final List<Item> items = new ArrayList<>();

    AppMenu icon(int icon, String label, boolean enabled, Runnable action) {
        row.add(new IconAction(icon, label, enabled, action));
        return this;
    }

    AppMenu item(int icon, String text, Runnable action) {
        items.add(new Item(icon, text, true, null, false, action));
        return this;
    }

    AppMenu disabled(int icon, String text) {
        items.add(new Item(icon, text, false, null, false, null));
        return this;
    }

    AppMenu submenu(int icon, String text, Runnable action) {
        items.add(new Item(icon, text, true, null, true, action));
        return this;
    }

    AppMenu check(int icon, String text, boolean checked, Runnable action) {
        items.add(new Item(icon, text, true, checked, false, action));
        return this;
    }

    AppMenu divider() {
        items.add(DIVIDER);
        return this;
    }

    void show(Activity a, View root) {
        int screenW = root.getWidth();
        int screenH = root.getHeight();
        android.view.WindowInsets wi = root.getRootWindowInsets();
        android.graphics.Insets bars = wi == null ? android.graphics.Insets.NONE
                : wi.getInsets(android.view.WindowInsets.Type.systemBars());
        int topOffset = bars.top + Ui.dp(a, 4);
        int width = Math.min(screenW - Ui.dp(a, 34), Ui.dp(a, 420));

        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, Ui.dp(a, 8), 0, Ui.dp(a, 8));

        PopupWindow popup = new PopupWindow(a);

        if (!row.isEmpty()) {
            LinearLayout r = new LinearLayout(a);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setPadding(Ui.dp(a, 8), Ui.dp(a, 4), Ui.dp(a, 8), Ui.dp(a, 8));
            for (IconAction ia : row) {
                FrameLayout cell = new FrameLayout(a);
                ImageView iv = new ImageView(a);
                iv.setImageResource(ia.icon);
                iv.setScaleType(ImageView.ScaleType.CENTER);
                iv.setContentDescription(ia.label);
                Ui.tint(iv, ia.enabled ? Ui.TEXT : Ui.ICON_DISABLED);
                int size = Ui.dp(a, 44);
                iv.setBackground(Ui.ripple(
                        Ui.round(ia.enabled ? Ui.MENU_ICON_CIRCLE : 0x40505050, size / 2f),
                        Ui.round(0xFFFFFFFF, size / 2f)));
                if (ia.enabled) {
                    iv.setOnClickListener(v -> {
                        popup.dismiss();
                        ia.action.run();
                    });
                }
                cell.addView(iv, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));
                r.addView(cell, new LinearLayout.LayoutParams(0, Ui.dp(a, 56), 1f));
            }
            list.addView(r);
        }

        for (Item it : items) {
            if (it == DIVIDER) {
                View d = new View(a);
                d.setBackgroundColor(Ui.MENU_DIVIDER);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(a, 1));
                lp.setMargins(0, Ui.dp(a, 6), 0, Ui.dp(a, 6));
                list.addView(d, lp);
                continue;
            }
            list.addView(itemView(a, it, popup), new LinearLayout.LayoutParams(-1, Ui.dp(a, 50)));
        }

        ScrollView scroll = new ScrollView(a);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(list);
        scroll.setBackground(Ui.round(Ui.MENU, Ui.dp(a, 20)));
        scroll.setClipToOutline(true);

        popup.setContentView(scroll);
        popup.setWidth(width);
        list.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int maxH = screenH - topOffset - bars.bottom - Ui.dp(a, 8);
        popup.setHeight(Math.min(list.getMeasuredHeight(), maxH));
        popup.setBackgroundDrawable(null);
        popup.setElevation(Ui.dp(a, 8));
        popup.setFocusable(true);
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(android.R.style.Animation_Dialog);
        popup.showAtLocation(root, Gravity.TOP | Gravity.END, Ui.dp(a, 6), topOffset);
    }

    private static View itemView(Activity a, Item it, PopupWindow popup) {
        LinearLayout v = new LinearLayout(a);
        v.setOrientation(LinearLayout.HORIZONTAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setPadding(Ui.dp(a, 20), 0, Ui.dp(a, 18), 0);
        v.setBackground(Ui.ripple(null, Ui.round(0xFFFFFFFF, 0)));

        ImageView icon = new ImageView(a);
        icon.setImageResource(it.icon);
        Ui.tint(icon, it.enabled ? Ui.ICON : Ui.ICON_DISABLED);
        v.addView(icon, new LinearLayout.LayoutParams(Ui.dp(a, 24), Ui.dp(a, 24)));

        TextView text = new TextView(a);
        text.setText(it.text);
        text.setTextSize(16);
        text.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        text.setTextColor(it.enabled ? Ui.TEXT : Ui.TEXT_DISABLED);
        text.setSingleLine(true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.setMarginStart(Ui.dp(a, 16));
        v.addView(text, tlp);

        if (it.checked != null) {
            CheckBox cb = new CheckBox(a);
            cb.setChecked(it.checked);
            cb.setClickable(false);
            cb.setFocusable(false);
            cb.setButtonTintList(new ColorStateList(
                    new int[][]{{android.R.attr.state_checked}, {}},
                    new int[]{Ui.ACCENT, Ui.ICON}));
            v.addView(cb);
        } else if (it.submenu) {
            ImageView chev = new ImageView(a);
            chev.setImageResource(R.drawable.ic_chevron_right);
            Ui.tint(chev, it.enabled ? Ui.ICON : Ui.ICON_DISABLED);
            v.addView(chev, new LinearLayout.LayoutParams(Ui.dp(a, 24), Ui.dp(a, 24)));
        }

        if (it.enabled && it.action != null) {
            v.setOnClickListener(x -> {
                popup.dismiss();
                it.action.run();
            });
        } else {
            v.setEnabled(false);
        }
        return v;
    }
}
