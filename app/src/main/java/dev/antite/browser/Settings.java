package dev.antite.browser;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.mozilla.geckoview.ContentBlocking;
import org.mozilla.geckoview.GeckoPreferenceController;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession.PermissionDelegate;
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission;
import org.mozilla.geckoview.StorageController;
import org.mozilla.geckoview.TranslationsController.Language;
import org.mozilla.geckoview.TranslationsController.RuntimeTranslation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.IntConsumer;

final class Settings extends LinearLayout {
    static final String[] ENGINE_NAMES = {"DuckDuckGo", "Google", "Яндекс", "Bing", "Startpage"};
    static final String[] ENGINE_URLS = {
            "https://duckduckgo.com/?q=",
            "https://www.google.com/search?q=",
            "https://yandex.ru/search/?text=",
            "https://www.bing.com/search?q=",
            "https://www.startpage.com/do/search?q=",
    };

    static final String[] TRACKING = {"Стандартная", "Строгая", "Выключена"};
    static final String[] COOKIES = {
            "Полная защита (изолировать сторонние)",
            "Блокировать сторонние трекеры",
            "Блокировать все сторонние",
            "Разрешить все",
    };
    private static final int[] COOKIE_VALUES = {
            ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS,
            ContentBlocking.CookieBehavior.ACCEPT_NON_TRACKERS,
            ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY,
            ContentBlocking.CookieBehavior.ACCEPT_ALL,
    };
    static final String[] DOH_NAMES = {"Выключено", "Cloudflare", "Quad9", "Mullvad", "AdGuard (с блокировкой рекламы)"};
    private static final String[] DOH_URLS = {
            null,
            "https://mozilla.cloudflare-dns.com/dns-query",
            "https://dns.quad9.net/dns-query",
            "https://dns.mullvad.net/dns-query",
            "https://dns.adguard-dns.com/dns-query",
    };
    static final String[] DOH_MODES = {"Если доступен (иначе обычный DNS)", "Только защищённый DNS"};
    static final String[] FONT_NAMES = {"80%", "90%", "100%", "110%", "120%", "130%", "150%", "175%", "200%"};
    private static final float[] FONT_VALUES = {0.8f, 0.9f, 1f, 1.1f, 1.2f, 1.3f, 1.5f, 1.75f, 2f};
    static final String[] STARTUP = {"Последние вкладки", "Новая вкладка"};
    static final String[] EXTERNAL = {"В новой вкладке", "В текущей вкладке"};
    static final String[] THEMES = {"Как в системе", "Светлая", "Тёмная"};
    static final String[] WEBRTC = {
            "Без ограничений",
            "Не раскрывать локальный IP",
            "Только через прокси",
            "Отключить WebRTC",
    };
    static final String[] FINGERPRINT = {
            "Стандартная (в инкогнито)",
            "Усиленная (везде)",
            "Максимальная (resistFingerprinting)",
    };
    static final String[] TAB_LAYOUTS = {"Сетка", "Список (как в Яндексе)"};
    static final String[] HOME_MODES = {"Новая вкладка", "Свой адрес"};
    static final String[] LANG_CODES = {"ru", "en", "uk", "be", "kk", "de", "fr", "es", "it", "pt", "pl", "cs",
            "tr", "zh", "ja", "ko", "ar", "he", "hi", "fi", "sv", "nl", "el", "hu", "ro", "bg", "sr", "ka", "hy",
            "az", "uz", "lv", "lt", "et", "vi", "id", "th", "fa"};

    private static final String[] PRELOAD_BOOLS = {"network.prefetch-next", "network.predictor.enabled",
            "browser.places.speculativeConnect.enabled"};

    interface Host {
        void closeSettings();

        void clearData(boolean cache, boolean siteData, boolean permissions, boolean history);

        void openUboDashboard();

        void onChromeSettingsChanged();
    }

    static int theme(SharedPreferences p) {
        if (p.contains("theme")) return p.getInt("theme", 2);
        return p.getBoolean("dark", true) ? 2 : 1;
    }

    static boolean isDark(Context c, SharedPreferences p) {
        int t = theme(p);
        if (t == 0) {
            return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
        }
        return t == 2;
    }

    static String[] locales(SharedPreferences p) {
        String s = p.getString("langs", "");
        if (s.isEmpty()) return new String[0];
        return s.split(",");
    }

    static String translateTarget(SharedPreferences p) {
        String t = p.getString("translateTo", "");
        if (!t.isEmpty()) return t;
        String[] l = locales(p);
        return l.length > 0 ? l[0] : Locale.getDefault().getLanguage();
    }

    static void apply(GeckoRuntime runtime, SharedPreferences p) {
        GeckoRuntimeSettings s = runtime.getSettings();
        int theme = theme(p);
        s.setPreferredColorScheme(theme == 0 ? GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM
                : theme == 2 ? GeckoRuntimeSettings.COLOR_SCHEME_DARK : GeckoRuntimeSettings.COLOR_SCHEME_LIGHT);
        s.setJavaScriptEnabled(true);
        s.setAutomaticFontSizeAdjustment(false);
        s.setFontSizeFactor(FONT_VALUES[Math.min(p.getInt("font", 2), FONT_VALUES.length - 1)]);
        s.setForceUserScalableEnabled(p.getBoolean("forceZoom", false));
        s.setDoubleTapZoomingEnabled(p.getBoolean("doubleTapZoom", true));
        s.setGlobalPrivacyControl(p.getBoolean("gpc", true));
        s.setAllowInsecureConnections(p.getBoolean("httpsOnly", false)
                ? GeckoRuntimeSettings.HTTPS_ONLY : GeckoRuntimeSettings.ALLOW_ALL);
        s.setTranslationsOfferPopup(p.getBoolean("translateOffer", true));
        String[] langs = locales(p);
        if (langs.length > 0) s.setLocales(langs);

        int doh = p.getInt("doh", 0);
        if (doh == 0) {
            s.setTrustedRecursiveResolverMode(GeckoRuntimeSettings.TRR_MODE_OFF);
        } else {
            s.setTrustedRecursiveResolverUri(DOH_URLS[doh]);
            s.setTrustedRecursiveResolverMode(p.getInt("dohMode", 0) == 1
                    ? GeckoRuntimeSettings.TRR_MODE_ONLY : GeckoRuntimeSettings.TRR_MODE_FIRST);
        }

        ContentBlocking.Settings cb = s.getContentBlocking();
        int tracking = p.getInt("tracking", 0);
        if (tracking == 1) {
            cb.setEnhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.STRICT);
            cb.setAntiTracking(ContentBlocking.AntiTracking.STRICT);
            cb.setStrictSocialTrackingProtection(true);
        } else if (tracking == 2) {
            cb.setEnhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.NONE);
            cb.setAntiTracking(ContentBlocking.AntiTracking.NONE);
            cb.setStrictSocialTrackingProtection(false);
        } else {
            cb.setEnhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.DEFAULT);
            cb.setAntiTracking(ContentBlocking.AntiTracking.DEFAULT);
            cb.setStrictSocialTrackingProtection(false);
        }
        int cookies = COOKIE_VALUES[p.getInt("cookies", 0)];
        cb.setCookieBehavior(cookies);
        cb.setCookieBehaviorPrivateMode(cookies);
        boolean strip = p.getBoolean("strip", true);
        cb.setQueryParameterStrippingEnabled(strip);
        cb.setQueryParameterStrippingPrivateBrowsingEnabled(strip);
        cb.setSafeBrowsing(p.getBoolean("safeBrowsing", false)
                ? ContentBlocking.SafeBrowsing.DEFAULT : ContentBlocking.SafeBrowsing.NONE);

        int fp = p.getInt("fingerprint", 0);
        s.setFingerprintingProtection(fp >= 1);
        s.setFingerprintingProtectionPrivateBrowsing(true);
        pref("privacy.resistFingerprinting", fp == 2);

        pref("privacy.donottrackheader.enabled", p.getBoolean("dnt", false));

        boolean preload = p.getBoolean("preload", false);
        for (String name : PRELOAD_BOOLS) pref(name, preload);
        pref("network.dns.disablePrefetch", !preload);
        GeckoPreferenceController.setGeckoPref("network.http.speculative-parallel-limit", preload ? 6 : 0,
                GeckoPreferenceController.PREF_BRANCH_USER);

        int rtc = p.getInt("webrtc", 1);
        pref("media.peerconnection.enabled", rtc != 3);
        pref("media.peerconnection.ice.default_address_only", rtc >= 1);
        pref("media.peerconnection.ice.no_host", rtc >= 1);
        pref("media.peerconnection.ice.proxy_only_if_behind_proxy", rtc >= 1);
        pref("media.peerconnection.ice.proxy_only", rtc == 2);
    }

    private static void pref(String name, boolean value) {
        GeckoPreferenceController.setGeckoPref(name, value, GeckoPreferenceController.PREF_BRANCH_USER);
    }

    static int autoplayDecision(SitePerms perms, String host, int permission) {
        int mode = perms.decide(SitePerms.AUTOPLAY, host);
        if (mode == SitePerms.ALLOW) return ContentPermission.VALUE_ALLOW;
        if (mode == SitePerms.BLOCK) return ContentPermission.VALUE_DENY;
        return permission == PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE
                ? ContentPermission.VALUE_ALLOW : ContentPermission.VALUE_DENY;
    }

    private static final class Page {
        final String title;
        final Runnable build;
        int scroll;

        Page(String title, Runnable build) {
            this.title = title;
            this.build = build;
        }
    }

    private final Activity a;
    private final Host host;
    private final GeckoRuntime runtime;
    private final SharedPreferences prefs;
    private final SitePerms perms;
    private final LinearLayout list;
    private final ScrollView scroll;
    private final TextView title;
    private final List<Page> stack = new ArrayList<>();
    private int generation;

    Settings(Activity a, Host host, GeckoRuntime runtime, SharedPreferences prefs, SitePerms perms) {
        super(a);
        this.a = a;
        this.host = host;
        this.runtime = runtime;
        this.prefs = prefs;
        this.perms = perms;
        setOrientation(VERTICAL);
        setBackgroundColor(Ui.SWITCHER_BG);
        setClickable(true);

        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(a, 4), 0, Ui.dp(a, 16), 0);
        ImageView back = Ui.iconButton(a, R.drawable.ic_arrow_back, 48);
        back.setContentDescription("Назад");
        back.setOnClickListener(v -> {
            if (!back()) host.closeSettings();
        });
        bar.addView(back, new LayoutParams(Ui.dp(a, 48), Ui.dp(a, 48)));
        title = new TextView(a);
        title.setTextSize(20);
        title.setTextColor(Ui.TEXT);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LayoutParams tlp = new LayoutParams(0, -2, 1f);
        tlp.setMarginStart(Ui.dp(a, 12));
        bar.addView(title, tlp);
        addView(bar, new LayoutParams(-1, Ui.dp(a, 56)));

        scroll = new ScrollView(a);
        list = new LinearLayout(a);
        list.setOrientation(VERTICAL);
        list.setPadding(0, 0, 0, Ui.dp(a, 24));
        scroll.addView(list);
        addView(scroll, new LayoutParams(-1, 0, 1f));
        rebuild();
    }

    void rebuild() {
        stack.clear();
        stack.add(new Page("Настройки", this::buildMain));
        render(0);
    }

    void openSite(String siteHost) {
        stack.clear();
        stack.add(new Page("Настройки сайта", () -> buildSite(siteHost)));
        render(0);
    }

    boolean back() {
        if (stack.size() <= 1) return false;
        stack.remove(stack.size() - 1);
        render(stack.get(stack.size() - 1).scroll);
        return true;
    }

    private void push(String name, Runnable build) {
        stack.get(stack.size() - 1).scroll = scroll.getScrollY();
        stack.add(new Page(name, build));
        render(0);
    }

    private void refresh() {
        render(scroll.getScrollY());
    }

    private void render(int y) {
        generation++;
        list.removeAllViews();
        Page p = stack.get(stack.size() - 1);
        title.setText(p.title);
        p.build.run();
        scroll.post(() -> scroll.scrollTo(0, y));
    }

    private void buildMain() {
        section("Основные");
        choice("Поисковая система", ENGINE_NAMES, "engine", 0, false);
        nav("Конфиденциальность и безопасность", "HTTPS, DNS, cookie, отслеживание, WebRTC",
                this::buildPrivacy);
        row("Уведомления", "Системные настройки уведомлений браузера", null, () -> {
            Intent i = new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, a.getPackageName());
            a.startActivity(i);
        });
        int th = theme(prefs);
        row("Тема", THEMES[th], null, () -> pick("Тема", THEMES, th, w -> {
            prefs.edit().putInt("theme", w).remove("dark").apply();
            apply(runtime, prefs);
            refresh();
        }));

        section("Дополнительные");
        nav("Главная страница", prefs.getBoolean("homeButton", false) ? homeSummary() : "Выключено",
                this::buildHome);
        nav("Вкладки", "Запуск, ссылки из приложений, инкогнито", this::buildTabs);
        nav("Специальные возможности", "Размер текста, масштабирование", this::buildAccessibility);
        nav("Настройки сайтов", "Разрешения, JavaScript, всплывающие окна", this::buildSiteSettings);
        nav("Языки", "Языки сайтов и перевод", this::buildLanguages);
        nav("Скачанные файлы", prefs.getBoolean("dlAsk", false)
                ? "Спрашивать, куда сохранять" : "Сохранять в «Загрузки»", this::buildDownloads);
        row("uBlock Origin", "Панель управления, списки фильтров", null, host::openUboDashboard);

        section("О программе");
        row("Antite 0.1", "Движок GeckoView " + org.mozilla.geckoview.BuildConfig.MOZILLA_VERSION, null, null);
    }

    private String homeSummary() {
        return prefs.getInt("homeMode", 0) == 0 ? "Новая вкладка" : prefs.getString("homeUrl", "");
    }

    private void buildPrivacy() {
        row("Удалить данные о работе в браузере", "Кэш, cookie, данные сайтов, история", null, this::showClearData);
        section("Безопасность");
        toggle("Всегда использовать безопасные подключения",
                "Открывать сайты по HTTPS и предупреждать, если сайт его не поддерживает",
                "httpsOnly", false, true);
        toggle("Безопасный просмотр (Google Safe Browsing)",
                "Предупреждать об опасных сайтах и загрузках. Браузер скачивает списки угроз Google",
                "safeBrowsing", false, true);
        nav("Безопасный DNS", prefs.getInt("doh", 0) == 0 ? "Выключено" : DOH_NAMES[prefs.getInt("doh", 0)],
                this::buildDns);
        section("Конфиденциальность");
        choice("Защита от отслеживания", TRACKING, "tracking", 0, true);
        choice("Сторонние файлы cookie", COOKIES, "cookies", 0, true);
        choice("Защита от цифровых отпечатков", FINGERPRINT, "fingerprint", 0, true);
        if (prefs.getInt("fingerprint", 0) == 2) {
            note("Максимальная защита подменяет часовой пояс на UTC, язык на английский и тему на светлую, "
                    + "некоторые сайты ломаются");
        }
        choice("WebRTC", WEBRTC, "webrtc", 1, true);
        toggle("Отправлять запрет отслеживания (Do Not Track)", null, "dnt", false, true);
        toggle("Global Privacy Control", "Просить сайты не продавать и не передавать ваши данные",
                "gpc", true, true);
        toggle("Очищать ссылки от меток отслеживания", "Удалять из адресов параметры вроде utm и fbclid",
                "strip", true, true);
        toggle("Предзагрузка страниц", "Заранее соединяться с сайтами и загружать ссылки, которые вы можете открыть",
                "preload", false, true);
        toggle("Подсказки поисковой системы", "Набираемый в адресной строке текст отправляется поисковику",
                "searchSuggest", true, false);
    }

    private void buildDns() {
        int doh = prefs.getInt("doh", 0);
        toggleRow("Использовать безопасный DNS", "DNS-запросы шифруются и уходят выбранному провайдеру",
                doh != 0, on -> {
                    prefs.edit().putInt("doh", on ? Math.max(1, prefs.getInt("dohLast", 1)) : 0).apply();
                    apply(runtime, prefs);
                    refresh();
                });
        if (doh == 0) return;
        String[] names = Arrays.copyOfRange(DOH_NAMES, 1, DOH_NAMES.length);
        row("Провайдер", DOH_NAMES[doh], null, () -> pick("Провайдер", names, doh - 1, w -> {
            prefs.edit().putInt("doh", w + 1).putInt("dohLast", w + 1).apply();
            apply(runtime, prefs);
            refresh();
        }));
        choice("Режим", DOH_MODES, "dohMode", 0, true);
    }

    private void buildHome() {
        toggle("Кнопка «Главная страница»", "Показывать кнопку слева от адресной строки", "homeButton", false,
                false);
        int mode = prefs.getInt("homeMode", 0);
        row("Открывать", HOME_MODES[mode], null, () -> pick("Открывать", HOME_MODES, mode, w -> {
            if (w == 1 && prefs.getString("homeUrl", "").isEmpty()) {
                editHomeUrl(() -> prefs.edit().putInt("homeMode", 1).apply());
            } else {
                prefs.edit().putInt("homeMode", w).apply();
                host.onChromeSettingsChanged();
                refresh();
            }
        }));
        if (mode == 1) {
            row("Адрес", prefs.getString("homeUrl", ""), null, () -> editHomeUrl(null));
        }
    }

    private void editHomeUrl(Runnable then) {
        EditText e = new EditText(a);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        e.setText(prefs.getString("homeUrl", ""));
        e.setHint("https://example.com");
        new Sheet(a)
                .setTitle("Адрес главной страницы")
                .setView(wrap(e))
                .setPositiveButton("Сохранить", (d, w) -> {
                    String url = e.getText().toString().trim();
                    if (url.isEmpty()) return;
                    if (!url.contains("://")) url = "https://" + url;
                    prefs.edit().putString("homeUrl", url).apply();
                    if (then != null) then.run();
                    host.onChromeSettingsChanged();
                    refresh();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void buildTabs() {
        choice("Вид списка вкладок", TAB_LAYOUTS, "tabLayout", 0, false);
        choice("При запуске открывать", STARTUP, "startup", 0, false);
        choice("Ссылки из других приложений", EXTERNAL, "external", 0, false);
        toggle("Закрывать вкладки инкогнито при сворачивании", null, "closeIncognito", false, false);
        toggle("Всегда открывать версию для ПК", "Для новых вкладок", "desktopDefault", false, false);
    }

    private void buildAccessibility() {
        choice("Размер текста", FONT_NAMES, "font", 2, true);
        toggle("Принудительное масштабирование", "Разрешать увеличивать любую страницу, даже если сайт это запрещает",
                "forceZoom", false, true);
        toggle("Масштаб двойным касанием", null, "doubleTapZoom", true, true);
    }

    private void buildDownloads() {
        toggle("Спрашивать, куда сохранять файлы", "Иначе файлы сохраняются в папку «Загрузки»",
                "dlAsk", false, false);
    }

    private void buildSiteSettings() {
        nav("Все сайты", "Разрешения и данные по каждому сайту", this::buildAllSites);
        section("Разрешения");
        for (SitePerms.Cat c : SitePerms.ALL) {
            nav(c.title, c.labels[perms.defaultIndex(c)], () -> buildCategory(c));
        }
        note("Буфер обмена: движок каждый раз показывает кнопку «Вставить», постоянного разрешения нет.");
    }

    private void buildCategory(SitePerms.Cat c) {
        int cur = perms.defaultIndex(c);
        if (c.labels.length == 2) {
            toggleRow(c.title, c.labels[cur], cur == 0, on -> {
                perms.setDefaultIndex(c, on ? 0 : 1);
                refresh();
            });
        } else {
            section("По умолчанию");
            for (int i = 0; i < c.labels.length; i++) {
                int idx = i;
                radioRow(c.labels[i], i == cur, () -> {
                    perms.setDefaultIndex(c, idx);
                    refresh();
                });
            }
        }
        if (c == SitePerms.NOTIFICATIONS) {
            note("Уведомления приходят, пока сайт открыт во вкладке. Push от закрытых сайтов не поддерживается.");
        }
        if (!c.perSite()) {
            note(c.note);
            return;
        }
        section("Исключения");
        Map<String, Integer> ex = perms.exceptions(c);
        if (ex.isEmpty()) note("Нет сайтов с особыми настройками");
        for (Map.Entry<String, Integer> e : ex.entrySet()) {
            String h = e.getKey();
            row(h, perms.label(c, e.getValue()), null, () -> editException(c, h));
        }
        row("Добавить сайт", null, null, () -> {
            EditText in = new EditText(a);
            in.setSingleLine(true);
            in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            in.setHint("example.com");
            new Sheet(a)
                    .setTitle("Добавить сайт")
                    .setView(wrap(in))
                    .setPositiveButton("Далее", (d, w) -> {
                        String h = hostOf(in.getText().toString());
                        if (!h.isEmpty()) editException(c, h);
                    })
                    .setNegativeButton("Отмена", null)
                    .show();
        });
    }

    private void editException(SitePerms.Cat c, String h) {
        List<String> labels = new ArrayList<>();
        List<Integer> values = new ArrayList<>();
        for (int i = 0; i < c.values.length; i++) {
            if (c.values[i] == SitePerms.ASK) continue;
            labels.add(c.labels[i]);
            values.add(c.values[i]);
        }
        if (!values.contains(SitePerms.ALLOW)) {
            labels.add(0, "Разрешать");
            values.add(0, SitePerms.ALLOW);
        }
        labels.add("По умолчанию (" + c.labels[perms.defaultIndex(c)].toLowerCase(Locale.ROOT) + ")");
        values.add(-1);
        int ex = perms.exception(c, h);
        int sel = values.indexOf(ex);
        pick(h + " · " + c.title, labels.toArray(new String[0]), sel, w -> {
            perms.setException(c, h, values.get(w));
            refresh();
        });
    }

    private void buildAllSites() {
        int gen = generation;
        TreeSet<String> hosts = new TreeSet<>(perms.hosts());
        runtime.getStorageController().getAllPermissions().accept(list -> {
            if (list != null) {
                for (ContentPermission p : list) {
                    if (p.privateMode || p.permission != PermissionDelegate.PERMISSION_TRACKING) continue;
                    String h = Uri.parse(p.uri).getHost();
                    if (h != null) hosts.add(h);
                }
            }
            if (gen != generation) return;
            if (hosts.isEmpty()) note("Сайтов с особыми настройками пока нет");
            for (String h : hosts) row(h, siteSummary(h), null, () -> push(h, () -> buildSite(h)));
        }, e -> {
            if (gen == generation) note("Не удалось получить список сайтов");
        });
    }

    private String siteSummary(String h) {
        int n = 0;
        for (SitePerms.Cat c : SitePerms.ALL) {
            if (perms.exception(c, h) >= 0) n++;
        }
        return n == 0 ? "Особых разрешений нет" : "Особых разрешений: " + n;
    }

    private void buildSite(String h) {
        title.setText(h);
        section("Разрешения");
        for (SitePerms.Cat c : SitePerms.ALL) {
            if (!c.perSite()) continue;
            int ex = perms.exception(c, h);
            String summary = ex >= 0 ? perms.label(c, ex)
                    : "По умолчанию: " + c.labels[perms.defaultIndex(c)].toLowerCase(Locale.ROOT);
            row(c.title, summary, null, () -> editException(c, h));
        }
        int gen = generation;
        String origin = "https://" + h;
        runtime.getStorageController().getPermissions(origin).accept(list -> {
            if (gen != generation) return;
            ContentPermission tp = null;
            if (list != null) {
                for (ContentPermission p : list) {
                    if (p.permission == PermissionDelegate.PERMISSION_TRACKING) tp = p;
                }
            }
            boolean off = tp != null && tp.value == ContentPermission.VALUE_ALLOW;
            ContentPermission existing = tp;
            section("Защита");
            toggleRow("Защита от отслеживания", off ? "Выключена для этого сайта" : "Включена", !off, on -> {
                ContentPermission p = existing != null ? existing : SitePerms.make(origin, "trackingprotection");
                if (p != null) {
                    runtime.getStorageController().setPermission(p,
                            on ? ContentPermission.VALUE_DENY : ContentPermission.VALUE_ALLOW);
                }
                postDelayed(this::refresh, 300);
            });
            section("Данные");
            row("Удалить данные и сбросить разрешения", "Cookie, хранилище и кэш сайта " + h, null,
                    () -> confirm("Удалить данные сайта " + h + " и сбросить его разрешения?", () -> {
                        perms.forgetSite(h);
                        runtime.getStorageController().clearDataFromBaseDomain(h,
                                StorageController.ClearFlags.ALL).accept(
                                v -> Toast.makeText(a, "Удалено", Toast.LENGTH_SHORT).show(), err -> { });
                        postDelayed(this::refresh, 300);
                    }));
        }, e -> { });
    }

    private void buildLanguages() {
        section("Языки сайтов");
        note("Сайты получают этот список в заголовке Accept-Language и выбирают по нему язык");
        String[] cur = locales(prefs);
        if (cur.length == 0) {
            row(langName(Locale.getDefault().getLanguage()), "Язык системы", null, null);
        }
        for (int i = 0; i < cur.length; i++) {
            int idx = i;
            row(langName(cur[i]), i == 0 ? "Основной" : null, null, () -> {
                List<String> acts = new ArrayList<>();
                if (idx > 0) acts.add("Сделать основным");
                acts.add("Удалить");
                new Sheet(a)
                        .setTitle(langName(cur[idx]))
                        .setItems(acts.toArray(new String[0]), (d, w) -> {
                            List<String> l = new ArrayList<>(Arrays.asList(cur));
                            String code = l.remove(idx);
                            if ("Сделать основным".equals(acts.get(w))) l.add(0, code);
                            saveLangs(l);
                        })
                        .show();
            });
        }
        row("Добавить язык", null, null, () -> {
            List<String> have = new ArrayList<>(Arrays.asList(cur));
            if (have.isEmpty()) have.add(Locale.getDefault().getLanguage());
            List<String> codes = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (String c : LANG_CODES) {
                if (have.contains(c)) continue;
                codes.add(c);
                names.add(langName(c));
            }
            push("Добавить язык", () -> {
                for (int i = 0; i < codes.size(); i++) {
                    String code = codes.get(i);
                    row(names.get(i), null, null, () -> {
                        have.add(code);
                        prefs.edit().putString("langs", String.join(",", have)).apply();
                        apply(runtime, prefs);
                        back();
                    });
                }
            });
        });

        section("Перевод");
        toggle("Предлагать перевод страниц", "Перевод выполняется на устройстве, текст никуда не отправляется",
                "translateOffer", true, true);
        String target = translateTarget(prefs);
        row("Переводить на", langName(target), null, () -> RuntimeTranslation.listSupportedLanguages().accept(sup -> {
            if (sup == null || sup.toLanguages == null) return;
            List<Language> to = new ArrayList<>(sup.toLanguages);
            String[] names = new String[to.size()];
            int sel = -1;
            for (int i = 0; i < names.length; i++) {
                names[i] = to.get(i).localizedDisplayName;
                if (to.get(i).code.equals(target)) sel = i;
            }
            pick("Переводить на", names, sel, w -> {
                prefs.edit().putString("translateTo", to.get(w).code).apply();
                refresh();
            });
        }, e -> Toast.makeText(a, "Перевод недоступен", Toast.LENGTH_SHORT).show()));
        nav("Языковые модели", "Скачанные модели перевода", this::buildModels);
        nav("Сайты без перевода", null, this::buildNeverTranslate);
    }

    private void saveLangs(List<String> l) {
        prefs.edit().putString("langs", String.join(",", l)).apply();
        apply(runtime, prefs);
        refresh();
    }

    private static String langName(String code) {
        Locale l = Locale.forLanguageTag(code);
        String n = l.getDisplayName(Locale.forLanguageTag("ru"));
        if (n.isEmpty()) return code;
        return n.substring(0, 1).toUpperCase(Locale.ROOT) + n.substring(1);
    }

    private void buildModels() {
        int gen = generation;
        note("Модель скачивается при первом переводе с языка. Здесь можно скачать заранее или удалить.");
        RuntimeTranslation.listModelDownloadStates().accept(models -> {
            if (gen != generation || models == null) return;
            for (RuntimeTranslation.LanguageModel m : models) {
                if (m.language == null) continue;
                boolean have = Boolean.TRUE.equals(m.isDownloaded);
                String size = android.text.format.Formatter.formatShortFileSize(a, m.size);
                row(m.language.localizedDisplayName, (have ? "Скачана, " : "Не скачана, ") + size, null, () ->
                        RuntimeTranslation.manageLanguageModel(new RuntimeTranslation.ModelManagementOptions.Builder()
                                .languageToManage(m.language.code)
                                .operation(have ? RuntimeTranslation.DELETE : RuntimeTranslation.DOWNLOAD)
                                .operationLevel(RuntimeTranslation.LANGUAGE)
                                .build()).accept(v -> {
                            if (gen == generation) refresh();
                        }, e -> Toast.makeText(a, "Не получилось", Toast.LENGTH_SHORT).show()));
            }
            if (models.isEmpty()) note("Список моделей пуст");
        }, e -> {
            if (gen == generation) note("Перевод недоступен на этом устройстве");
        });
    }

    private void buildNeverTranslate() {
        int gen = generation;
        RuntimeTranslation.getNeverTranslateSiteList().accept(sites -> {
            if (gen != generation) return;
            if (sites == null || sites.isEmpty()) {
                note("Нет сайтов");
                return;
            }
            for (String s : sites) {
                row(s, "Нажмите, чтобы снова предлагать перевод", null, () ->
                        RuntimeTranslation.setNeverTranslateSpecifiedSite(false, s).accept(v -> {
                            if (gen == generation) refresh();
                        }, e -> { }));
            }
        }, e -> { });
    }

    void showClearData() {
        String[] items = {"Кэш", "Cookie и данные сайтов", "Разрешения сайтов", "История просмотров"};
        boolean[] checked = {true, true, false, false};
        new Sheet(a)
                .setTitle("Удалить данные")
                .setMultiChoiceItems(items, checked, (d, w, on) -> checked[w] = on)
                .setPositiveButton("Удалить", (d, w) ->
                        host.clearData(checked[0], checked[1], checked[2], checked[3]))
                .setNegativeButton("Отмена", null)
                .show();
    }

    static String hostOf(String in) {
        String s = in.trim();
        if (s.isEmpty()) return "";
        if (!s.contains("://")) s = "https://" + s;
        String h = Uri.parse(s).getHost();
        return h == null ? "" : h.toLowerCase(Locale.ROOT);
    }

    private View wrap(View v) {
        LinearLayout l = new LinearLayout(a);
        l.setPadding(Ui.dp(a, 20), Ui.dp(a, 8), Ui.dp(a, 20), 0);
        l.addView(v, new LayoutParams(-1, -2));
        return l;
    }

    private void confirm(String text, Runnable ok) {
        new Sheet(a)
                .setMessage(text)
                .setPositiveButton("Удалить", (d, w) -> ok.run())
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void pick(String name, String[] options, int cur, IntConsumer done) {
        int[] sel = {cur};
        push(name, () -> {
            for (int i = 0; i < options.length; i++) {
                int idx = i;
                radioRow(options[i], sel[0] == i, () -> {
                    sel[0] = idx;
                    done.accept(idx);
                });
            }
        });
    }

    private void radioRow(String text, boolean on, Runnable click) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(Ui.dp(a, 56));
        r.setPadding(Ui.dp(a, 12), Ui.dp(a, 6), Ui.dp(a, 16), Ui.dp(a, 6));
        r.setBackground(Ui.ripple(null, Ui.round(0xFFFFFFFF, 0)));
        android.widget.RadioButton rb = new android.widget.RadioButton(a);
        rb.setChecked(on);
        rb.setClickable(false);
        rb.setButtonTintList(new ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{Ui.ACCENT, Ui.ICON}));
        LayoutParams rlp = new LayoutParams(-2, -2);
        rlp.setMarginEnd(Ui.dp(a, 12));
        r.addView(rb, rlp);
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextSize(16);
        t.setTextColor(Ui.TEXT);
        r.addView(t, new LayoutParams(0, -2, 1f));
        r.setOnClickListener(v -> click.run());
        list.addView(r, new LayoutParams(-1, -2));
    }

    private void section(String name) {
        TextView t = new TextView(a);
        t.setText(name);
        t.setTextSize(14);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(Ui.ACCENT);
        t.setPadding(Ui.dp(a, 20), Ui.dp(a, 24), Ui.dp(a, 20), Ui.dp(a, 8));
        list.addView(t);
    }

    private void note(String text) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextSize(13);
        t.setTextColor(Ui.TEXT_SECONDARY);
        t.setPadding(Ui.dp(a, 20), Ui.dp(a, 8), Ui.dp(a, 20), Ui.dp(a, 8));
        list.addView(t);
    }

    private void nav(String name, String summary, Runnable build) {
        row(name, summary, null, () -> push(name, build));
    }

    private LinearLayout row(String title, String summary, View end, Runnable click) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(Ui.dp(a, 64));
        r.setPadding(Ui.dp(a, 20), Ui.dp(a, 10), Ui.dp(a, 16), Ui.dp(a, 10));
        if (click != null) {
            r.setBackground(Ui.ripple(null, Ui.round(0xFFFFFFFF, 0)));
            r.setOnClickListener(v -> click.run());
        }
        LinearLayout texts = new LinearLayout(a);
        texts.setOrientation(VERTICAL);
        TextView t = new TextView(a);
        t.setText(title);
        t.setTextSize(16);
        t.setTextColor(Ui.TEXT);
        texts.addView(t);
        if (summary != null && !summary.isEmpty()) {
            TextView s = new TextView(a);
            s.setText(summary);
            s.setTextSize(14);
            s.setTextColor(Ui.TEXT_SECONDARY);
            texts.addView(s);
        }
        r.addView(texts, new LayoutParams(0, -2, 1f));
        if (end != null) r.addView(end);
        list.addView(r, new LayoutParams(-1, -2));
        return r;
    }

    private void choice(String title, String[] options, String key, int def, boolean engine) {
        int cur = Math.min(prefs.getInt(key, def), options.length - 1);
        row(title, options[cur], null, () -> pick(title, options, cur, w -> {
            prefs.edit().putInt(key, w).apply();
            if (engine) apply(runtime, prefs);
            refresh();
        }));
    }

    private void toggle(String title, String summary, String key, boolean def, boolean engine) {
        toggleRow(title, summary, prefs.getBoolean(key, def), v -> {
            prefs.edit().putBoolean(key, v).apply();
            if (engine) apply(runtime, prefs);
            host.onChromeSettingsChanged();
        });
    }

    private interface BoolConsumer {
        void accept(boolean v);
    }

    private void toggleRow(String title, String summary, boolean on, BoolConsumer changed) {
        Switch sw = new Switch(a);
        sw.setChecked(on);
        sw.setClickable(false);
        sw.setThumbTintList(new ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{Ui.ACCENT, Ui.ICON}));
        sw.setTrackTintList(new ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{0x88FCB4B8, 0x55888888}));
        row(title, summary, sw, () -> {
            boolean v = !sw.isChecked();
            sw.setChecked(v);
            changed.accept(v);
        });
    }
}
