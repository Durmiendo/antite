package dev.antite.browser;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;
import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebResponse;

import java.security.cert.X509Certificate;
import java.util.List;

final class TabDelegates {
    interface Host {
        Tab current();

        void switchTo(Tab t);

        void closeTab(Tab t);

        void recover(Tab t);

        GeckoSession openChild(Tab parent, String uri);

        void onUrlChanged(Tab t);

        void onLoadingChanged(Tab t);

        void onProgress(Tab t);

        void captureThumbSoon(Tab t, long delayMs, boolean onlyWhenLoaded);

        void scheduleSave();

        void setFullscreen(boolean full);

        void showContextMenu(Tab t, GeckoSession.ContentDelegate.ContextElement el);

        void download(WebResponse r);

        void onScroll(Tab t, int y);
    }

    private final Context c;
    private final Host host;
    private final Db db;
    private final IconCache icons;
    private final SitePerms perms;
    private final SitePrompts prompts;
    private final Translator translator;
    private final Prompts dialogs;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());

    TabDelegates(Context c, Host host, Db db, IconCache icons, SitePerms perms, SitePrompts prompts,
                 Translator translator, Prompts dialogs) {
        this.c = c;
        this.host = host;
        this.db = db;
        this.icons = icons;
        this.perms = perms;
        this.prompts = prompts;
        this.translator = translator;
        this.dialogs = dialogs;
    }

    boolean javascriptAllowed(String url) {
        return perms.decide(SitePerms.JS, Urls.siteHost(url)) == SitePerms.ALLOW;
    }

    void attach(Tab t, GeckoSession s, WebExtension iconsExt) {
        attachIcons(t, s, iconsExt);
        s.setMediaSessionDelegate(MediaService.delegateFor(c, t, icons, () -> prompts.requestNotifications(true)));
        s.setPromptDelegate(dialogs);
        s.setTranslationsSessionDelegate(translator.delegateFor(t));
        s.setHistoryDelegate(history(t));
        s.setPermissionDelegate(prompts.delegateFor(t));
        s.setNavigationDelegate(navigation(t));
        s.setProgressDelegate(progress(t));
        s.setContentDelegate(content(t));
        s.setScrollDelegate(new GeckoSession.ScrollDelegate() {
            @Override
            public void onScrollChanged(GeckoSession session, int x, int y) {
                host.onScroll(t, y);
            }
        });
    }

    void attachIcons(Tab t, GeckoSession s, WebExtension iconsExt) {
        if (iconsExt == null || t.incognito) return;
        s.getWebExtensionController().setMessageDelegate(iconsExt, new WebExtension.MessageDelegate() {
            @Override
            public GeckoResult<Object> onMessage(String app, Object message, WebExtension.MessageSender sender) {
                if (!(message instanceof JSONObject)) return null;
                JSONObject m = (JSONObject) message;
                Uri page = Uri.parse(m.optString("page"));
                if (page.getHost() == null) return null;
                String h = Urls.host(page.toString());
                JSONArray th = m.optJSONArray("theme");
                icons.setTheme(h, th == null || th.length() < 3 ? 0
                        : android.graphics.Color.rgb(th.optInt(0), th.optInt(1), th.optInt(2)));
                icons.onPageIcon(h, m.optString("icon", null), page.getScheme() + "://" + page.getHost());
                return null;
            }
        }, "antite");
    }

    private GeckoSession.HistoryDelegate history(Tab t) {
        return new GeckoSession.HistoryDelegate() {
            @Override
            public GeckoResult<Boolean> onVisited(GeckoSession s, String url, String last, int flags) {
                if (t.incognito || (flags & VISIT_TOP_LEVEL) == 0 || (flags & VISIT_UNRECOVERABLE_ERROR) != 0
                        || (flags & (VISIT_REDIRECT_SOURCE | VISIT_REDIRECT_SOURCE_PERMANENT)) != 0
                        || !Urls.isWeb(url)) {
                    return GeckoResult.fromValue(false);
                }
                String title = t.url.equals(url) ? t.title : null;
                db.run(() -> db.visit(url, title));
                return GeckoResult.fromValue(true);
            }

            @Override
            public GeckoResult<boolean[]> getVisited(GeckoSession s, String[] urls) {
                GeckoResult<boolean[]> r = new GeckoResult<>();
                db.run(() -> r.complete(db.visited(urls)));
                return r;
            }
        };
    }

    private GeckoSession.NavigationDelegate navigation(Tab t) {
        return new GeckoSession.NavigationDelegate() {
            @Override
            public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession s, LoadRequest request) {
                boolean js = javascriptAllowed(request.uri);
                if (s.getSettings().getAllowJavascript() != js) s.getSettings().setAllowJavascript(js);
                return null;
            }

            @Override
            public void onLocationChange(GeckoSession s, String url,
                                         List<GeckoSession.PermissionDelegate.ContentPermission> perms,
                                         Boolean hasUserGesture) {
                t.url = url == null ? "" : url;
                host.onUrlChanged(t);
            }

            @Override
            public void onCanGoBack(GeckoSession s, boolean can) {
                t.canGoBack = can;
            }

            @Override
            public void onCanGoForward(GeckoSession s, boolean can) {
                t.canGoForward = can;
            }

            @Override
            public GeckoResult<GeckoSession> onNewSession(GeckoSession s, String uri) {
                return GeckoResult.fromValue(host.openChild(t, uri));
            }
        };
    }

    private GeckoSession.ProgressDelegate progress(Tab t) {
        return new GeckoSession.ProgressDelegate() {
            @Override
            public void onPageStart(GeckoSession s, String url) {
                t.loading = true;
                t.progress = 0;
                host.onLoadingChanged(t);
            }

            @Override
            public void onPageStop(GeckoSession s, boolean success) {
                t.loading = false;
                host.onLoadingChanged(t);
                host.captureThumbSoon(t, 1000, true);
            }

            @Override
            public void onProgressChange(GeckoSession s, int p) {
                t.progress = p;
                host.onProgress(t);
            }

            @Override
            public void onSecurityChange(GeckoSession s, SecurityInformation info) {
                t.secure = info.isSecure;
                t.host = info.host == null ? "" : info.host;
                X509Certificate cert = info.certificate;
                t.issuer = cert != null ? cert.getIssuerX500Principal().getName() : "";
            }

            @Override
            public void onSessionStateChange(GeckoSession s, GeckoSession.SessionState state) {
                if (t.incognito) return;
                t.state = state.toString();
                host.scheduleSave();
            }
        };
    }

    private GeckoSession.ContentDelegate content(Tab t) {
        return new GeckoSession.ContentDelegate() {
            @Override
            public void onTitleChange(GeckoSession s, String title) {
                t.title = title == null ? "" : title;
                if (!t.incognito) {
                    String url = t.url;
                    String name = t.title;
                    db.run(() -> db.title(url, name));
                }
                host.scheduleSave();
            }

            @Override
            public void onCloseRequest(GeckoSession s) {
                host.closeTab(t);
            }

            @Override
            public void onFocusRequest(GeckoSession s) {
                if (t != host.current()) main.post(() -> host.switchTo(t));
            }

            @Override
            public void onFirstContentfulPaint(GeckoSession s) {
                host.captureThumbSoon(t, 1500, false);
            }

            @Override
            public void onFullScreen(GeckoSession s, boolean full) {
                if (t == host.current()) host.setFullscreen(full);
            }

            @Override
            public void onContextMenu(GeckoSession s, int x, int y, ContextElement el) {
                host.showContextMenu(t, el);
            }

            @Override
            public void onExternalResponse(GeckoSession s, WebResponse response) {
                host.download(response);
            }

            @Override
            public void onCrash(GeckoSession s) {
                host.recover(t);
            }

            @Override
            public void onKill(GeckoSession s) {
                host.recover(t);
            }
        };
    }
}
