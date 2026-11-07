package dev.antite.browser;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import org.mozilla.geckoview.ContentBlocking;
import org.mozilla.geckoview.GeckoPreferenceController;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;

import java.util.Locale;

final class EngineSettings {
    static final String[] ENGINE_URLS = {
            "https://duckduckgo.com/?q=",
            "https://www.google.com/search?q=",
            "https://yandex.ru/search/?text=",
            "https://www.bing.com/search?q=",
            "https://www.startpage.com/do/search?q=",
    };
    static final int[] COOKIE_VALUES = {
            ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS,
            ContentBlocking.CookieBehavior.ACCEPT_NON_TRACKERS,
            ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY,
            ContentBlocking.CookieBehavior.ACCEPT_ALL,
    };
    static final String[] DOH_URLS = {
            null,
            "https://mozilla.cloudflare-dns.com/dns-query",
            "https://dns.quad9.net/dns-query",
            "https://dns.mullvad.net/dns-query",
            "https://dns.adguard-dns.com/dns-query",
    };
    static final float[] FONT_VALUES = {0.8f, 0.9f, 1f, 1.1f, 1.2f, 1.3f, 1.5f, 1.75f, 2f};
    static final String[] PRELOAD_BOOLS = {"network.prefetch-next", "network.predictor.enabled",
            "browser.places.speculativeConnect.enabled"};

    private EngineSettings() {
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

        s.setLoginAutofillEnabled(p.getBoolean("passwordsAutofill", true));
        pref("signon.rememberSignons", p.getBoolean("passwordsSave", true));
        pref("signon.generation.enabled", p.getBoolean("passwordsSave", true));
        boolean addresses = p.getBoolean("addressesEnabled", true);
        GeckoPreferenceController.setGeckoPref("extensions.formautofill.available", "on",
                GeckoPreferenceController.PREF_BRANCH_USER);
        GeckoPreferenceController.setGeckoPref("extensions.formautofill.addresses.supported",
                addresses ? "on" : "off", GeckoPreferenceController.PREF_BRANCH_USER);
        GeckoPreferenceController.setGeckoPref("extensions.formautofill.addresses.capture.requiredFields",
                "street-address,address-level2", GeckoPreferenceController.PREF_BRANCH_USER);
        pref("extensions.formautofill.addresses.enabled", addresses);
        pref("extensions.formautofill.addresses.capture.enabled", addresses);
        pref("extensions.formautofill.creditCards.enabled", p.getBoolean("cardsEnabled", true));

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
}
