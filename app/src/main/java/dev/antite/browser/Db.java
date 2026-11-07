package dev.antite.browser;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class Db extends SQLiteOpenHelper {
    static final class Entry {
        final long id;
        final String url;
        final String title;
        final long time;
        final String extra;

        Entry(long id, String url, String title, long time, String extra) {
            this.id = id;
            this.url = url;
            this.title = title;
            this.time = time;
            this.extra = extra;
        }
    }

    private static final long HISTORY_KEEP_MS = 90L * 24 * 3600 * 1000;

    private static Db instance;

    private final Worker worker = new Worker();

    static synchronized Db get(Context c) {
        if (instance == null) instance = new Db(c.getApplicationContext());
        return instance;
    }

    private Db(Context c) {
        super(c, "antite.db", null, 2);
        setWriteAheadLoggingEnabled(true);
    }

    void run(Runnable work) {
        worker.run(work);
    }

    <T> void read(Supplier<T> query, Consumer<T> onMain) {
        worker.read(query, onMain);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE history (id INTEGER PRIMARY KEY, url TEXT UNIQUE NOT NULL, title TEXT, visited INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX history_visited ON history(visited)");
        db.execSQL("CREATE TABLE bookmarks (id INTEGER PRIMARY KEY, url TEXT UNIQUE NOT NULL, title TEXT, created INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE downloads (id INTEGER PRIMARY KEY, name TEXT NOT NULL, uri TEXT, mime TEXT, created INTEGER NOT NULL, status TEXT NOT NULL)");
        db.execSQL("CREATE TABLE closed (id INTEGER PRIMARY KEY, url TEXT NOT NULL, title TEXT, state TEXT, closed INTEGER NOT NULL)");
        onUpgrade(db, 1, 2);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        if (oldV < 2) {
            db.execSQL("ALTER TABLE history ADD COLUMN visits INTEGER NOT NULL DEFAULT 1");
            db.execSQL("CREATE TABLE pins (id INTEGER PRIMARY KEY, url TEXT NOT NULL, title TEXT, pos INTEGER NOT NULL)");
        }
    }

    static final class Site {
        final String host;
        final String url;
        final String title;

        Site(String host, String url, String title) {
            this.host = host;
            this.url = url;
            this.title = title;
        }
    }

    List<Entry> pins() {
        List<Entry> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id, url, title, pos FROM pins ORDER BY pos", null)) {
            while (c.moveToNext()) out.add(new Entry(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), null));
        }
        return out;
    }

    void addPin(String url, String title) {
        SQLiteDatabase db = getWritableDatabase();
        long pos;
        try (Cursor c = db.rawQuery("SELECT IFNULL(MAX(pos), -1) + 1 FROM pins", null)) {
            c.moveToFirst();
            pos = c.getLong(0);
        }
        ContentValues v = new ContentValues();
        v.put("url", url);
        v.put("title", title);
        v.put("pos", pos);
        db.insert("pins", null, v);
    }

    void updatePin(long id, String url, String title) {
        ContentValues v = new ContentValues();
        v.put("url", url);
        v.put("title", title);
        getWritableDatabase().update("pins", v, "id=?", new String[]{String.valueOf(id)});
    }

    void deletePin(long id) {
        getWritableDatabase().delete("pins", "id=?", new String[]{String.valueOf(id)});
    }

    void movePin(long id, int delta) {
        List<Entry> list = pins();
        int i = -1;
        for (int k = 0; k < list.size(); k++) if (list.get(k).id == id) i = k;
        int j = i + delta;
        if (i < 0 || j < 0 || j >= list.size()) return;
        Entry a = list.get(i);
        Entry b = list.get(j);
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("UPDATE pins SET pos=? WHERE id=?", new Object[]{b.time, a.id});
        db.execSQL("UPDATE pins SET pos=? WHERE id=?", new Object[]{a.time, b.id});
    }

    List<Site> frequent(java.util.Set<String> skipHosts, int limit) {
        List<Site> out = new ArrayList<>();
        java.util.Map<String, int[]> score = new java.util.LinkedHashMap<>();
        java.util.Map<String, String> titles = new java.util.HashMap<>();
        java.util.Map<String, String> schemes = new java.util.HashMap<>();
        long cutoff = System.currentTimeMillis() - 30L * 24 * 3600 * 1000;
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT url, title, visits FROM history WHERE visited>? ORDER BY visits DESC LIMIT 500",
                new String[]{String.valueOf(cutoff)})) {
            while (c.moveToNext()) {
                android.net.Uri u = android.net.Uri.parse(c.getString(0));
                String h = u.getHost();
                if (h == null) continue;
                if (h.startsWith("www.")) h = h.substring(4);
                if (skipHosts.contains(h)) continue;
                int[] sc = score.get(h);
                if (sc == null) {
                    score.put(h, new int[]{c.getInt(2)});
                    schemes.put(h, u.getScheme() + "://" + u.getHost() + "/");
                } else {
                    sc[0] += c.getInt(2);
                }
                if (!titles.containsKey(h) && c.getString(1) != null) titles.put(h, c.getString(1));
            }
        }
        List<java.util.Map.Entry<String, int[]>> sorted = new ArrayList<>(score.entrySet());
        sorted.sort((x, y) -> y.getValue()[0] - x.getValue()[0]);
        for (java.util.Map.Entry<String, int[]> e : sorted) {
            if (out.size() >= limit) break;
            out.add(new Site(e.getKey(), schemes.get(e.getKey()), titles.get(e.getKey())));
        }
        return out;
    }

    List<Entry> suggest(String text, int limit) {
        List<Entry> out = new ArrayList<>();
        String f = "%" + text + "%";
        java.util.Set<String> seen = new java.util.HashSet<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, url, title, created, 'b' FROM bookmarks WHERE url LIKE ? OR title LIKE ? LIMIT ?",
                new String[]{f, f, String.valueOf(limit)})) {
            while (c.moveToNext()) {
                if (seen.add(c.getString(1))) out.add(new Entry(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), "b"));
            }
        }
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, url, title, visited, 'h' FROM history WHERE url LIKE ? OR title LIKE ? "
                        + "ORDER BY (url LIKE ?) DESC, visits DESC, visited DESC LIMIT ?",
                new String[]{f, f, "%://" + text + "%", String.valueOf(limit * 2)})) {
            while (c.moveToNext() && out.size() < limit) {
                if (seen.add(c.getString(1))) out.add(new Entry(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), "h"));
            }
        }
        return out;
    }

    void visit(String url, String title) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("url", url);
        v.put("visited", System.currentTimeMillis());
        if (title != null && !title.isEmpty()) v.put("title", title);
        if (db.update("history", v, "url=?", new String[]{url}) == 0) {
            db.insert("history", null, v);
        } else {
            db.execSQL("UPDATE history SET visits=visits+1 WHERE url=?", new Object[]{url});
        }
    }

    void title(String url, String title) {
        if (title == null || title.isEmpty()) return;
        ContentValues v = new ContentValues();
        v.put("title", title);
        getWritableDatabase().update("history", v, "url=?", new String[]{url});
    }

    boolean[] visited(String[] urls) {
        boolean[] out = new boolean[urls.length];
        SQLiteDatabase db = getReadableDatabase();
        for (int i = 0; i < urls.length; i++) {
            try (Cursor c = db.rawQuery("SELECT 1 FROM history WHERE url=? LIMIT 1", new String[]{urls[i]})) {
                out[i] = c.moveToFirst();
            }
        }
        return out;
    }

    List<Entry> history(String filter, int limit) {
        prune();
        return query("SELECT id, url, title, visited, NULL FROM history", "visited", filter, limit);
    }

    void deleteHistory(long id) {
        getWritableDatabase().delete("history", "id=?", new String[]{String.valueOf(id)});
    }

    void clearHistory() {
        getWritableDatabase().delete("history", null, null);
    }

    private void prune() {
        long cutoff = System.currentTimeMillis() - HISTORY_KEEP_MS;
        getWritableDatabase().delete("history", "visited<?", new String[]{String.valueOf(cutoff)});
    }

    boolean isBookmarked(String url) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT 1 FROM bookmarks WHERE url=?", new String[]{url})) {
            return c.moveToFirst();
        }
    }

    void addBookmark(String url, String title) {
        ContentValues v = new ContentValues();
        v.put("url", url);
        v.put("title", title);
        v.put("created", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("bookmarks", null, v, SQLiteDatabase.CONFLICT_IGNORE);
    }

    void removeBookmark(String url) {
        getWritableDatabase().delete("bookmarks", "url=?", new String[]{url});
    }

    void deleteBookmark(long id) {
        getWritableDatabase().delete("bookmarks", "id=?", new String[]{String.valueOf(id)});
    }

    void renameBookmark(long id, String title) {
        ContentValues v = new ContentValues();
        v.put("title", title);
        getWritableDatabase().update("bookmarks", v, "id=?", new String[]{String.valueOf(id)});
    }

    List<Entry> bookmarks(String filter) {
        return query("SELECT id, url, title, created, NULL FROM bookmarks", "created", filter, 5000);
    }

    long addDownload(String name, String mime) {
        ContentValues v = new ContentValues();
        v.put("name", name);
        v.put("mime", mime);
        v.put("created", System.currentTimeMillis());
        v.put("status", "loading");
        return getWritableDatabase().insert("downloads", null, v);
    }

    void finishDownload(long id, String uri, boolean ok) {
        ContentValues v = new ContentValues();
        v.put("uri", uri);
        v.put("status", ok ? "done" : "failed");
        getWritableDatabase().update("downloads", v, "id=?", new String[]{String.valueOf(id)});
    }

    List<Entry> downloads(String filter) {
        return query("SELECT id, uri, name, created, status || '|' || IFNULL(mime,'') FROM downloads",
                "created", filter, 1000);
    }

    void clearDownloads() {
        getWritableDatabase().delete("downloads", null, null);
    }

    void clearClosed() {
        getWritableDatabase().delete("closed", null, null);
    }

    void deleteDownload(long id) {
        getWritableDatabase().delete("downloads", "id=?", new String[]{String.valueOf(id)});
    }

    void addClosed(String url, String title, String state) {
        ContentValues v = new ContentValues();
        v.put("url", url);
        v.put("title", title);
        v.put("state", state);
        v.put("closed", System.currentTimeMillis());
        SQLiteDatabase db = getWritableDatabase();
        db.insert("closed", null, v);
        db.execSQL("DELETE FROM closed WHERE id NOT IN (SELECT id FROM closed ORDER BY closed DESC LIMIT 50)");
    }

    List<Entry> closed(String filter) {
        return query("SELECT id, url, title, closed, state FROM closed", "closed", filter, 50);
    }

    void deleteClosed(long id) {
        getWritableDatabase().delete("closed", "id=?", new String[]{String.valueOf(id)});
    }

    private List<Entry> query(String base, String order, String filter, int limit) {
        List<Entry> out = new ArrayList<>();
        String sql = base;
        String[] args = null;
        if (filter != null && !filter.isEmpty()) {
            String col = base.contains("FROM downloads") ? "name" : "title";
            String urlCol = base.contains("FROM downloads") ? "uri" : "url";
            sql += " WHERE " + col + " LIKE ? OR " + urlCol + " LIKE ?";
            String f = "%" + filter + "%";
            args = new String[]{f, f};
        }
        sql += " ORDER BY " + order + " DESC LIMIT " + limit;
        try (Cursor c = getReadableDatabase().rawQuery(sql, args)) {
            while (c.moveToNext()) {
                out.add(new Entry(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getString(4)));
            }
        }
        return out;
    }
}
