package dev.antite.browser;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

final class Sheet {
    private final Context c;
    private CharSequence title;
    private CharSequence message;
    private int icon;
    private View view;
    private CharSequence[] items;
    private int checked = -2;
    private boolean[] multi;
    private DialogInterface.OnClickListener itemClick;
    private DialogInterface.OnMultiChoiceClickListener multiClick;
    private final CharSequence[] buttonText = new CharSequence[3];
    private final DialogInterface.OnClickListener[] buttonClick = new DialogInterface.OnClickListener[3];
    private DialogInterface.OnCancelListener cancel;
    private DialogInterface.OnDismissListener dismiss;
    private boolean stacked;
    private boolean focused;

    Sheet(Context c) {
        this.c = c;
    }

    Sheet setTitle(CharSequence t) {
        title = t;
        return this;
    }

    Sheet setIcon(int res) {
        icon = res;
        return this;
    }

    Sheet setMessage(CharSequence m) {
        message = m;
        return this;
    }

    Sheet setView(View v) {
        view = v;
        return this;
    }

    Sheet setItems(CharSequence[] list, DialogInterface.OnClickListener l) {
        items = list;
        itemClick = l;
        return this;
    }

    Sheet setSingleChoiceItems(CharSequence[] list, int sel, DialogInterface.OnClickListener l) {
        items = list;
        checked = sel;
        itemClick = l;
        return this;
    }

    Sheet setMultiChoiceItems(CharSequence[] list, boolean[] state, DialogInterface.OnMultiChoiceClickListener l) {
        items = list;
        multi = state;
        multiClick = l;
        return this;
    }

    Sheet setPositiveButton(CharSequence t, DialogInterface.OnClickListener l) {
        return button(2, t, l);
    }

    Sheet setNegativeButton(CharSequence t, DialogInterface.OnClickListener l) {
        return button(1, t, l);
    }

    Sheet setNeutralButton(CharSequence t, DialogInterface.OnClickListener l) {
        return button(0, t, l);
    }

    Sheet setStacked(boolean s) {
        stacked = s;
        return this;
    }

    Sheet setOnCancelListener(DialogInterface.OnCancelListener l) {
        cancel = l;
        return this;
    }

    Sheet setOnDismissListener(DialogInterface.OnDismissListener l) {
        dismiss = l;
        return this;
    }

    private Sheet button(int i, CharSequence t, DialogInterface.OnClickListener l) {
        buttonText[i] = t;
        buttonClick[i] = l;
        return this;
    }

    Dialog show() {
        Dialog d = new Dialog(c);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        int pad = Ui.dp(c, 24);

        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Ui.round(Ui.MENU, Ui.dp(c, 28)));
        box.setPadding(0, pad, 0, Ui.dp(c, 12));

        if (icon != 0) {
            ImageView iv = new ImageView(c);
            iv.setImageResource(icon);
            iv.setImageTintList(ColorStateList.valueOf(Ui.ACCENT));
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(Ui.dp(c, 24), Ui.dp(c, 24));
            ilp.gravity = Gravity.CENTER_HORIZONTAL;
            ilp.bottomMargin = Ui.dp(c, 16);
            box.addView(iv, ilp);
        }
        if (title != null && title.length() > 0) {
            TextView t = new TextView(c);
            t.setText(title);
            t.setTextSize(22);
            t.setTextColor(Ui.TEXT);
            t.setPadding(pad, 0, pad, Ui.dp(c, 16));
            if (icon != 0) t.setGravity(Gravity.CENTER_HORIZONTAL);
            box.addView(t);
        }

        LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        if (message != null && message.length() > 0) {
            TextView m = new TextView(c);
            m.setText(message);
            m.setTextSize(14);
            m.setLineSpacing(0, 1.15f);
            m.setTextColor(Ui.TEXT_SECONDARY);
            m.setTextIsSelectable(false);
            m.setPadding(pad, 0, pad, Ui.dp(c, 8));
            body.addView(m);
        }
        if (view != null) {
            styleInputs(view);
            LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(-1, -2);
            body.addView(view, vlp);
        }
        if (items != null) addItems(d, body);

        int maxH = (int) (c.getResources().getDisplayMetrics().heightPixels * 0.6f);
        ScrollView scroll = new ScrollView(c) {
            @Override
            protected void onMeasure(int w, int h) {
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST));
            }
        };
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(body);
        box.addView(scroll, new LinearLayout.LayoutParams(-1, -2));

        List<Integer> order = new ArrayList<>();
        if (stacked) {
            order.add(2);
            order.add(0);
            order.add(1);
        } else {
            order.add(0);
            order.add(1);
            order.add(2);
        }
        LinearLayout buttons = new LinearLayout(c);
        buttons.setOrientation(stacked ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        buttons.setPadding(Ui.dp(c, 12), Ui.dp(c, 8), Ui.dp(c, 12), 0);
        boolean any = false;
        for (int i : order) {
            if (buttonText[i] == null) continue;
            any = true;
            TextView b = new TextView(c);
            b.setText(buttonText[i]);
            b.setTextSize(14);
            b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            b.setTextColor(Ui.ACCENT);
            b.setGravity(Gravity.CENTER);
            b.setMinHeight(Ui.dp(c, 40));
            b.setPadding(Ui.dp(c, 12), 0, Ui.dp(c, 12), 0);
            b.setBackground(Ui.ripple(null, Ui.round(0xFFFFFFFF, Ui.dp(c, 20))));
            int which = i == 2 ? DialogInterface.BUTTON_POSITIVE
                    : i == 1 ? DialogInterface.BUTTON_NEGATIVE : DialogInterface.BUTTON_NEUTRAL;
            DialogInterface.OnClickListener l = buttonClick[i];
            b.setOnClickListener(v -> {
                if (l != null) l.onClick(d, which);
                d.dismiss();
            });
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
            if (stacked) blp.gravity = Gravity.END;
            buttons.addView(b, blp);
            if (!stacked && i == 0) {
                View spacer = new View(c);
                buttons.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
            }
        }
        if (any) box.addView(buttons, new LinearLayout.LayoutParams(-1, -2));

        d.setContentView(box);
        if (cancel != null) d.setOnCancelListener(cancel);
        if (dismiss != null) d.setOnDismissListener(dismiss);
        d.show();
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0));
            int width = Math.min(c.getResources().getDisplayMetrics().widthPixels - Ui.dp(c, 48), Ui.dp(c, 560));
            w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setDimAmount(0.6f);
            if (view != null && hasInput(view)) {
                w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                        | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            }
        }
        return d;
    }

    private void addItems(Dialog d, LinearLayout body) {
        ColorStateList tint = new ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{Ui.ACCENT, Ui.ICON});
        List<RadioButton> radios = new ArrayList<>();
        for (int i = 0; i < items.length; i++) {
            int idx = i;
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(Ui.dp(c, 48));
            row.setPadding(Ui.dp(c, 24), 0, Ui.dp(c, 24), 0);
            row.setBackground(Ui.ripple(null, Ui.round(0xFFFFFFFF, 0)));
            View mark = null;
            if (multi != null) {
                CheckBox cb = new CheckBox(c);
                cb.setChecked(multi[i]);
                cb.setClickable(false);
                cb.setButtonTintList(tint);
                mark = cb;
            } else if (checked != -2) {
                RadioButton rb = new RadioButton(c);
                rb.setChecked(i == checked);
                rb.setClickable(false);
                rb.setButtonTintList(tint);
                radios.add(rb);
                mark = rb;
            }
            if (mark != null) {
                LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-2, -2);
                mlp.setMarginEnd(Ui.dp(c, 12));
                row.addView(mark, mlp);
            }
            TextView t = new TextView(c);
            t.setText(items[i]);
            t.setTextSize(16);
            t.setTextColor(Ui.TEXT);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setMaxLines(3);
            t.setPadding(0, Ui.dp(c, 10), 0, Ui.dp(c, 10));
            row.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
            View m = mark;
            row.setOnClickListener(v -> {
                if (multi != null) {
                    multi[idx] = !multi[idx];
                    ((CheckBox) m).setChecked(multi[idx]);
                    if (multiClick != null) multiClick.onClick(d, idx, multi[idx]);
                } else if (checked != -2) {
                    for (RadioButton r : radios) r.setChecked(r == m);
                    if (itemClick != null) itemClick.onClick(d, idx);
                } else {
                    if (itemClick != null) itemClick.onClick(d, idx);
                    d.dismiss();
                }
            });
            body.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private static boolean hasInput(View v) {
        if (v instanceof EditText) return true;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (hasInput(g.getChildAt(i))) return true;
            }
        }
        return false;
    }

    private void styleInputs(View v) {
        if (v instanceof EditText) {
            EditText e = (EditText) v;
            e.setTextColor(Ui.TEXT);
            e.setHintTextColor(Ui.TEXT_SECONDARY);
            e.setTextSize(16);
            e.setBackgroundTintList(ColorStateList.valueOf(Ui.ACCENT));
            if (e.getParent() == null) {
                LinearLayout wrap = new LinearLayout(c);
                wrap.setPadding(Ui.dp(c, 20), 0, Ui.dp(c, 20), 0);
                wrap.addView(e, new LinearLayout.LayoutParams(-1, -2));
                view = wrap;
            }
            if (!focused) {
                focused = true;
                e.requestFocus();
            }
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) styleInputs(g.getChildAt(i));
        }
    }
}
