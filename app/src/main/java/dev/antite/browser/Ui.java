package dev.antite.browser;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.View;
import android.widget.ImageView;

final class Ui {
    static final int TOOLBAR = 0xFF131313;
    static final int TOOLBAR_INCOGNITO = 0xFF1F2023;
    static final int PILL = 0xFF343635;
    static final int PILL_INCOGNITO = 0xFF3C4043;
    static final int PAGE = 0xFF0A0A0A;
    static final int MENU = 0xFF393939;
    static final int MENU_DIVIDER = 0xFF414141;
    static final int MENU_ICON_CIRCLE = 0xFF2C2C2C;
    static final int ICON = 0xFFC6C6C6;
    static final int ICON_DISABLED = 0xFF6E6E6E;
    static final int TEXT = 0xFFE5E5E5;
    static final int TEXT_SECONDARY = 0xFFA8A8A8;
    static final int TEXT_DISABLED = 0xFF7A7A7A;
    static final int ACCENT = 0xFFFCB4B8;
    static final int ON_ACCENT = 0xFF561D24;
    static final int SECURE = 0xFF81C995;
    static final int CARD = 0xFF2A2A2A;
    static final int SWITCHER_BG = 0xFF131313;

    private Ui() {
    }

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusPx);
        return d;
    }

    static Drawable ripple(Drawable content, Drawable mask) {
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, mask);
    }

    static Drawable borderlessRipple() {
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, null);
    }

    static ImageView iconButton(Context c, int res, int sizeDp) {
        ImageView v = new ImageView(c);
        v.setImageResource(res);
        v.setImageTintList(ColorStateList.valueOf(ICON));
        v.setScaleType(ImageView.ScaleType.CENTER);
        v.setBackground(borderlessRipple());
        v.setClickable(true);
        v.setFocusable(true);
        v.setMinimumWidth(dp(c, sizeDp));
        v.setMinimumHeight(dp(c, sizeDp));
        return v;
    }

    static void tint(ImageView v, int color) {
        v.setImageTintList(ColorStateList.valueOf(color));
    }

    static void setEnabled(View v, boolean enabled) {
        v.setEnabled(enabled);
        v.setAlpha(enabled ? 1f : 0.45f);
    }

    static android.graphics.Bitmap letterIcon(Context c, String host, int sizeDp) {
        int size = dp(c, sizeDp);
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas cv = new android.graphics.Canvas(b);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        float[] hsv = {Math.abs(host.hashCode() % 360), 0.45f, 0.75f};
        p.setColor(Color.HSVToColor(hsv));
        cv.drawCircle(size / 2f, size / 2f, size / 2f, p);
        p.setColor(0xFFFFFFFF);
        p.setTextSize(size * 0.5f);
        p.setTextAlign(android.graphics.Paint.Align.CENTER);
        p.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        String letter = host.isEmpty() ? "?" : host.substring(0, 1).toUpperCase();
        android.graphics.Rect r = new android.graphics.Rect();
        p.getTextBounds(letter, 0, 1, r);
        cv.drawText(letter, size / 2f, size / 2f + r.height() / 2f, p);
        return b;
    }

    static int blend(int a, int b, float t) {
        return Color.rgb(
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }
}
