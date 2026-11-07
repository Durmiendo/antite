package dev.antite.browser;

import android.app.Activity;
import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.mozilla.geckoview.GeckoSession;

import java.util.function.Supplier;

final class FindBar extends LinearLayout {
    private final Supplier<Tab> current;
    private final EditText input;
    private final TextView count;

    FindBar(Activity a, Supplier<Tab> current) {
        super(a);
        this.current = current;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setBackgroundColor(Ui.TOOLBAR);
        setPadding(Ui.dp(a, 16), 0, Ui.dp(a, 4), 0);
        setVisibility(GONE);
        setClickable(true);

        input = new EditText(a);
        input.setBackground(null);
        input.setSingleLine(true);
        input.setTextColor(Ui.TEXT);
        input.setHintTextColor(Ui.TEXT_SECONDARY);
        input.setHint("Найти на странице");
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void afterTextChanged(Editable s) {
                find(s.toString(), 0);
            }
        });
        input.setOnEditorActionListener((v, id, e) -> {
            find(null, 0);
            return true;
        });
        addView(input, new LayoutParams(0, -1, 1f));

        count = new TextView(a);
        count.setTextColor(Ui.TEXT_SECONDARY);
        count.setTextSize(14);
        count.setPadding(Ui.dp(a, 8), 0, Ui.dp(a, 8), 0);
        addView(count);

        ImageView up = Ui.iconButton(a, R.drawable.ic_keyboard_arrow_up, 48);
        up.setOnClickListener(v -> find(null, GeckoSession.FINDER_FIND_BACKWARDS));
        addView(up, new LayoutParams(Ui.dp(a, 44), Ui.dp(a, 48)));
        ImageView down = Ui.iconButton(a, R.drawable.ic_keyboard_arrow_down, 48);
        down.setOnClickListener(v -> find(null, 0));
        addView(down, new LayoutParams(Ui.dp(a, 44), Ui.dp(a, 48)));
        ImageView close = Ui.iconButton(a, R.drawable.ic_close, 48);
        close.setOnClickListener(v -> hide());
        addView(close, new LayoutParams(Ui.dp(a, 48), Ui.dp(a, 48)));
    }

    void show() {
        setVisibility(VISIBLE);
        input.setText("");
        count.setText("");
        input.requestFocus();
        input.post(() -> ime().showSoftInput(input, InputMethodManager.SHOW_IMPLICIT));
    }

    boolean hide() {
        if (getVisibility() != VISIBLE) return false;
        setVisibility(GONE);
        ime().hideSoftInputFromWindow(input.getWindowToken(), 0);
        Tab t = current.get();
        if (t != null && t.session != null) t.session.getFinder().clear();
        return true;
    }

    private InputMethodManager ime() {
        return (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
    }

    private void find(String text, int flags) {
        Tab t = current.get();
        if (t == null || t.session == null) return;
        if (text != null && text.isEmpty()) {
            t.session.getFinder().clear();
            count.setText("");
            return;
        }
        t.session.getFinder().find(text, flags).accept(r -> {
            if (r != null) count.setText(r.total > 0 ? r.current + "/" + r.total : "0/0");
        });
    }
}
