package dev.antite.browser;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
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
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.StorageController;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebExtensionController;
import org.mozilla.geckoview.WebResponse;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity
        implements TabSwitcher.Host, Settings.Host, Library.Host, TabDelegates.Host {
    private static final String UBO_ID = "uBlock0@raymondhill.net";
    private static final String UBO_URL =
            "https://addons.mozilla.org/firefox/downloads/latest/ublock-origin/latest.xpi";
    private static final int MAX_LIVE_SESSIONS = 6;

    private static GeckoRuntime runtime;
    private static SitePerms sitePerms;
    private static IconCache icons;
    private static WebExtension iconsExt;
    private static MainActivity visible;
    private static final Map<String, WebExtension> extensions = new LinkedHashMap<>();
    private static final Map<String, WebExtension.Action> extensionActions = new LinkedHashMap<>();

    private final List<Tab> tabs = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable saveTask = this::saveNow;
    private final Runnable redrawSwitcher = this::redrawSwitcher;
    private Tab current;
    private TabStore store;
    private SharedPreferences prefs;
    private Db db;
    private Prompts dialogs;
    private SitePrompts sitePrompts;
    private Downloads downloads;
    private Library library;
    private Translator translator;
    private TabDelegates delegates;

    private FrameLayout root;
    private GeckoView geckoView;
    private LinearLayout topBar;
    private LinearLayout pill;
    private ImageView pageInfo;
    private ImageView homeButton;
    private EditText urlBar;
    private ImageView clearUrl;
    private ImageView newTabButton;
    private TextView tabCount;
    private ImageView menuButton;
    private ProgressBar progress;
    private FindBar findBar;
    private TabSwitcher switcher;
    private Settings settingsPage;
    private ListPage listPage;
    private NewTabPage ntp;
    private Suggestions suggest;

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
        StrictModeSetup.enableIfDebuggable(this);
        prefs = getSharedPreferences("antite", MODE_PRIVATE);
        store = new TabStore(this);
        db = Db.get(this);
        if (runtime == null) startEngine();
        dialogs = new Prompts(this);
        dialogs.popupPolicy = s -> sitePrompts.allowPopup(tabFor(s));
        sitePrompts = new SitePrompts(this, sitePerms);
        downloads = new Downloads(this, db, () -> {
            if (listPage.getVisibility() == View.VISIBLE) listPage.refresh();
        });
        library = new Library(this, this, db, icons, downloads);
        translator = new Translator(this, prefs, () -> current, this::ensureSession);
        delegates = new TabDelegates(getApplicationContext(), this, db, icons, sitePerms, sitePrompts, translator,
                dialogs);
        buildUi();
        icons.setListener(this::onIconLoaded);
        restoreTabs();
        if (state == null) {
            handleIntent(getIntent(), current == null || prefs.getInt("startup", 0) == 1);
        } else if (current == null) {
            newTabAndFocus(false);
        }
    }

    private void startEngine() {
        Context app = getApplicationContext();
        runtime = GeckoRuntime.create(app, new GeckoRuntimeSettings.Builder()
                .configFilePath(writeGeckoConfig())
                .build());
        sitePerms = new SitePerms(app, prefs);
        sitePerms.attach(runtime);
        EngineSettings.apply(runtime, prefs);
        runtime.setWebNotificationDelegate(new WebNotifications(app));
        icons = new IconCache(app, runtime);
        setupExtensions();
        runtime.getWebExtensionController()
                .ensureBuiltIn("resource://android/assets/extensions/icons/", "icons@antite.dev")
                .accept(e -> {
                    iconsExt = e;
                    if (visible != null) visible.attachIconDelegates();
                }, err -> android.util.Log.w("Antite", "icons extension failed", err));
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
    protected void onResume() {
        super.onResume();
        visible = this;
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
    protected void onDestroy() {
        if (visible == this) visible = null;
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == Prompts.FILE_REQUEST) dialogs.onFileResult(result, data);
        if (request == Downloads.REQUEST) downloads.onTarget(result, data);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == SitePrompts.ANDROID_REQUEST) sitePrompts.onAndroidResult(results);
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
                return openPopup();
            }

            @Override
            public GeckoResult<GeckoSession> onOpenPopup(WebExtension ext, WebExtension.Action action) {
                return openPopup();
            }
        });
    }

    private static GeckoResult<GeckoSession> openPopup() {
        MainActivity a = visible;
        if (a == null) return null;
        GeckoSession s = new GeckoSession();
        s.open(runtime);
        a.showPopupSession(s);
        return GeckoResult.fromValue(s);
    }

    private void buildUi() {
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(Ui.TOOLBAR);
        getWindow().setNavigationBarColor(Ui.TOOLBAR);
        toolbarHeight = Ui.dp(this, 56);

        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.PAGE);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets stable = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars());
            imeInset = insets.getInsets(WindowInsets.Type.ime()).bottom;
            navInset = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
            root.setPadding(stable.left, fullscreen ? 0 : stable.top, stable.right, imeInset);
            int overlayBottom = imeInset > 0 ? 0 : stable.bottom;
            switcher.setPadding(0, 0, 0, overlayBottom);
            settingsPage.setPadding(0, 0, 0, overlayBottom);
            listPage.setPadding(0, 0, 0, overlayBottom);
            View bar = translator.bar();
            ((FrameLayout.LayoutParams) bar.getLayoutParams()).bottomMargin = overlayBottom;
            bar.requestLayout();
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
        topBar.addView(buildToolbar(), new LinearLayout.LayoutParams(-1, toolbarHeight - Ui.dp(this, 2)));
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

        findBar = new FindBar(this, () -> current);
        root.addView(translator.bar(), new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
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

    private LinearLayout buildToolbar() {
        LinearLayout toolbar = new LinearLayout(this);
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
        return toolbar;
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
        if (Urls.isBlank(url)) {
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

    private void updateNtp() {
        if (ntp == null) return;
        boolean show = current != null && Urls.isBlank(current.url);
        if (show) {
            ntp.setVisibility(View.VISIBLE);
            ntp.refresh();
        } else if (ntp.getVisibility() == View.VISIBLE) {
            ntp.setVisibility(View.GONE);
        }
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
            url = EngineSettings.ENGINE_URLS[prefs.getInt("engine", 0)] + Uri.encode(s);
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
        topBar.setTranslationY(-hidden);
        geckoView.setTranslationY(toolbarHeight - hidden);
        geckoView.setVerticalClipping((imeInset > 0 ? 0 : navInset) - hidden);
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

    @Override
    public void setFullscreen(boolean full) {
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

    private void restoreTabs() {
        TabStore.Loaded loaded = store.load();
        tabs.addAll(loaded.tabs);
        Tab sel = findTab(loaded.selected);
        if (sel == null && !tabs.isEmpty()) sel = tabs.get(tabs.size() - 1);
        if (sel != null) switchTo(sel);
    }

    private Tab findTab(long id) {
        for (Tab t : tabs) {
            if (t.id == id) return t;
        }
        return null;
    }

    private Tab tabFor(GeckoSession s) {
        for (Tab t : tabs) {
            if (t.session == s) return t;
        }
        return null;
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
        store.loadThumb(t, () -> {
            main.removeCallbacks(redrawSwitcher);
            main.postDelayed(redrawSwitcher, 50);
        });
        return t.thumb;
    }

    @Override
    public Bitmap icon(Tab t) {
        return icons.get(Urls.host(t.url));
    }

    @Override
    public int tabColor(Tab t) {
        return t.incognito ? 0 : icons.color(Urls.host(t.url));
    }

    @Override
    public boolean listLayout() {
        return prefs.getInt("tabLayout", 0) == 1;
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

    @Override
    public GeckoSession openChild(Tab parent, String uri) {
        Tab child = new Tab(parent.incognito);
        child.url = uri;
        child.parentId = parent.id;
        tabs.add(tabs.indexOf(parent) + 1, child);
        GeckoSession cs = createSession(child);
        main.post(() -> switchTo(child));
        return cs;
    }

    private GeckoSession createSession(Tab t) {
        GeckoSessionSettings.Builder sb = new GeckoSessionSettings.Builder()
                .usePrivateMode(t.incognito)
                .allowJavascript(delegates.javascriptAllowed(t.url));
        if (t.desktop) {
            sb.userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP)
                    .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_DESKTOP);
        }
        GeckoSession s = new GeckoSession(sb.build());
        delegates.attach(t, s, iconsExt);
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
                return s;
            } catch (Exception ignored) {
            }
        }
        s.loadUri(t.url.isEmpty() ? "about:blank" : t.url);
        return s;
    }

    @Override
    public void switchTo(Tab t) {
        if (current != null && current != t && current.session != null) {
            current.session.setActive(false);
            runtime.getWebExtensionController().setTabActive(current.session, false);
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
        findBar.hide();
        trimSessions();
        applyChrome();
        refreshBookmarked(t);
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

    @Override
    public void closeTab(Tab t) {
        int i = tabs.indexOf(t);
        if (i < 0) return;
        tabs.remove(i);
        if (t == current && geckoView.getSession() == t.session) geckoView.releaseSession();
        if (t.session != null) t.session.close();
        t.session = null;
        store.deleteThumb(t.id);
        if (!t.incognito && !Urls.isBlank(t.url)) {
            String url = t.url;
            String title = t.title;
            String state = t.state;
            db.run(() -> db.addClosed(url, title, state));
        }
        if (t == current) {
            current = null;
            Tab next = nextAfterClose(t, i);
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

    private Tab nextAfterClose(Tab closed, int index) {
        Tab parent = findTab(closed.parentId);
        if (parent != null && parent.incognito == closed.incognito) return parent;
        for (int k = Math.min(index, tabs.size()) - 1; k >= 0; k--) {
            if (tabs.get(k).incognito == closed.incognito) return tabs.get(k);
        }
        for (int k = index; k < tabs.size(); k++) {
            if (tabs.get(k).incognito == closed.incognito) return tabs.get(k);
        }
        Tab next = null;
        for (Tab x : tabs) {
            if (!x.incognito && (next == null || x.lastUsed > next.lastUsed)) next = x;
        }
        return next;
    }

    @Override
    public void recover(Tab t) {
        if (t.session != null) {
            if (geckoView.getSession() == t.session) geckoView.releaseSession();
            t.session.close();
            t.session = null;
        }
        if (t == current) switchTo(t);
    }

    private void attachIconDelegates() {
        for (Tab t : tabs) {
            if (t.session != null) delegates.attachIcons(t, t.session, iconsExt);
        }
    }

    private void redrawSwitcher() {
        if (switcher.getVisibility() == View.VISIBLE) switcher.refresh();
    }

    private void onIconLoaded(String host) {
        if (ntp.getVisibility() == View.VISIBLE) ntp.redraw();
        main.removeCallbacks(redrawSwitcher);
        main.postDelayed(redrawSwitcher, 50);
        if (listPage.getVisibility() == View.VISIBLE) listPage.redraw();
    }

    @Override
    public void onUrlChanged(Tab t) {
        refreshBookmarked(t);
        if (t != current) return;
        updateUrlText();
        updateNtp();
    }

    @Override
    public void onLoadingChanged(Tab t) {
        if (t != current) return;
        if (t.loading) setHidden(0);
        updateProgress();
    }

    @Override
    public void onProgress(Tab t) {
        if (t == current) updateProgress();
    }

    @Override
    public void onScroll(Tab t, int y) {
        if (t != current || urlBar.hasFocus()) return;
        if (lastScrollY < 0) {
            lastScrollY = y;
            return;
        }
        int dy = y - lastScrollY;
        lastScrollY = y;
        setHidden(y <= 0 ? 0 : hidden + dy);
    }

    @Override
    public void captureThumbSoon(Tab t, long delayMs, boolean onlyWhenLoaded) {
        if (t != current) return;
        main.postDelayed(() -> {
            if (t == current && !(onlyWhenLoaded && t.loading)) captureThumb(t);
        }, delayMs);
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
        boolean webPage = Urls.isWeb(t.url);
        boolean bookmarked = webPage && t.bookmarked;
        new AppMenu()
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
                .item(R.drawable.ic_history, "История", library::showHistory)
                .item(R.drawable.ic_delete, "Удалить данные о работе в браузере", settingsPage::showClearData)
                .divider()
                .item(R.drawable.ic_download_done, "Скачанные файлы", library::showDownloads)
                .item(R.drawable.ic_star, "Закладки", library::showBookmarks)
                .item(R.drawable.ic_folder, "Добавить все вкладки в закладки", this::bookmarkAllTabs)
                .item(R.drawable.ic_devices, "Недавние вкладки", library::showClosedTabs)
                .item(R.drawable.ic_extension, "Открыть меню расширений", this::showExtensions)
                .divider()
                .item(R.drawable.ic_share, "Поделиться…", () -> share(t.url, t.title))
                .item(R.drawable.ic_find_in_page, "Найти на странице", findBar::show)
                .item(R.drawable.ic_translate, t.translated ? "Показать оригинал" : "Перевести…",
                        () -> translator.translate(t))
                .submenu(R.drawable.ic_home_repair_service, "Ещё инструменты", () -> showMoreTools(t))
                .item(R.drawable.ic_install_desktop, "Установить и создать ярлык", () -> addShortcut(t))
                .item(R.drawable.ic_description, "View source", () -> {
                    if (!t.url.isEmpty() && !t.url.startsWith("view-source:")) {
                        openTab("view-source:" + t.url, t.incognito, t, true);
                    }
                })
                .check(R.drawable.ic_desktop_windows, "Версия для ПК", t.desktop, () -> toggleDesktop(t))
                .check(R.drawable.ic_brightness_6, "Тёмная тема", EngineSettings.isDark(this, prefs),
                        this::toggleDark)
                .divider()
                .item(R.drawable.ic_settings, "Настройки", this::showSettings)
                .item(R.drawable.ic_exit_to_app, "Exit", this::exit)
                .show(this, root);
    }

    private void showMoreTools(Tab t) {
        AppMenu m = new AppMenu();
        if (Urls.isWeb(t.url)) {
            m.item(R.drawable.ic_picture_as_pdf, "Сохранить как PDF", () -> savePdf(t));
        } else {
            m.disabled(R.drawable.ic_picture_as_pdf, "Сохранить как PDF");
        }
        m.item(R.drawable.ic_link, "Копировать ссылку", () -> copy(t.url))
                .item(R.drawable.ic_refresh, "Обновить без кэша",
                        () -> ensureSession(t).reload(GeckoSession.LOAD_FLAGS_BYPASS_CACHE))
                .item(R.drawable.ic_restore_page, "Недавние вкладки", library::showClosedTabs)
                .show(this, root);
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
        boolean dark = !EngineSettings.isDark(this, prefs);
        prefs.edit().putInt("theme", dark ? 2 : 1).remove("dark").apply();
        EngineSettings.apply(runtime, prefs);
    }

    private void savePdf(Tab t) {
        downloads.savePdf(t, ensureSession(t));
    }

    @Override
    public void download(WebResponse response) {
        downloads.start(response, prefs.getBoolean("dlAsk", false));
    }

    private void refreshBookmarked(Tab t) {
        if (!Urls.isWeb(t.url)) {
            t.bookmarked = false;
            return;
        }
        String url = t.url;
        db.read(() -> db.isBookmarked(url), b -> {
            if (url.equals(t.url)) t.bookmarked = b;
        });
    }

    private void toggleBookmark(Tab t) {
        String url = t.url;
        String title = t.title;
        boolean add = !t.bookmarked;
        t.bookmarked = add;
        db.run(() -> {
            if (add) {
                db.addBookmark(url, title);
            } else {
                db.removeBookmark(url);
            }
        });
        Toast.makeText(this, add ? "Добавлено в закладки" : "Удалено из закладок", Toast.LENGTH_SHORT).show();
    }

    private void bookmarkAllTabs() {
        List<String[]> pages = new ArrayList<>();
        for (Tab t : tabs) {
            if (!t.incognito && Urls.isWeb(t.url)) pages.add(new String[]{t.url, t.title});
        }
        db.run(() -> {
            for (String[] p : pages) db.addBookmark(p[0], p[1]);
        });
        for (Tab t : tabs) {
            if (!t.incognito && Urls.isWeb(t.url)) t.bookmarked = true;
        }
        Toast.makeText(this, "В закладки добавлено вкладок: " + pages.size(), Toast.LENGTH_SHORT).show();
    }

    @Override
    public void openListPage(ListPage.Source src) {
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

    @Override
    public void openFromList(String url, boolean newTab) {
        closeListPage();
        hideSwitcher();
        if (newTab || current == null || current.incognito) {
            openTab(url, false, null, true);
        } else {
            ensureSession(current).loadUri(url);
        }
    }

    @Override
    public void restoreClosed(Db.Entry e) {
        closeListPage();
        hideSwitcher();
        db.run(() -> db.deleteClosed(e.id));
        Tab t = new Tab(false);
        t.url = e.url;
        t.title = e.title == null ? "" : e.title;
        t.state = e.extra;
        tabs.add(t);
        switchTo(t);
        scheduleSave();
    }

    private void addShortcut(Tab t) {
        if (!Urls.isWeb(t.url)) {
            Toast.makeText(this, "Ярлык можно создать только для сайта", Toast.LENGTH_SHORT).show();
            return;
        }
        android.content.pm.ShortcutManager sm = getSystemService(android.content.pm.ShortcutManager.class);
        if (sm == null || !sm.isRequestPinShortcutSupported()) {
            Toast.makeText(this, "Рабочий стол не поддерживает ярлыки", Toast.LENGTH_SHORT).show();
            return;
        }
        String host = Urls.host(t.url);
        String label = t.title.isEmpty() ? host : t.title;
        Bitmap icon = icons.get(host);
        android.content.pm.ShortcutInfo info = new android.content.pm.ShortcutInfo.Builder(this, "site-" + t.url.hashCode())
                .setShortLabel(label.length() > 24 ? label.substring(0, 24) : label)
                .setLongLabel(label)
                .setIcon(android.graphics.drawable.Icon.createWithBitmap(
                        icon != null ? icon : Ui.letterIcon(this, host, 48)))
                .setIntent(new Intent(Intent.ACTION_VIEW, Uri.parse(t.url)).setClass(this, MainActivity.class))
                .build();
        sm.requestPinShortcut(info, null);
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
                .setNegativeButton(Urls.isWeb(t.url) ? "Настройки сайта" : null,
                        (d, w) -> showSiteSettings(Urls.siteHost(t.url)))
                .show();
    }

    private static String issuerName(String dn) {
        for (String prefix : new String[]{"O=", "CN="}) {
            for (String part : dn.split(",")) {
                String p = part.trim();
                if (p.startsWith(prefix)) return p.substring(prefix.length());
            }
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
        if (url.isEmpty()) url = "about:blank";
        if (current == null) {
            openTab(url, false, null, true);
        } else {
            ensureSession(current).loadUri(url);
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
            db.run(() -> {
                db.clearHistory();
                db.clearClosed();
            });
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

    private void showPopupSession(GeckoSession s) {
        Dialog d = new Dialog(this);
        GeckoView v = new GeckoView(this);
        v.setSession(s);
        int height = (int) (root.getHeight() * 0.7f);
        d.setContentView(v, new ViewGroup.LayoutParams(-1, height));
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
            d.getWindow().setLayout(-1, height);
            d.getWindow().setGravity(Gravity.BOTTOM);
        }
    }

    @Override
    public void showContextMenu(Tab t, GeckoSession.ContentDelegate.ContextElement el) {
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
            actions.add(() -> share(link, null));
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
        new Sheet(this)
                .setTitle(link != null ? link : src)
                .setItems(labels.toArray(new String[0]), (d, w) -> actions.get(w).run())
                .show();
    }

    private void share(String url, String title) {
        if (url == null || url.isEmpty()) return;
        Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url);
        if (title != null && !title.isEmpty()) i.putExtra(Intent.EXTRA_SUBJECT, title);
        startActivity(Intent.createChooser(i, null));
    }

    @Override
    public void copy(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("url", text));
        Toast.makeText(this, "Скопировано", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void scheduleSave() {
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
        } else if (findBar.hide()) {
            return;
        } else if (urlBar.hasFocus()) {
            urlBar.clearFocus();
            hideKeyboard(urlBar);
            geckoView.requestFocus();
        } else if (fullscreen && current != null && current.session != null) {
            current.session.exitFullScreen();
        } else if (current != null && current.canGoBack) {
            ensureSession(current).goBack();
        } else if (current != null && findTab(current.parentId) != null) {
            Tab closing = current;
            switchTo(findTab(current.parentId));
            closeTab(closing);
        } else {
            moveTaskToBack(true);
        }
    }
}
