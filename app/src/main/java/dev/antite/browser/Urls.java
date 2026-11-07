package dev.antite.browser;

import android.net.Uri;

import java.util.Locale;

final class Urls {
    private Urls() {
    }

    static boolean isWeb(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }

    static boolean isBlank(String url) {
        return url == null || url.isEmpty() || "about:blank".equals(url);
    }

    static String host(String url) {
        String h = url == null ? null : Uri.parse(url).getHost();
        if (h == null) return url == null ? "" : url;
        return h.startsWith("www.") ? h.substring(4) : h;
    }

    static String siteHost(String url) {
        String h = url == null ? null : Uri.parse(url).getHost();
        return h == null ? "" : h.toLowerCase(Locale.ROOT);
    }

    static String hostOfInput(String in) {
        String s = in.trim();
        if (s.isEmpty()) return "";
        return siteHost(s.contains("://") ? s : "https://" + s);
    }

    static String langName(String code) {
        String n = Locale.forLanguageTag(code).getDisplayName(Locale.forLanguageTag("ru"));
        if (n.isEmpty()) return code;
        return n.substring(0, 1).toUpperCase(Locale.ROOT) + n.substring(1);
    }
}
