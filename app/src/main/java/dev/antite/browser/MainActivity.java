package dev.antite.browser;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.URLUtil;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.StorageController;
import org.mozilla.geckoview.TranslationsController;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebExtensionController;
import org.mozilla.geckoview.WebResponse;

import java.io.InputStream;
import java.io.OutputStream;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity implements TabSwitcher.Host, Settings.Host {
    private static final String UBO_ID = "uBlock0@raymondhill.net";
    private static final String UBO_URL =
            "https://addons.mozilla.org/firefox/downloads/latest/ublock-origin/latest.xpi";
    private static final int MAX_LIVE_SESSIONS = 6;

    private static GeckoRuntime runtime;
    private static SitePerms sitePerms;
    private static IconCache icons;
    private static WebExtension iconsExt;
    private static final Map<String, WebExtension> extensions = new LinkedHashMap<>();
    private static final Map<String, WebExtension.Action> extensionActions = new LinkedHashMap<>();

    private final List<Tab> tabs = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable saveTask = this::saveNow;
    private Tab current;
    private TabStore store;
    private Prompts prompts;
    private SharedPreferences prefs;

    private FrameLayout root;
    private GeckoView geckoView;
    private LinearLayout topBar;
    private LinearLayout toolbar;
    private LinearLayout pill;
    private ImageView pageInfo;
    private ImageView homeButton;
    private LinearLayout translateBar;
    private TextView translateText;
    private EditText urlBar;
    private ImageView clearUrl;
    private ImageView newTabButton;
    private TextView tabCount;
    private ImageView menuButton;
    private ProgressBar progress;
    private LinearLayout findBar;
    private EditText findInput;
    private TextView findCount;
    private TabSwitcher switcher;
    private Settings settingsPage;
    private ListPage listPage;
    private NewTabPage ntp;
    private Suggestions suggest;
    private GeckoSession.PermissionDelegate.Callback pendingAndroidPerms;
    private Db db;
    private final java.util.concurrent.ExecutorService bg = java.util.concurrent.Executors.newSingleThreadExecutor();

    private int toolbarHeight;
    private int hidden;
    private int lastScrollY;
    private boolean fullscreen;
    private boolean navHidden;
    private int navInset;
    private int imeInset;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("antite", MODE_PRIVATE);
        store = new TabStore(this);
        db = new Db(this);
        prompts = new Prompts(this);
        prompts.popupPolicy = this::allowPopup;
        if (runtime == null) {
            runtime = GeckoRuntime.create(getApplicationContext(),
                    new org.mozilla.geckoview.GeckoRuntimeSettings.Builder()
                            .configFilePath(writeGeckoConfig())
                            .build());
            sitePerms = new SitePerms(getApplicationContext(), prefs);
            sitePerms.attach(runtime);
            Settings.apply(runtime, prefs);
            runtime.setWebNotificationDelegate(new WebNotifications(getApplicationContext()));
            icons = new IconCache(getApplicationContext(), runtime);
            setupExtensions();
            runtime.getWebExtensionController()
                    .ensureBuiltIn("resource://android/assets/extensions/icons/", "icons@antite.dev")
                    .accept(e -> {
                        iconsExt = e;
                        if (visible != null) visible.attachIconDelegates();
                    }, err -> android.util.Log.w("Antite", "icons extension failed", err));
        }
        buildUi();
        icons.setListener(this::onIconLoaded);
        restoreTabs();
        if (state == null) {
            handleIntent(getIntent(), current == null || prefs.getInt("startup", 0) == 1);
        } else if (current == null) {
            newTabAndFocus(false);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent, false);
    }

    private void handleIntent(Intent intent, boolean needTab) {
        if (intent != null && WebNotifications.ACTION_CLICK.equals(intent.getAction())) {
            WebNotifications.click(intent);
            if (!needTab) return;
        }
        Uri data = intent != null ? intent.getData() : null;
        if (data != null && Intent.ACTION_VIEW.equals(intent.getAction())) {
            hideSwitcher();
            closeSettings();
            closeListPage();
            if (prefs.getInt("external", 0) == 1 && current != null && !current.incognito) {
                ensureSession(current).loadUri(data.toString());
            } else {
                openTab(data.toString(), false, null, true);
            }
        } else if (data != null && intent.getAction() == null) {
            openTab(data.toString(), false, null, true);
        } else if (needTab) {
            newTabAndFocus(false);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        captureThumb(current);
        saveNow();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (prefs.getBoolean("closeIncognito", false)) {
            for (Tab t : new ArrayList<>(tabs)) {
                if (t.incognito) closeTab(t);
            }
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == Prompts.FILE_REQUEST) prompts.onFileResult(result, data);
        if (request == DOWNLOAD_REQUEST) onDownloadTarget(result, data);
    }

    private void setupExtensions() {
        WebExtensionController ctl = runtime.getWebExtensionController();
        ctl.setPromptDelegate(new WebExtensionController.PromptDelegate() {
            @Override
            public GeckoResult<WebExtension.PermissionPromptResponse> onInstallPromptRequest(
                    WebExtension extension, String[] permissions, String[] origins,
                    String[] dataCollectionPermissions) {
                return GeckoResult.fromValue(new WebExtension.PermissionPromptResponse(true, true, false));
            }
        });
        ctl.list().accept(list -> {
            boolean hasUbo = false;
            for (WebExtension e : list) {
                if (e.isBuiltIn) continue;
                registerExtension(e);
                if (UBO_ID.equals(e.id)) hasUbo = true;
            }
            if (!hasUbo) ctl.install(UBO_URL).accept(MainActivity::registerExtension);
        });
    }

    private static void registerExtension(WebExtension e) {
        if (e == null) return;
        extensions.put(e.id, e);
        e.setActionDelegate(new WebExtension.ActionDelegate() {
            @Override
            public void onBrowserAction(WebExtension ext, GeckoSession session, WebExtension.Action action) {
                if (session == null) extensionActions.put(ext.id, action);
            }

            @Override
            public GeckoResult<GeckoSession> onTogglePopup(WebExtension ext, WebExtension.Action action) {
                return openPopup(ext);
            }

            @Override
            public GeckoResult<GeckoSession> onOpenPopup(WebExtension ext, WebExtension.Action action) {
                return openPopup(ext);
            }
        });
    }

    private static MainActivity visible;

    private static GeckoResult<GeckoSession> openPopup(WebExtension ext) {
        MainActivity a = visible;
        if (a == null) return null;
        GeckoSession s = new GeckoSession();
        s.open(runtime);
        a.showPopupSession(ext, s);
        return GeckoResult.fromValue(s);
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = this;
    }

    @Override
    protected void onDestroy() {
        if (visible == this) visible = null;
        super.onDestroy();
    }

    private void buildUi() {
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(Ui.TOOLBAR);
        getWindow().setNavigationBarColor(Ui.TOOLBAR);
        toolbarHeight = Ui.dp(this, 56);

        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.PAGE);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            android.graphics.Insets stable = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars());
            imeInset = insets.getInsets(WindowInsets.Type.ime()).bottom;
            navInset = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
            int top = fullscreen ? 0 : stable.top;
            root.setPadding(stable.left, top, stable.right, imeInset);
            int overlayBottom = imeInset > 0 ? 0 : stable.bottom;
            switcher.setPadding(0, 0, 0, overlayBottom);
            settingsPage.setPadding(0, 0, 0, overlayBottom);
            listPage.setPadding(0, 0, 0, overlayBottom);
            ((FrameLayout.LayoutParams) translateBar.getLayoutParams()).bottomMargin = overlayBottom;
            translateBar.requestLayout();
            applyToolbarOffset();
            return WindowInsets.CONSUMED;
        });

        geckoView = new GeckoView(this);
        geckoView.setDynamicToolbarMaxHeight(toolbarHeight);
        root.addView(geckoView, new FrameLayout.LayoutParams(-1, -1));

        ntp = new NewTabPage(this, db, prefs, icons, (url, newTab) -> {
            if (newTab || current == null) {
                openTab(url, current != null && current.incognito, current, true);
            } else {
                ensureSession(current).loadUri(url);
            }
        });
        ntp.setVisibility(View.GONE);
        FrameLayout.LayoutParams ntpLp = new FrameLayout.LayoutParams(-1, -1);
        ntpLp.topMargin = toolbarHeight;
        root.addView(ntp, ntpLp);

        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.VERTICAL);
        topBar.setBackgroundColor(Ui.TOOLBAR);
        topBar.setClickable(true);

        toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 4), 0);

        pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setBackground(Ui.round(Ui.PILL, Ui.dp(this, 20)));
        pill.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);

        pageInfo = Ui.iconButton(this, R.drawable.ic_tune, 36);
        pageInfo.setContentDescription("Сведения о сайте");
        pageInfo.setOnClickListener(v -> showPageInfo());
        pill.addView(pageInfo, new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36)));

        urlBar = new EditText(this);
        urlBar.setBackground(null);
        urlBar.setSingleLine(true);
        urlBar.setTextSize(16);
        urlBar.setTextColor(Ui.TEXT);
        urlBar.setHintTextColor(Ui.TEXT_SECONDARY);
        urlBar.setHint("Введите запрос или URL");
        urlBar.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        urlBar.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        urlBar.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        urlBar.setSelectAllOnFocus(true);
        urlBar.setHorizontalFadingEdgeEnabled(true);
        urlBar.setOnFocusChangeListener((v, focused) -> onUrlFocus(focused));
        urlBar.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                navigate(urlBar.getText().toString());
                return true;
            }
            return false;
        });
        urlBar.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void afterTextChanged(Editable s) {
                if (clearUrl != null) {
                    clearUrl.setVisibility(urlBar.hasFocus() && s.length() > 0 ? View.VISIBLE : View.GONE);
                }
                if (suggest != null && urlBar.hasFocus()) {
                    suggest.update(s.toString(), current != null && current.incognito);
                }
            }
        });
        pill.addView(urlBar, new LinearLayout.LayoutParams(0, -1, 1f));

        clearUrl = Ui.iconButton(this, R.drawable.ic_close, 36);
        clearUrl.setContentDescription("Очистить");
        clearUrl.setVisibility(View.GONE);
        clearUrl.setOnClickListener(v -> urlBar.setText(""));
        pill.addView(clearUrl, new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36)));

        homeButton = Ui.iconButton(this, R.drawable.ic_home, 48);
        homeButton.setContentDescription("Главная страница");
        homeButton.setOnClickListener(v -> goHome());
        homeButton.setVisibility(prefs.getBoolean("homeButton", false) ? View.VISIBLE : View.GONE);
        toolbar.addView(homeButton, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));

        toolbar.addView(pill, new LinearLayout.LayoutParams(0, Ui.dp(this, 40), 1f));

        newTabButton = Ui.iconButton(this, R.drawable.ic_add, 48);
        newTabButton.setContentDescription("Новая вкладка");
        newTabButton.setOnClickListener(v -> newTabAndFocus(current != null && current.incognito));
        toolbar.addView(newTabButton, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));

        FrameLayout countWrap = new FrameLayout(this);
        countWrap.setBackground(Ui.borderlessRipple());
        countWrap.setClickable(true);
        countWrap.setContentDescription("Вкладки");
        tabCount = new TextView(this);
        tabCount.setGravity(Gravity.CENTER);
        tabCount.setTextColor(Ui.TEXT);
        tabCount.setTextSize(12);
        tabCount.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable box = new GradientDrawable();
        box.setStroke(Ui.dp(this, 2), Ui.TEXT);
        box.setCornerRadius(Ui.dp(this, 5));
        tabCount.setBackground(box);
        countWrap.addView(tabCount, new FrameLayout.LayoutParams(Ui.dp(this, 22), Ui.dp(this, 22), Gravity.CENTER));
        countWrap.setOnClickListener(v -> showSwitcher());
        toolbar.addView(countWrap, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));

        menuButton = Ui.iconButton(this, R.drawable.ic_more_vert, 48);
        menuButton.setContentDescription("Меню");
        menuButton.setOnClickListener(v -> showMenu());
        toolbar.addView(menuButton, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 48)));

        topBar.addView(toolbar, new LinearLayout.LayoutParams(-1, toolbarHeight - Ui.dp(this, 2)));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Ui.TOOLBAR));
        progress.setVisibility(View.INVISIBLE);
        topBar.addView(progress, new LinearLayout.LayoutParams(-1, Ui.dp(this, 2)));

        root.addView(topBar, new FrameLayout.LayoutParams(-1, toolbarHeight, Gravity.TOP));

        suggest = new Suggestions(this, db, prefs, icons, new Suggestions.Host() {
            @Override
            public void go(String input) {
                navigate(input);
            }

            @Override
            public void fill(String text) {
                urlBar.setText(text);
                urlBar.setSelection(urlBar.length());
            }
        });
        suggest.setVisibility(View.GONE);
        FrameLayout.LayoutParams sugLp = new FrameLayout.LayoutParams(-1, -1);
        sugLp.topMargin = toolbarHeight;
        root.addView(suggest, sugLp);

        buildFindBar();
        buildTranslateBar();
        root.addView(translateBar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        root.addView(findBar, new FrameLayout.LayoutParams(-1, toolbarHeight, Gravity.TOP));

        switcher = new TabSwitcher(this, this);
        switcher.setVisibility(View.GONE);
        root.addView(switcher, new FrameLayout.LayoutParams(-1, -1));

        settingsPage = new Settings(this, this, runtime, prefs, sitePerms);
        settingsPage.setVisibility(View.GONE);
        root.addView(settingsPage, new FrameLayout.LayoutParams(-1, -1));

        listPage = new ListPage(this, this::closeListPage);
        listPage.setVisibility(View.GONE);
        root.addView(listPage, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);
        applyToolbarOffset();
    }

    private void buildTranslateBar() {
        translateBar = new LinearLayout(this);
        translateBar.setOrientation(LinearLayout.HORIZONTAL);
        translateBar.setGravity(Gravity.CENTER_VERTICAL);
        translateBar.setBackgroundColor(Ui.MENU);
        translateBar.setPadding(Ui.dp(this, 16), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
        translateBar.setVisibility(View.GONE);
        translateBar.setClickable(true);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_translate);
        Ui.tint(icon, Ui.ICON);
        translateBar.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24)));
        translateText = new TextView(this);
        translateText.setTextColor(Ui.TEXT);
        translateText.setTextSize(14);
        translateText.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 8), 0);
        translateBar.addView(translateText, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView go = new TextView(this);
        go.setText("Перевести");
        go.setTextColor(Ui.ACCENT);
        go.setTextSize(14);
        go.setTypeface(Typeface.DEFAULT_BOLD);
        go.setGravity(Gravity.CENTER);
        go.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        go.setBackground(Ui.borderlessRipple());
        go.setOnClickListener(v -> {
            translateBar.setVisibility(View.GONE);
            if (current != null) translatePage(current);
        });
        translateBar.addView(go, new LinearLayout.LayoutParams(-2, Ui.dp(this, 48)));
        ImageView close = Ui.iconButton(this, R.drawable.ic_close, 48);
        close.setContentDescription("Закрыть");
        close.setOnClickListener(v -> translateBar.setVisibility(View.GONE));
        translateBar.addView(close, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
    }

    private final Runnable hideTranslateBar = () -> translateBar.setVisibility(View.GONE);

    private void offerTranslate(Tab t) {
        if (t != current || !prefs.getBoolean("translateOffer", true)) return;
        String from = t.docLang == null ? "" : langName(t.docLang) + " → ";
        translateText.setText("Перевести страницу? " + from + langName(Settings.translateTarget(prefs)));
        translateBar.setVisibility(View.VISIBLE);
        main.removeCallbacks(hideTranslateBar);
        main.postDelayed(hideTranslateBar, 15000);
    }

    private static String langName(String code) {
        java.util.Locale l = java.util.Locale.forLanguageTag(code);
        String n = l.getDisplayLanguage(java.util.Locale.forLanguageTag("ru"));
        return n.isEmpty() ? code : n;
    }

    private void translatePage(Tab t) {
        if (!isWeb(t.url)) return;
        GeckoSession s = ensureSession(t);
        TranslationsController.SessionTranslation st = s.getSessionTranslation();
        if (st == null) {
            Toast.makeText(this, "Перевод недоступен", Toast.LENGTH_SHORT).show();
            return;
        }
        if (t.translated) {
            st.restoreOriginalPage().accept(v -> t.translated = false, e -> { });
            return;
        }
        String to = Settings.translateTarget(prefs);
        if (t.docLang != null && !t.docLang.isEmpty()) {
            runTranslation(t, st, t.docLang, to);
            return;
        }
        TranslationsController.RuntimeTranslation.listSupportedLanguages().accept(sup -> {
            if (sup == null || sup.fromLanguages == null || sup.fromLanguages.isEmpty()) return;
            List<TranslationsController.Language> from = new ArrayList<>(sup.fromLanguages);
            String[] names = new String[from.size()];
            for (int i = 0; i < names.length; i++) names[i] = from.get(i).localizedDisplayName;
            new Sheet(this)
                    .setTitle("Язык страницы")
                    .setItems(names, (d, w) -> runTranslation(t, st, from.get(w).code, to))
                    .show();
        }, e -> Toast.makeText(this, "Перевод недоступен", Toast.LENGTH_SHORT).show());
    }

    private void runTranslation(Tab t, TranslationsController.SessionTranslation st, String from, String to) {
        if (from.equals(to)) {
            Toast.makeText(this, "Страница уже на языке перевода", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "Перевод… Первый раз скачивается языковая модель", Toast.LENGTH_SHORT).show();
        st.translate(from, to, new TranslationsController.SessionTranslation.TranslationOptions.Builder()
                .downloadModel(true).build()).accept(v -> { },
                e -> Toast.makeText(this, "Не удалось перевести: " + e.getMessage(), Toast.LENGTH_LONG).show());
    }

    private void buildFindBar() {
        findBar = new LinearLayout(this);
        findBar.setOrientation(LinearLayout.HORIZONTAL);
        findBar.setGravity(Gravity.CENTER_VERTICAL);
        findBar.setBackgroundColor(Ui.TOOLBAR);
        findBar.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 4), 0);
        findBar.setVisibility(View.GONE);
        findBar.setClickable(true);

        findInput = new EditText(this);
        findInput.setBackground(null);
        findInput.setSingleLine(true);
        findInput.setTextColor(Ui.TEXT);
        findInput.setHintTextColor(Ui.TEXT_SECONDARY);
        findInput.setHint("Найти на странице");
        findInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        findInput.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void afterTextChanged(Editable s) {
                find(s.toString(), 0);
            }
        });
        findInput.setOnEditorActionListener((v, id, e) -> {
            find(null, 0);
            return true;
        });
        findBar.addView(findInput, new LinearLayout.LayoutParams(0, -1, 1f));

        findCount = new TextView(this);
        findCount.setTextColor(Ui.TEXT_SECONDARY);
        findCount.setTextSize(14);
        findCount.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), 0);
        findBar.addView(findCount);

        ImageView up = Ui.iconButton(this, R.drawable.ic_keyboard_arrow_up, 48);
        up.setOnClickListener(v -> find(null, GeckoSession.FINDER_FIND_BACKWARDS));
        findBar.addView(up, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 48)));
        ImageView down = Ui.iconButton(this, R.drawable.ic_keyboard_arrow_down, 48);
        down.setOnClickListener(v -> find(null, 0));
        findBar.addView(down, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 48)));
        ImageView close = Ui.iconButton(this, R.drawable.ic_close, 48);
        close.setOnClickListener(v -> hideFindBar());
        findBar.addView(close, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
    }

    private void applyChrome() {
        boolean inc = current != null && current.incognito;
        int bar = inc ? Ui.TOOLBAR_INCOGNITO : Ui.TOOLBAR;
        topBar.setBackgroundColor(bar);
        findBar.setBackgroundColor(bar);
        root.setBackgroundColor(bar);
        getWindow().setNavigationBarColor(bar);
        getWindow().setStatusBarColor(switcher.getVisibility() == View.VISIBLE ? Ui.SWITCHER_BG : bar);
        pill.setBackground(Ui.round(inc ? Ui.PILL_INCOGNITO : Ui.PILL, Ui.dp(this, 20)));
        progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(bar));
        pageInfo.setImageResource(inc ? R.drawable.ic_incognito : R.drawable.ic_tune);
        int n = 0;
        for (Tab t : tabs) {
            if (t.incognito == inc) n++;
        }
        tabCount.setText(n >= 100 ? ":D" : String.valueOf(n));
        updateNtp();
        updateUrlText();
        updateProgress();
    }

    private void updateUrlText() {
        if (urlBar.hasFocus()) return;
        String url = current == null ? "" : current.url;
        if (url.isEmpty() || "about:blank".equals(url)) {
            urlBar.setText("");
            return;
        }
        SpannableString s = new SpannableString(url);
        if (url.startsWith("https://")) {
            s.setSpan(new ForegroundColorSpan(Ui.SECURE), 0, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            s.setSpan(new ForegroundColorSpan(Ui.TEXT_SECONDARY), 5, 8, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else if (url.startsWith("http://")) {
            s.setSpan(new ForegroundColorSpan(Ui.TEXT_SECONDARY), 0, 7, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        urlBar.setText(s);
    }

    private void updateProgress() {
        if (current == null || !current.loading) {
            progress.setVisibility(View.INVISIBLE);
            return;
        }
        progress.setVisibility(View.VISIBLE);
        progress.setProgress(Math.max(5, current.progress));
    }

    private void onUrlFocus(boolean focused) {
        newTabButton.setVisibility(focused ? View.GONE : View.VISIBLE);
        ((View) tabCount.getParent()).setVisibility(focused ? View.GONE : View.VISIBLE);
        menuButton.setVisibility(focused ? View.GONE : View.VISIBLE);
        pageInfo.setVisibility(focused ? View.GONE : View.VISIBLE);
        clearUrl.setVisibility(focused && urlBar.length() > 0 ? View.VISIBLE : View.GONE);
        urlBar.setPadding(Ui.dp(this, focused ? 12 : 4), 0, Ui.dp(this, 4), 0);
        if (focused) {
            setHidden(0);
            String url = current == null ? "" : current.url;
            urlBar.setText("about:blank".equals(url) ? "" : url);
            urlBar.selectAll();
        } else {
            suggest.setVisibility(View.GONE);
            updateUrlText();
        }
    }

    private void focusUrlBar() {
        urlBar.requestFocus();
        urlBar.post(() -> ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE))
                .showSoftInput(urlBar, InputMethodManager.SHOW_IMPLICIT));
    }

    private void hideKeyboard(View v) {
        ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    private void navigate(String input) {
        String s = input.trim();
        if (s.isEmpty() || current == null) return;
        String url;
        if (s.contains("://") || s.startsWith("about:") || s.startsWith("view-source:")
                || s.startsWith("data:") || s.startsWith("moz-extension:")) {
            url = s;
        } else if (!s.contains(" ") && s.contains(".") && !s.endsWith(".")) {
            url = "https://" + s;
        } else {
            url = Settings.ENGINE_URLS[prefs.getInt("engine", 0)] + Uri.encode(s);
        }
        ensureSession(current).loadUri(url);
        urlBar.clearFocus();
        hideKeyboard(urlBar);
        geckoView.requestFocus();
    }

    private void setHidden(int h) {
        int v = Math.max(0, Math.min(toolbarHeight, h));
        if (v == hidden) return;
        hidden = v;
        applyToolbarOffset();
    }

    private void applyToolbarOffset() {
        if (fullscreen) {
            topBar.setTranslationY(-toolbarHeight);
            geckoView.setTranslationY(0);
            geckoView.setVerticalClipping(0);
            return;
        }
        int visibleBar = toolbarHeight - hidden;
        topBar.setTranslationY(-hidden);
        geckoView.setTranslationY(visibleBar);
        int navCover = imeInset > 0 ? 0 : navInset;
        geckoView.setVerticalClipping(navCover - hidden);
        updateNavBar();
    }

    private void updateNavBar() {
        WindowInsetsController c = getWindow().getInsetsController();
        if (c == null || fullscreen) return;
        boolean overlay = switcher.getVisibility() == View.VISIBLE
                || settingsPage.getVisibility() == View.VISIBLE
                || listPage.getVisibility() == View.VISIBLE;
        boolean hide = hidden >= toolbarHeight && !overlay && !urlBar.hasFocus();
        if (hide == navHidden) return;
        navHidden = hide;
        if (hide) {
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            c.hide(WindowInsets.Type.navigationBars());
        } else {
            c.show(WindowInsets.Type.navigationBars());
        }
    }

    private void restoreTabs() {
        TabStore.Loaded loaded = store.load();
        tabs.addAll(loaded.tabs);
        Tab sel = null;
        for (Tab t : tabs) {
            if (t.id == loaded.selected) sel = t;
        }
        if (sel == null && !tabs.isEmpty()) sel = tabs.get(tabs.size() - 1);
        if (sel != null) switchTo(sel);
    }

    @Override
    public List<Tab> tabs() {
        return tabs;
    }

    @Override
    public Tab current() {
        return current;
    }

    @Override
    public void select(Tab t) {
        hideSwitcher();
        switchTo(t);
    }

    @Override
    public void close(Tab t) {
        closeTab(t);
    }

    @Override
    public void newTab(boolean incognito) {
        hideSwitcher();
        newTabAndFocus(incognito);
    }

    @Override
    public void moved(Tab t, Tab before) {
        tabs.remove(t);
        int idx = before == null ? tabs.size() : tabs.indexOf(before);
        tabs.add(idx < 0 ? tabs.size() : idx, t);
        scheduleSave();
    }

    @Override
    public Bitmap thumb(Tab t) {
        if (t.thumb == null && !t.incognito) t.thumb = store.loadThumb(t.id);
        return t.thumb;
    }

    private void newTabAndFocus(boolean incognito) {
        openTab("about:blank", incognito, null, true);
        focusUrlBar();
    }

    private Tab openTab(String url, boolean incognito, Tab parent, boolean select) {
        Tab t = new Tab(incognito);
        t.url = url;
        t.desktop = prefs.getBoolean("desktopDefault", false);
        if (parent != null) t.parentId = parent.id;
        int idx = parent != null ? tabs.indexOf(parent) + 1 : tabs.size();
        tabs.add(idx, t);
        if (select) {
            switchTo(t);
        } else {
            ensureSession(t);
            trimSessions();
            applyChrome();
        }
        scheduleSave();
        return t;
    }

    private GeckoSession createSession(Tab t) {
        GeckoSessionSettings.Builder sb = new GeckoSessionSettings.Builder()
                .usePrivateMode(t.incognito)
                .allowJavascript(sitePerms.decide(SitePerms.JS, siteHost(t.url)) == SitePerms.ALLOW);
        if (t.desktop) {
            sb.userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP)
                    .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_DESKTOP);
        }
        GeckoSession s = new GeckoSession(sb.build());
        attachDelegates(t, s);
        t.session = s;
        return s;
    }

    private GeckoSession ensureSession(Tab t) {
        if (t.session != null) return t.session;
        GeckoSession s = createSession(t);
        s.open(runtime);
        if (t.state != null) {
            try {
                s.restoreState(GeckoSession.SessionState.fromString(t.state));
            } catch (Exception e) {
                s.loadUri(t.url.isEmpty() ? "about:blank" : t.url);
            }
        } else {
            s.loadUri(t.url.isEmpty() ? "about:blank" : t.url);
        }
        return s;
    }

    private void switchTo(Tab t) {
        if (current != null && current != t) {
            if (current.session != null) {
                current.session.setActive(false);
                runtime.getWebExtensionController().setTabActive(current.session, false);
            }
        }
        if (urlBar.hasFocus()) {
            urlBar.clearFocus();
            hideKeyboard(urlBar);
        }
        current = t;
        t.lastUsed = System.currentTimeMillis();
        GeckoSession s = ensureSession(t);
        if (geckoView.getSession() != s) {
            geckoView.releaseSession();
            geckoView.setSession(s);
        }
        s.setActive(true);
        runtime.getWebExtensionController().setTabActive(s, true);
        lastScrollY = -1;
        setHidden(0);
        hideFindBar();
        trimSessions();
        applyChrome();
        scheduleSave();
    }

    private void trimSessions() {
        List<Tab> live = new ArrayList<>();
        for (Tab t : tabs) {
            if (t.isLive() && !t.incognito && t != current) live.add(t);
        }
        if (live.size() < MAX_LIVE_SESSIONS) return;
        live.sort(Comparator.comparingLong(x -> x.lastUsed));
        for (int i = 0; i <= live.size() - MAX_LIVE_SESSIONS; i++) {
            Tab t = live.get(i);
            t.session.close();
            t.session = null;
        }
    }

    private void closeTab(Tab t) {
        int i = tabs.indexOf(t);
        if (i < 0) return;
        tabs.remove(i);
        if (t == current && geckoView.getSession() == t.session) geckoView.releaseSession();
        if (t.session != null) t.session.close();
        t.session = null;
        store.deleteThumb(t.id);
        db.addClosed(t);
        if (t == current) {
            current = null;
            Tab next = null;
            for (Tab x : tabs) {
                if (x.id == t.parentId && x.incognito == t.incognito) next = x;
            }
            if (next == null) {
                for (int k = Math.min(i, tabs.size()) - 1; k >= 0 && next == null; k--) {
                    if (tabs.get(k).incognito == t.incognito) next = tabs.get(k);
                }
                for (int k = i; k < tabs.size() && next == null; k++) {
                    if (tabs.get(k).incognito == t.incognito) next = tabs.get(k);
                }
            }
            if (next == null) {
                for (Tab x : tabs) {
                    if (!x.incognito && (next == null || x.lastUsed > next.lastUsed)) next = x;
                }
            }
            if (next != null) {
                switchTo(next);
            } else if (switcher.getVisibility() == View.VISIBLE) {
                applyChrome();
            } else {
                newTabAndFocus(false);
            }
        } else {
            applyChrome();
        }
        scheduleSave();
    }

    private void attachIconDelegates() {
        for (Tab t : tabs) {
            if (t.session != null) attachIconDelegate(t, t.session);
        }
    }

    private void attachIconDelegate(Tab t, GeckoSession s) {
        if (iconsExt == null || t.incognito) return;
        s.getWebExtensionController().setMessageDelegate(iconsExt, new WebExtension.MessageDelegate() {
            @Override
            public GeckoResult<Object> onMessage(String app, Object message, WebExtension.MessageSender sender) {
                if (message instanceof org.json.JSONObject) {
                    org.json.JSONObject m = (org.json.JSONObject) message;
                    Uri page = Uri.parse(m.optString("page"));
                    if (page.getHost() != null) {
                        org.json.JSONArray th = m.optJSONArray("theme");
                        icons.setTheme(host(page.toString()), th == null || th.length() < 3 ? 0
                                : android.graphics.Color.rgb(th.optInt(0), th.optInt(1), th.optInt(2)));
                        icons.onPageIcon(host(page.toString()), m.optString("icon", null),
                                page.getScheme() + "://" + page.getHost());
                    }
                }
                return null;
            }
        }, "antite");
    }

    private void onIconLoaded(String host) {
        if (ntp.getVisibility() == View.VISIBLE) ntp.refresh();
        if (switcher.getVisibility() == View.VISIBLE) switcher.refresh();
        if (listPage.getVisibility() == View.VISIBLE) listPage.refresh();
    }

    @Override
    public Bitmap icon(Tab t) {
        return icons.get(host(t.url));
    }

    @Override
    public int tabColor(Tab t) {
        return t.incognito ? 0 : icons.color(host(t.url));
    }

    @Override
    public boolean listLayout() {
        return prefs.getInt("tabLayout", 0) == 1;
    }

    private void attachDelegates(Tab t, GeckoSession s) {
        attachIconDelegate(t, s);
        s.setMediaSessionDelegate(new org.mozilla.geckoview.MediaSession.Delegate() {
            @Override
            public void onActivated(GeckoSession session, org.mozilla.geckoview.MediaSession ms) {
                MediaService.gecko = ms;
                MediaService.title = t.title;
                MediaService.artist = host(t.url);
                MediaService.art = icons.get(host(t.url));
            }

            @Override
            public void onDeactivated(GeckoSession session, org.mozilla.geckoview.MediaSession ms) {
                if (MediaService.gecko == ms) MediaService.stop(MainActivity.this);
            }

            @Override
            public void onMetadata(GeckoSession session, org.mozilla.geckoview.MediaSession ms,
                                   org.mozilla.geckoview.MediaSession.Metadata meta) {
                if (MediaService.gecko != ms) return;
                MediaService.title = meta.title != null && !meta.title.isEmpty() ? meta.title : t.title;
                MediaService.artist = meta.artist != null && !meta.artist.isEmpty() ? meta.artist : host(t.url);
                if (meta.artwork != null) {
                    meta.artwork.getBitmap(256).accept(b -> {
                        if (b != null && MediaService.gecko == ms) {
                            MediaService.art = b;
                            MediaService.update(MainActivity.this);
                        }
                    }, e -> { });
                }
                if (MediaService.playing) MediaService.update(MainActivity.this);
            }

            @Override
            public void onPlay(GeckoSession session, org.mozilla.geckoview.MediaSession ms) {
                MediaService.gecko = ms;
                MediaService.playing = true;
                askNotificationsOnce();
                MediaService.update(MainActivity.this);
            }

            @Override
            public void onPause(GeckoSession session, org.mozilla.geckoview.MediaSession ms) {
                if (MediaService.gecko != ms) return;
                MediaService.playing = false;
                MediaService.update(MainActivity.this);
            }

            @Override
            public void onStop(GeckoSession session, org.mozilla.geckoview.MediaSession ms) {
                if (MediaService.gecko == ms) MediaService.stop(MainActivity.this);
            }
        });
        s.setPromptDelegate(prompts);
        s.setTranslationsSessionDelegate(new TranslationsController.SessionTranslation.Delegate() {
            @Override
            public void onOfferTranslate(GeckoSession session) {
                offerTranslate(t);
            }

            @Override
            public void onTranslationStateChange(GeckoSession session,
                                                 TranslationsController.SessionTranslation.TranslationState state) {
                if (state == null) return;
                if (state.detectedLanguages != null) t.docLang = state.detectedLanguages.docLangTag;
                t.translated = state.requestedTranslationPair != null;
                if (state.error != null && t == current) {
                    Toast.makeText(MainActivity.this, "Ошибка перевода: " + state.error, Toast.LENGTH_LONG).show();
                }
            }
        });
        s.setHistoryDelegate(new GeckoSession.HistoryDelegate() {
            @Override
            public GeckoResult<Boolean> onVisited(GeckoSession session, String url, String last, int flags) {
                if (t.incognito || (flags & VISIT_TOP_LEVEL) == 0 || (flags & VISIT_UNRECOVERABLE_ERROR) != 0
                        || (flags & (VISIT_REDIRECT_SOURCE | VISIT_REDIRECT_SOURCE_PERMANENT)) != 0
                        || !isWeb(url)) {
                    return GeckoResult.fromValue(false);
                }
                db.visit(url, t.url.equals(url) ? t.title : null);
                return GeckoResult.fromValue(true);
            }

            @Override
            public GeckoResult<boolean[]> getVisited(GeckoSession session, String[] urls) {
                GeckoResult<boolean[]> r = new GeckoResult<>();
                bg.execute(() -> r.complete(db.visited(urls)));
                return r;
            }
        });
        s.setPermissionDelegate(new GeckoSession.PermissionDelegate() {
            @Override
            public void onAndroidPermissionsRequest(GeckoSession session, String[] perms, Callback callback) {
                requestAndroidPermissions(perms, callback);
            }

            @Override
            public void onMediaPermissionRequest(GeckoSession session, String uri, MediaSource[] video,
                                                 MediaSource[] audio, MediaCallback callback) {
                MediaSource cam = null;
                MediaSource mic = null;
                if (video != null) {
                    for (MediaSource m : video) {
                        if (m.source == MediaSource.SOURCE_CAMERA) {
                            cam = m;
                            break;
                        }
                    }
                }
                if (audio != null) {
                    for (MediaSource m : audio) {
                        if (m.source == MediaSource.SOURCE_MICROPHONE) {
                            mic = m;
                            break;
                        }
                    }
                }
                String h = siteHost(uri);
                int camMode = cam == null ? SitePerms.BLOCK : sitePerms.decide(SitePerms.CAMERA, h);
                int micMode = mic == null ? SitePerms.BLOCK : sitePerms.decide(SitePerms.MIC, h);
                if (camMode == SitePerms.BLOCK) cam = null;
                if (micMode == SitePerms.BLOCK) mic = null;
                if (cam == null && mic == null) {
                    callback.reject();
                    return;
                }
                MediaSource fc = cam;
                MediaSource fm = mic;
                boolean askCam = cam != null && camMode == SitePerms.ASK;
                boolean askMic = mic != null && micMode == SitePerms.ASK;
                if (!askCam && !askMic) {
                    callback.grant(fc, fm);
                    return;
                }
                String what = askCam && askMic ? "доступ к камере и микрофону" : askCam ? "доступ к камере" : "доступ к микрофону";
                askSite(askCam ? R.drawable.ic_videocam : R.drawable.ic_mic, h, what, answer -> {
                    if (answer == SitePerms.ALLOW || answer == SitePerms.BLOCK) {
                        if (!t.incognito) {
                            if (askCam) sitePerms.setException(SitePerms.CAMERA, h, answer);
                            if (askMic) sitePerms.setException(SitePerms.MIC, h, answer);
                        }
                    }
                    if (answer == SitePerms.ALLOW || answer == ALLOW_ONCE) {
                        callback.grant(fc, fm);
                    } else if (!askCam || !askMic) {
                        MediaSource gc = askCam ? null : fc;
                        MediaSource gm = askMic ? null : fm;
                        if (gc == null && gm == null) {
                            callback.reject();
                        } else {
                            callback.grant(gc, gm);
                        }
                    } else {
                        callback.reject();
                    }
                });
            }

            @Override
            public GeckoResult<Integer> onContentPermissionRequest(GeckoSession session, ContentPermission perm) {
                SitePerms.Cat c = SitePerms.forGecko(perm.permission);
                if (c == null) return GeckoResult.fromValue(ContentPermission.VALUE_DENY);
                String h = siteHost(perm.uri);
                if (c == SitePerms.AUTOPLAY) {
                    sitePerms.forgetAuto(perm);
                    return GeckoResult.fromValue(Settings.autoplayDecision(sitePerms, h, perm.permission));
                }
                boolean fromException = sitePerms.exception(c, h) >= 0;
                int mode = sitePerms.decide(c, h);
                if (mode != SitePerms.ASK) {
                    if (!fromException) sitePerms.forgetAuto(perm);
                    return GeckoResult.fromValue(mode == SitePerms.ALLOW
                            ? ContentPermission.VALUE_ALLOW : ContentPermission.VALUE_DENY);
                }
                GeckoResult<Integer> r = new GeckoResult<>();
                askSite(permIcon(c), h, c.request, answer -> {
                    if ((answer == SitePerms.ALLOW || answer == SitePerms.BLOCK) && !t.incognito) {
                        sitePerms.setException(c, h, answer);
                    } else {
                        sitePerms.forgetAuto(perm);
                    }
                    boolean allow = answer == SitePerms.ALLOW || answer == ALLOW_ONCE;
                    if (allow && c == SitePerms.NOTIFICATIONS) requestPostNotifications();
                    r.complete(allow ? ContentPermission.VALUE_ALLOW : ContentPermission.VALUE_DENY);
                });
                return r;
            }
        });
        s.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override
            public GeckoResult<org.mozilla.geckoview.AllowOrDeny> onLoadRequest(GeckoSession session,
                                                                                LoadRequest request) {
                boolean js = sitePerms.decide(SitePerms.JS, siteHost(request.uri)) == SitePerms.ALLOW;
                if (session.getSettings().getAllowJavascript() != js) session.getSettings().setAllowJavascript(js);
                return null;
            }

            @Override
            public void onLocationChange(GeckoSession session, String url,
                                         List<GeckoSession.PermissionDelegate.ContentPermission> perms,
                                         Boolean hasUserGesture) {
                t.url = url == null ? "" : url;
                if (t == current) {
                    updateUrlText();
                    updateNtp();
                }
            }

            @Override
            public void onCanGoBack(GeckoSession session, boolean can) {
                t.canGoBack = can;
            }

            @Override
            public void onCanGoForward(GeckoSession session, boolean can) {
                t.canGoForward = can;
            }

            @Override
            public GeckoResult<GeckoSession> onNewSession(GeckoSession session, String uri) {
                Tab child = new Tab(t.incognito);
                child.url = uri;
                child.parentId = t.id;
                tabs.add(tabs.indexOf(t) + 1, child);
                GeckoSession cs = createSession(child);
                main.post(() -> switchTo(child));
                return GeckoResult.fromValue(cs);
            }
        });
        s.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override
            public void onPageStart(GeckoSession session, String url) {
                t.loading = true;
                t.progress = 0;
                if (t == current) {
                    setHidden(0);
                    updateProgress();
                }
            }

            @Override
            public void onPageStop(GeckoSession session, boolean success) {
                t.loading = false;
                if (t == current) {
                    updateProgress();
                    main.postDelayed(() -> {
                        if (t == current && !t.loading) captureThumb(t);
                    }, 1000);
                }
            }

            @Override
            public void onProgressChange(GeckoSession session, int p) {
                t.progress = p;
                if (t == current) updateProgress();
            }

            @Override
            public void onSecurityChange(GeckoSession session, SecurityInformation info) {
                t.secure = info.isSecure;
                t.host = info.host == null ? "" : info.host;
                X509Certificate c = info.certificate;
                t.issuer = c != null ? c.getIssuerX500Principal().getName() : "";
            }

            @Override
            public void onSessionStateChange(GeckoSession session, GeckoSession.SessionState state) {
                if (t.incognito) return;
                t.state = state.toString();
                scheduleSave();
            }
        });
        s.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override
            public void onTitleChange(GeckoSession session, String title) {
                t.title = title == null ? "" : title;
                if (!t.incognito) db.title(t.url, t.title);
                scheduleSave();
            }

            @Override
            public void onCloseRequest(GeckoSession session) {
                closeTab(t);
            }

            @Override
            public void onFocusRequest(GeckoSession session) {
                if (t != current) main.post(() -> switchTo(t));
            }

            @Override
            public void onFirstContentfulPaint(GeckoSession session) {
                main.postDelayed(() -> {
                    if (t == current) captureThumb(t);
                }, 1500);
            }

            @Override
            public void onFullScreen(GeckoSession session, boolean full) {
                if (t == current) setFullscreen(full);
            }

            @Override
            public void onContextMenu(GeckoSession session, int x, int y, ContextElement el) {
                showContextMenu(t, el);
            }

            @Override
            public void onExternalResponse(GeckoSession session, WebResponse response) {
                download(response);
            }

            @Override
            public void onCrash(GeckoSession session) {
                recover(t);
            }

            @Override
            public void onKill(GeckoSession session) {
                recover(t);
            }
        });
        s.setScrollDelegate(new GeckoSession.ScrollDelegate() {
            @Override
            public void onScrollChanged(GeckoSession session, int x, int y) {
                if (t != current || urlBar.hasFocus()) return;
                if (lastScrollY < 0) {
                    lastScrollY = y;
                    return;
                }
                int dy = y - lastScrollY;
                lastScrollY = y;
                if (y <= 0) {
                    setHidden(0);
                } else {
                    setHidden(hidden + dy);
                }
            }
        });
    }

    private void recover(Tab t) {
        if (t.session != null) {
            if (geckoView.getSession() == t.session) geckoView.releaseSession();
            t.session.close();
            t.session = null;
        }
        if (t == current) switchTo(t);
    }

    private void setFullscreen(boolean full) {
        fullscreen = full;
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            if (full) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            } else {
                c.show(WindowInsets.Type.systemBars());
                navHidden = false;
            }
        }
        root.requestApplyInsets();
        applyToolbarOffset();
    }

    private void captureThumb(Tab t) {
        if (t == null || t.session == null || geckoView.getSession() != t.session
                || switcher.getVisibility() == View.VISIBLE) {
            return;
        }
        try {
            geckoView.capturePixels().accept(b -> {
                if (b == null) return;
                int w = Math.max(1, b.getWidth() / 3);
                int h = Math.max(1, b.getHeight() / 3);
                t.thumb = Bitmap.createScaledBitmap(b, w, h, true);
                store.saveThumb(t);
            }, e -> { });
        } catch (Exception ignored) {
        }
    }

    private void showSwitcher() {
        captureThumb(current);
        main.postDelayed(() -> {
            switcher.open(current != null && current.incognito);
            switcher.setVisibility(View.VISIBLE);
            setHidden(0);
            updateNavBar();
            getWindow().setStatusBarColor(Ui.SWITCHER_BG);
        }, 60);
    }

    private void hideSwitcher() {
        if (switcher.getVisibility() != View.VISIBLE) return;
        switcher.setVisibility(View.GONE);
        if (current == null) newTabAndFocus(false);
        applyChrome();
    }

    private void showMenu() {
        Tab t = current;
        if (t == null) return;
        boolean dark = Settings.isDark(this, prefs);
        boolean webPage = isWeb(t.url);
        boolean bookmarked = webPage && db.isBookmarked(t.url);
        AppMenu m = new AppMenu()
                .icon(R.drawable.ic_arrow_forward, "Вперёд", t.canGoForward,
                        () -> ensureSession(t).goForward())
                .icon(bookmarked ? R.drawable.ic_star_fill1 : R.drawable.ic_star,
                        bookmarked ? "Удалить из закладок" : "Добавить в закладки", webPage,
                        () -> toggleBookmark(t))
                .icon(R.drawable.ic_download, "Сохранить как PDF", webPage, () -> savePdf(t))
                .icon(R.drawable.ic_info, "Сведения о сайте", true, this::showPageInfo)
                .icon(t.loading ? R.drawable.ic_close : R.drawable.ic_refresh,
                        t.loading ? "Остановить" : "Обновить", true, () -> {
                            if (t.loading) {
                                ensureSession(t).stop();
                            } else {
                                ensureSession(t).reload();
                            }
                        })
                .item(R.drawable.ic_add_box, "Новая вкладка", () -> newTabAndFocus(false))
                .item(R.drawable.ic_incognito, "Новая вкладка инкогнито", () -> newTabAndFocus(true))
                .divider()
                .item(R.drawable.ic_history, "История", this::showHistory)
                .item(R.drawable.ic_delete, "Удалить данные о работе в браузере", settingsPage::showClearData)
                .divider()
                .item(R.drawable.ic_download_done, "Скачанные файлы", this::showDownloads)
                .item(R.drawable.ic_star, "Закладки", this::showBookmarks)
                .item(R.drawable.ic_folder, "Добавить все вкладки в закладки", this::bookmarkAllTabs)
                .item(R.drawable.ic_devices, "Недавние вкладки", this::showClosedTabs)
                .item(R.drawable.ic_extension, "Открыть меню расширений", this::showExtensions)
                .divider()
                .item(R.drawable.ic_share, "Поделиться…", this::shareCurrent)
                .item(R.drawable.ic_find_in_page, "Найти на странице", this::showFindBar)
                .item(R.drawable.ic_translate, t.translated ? "Показать оригинал" : "Перевести…",
                        () -> translatePage(t))
                .submenu(R.drawable.ic_home_repair_service, "Ещё инструменты", () -> showMoreTools(t))
                .item(R.drawable.ic_install_desktop, "Установить и создать ярлык", () -> addShortcut(t))
                .item(R.drawable.ic_description, "View source", () -> {
                    if (!t.url.isEmpty() && !t.url.startsWith("view-source:")) {
                        openTab("view-source:" + t.url, t.incognito, t, true);
                    }
                })
                .check(R.drawable.ic_desktop_windows, "Версия для ПК", t.desktop, () -> toggleDesktop(t))
                .check(R.drawable.ic_brightness_6, "Тёмная тема", dark, this::toggleDark)
                .divider()
                .item(R.drawable.ic_settings, "Настройки", this::showSettings)
                .item(R.drawable.ic_exit_to_app, "Exit", this::exit);
        m.show(this, root);
    }

    private void toggleDesktop(Tab t) {
        t.desktop = !t.desktop;
        GeckoSession s = ensureSession(t);
        s.getSettings().setUserAgentMode(t.desktop
                ? GeckoSessionSettings.USER_AGENT_MODE_DESKTOP : GeckoSessionSettings.USER_AGENT_MODE_MOBILE);
        s.getSettings().setViewportMode(t.desktop
                ? GeckoSessionSettings.VIEWPORT_MODE_DESKTOP : GeckoSessionSettings.VIEWPORT_MODE_MOBILE);
        s.reload();
        scheduleSave();
    }

    private void toggleDark() {
        boolean dark = !Settings.isDark(this, prefs);
        prefs.edit().putInt("theme", dark ? 2 : 1).remove("dark").apply();
        Settings.apply(runtime, prefs);
    }

    private void shareCurrent() {
        if (current == null || current.url.isEmpty()) return;
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, current.url);
        if (!current.title.isEmpty()) i.putExtra(Intent.EXTRA_SUBJECT, current.title);
        startActivity(Intent.createChooser(i, null));
    }

    private String writeGeckoConfig() {
        java.io.File f = new java.io.File(getFilesDir(), "gecko-config.yaml");
        String yaml = "prefs:\n"
                + "  media.hardwaremediakeys.enabled: true\n";
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
            out.write(yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            android.util.Log.w("Antite", "gecko config write failed", e);
        }
        return f.getPath();
    }

    private void requestPostNotifications() {
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 4344);
        }
    }

    private void askNotificationsOnce() {
        if (prefs.getBoolean("askedNotif", false)) return;
        prefs.edit().putBoolean("askedNotif", true).apply();
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 4344);
        }
    }

    private void updateNtp() {
        if (ntp == null) return;
        boolean show = current != null && (current.url.isEmpty() || "about:blank".equals(current.url));
        if (show && ntp.getVisibility() != View.VISIBLE) {
            ntp.setVisibility(View.VISIBLE);
            ntp.refresh();
        } else if (show) {
            ntp.refresh();
        } else if (!show && ntp.getVisibility() == View.VISIBLE) {
            ntp.setVisibility(View.GONE);
        }
    }

    private static final int ALLOW_ONCE = 4;

    private void askSite(int icon, String host, String what, java.util.function.IntConsumer answer) {
        int[] result = {-1};
        new Sheet(this)
                .setIcon(icon)
                .setTitle(host + " запрашивает " + what)
                .setStacked(true)
                .setPositiveButton("Разрешить при посещении сайта", (d, w) -> result[0] = SitePerms.ALLOW)
                .setNeutralButton("Разрешить в этот раз", (d, w) -> result[0] = ALLOW_ONCE)
                .setNegativeButton("Не разрешать", (d, w) -> result[0] = SitePerms.BLOCK)
                .setOnDismissListener(d -> answer.accept(result[0]))
                .show();
    }

    private static int permIcon(SitePerms.Cat c) {
        if (c == SitePerms.LOCATION) return R.drawable.ic_location_on;
        if (c == SitePerms.NOTIFICATIONS) return R.drawable.ic_notifications;
        if (c == SitePerms.LNA) return R.drawable.ic_lan;
        if (c == SitePerms.XR) return R.drawable.ic_view_in_ar;
        if (c == SitePerms.STORAGE) return R.drawable.ic_database;
        if (c == SitePerms.DRM) return R.drawable.ic_shield_lock;
        return R.drawable.ic_public;
    }

    private void requestAndroidPermissions(String[] perms, GeckoSession.PermissionDelegate.Callback callback) {
        List<String> need = new ArrayList<>();
        if (perms != null) {
            for (String p : perms) {
                if (checkSelfPermission(p) != android.content.pm.PackageManager.PERMISSION_GRANTED) need.add(p);
            }
        }
        if (need.isEmpty()) {
            callback.grant();
            return;
        }
        if (pendingAndroidPerms != null) pendingAndroidPerms.reject();
        pendingAndroidPerms = callback;
        requestPermissions(need.toArray(new String[0]), 4343);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code != 4343 || pendingAndroidPerms == null) return;
        GeckoSession.PermissionDelegate.Callback cb = pendingAndroidPerms;
        pendingAndroidPerms = null;
        for (int r : results) {
            if (r != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                cb.reject();
                return;
            }
        }
        if (results.length == 0) {
            cb.reject();
        } else {
            cb.grant();
        }
    }

    private static boolean isWeb(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }

    private boolean allowPopup(GeckoSession s) {
        Tab owner = null;
        for (Tab t : tabs) {
            if (t.session == s) owner = t;
        }
        String h = owner == null ? "" : siteHost(owner.url);
        boolean ok = sitePerms.decide(SitePerms.POPUPS, h) == SitePerms.ALLOW;
        if (!ok) Toast.makeText(this, "Всплывающее окно заблокировано", Toast.LENGTH_SHORT).show();
        return ok;
    }

    static String siteHost(String url) {
        String h = url == null ? null : Uri.parse(url).getHost();
        return h == null ? "" : h.toLowerCase(java.util.Locale.ROOT);
    }

    private static String host(String url) {
        String h = url == null ? null : Uri.parse(url).getHost();
        return h == null ? (url == null ? "" : url) : h.startsWith("www.") ? h.substring(4) : h;
    }

    private void toggleBookmark(Tab t) {
        if (db.isBookmarked(t.url)) {
            db.removeBookmark(t.url);
            Toast.makeText(this, "Удалено из закладок", Toast.LENGTH_SHORT).show();
        } else {
            db.addBookmark(t.url, t.title);
            Toast.makeText(this, "Добавлено в закладки", Toast.LENGTH_SHORT).show();
        }
    }

    private void bookmarkAllTabs() {
        int n = 0;
        for (Tab t : tabs) {
            if (t.incognito || !isWeb(t.url)) continue;
            db.addBookmark(t.url, t.title);
            n++;
        }
        Toast.makeText(this, "В закладки добавлено вкладок: " + n, Toast.LENGTH_SHORT).show();
    }

    private void openListPage(ListPage.Source src) {
        listPage.show(src);
        setHidden(0);
        updateNavBar();
        getWindow().setStatusBarColor(Ui.SWITCHER_BG);
    }

    private void closeListPage() {
        if (listPage == null || listPage.getVisibility() != View.VISIBLE) return;
        listPage.setVisibility(View.GONE);
        hideKeyboard(listPage);
        applyChrome();
    }

    private void openFromList(String url, boolean newTab) {
        closeListPage();
        hideSwitcher();
        if (newTab || current == null || current.incognito) {
            openTab(url, false, null, true);
        } else {
            ensureSession(current).loadUri(url);
        }
    }

    private void entryActions(Db.Entry e, String[] labels, Runnable[] actions) {
        new Sheet(this)
                .setTitle(e.title == null || e.title.isEmpty() ? e.url : e.title)
                .setItems(labels, (d, w) -> actions[w].run())
                .show();
    }

    private void confirm(String text, String button, Runnable action) {
        new Sheet(this)
                .setMessage(text)
                .setPositiveButton(button, (d, w) -> action.run())
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void showHistory() {
        openListPage(new ListPage.Source() {
            public String title() {
                return "История";
            }

            public int icon() {
                return R.drawable.ic_history;
            }

            public String emptyText() {
                return "История пуста";
            }

            public List<Db.Entry> load(String filter) {
                return db.history(filter, 2000);
            }

            public android.graphics.Bitmap image(Db.Entry e) {
                return icons.get(host(e.url));
            }


            public String subtitle(Db.Entry e) {
                return host(e.url) + " · " + ListPage.ago(e.time);
            }

            public void open(Db.Entry e) {
                openFromList(e.url, false);
            }

            public void longPress(Db.Entry e, Runnable refresh) {
                entryActions(e, new String[]{"Открыть в новой вкладке", "Копировать ссылку", "Удалить из истории"},
                        new Runnable[]{() -> openFromList(e.url, true), () -> copy(e.url), () -> {
                            db.deleteHistory(e.id);
                            refresh.run();
                        }});
            }

            public String clearLabel() {
                return "Очистить";
            }

            public void clearAll(Runnable refresh) {
                confirm("Удалить всю историю просмотров?", "Удалить", () -> {
                    db.clearHistory();
                    refresh.run();
                });
            }
        });
    }

    private void showBookmarks() {
        openListPage(new ListPage.Source() {
            public String title() {
                return "Закладки";
            }

            public int icon() {
                return R.drawable.ic_star;
            }

            public String emptyText() {
                return "Закладок пока нет. Добавить можно звездой в меню.";
            }

            public List<Db.Entry> load(String filter) {
                return db.bookmarks(filter);
            }

            public android.graphics.Bitmap image(Db.Entry e) {
                return icons.get(host(e.url));
            }


            public String subtitle(Db.Entry e) {
                return e.url;
            }

            public void open(Db.Entry e) {
                openFromList(e.url, false);
            }

            public void longPress(Db.Entry e, Runnable refresh) {
                entryActions(e, new String[]{"Открыть в новой вкладке", "Переименовать", "Копировать ссылку", "Удалить"},
                        new Runnable[]{() -> openFromList(e.url, true), () -> {
                            EditText input = new EditText(MainActivity.this);
                            input.setSingleLine(true);
                            input.setText(e.title);
                            new Sheet(MainActivity.this)
                                    .setTitle("Название закладки")
                                    .setView(input)
                                    .setPositiveButton("Сохранить", (d, w) -> {
                                        db.renameBookmark(e.id, input.getText().toString().trim());
                                        refresh.run();
                                    })
                                    .setNegativeButton("Отмена", null)
                                    .show();
                        }, () -> copy(e.url), () -> {
                            db.deleteBookmark(e.id);
                            refresh.run();
                        }});
            }

            public String clearLabel() {
                return null;
            }

            public void clearAll(Runnable refresh) {
            }
        });
    }

    private void showClosedTabs() {
        openListPage(new ListPage.Source() {
            public String title() {
                return "Недавние вкладки";
            }

            public int icon() {
                return R.drawable.ic_tab;
            }

            public String emptyText() {
                return "Недавно закрытых вкладок нет";
            }

            public List<Db.Entry> load(String filter) {
                return db.closed(filter);
            }

            public android.graphics.Bitmap image(Db.Entry e) {
                return icons.get(host(e.url));
            }


            public String subtitle(Db.Entry e) {
                return host(e.url) + " · закрыта " + ListPage.ago(e.time);
            }

            public void open(Db.Entry e) {
                restoreClosed(e);
            }

            public void longPress(Db.Entry e, Runnable refresh) {
                entryActions(e, new String[]{"Восстановить", "Копировать ссылку", "Удалить из списка"},
                        new Runnable[]{() -> restoreClosed(e), () -> copy(e.url), () -> {
                            db.deleteClosed(e.id);
                            refresh.run();
                        }});
            }

            public String clearLabel() {
                return "Очистить";
            }

            public void clearAll(Runnable refresh) {
                db.clearClosed();
                refresh.run();
            }
        });
    }

    private void restoreClosed(Db.Entry e) {
        closeListPage();
        hideSwitcher();
        db.deleteClosed(e.id);
        Tab t = new Tab(false);
        t.url = e.url;
        t.title = e.title == null ? "" : e.title;
        t.state = e.extra;
        tabs.add(t);
        switchTo(t);
        scheduleSave();
    }

    private void showDownloads() {
        openListPage(new ListPage.Source() {
            public String title() {
                return "Скачанные файлы";
            }

            public int icon() {
                return R.drawable.ic_download_done;
            }

            public String emptyText() {
                return "Загрузок пока нет";
            }

            public List<Db.Entry> load(String filter) {
                return db.downloads(filter);
            }

            public String subtitle(Db.Entry e) {
                String status = e.extra == null ? "" : e.extra.split("\\|", -1)[0];
                String st = "done".equals(status) ? "Скачано" : "failed".equals(status) ? "Ошибка" : "Загружается";
                return st + " · " + ListPage.ago(e.time);
            }

            public void open(Db.Entry e) {
                openDownload(e);
            }

            public void longPress(Db.Entry e, Runnable refresh) {
                entryActions(e, new String[]{"Открыть", "Поделиться", "Убрать из списка"},
                        new Runnable[]{() -> openDownload(e), () -> shareDownload(e), () -> {
                            db.deleteDownload(e.id);
                            refresh.run();
                        }});
            }

            public String clearLabel() {
                return "Очистить";
            }

            public void clearAll(Runnable refresh) {
                confirm("Очистить список загрузок? Сами файлы останутся в папке Download.", "Очистить", () -> {
                    db.clearDownloads();
                    refresh.run();
                });
            }
        });
    }

    private static String downloadMime(Db.Entry e) {
        String[] parts = e.extra == null ? new String[0] : e.extra.split("\\|", -1);
        return parts.length > 1 && !parts[1].isEmpty() ? parts[1] : "*/*";
    }

    private void openDownload(Db.Entry e) {
        if (e.url == null) {
            Toast.makeText(this, "Файл ещё не готов", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(Uri.parse(e.url), downloadMime(e))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (Exception ex) {
            Toast.makeText(this, "Нет приложения, чтобы открыть этот файл", Toast.LENGTH_SHORT).show();
        }
    }

    private void shareDownload(Db.Entry e) {
        if (e.url == null) return;
        Intent i = new Intent(Intent.ACTION_SEND)
                .setType(downloadMime(e))
                .putExtra(Intent.EXTRA_STREAM, Uri.parse(e.url))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i, null));
    }

    private void saveToDownloads(InputStream body, String name, String mimeType) {
        Context app = getApplicationContext();
        bg.execute(() -> {
            long id = db.addDownload(name, mimeType);
            String msg;
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, name);
            v.put(MediaStore.Downloads.MIME_TYPE, mimeType);
            v.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = app.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) {
                db.finishDownload(id, null, false);
                msg = "Не удалось создать файл";
            } else {
                try (InputStream in = body;
                     OutputStream out = app.getContentResolver().openOutputStream(uri)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    ContentValues done = new ContentValues();
                    done.put(MediaStore.Downloads.IS_PENDING, 0);
                    app.getContentResolver().update(uri, done, null, null);
                    db.finishDownload(id, uri.toString(), true);
                    msg = "Скачано: " + name;
                } catch (Exception e) {
                    app.getContentResolver().delete(uri, null, null);
                    db.finishDownload(id, null, false);
                    msg = "Ошибка загрузки: " + name;
                }
            }
            String m = msg;
            main.post(() -> {
                Toast.makeText(app, m, Toast.LENGTH_LONG).show();
                if (listPage.getVisibility() == View.VISIBLE) listPage.refresh();
            });
        });
    }

    private void savePdf(Tab t) {
        String base = (t.title.isEmpty() ? host(t.url) : t.title).replaceAll("[\\\\/:*?\"<>|]", "_");
        if (base.length() > 80) base = base.substring(0, 80);
        String name = base + ".pdf";
        Toast.makeText(this, "Сохранение: " + name, Toast.LENGTH_SHORT).show();
        ensureSession(t).saveAsPdf().accept(
                in -> {
                    if (in != null) saveToDownloads(in, name, "application/pdf");
                },
                e -> Toast.makeText(this, "Не удалось сохранить PDF", Toast.LENGTH_SHORT).show());
    }

    private void showMoreTools(Tab t) {
        boolean web = isWeb(t.url);
        AppMenu m = new AppMenu();
        if (web) {
            m.item(R.drawable.ic_picture_as_pdf, "Сохранить как PDF", () -> savePdf(t));
        } else {
            m.disabled(R.drawable.ic_picture_as_pdf, "Сохранить как PDF");
        }
        m.item(R.drawable.ic_link, "Копировать ссылку", () -> copy(t.url))
                .item(R.drawable.ic_refresh, "Обновить без кэша",
                        () -> ensureSession(t).reload(GeckoSession.LOAD_FLAGS_BYPASS_CACHE))
                .item(R.drawable.ic_restore_page, "Недавние вкладки", this::showClosedTabs);
        m.show(this, root);
    }

    private void addShortcut(Tab t) {
        if (!isWeb(t.url)) {
            Toast.makeText(this, "Ярлык можно создать только для сайта", Toast.LENGTH_SHORT).show();
            return;
        }
        android.content.pm.ShortcutManager sm = getSystemService(android.content.pm.ShortcutManager.class);
        if (sm == null || !sm.isRequestPinShortcutSupported()) {
            Toast.makeText(this, "Рабочий стол не поддерживает ярлыки", Toast.LENGTH_SHORT).show();
            return;
        }
        String label = t.title.isEmpty() ? host(t.url) : t.title;
        Intent open = new Intent(Intent.ACTION_VIEW, Uri.parse(t.url))
                .setClass(this, MainActivity.class);
        android.content.pm.ShortcutInfo info = new android.content.pm.ShortcutInfo.Builder(this, "site-" + t.url.hashCode())
                .setShortLabel(label.length() > 24 ? label.substring(0, 24) : label)
                .setLongLabel(label)
                .setIcon(android.graphics.drawable.Icon.createWithBitmap(
                        icons.get(host(t.url)) != null ? icons.get(host(t.url)) : letterIcon(host(t.url))))
                .setIntent(open)
                .build();
        sm.requestPinShortcut(info, null);
    }

    private Bitmap letterIcon(String host) {
        return Ui.letterIcon(this, host, 48);
    }

    private void exit() {
        captureThumb(current);
        saveNow();
        store.flush();
        finishAndRemoveTask();
        main.postDelayed(() -> System.exit(0), 300);
    }

    private void showPageInfo() {
        Tab t = current;
        if (t == null) return;
        String host = !t.host.isEmpty() ? t.host : Uri.parse(t.url).getHost();
        StringBuilder sb = new StringBuilder();
        sb.append(t.url).append("\n\n");
        if (t.url.startsWith("https://")) {
            sb.append(t.secure ? "Подключение защищено" : "Подключение не защищено");
            if (!t.issuer.isEmpty()) sb.append("\nСертификат: ").append(issuerName(t.issuer));
        } else if (t.url.startsWith("http://")) {
            sb.append("Подключение не защищено (HTTP)");
        }
        if (t.incognito) sb.append("\n\nРежим инкогнито");
        new Sheet(this)
                .setTitle(host == null ? "Сведения о сайте" : host)
                .setMessage(sb)
                .setPositiveButton("OK", null)
                .setNeutralButton("Копировать URL", (d, w) -> copy(t.url))
                .setNegativeButton(isWeb(t.url) ? "Настройки сайта" : null,
                        (d, w) -> showSiteSettings(siteHost(t.url)))
                .show();
    }

    private static String issuerName(String dn) {
        for (String part : dn.split(",")) {
            String p = part.trim();
            if (p.startsWith("O=")) return p.substring(2);
        }
        for (String part : dn.split(",")) {
            String p = part.trim();
            if (p.startsWith("CN=")) return p.substring(3);
        }
        return dn;
    }

    private void showSettings() {
        settingsPage.rebuild();
        revealSettings();
    }

    private void showSiteSettings(String h) {
        settingsPage.openSite(h);
        revealSettings();
    }

    private void revealSettings() {
        settingsPage.setVisibility(View.VISIBLE);
        setHidden(0);
        updateNavBar();
        getWindow().setStatusBarColor(Ui.SWITCHER_BG);
    }

    @Override
    public void closeSettings() {
        if (settingsPage == null || settingsPage.getVisibility() != View.VISIBLE) return;
        settingsPage.setVisibility(View.GONE);
        applyChrome();
    }

    @Override
    public void onChromeSettingsChanged() {
        homeButton.setVisibility(prefs.getBoolean("homeButton", false) ? View.VISIBLE : View.GONE);
    }

    private void goHome() {
        String url = prefs.getInt("homeMode", 0) == 1 ? prefs.getString("homeUrl", "") : "";
        if (current == null) {
            openTab(url.isEmpty() ? "about:blank" : url, false, null, true);
        } else {
            ensureSession(current).loadUri(url.isEmpty() ? "about:blank" : url);
        }
    }

    @Override
    public void openUboDashboard() {
        WebExtension ubo = extensions.get(UBO_ID);
        if (ubo != null && ubo.metaData.optionsPageUrl != null) {
            closeSettings();
            openTab(ubo.metaData.optionsPageUrl, false, current, true);
        } else {
            Toast.makeText(this, "uBlock Origin ещё не установлен", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void clearData(boolean cache, boolean siteData, boolean permissions, boolean history) {
        long flags = 0;
        if (cache) {
            flags |= StorageController.ClearFlags.ALL_CACHES;
            icons.clear();
        }
        if (siteData) flags |= StorageController.ClearFlags.COOKIES | StorageController.ClearFlags.SITE_DATA
                | StorageController.ClearFlags.DOM_STORAGES | StorageController.ClearFlags.AUTH_SESSIONS;
        if (permissions) flags |= StorageController.ClearFlags.PERMISSIONS
                | StorageController.ClearFlags.SITE_SETTINGS;
        if (history) {
            db.clearHistory();
            db.clearClosed();
            for (Tab t : tabs) {
                if (t.session != null) t.session.purgeHistory();
                t.state = null;
            }
            scheduleSave();
        }
        if (flags == 0) {
            Toast.makeText(this, "Удалено", Toast.LENGTH_SHORT).show();
            return;
        }
        runtime.getStorageController().clearData(flags).accept(
                v -> Toast.makeText(this, "Удалено", Toast.LENGTH_SHORT).show(),
                e -> Toast.makeText(this, "Не удалось удалить данные", Toast.LENGTH_SHORT).show());
    }

    private void showExtensions() {
        List<WebExtension> list = new ArrayList<>(extensions.values());
        if (list.isEmpty()) {
            Toast.makeText(this, "Расширений нет", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[list.size()];
        for (int i = 0; i < names.length; i++) names[i] = list.get(i).metaData.name;
        new Sheet(this)
                .setTitle("Расширения")
                .setItems(names, (d, which) -> {
                    WebExtension e = list.get(which);
                    WebExtension.Action action = extensionActions.get(e.id);
                    if (action != null) {
                        action.click();
                    } else if (e.metaData.optionsPageUrl != null) {
                        openTab(e.metaData.optionsPageUrl, false, current, true);
                    }
                })
                .show();
    }

    private void showPopupSession(WebExtension ext, GeckoSession s) {
        Dialog d = new Dialog(this);
        GeckoView v = new GeckoView(this);
        v.setSession(s);
        d.setContentView(v, new ViewGroup.LayoutParams(-1, (int) (root.getHeight() * 0.7f)));
        s.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override
            public void onCloseRequest(GeckoSession session) {
                d.dismiss();
            }
        });
        d.setOnDismissListener(x -> {
            v.releaseSession();
            s.close();
        });
        d.show();
        if (d.getWindow() != null) {
            d.getWindow().setLayout(-1, (int) (root.getHeight() * 0.7f));
            d.getWindow().setGravity(Gravity.BOTTOM);
        }
    }

    private void showFindBar() {
        findBar.setVisibility(View.VISIBLE);
        findInput.setText("");
        findCount.setText("");
        findInput.requestFocus();
        findInput.post(() -> ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE))
                .showSoftInput(findInput, InputMethodManager.SHOW_IMPLICIT));
    }

    private void hideFindBar() {
        if (findBar == null || findBar.getVisibility() != View.VISIBLE) return;
        findBar.setVisibility(View.GONE);
        hideKeyboard(findInput);
        if (current != null && current.session != null) current.session.getFinder().clear();
    }

    private void find(String text, int flags) {
        if (current == null || current.session == null) return;
        if (text != null && text.isEmpty()) {
            current.session.getFinder().clear();
            findCount.setText("");
            return;
        }
        current.session.getFinder().find(text, flags).accept(r -> {
            if (r == null) return;
            findCount.setText(r.total > 0 ? r.current + "/" + r.total : "0/0");
        });
    }

    private void showContextMenu(Tab t, GeckoSession.ContentDelegate.ContextElement el) {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        String link = el.linkUri;
        String src = el.srcUri;
        if (link != null) {
            labels.add("Открыть в новой вкладке");
            actions.add(() -> {
                openTab(link, t.incognito, t, false);
                Toast.makeText(this, "Вкладка открыта в фоне", Toast.LENGTH_SHORT).show();
            });
            if (!t.incognito) {
                labels.add("Открыть в новой вкладке инкогнито");
                actions.add(() -> openTab(link, true, null, true));
            }
            labels.add("Копировать адрес ссылки");
            actions.add(() -> copy(link));
            if (el.linkText != null && !el.linkText.isEmpty()) {
                labels.add("Копировать текст ссылки");
                actions.add(() -> copy(el.linkText));
            }
            labels.add("Поделиться ссылкой");
            actions.add(() -> {
                Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, link);
                startActivity(Intent.createChooser(i, null));
            });
        }
        if (src != null && el.type == GeckoSession.ContentDelegate.ContextElement.TYPE_IMAGE) {
            labels.add("Открыть изображение в новой вкладке");
            actions.add(() -> openTab(src, t.incognito, t, false));
            labels.add("Скачать изображение");
            actions.add(() -> ensureSession(t).loadUri(src));
            labels.add("Копировать адрес изображения");
            actions.add(() -> copy(src));
        }
        if (labels.isEmpty()) return;
        String title = link != null ? link : src;
        new Sheet(this)
                .setTitle(title)
                .setItems(labels.toArray(new String[0]), (d, w) -> actions.get(w).run())
                .show();
    }

    private void copy(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("url", text));
        Toast.makeText(this, "Скопировано", Toast.LENGTH_SHORT).show();
    }

    private void download(WebResponse response) {
        if (response.body == null) return;
        String disposition = response.headers.get("content-disposition");
        if (disposition == null) disposition = response.headers.get("Content-Disposition");
        String mime = response.headers.get("content-type");
        if (mime == null) mime = response.headers.get("Content-Type");
        String name = URLUtil.guessFileName(response.uri, disposition, mime);
        String mimeType = mime == null ? "application/octet-stream" : mime.split(";")[0].trim();
        if (prefs.getBoolean("dlAsk", false)) {
            if (pendingDlBody != null) {
                try {
                    pendingDlBody.close();
                } catch (java.io.IOException ignored) {
                }
            }
            pendingDlBody = response.body;
            pendingDlName = name;
            pendingDlMime = mimeType;
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(mimeType)
                    .putExtra(Intent.EXTRA_TITLE, name);
            startActivityForResult(i, DOWNLOAD_REQUEST);
            return;
        }
        Toast.makeText(this, "Загрузка: " + name, Toast.LENGTH_SHORT).show();
        saveToDownloads(response.body, name, mimeType);
    }

    private static final int DOWNLOAD_REQUEST = 4250;
    private InputStream pendingDlBody;
    private String pendingDlName;
    private String pendingDlMime;

    private void onDownloadTarget(int result, Intent data) {
        InputStream body = pendingDlBody;
        pendingDlBody = null;
        if (body == null) return;
        Uri target = result == RESULT_OK && data != null ? data.getData() : null;
        if (target == null) {
            try {
                body.close();
            } catch (java.io.IOException ignored) {
            }
            return;
        }
        String name = pendingDlName;
        String mimeType = pendingDlMime;
        Context app = getApplicationContext();
        Toast.makeText(this, "Загрузка: " + name, Toast.LENGTH_SHORT).show();
        bg.execute(() -> {
            long id = db.addDownload(name, mimeType);
            String msg;
            try (InputStream in = body;
                 OutputStream out = app.getContentResolver().openOutputStream(target)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                db.finishDownload(id, target.toString(), true);
                msg = "Скачано: " + name;
            } catch (Exception e) {
                db.finishDownload(id, null, false);
                msg = "Ошибка загрузки: " + name;
            }
            String m = msg;
            main.post(() -> {
                Toast.makeText(app, m, Toast.LENGTH_LONG).show();
                if (listPage.getVisibility() == View.VISIBLE) listPage.refresh();
            });
        });
    }

    private void scheduleSave() {
        main.removeCallbacks(saveTask);
        main.postDelayed(saveTask, 1500);
    }

    private void saveNow() {
        main.removeCallbacks(saveTask);
        store.save(tabs, current);
    }

    @Override
    public void onBackPressed() {
        if (listPage.getVisibility() == View.VISIBLE) {
            closeListPage();
        } else if (settingsPage.getVisibility() == View.VISIBLE) {
            if (!settingsPage.back()) closeSettings();
        } else if (switcher.getVisibility() == View.VISIBLE) {
            hideSwitcher();
        } else if (findBar.getVisibility() == View.VISIBLE) {
            hideFindBar();
        } else if (urlBar.hasFocus()) {
            urlBar.clearFocus();
            hideKeyboard(urlBar);
            geckoView.requestFocus();
        } else if (fullscreen && current != null && current.session != null) {
            current.session.exitFullScreen();
        } else if (current != null && current.canGoBack) {
            ensureSession(current).goBack();
        } else if (current != null && current.parentId != 0 && findTab(current.parentId) != null) {
            Tab parent = findTab(current.parentId);
            Tab closing = current;
            switchTo(parent);
            closeTab(closing);
        } else {
            moveTaskToBack(true);
        }
    }

    private Tab findTab(long id) {
        for (Tab t : tabs) {
            if (t.id == id) return t;
        }
        return null;
    }
}
