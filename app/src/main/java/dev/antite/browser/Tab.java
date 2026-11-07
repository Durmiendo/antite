package dev.antite.browser;

import android.graphics.Bitmap;

import org.mozilla.geckoview.GeckoSession;

final class Tab {
    private static long nextId = System.currentTimeMillis();

    final long id;
    final boolean incognito;
    GeckoSession session;
    String url = "";
    String title = "";
    String state;
    Bitmap thumb;
    long parentId;
    long lastUsed;
    boolean canGoBack;
    boolean canGoForward;
    boolean loading;
    boolean secure;
    boolean desktop;
    String host = "";
    String issuer = "";
    int progress;
    String docLang;
    boolean translated;

    Tab(long id, boolean incognito) {
        this.id = id;
        this.incognito = incognito;
        if (id >= nextId) nextId = id + 1;
    }

    Tab(boolean incognito) {
        this(nextId++, incognito);
    }

    String label() {
        if (!title.isEmpty()) return title;
        if (!url.isEmpty() && !"about:blank".equals(url)) return url;
        return "Новая вкладка";
    }

    boolean isLive() {
        return session != null;
    }
}
