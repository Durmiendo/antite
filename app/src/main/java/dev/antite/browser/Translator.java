package dev.antite.browser;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.TranslationsController.Language;
import org.mozilla.geckoview.TranslationsController.RuntimeTranslation;
import org.mozilla.geckoview.TranslationsController.SessionTranslation;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

final class Translator {
    private final Activity a;
    private final SharedPreferences prefs;
    private final Supplier<Tab> current;
    private final Function<Tab, GeckoSession> session;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinearLayout bar;
    private final TextView text;
    private final Runnable hide;

    Translator(Activity a, SharedPreferences prefs, Supplier<Tab> current, Function<Tab, GeckoSession> session) {
        this.a = a;
        this.prefs = prefs;
        this.current = current;
        this.session = session;

        bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Ui.MENU);
        bar.setPadding(Ui.dp(a, 16), Ui.dp(a, 4), Ui.dp(a, 4), Ui.dp(a, 4));
        bar.setVisibility(View.GONE);
        bar.setClickable(true);
        hide = () -> bar.setVisibility(View.GONE);

        ImageView icon = new ImageView(a);
        icon.setImageResource(R.drawable.ic_translate);
        Ui.tint(icon, Ui.ICON);
        bar.addView(icon, new LinearLayout.LayoutParams(Ui.dp(a, 24), Ui.dp(a, 24)));

        text = new TextView(a);
        text.setTextColor(Ui.TEXT);
        text.setTextSize(14);
        text.setPadding(Ui.dp(a, 12), 0, Ui.dp(a, 8), 0);
        bar.addView(text, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView go = new TextView(a);
        go.setText("Перевести");
        go.setTextColor(Ui.ACCENT);
        go.setTextSize(14);
        go.setTypeface(Typeface.DEFAULT_BOLD);
        go.setGravity(Gravity.CENTER);
        go.setPadding(Ui.dp(a, 12), 0, Ui.dp(a, 12), 0);
        go.setBackground(Ui.borderlessRipple());
        go.setOnClickListener(v -> {
            hide.run();
            Tab t = current.get();
            if (t != null) translate(t);
        });
        bar.addView(go, new LinearLayout.LayoutParams(-2, Ui.dp(a, 48)));

        ImageView close = Ui.iconButton(a, R.drawable.ic_close, 48);
        close.setContentDescription("Закрыть");
        close.setOnClickListener(v -> hide.run());
        bar.addView(close, new LinearLayout.LayoutParams(Ui.dp(a, 48), Ui.dp(a, 48)));
    }

    View bar() {
        return bar;
    }

    SessionTranslation.Delegate delegateFor(Tab t) {
        return new SessionTranslation.Delegate() {
            @Override
            public void onOfferTranslate(GeckoSession s) {
                offer(t);
            }

            @Override
            public void onTranslationStateChange(GeckoSession s, SessionTranslation.TranslationState state) {
                if (state == null) return;
                if (state.detectedLanguages != null) t.docLang = state.detectedLanguages.docLangTag;
                t.translated = state.requestedTranslationPair != null;
                if (state.error != null && t == current.get()) {
                    Toast.makeText(a, "Ошибка перевода: " + state.error, Toast.LENGTH_LONG).show();
                }
            }
        };
    }

    private void offer(Tab t) {
        if (t != current.get() || !prefs.getBoolean("translateOffer", true)) return;
        String from = t.docLang == null ? "" : Urls.langName(t.docLang) + " → ";
        text.setText("Перевести страницу? " + from + Urls.langName(EngineSettings.translateTarget(prefs)));
        bar.setVisibility(View.VISIBLE);
        main.removeCallbacks(hide);
        main.postDelayed(hide, 15000);
    }

    void translate(Tab t) {
        if (!Urls.isWeb(t.url)) return;
        SessionTranslation st = session.apply(t).getSessionTranslation();
        if (st == null) {
            Toast.makeText(a, "Перевод недоступен", Toast.LENGTH_SHORT).show();
            return;
        }
        if (t.translated) {
            st.restoreOriginalPage().accept(v -> t.translated = false, e -> { });
            return;
        }
        String to = EngineSettings.translateTarget(prefs);
        if (t.docLang != null && !t.docLang.isEmpty()) {
            run(st, t.docLang, to);
            return;
        }
        RuntimeTranslation.listSupportedLanguages().accept(sup -> {
            if (sup == null || sup.fromLanguages == null || sup.fromLanguages.isEmpty()) return;
            List<Language> from = new ArrayList<>(sup.fromLanguages);
            String[] names = new String[from.size()];
            for (int i = 0; i < names.length; i++) names[i] = from.get(i).localizedDisplayName;
            new Sheet(a)
                    .setTitle("Язык страницы")
                    .setItems(names, (d, w) -> run(st, from.get(w).code, to))
                    .show();
        }, e -> Toast.makeText(a, "Перевод недоступен", Toast.LENGTH_SHORT).show());
    }

    private void run(SessionTranslation st, String from, String to) {
        if (from.equals(to)) {
            Toast.makeText(a, "Страница уже на языке перевода", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(a, "Перевод… Первый раз скачивается языковая модель", Toast.LENGTH_SHORT).show();
        st.translate(from, to, new SessionTranslation.TranslationOptions.Builder().downloadModel(true).build())
                .accept(v -> { }, e -> Toast.makeText(a, "Не удалось перевести: " + e.getMessage(),
                        Toast.LENGTH_LONG).show());
    }
}
