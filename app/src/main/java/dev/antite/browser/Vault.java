package dev.antite.browser;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;

import org.json.JSONException;
import org.json.JSONObject;
import org.mozilla.geckoview.Autocomplete;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class Vault extends SQLiteOpenHelper {
    static final class Login {
        final String guid;
        final String origin;
        final String username;
        final long used;

        Login(String guid, String origin, String username, long used) {
            this.guid = guid;
            this.origin = origin;
            this.username = username;
            this.used = used;
        }

        String host() {
            return Urls.host(origin);
        }
    }

    static final class Card {
        final String guid;
        final String name;
        final String last4;
        final String month;
        final String year;

        Card(String guid, String name, String last4, String month, String year) {
            this.guid = guid;
            this.name = name;
            this.last4 = last4;
            this.month = month;
            this.year = year;
        }

        String label() {
            return "•••• " + last4 + (month == null || month.isEmpty() ? "" : "  " + month + "/" + year);
        }
    }

    private static Vault instance;
    private final Worker worker = new Worker();

    static synchronized Vault get(Context c) {
        if (instance == null) instance = new Vault(c.getApplicationContext());
        return instance;
    }

    private Vault(Context c) {
        super(c, "vault.db", null, 1);
        setWriteAheadLoggingEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE logins (guid TEXT PRIMARY KEY, origin TEXT NOT NULL, host TEXT NOT NULL, "
                + "form_action TEXT, http_realm TEXT, username TEXT NOT NULL, password TEXT NOT NULL, "
                + "created INTEGER NOT NULL, used INTEGER NOT NULL, times INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX logins_host ON logins(host)");
        db.execSQL("CREATE TABLE never (host TEXT PRIMARY KEY)");
        db.execSQL("CREATE TABLE addresses (guid TEXT PRIMARY KEY, data TEXT NOT NULL, used INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE cards (guid TEXT PRIMARY KEY, name TEXT, number TEXT NOT NULL, last4 TEXT NOT NULL, "
                + "month TEXT, year TEXT, used INTEGER NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int from, int to) {
    }

    void run(Runnable work) {
        worker.run(work);
    }

    <T> void read(Supplier<T> query, Consumer<T> onMain) {
        worker.read(query, onMain);
    }

    private static String guid() {
        return "{" + UUID.randomUUID() + "}";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    static String hostOf(String origin) {
        String h = origin == null ? null : Uri.parse(origin).getHost();
        return h == null ? nz(origin).toLowerCase(Locale.ROOT) : h.toLowerCase(Locale.ROOT);
    }

    List<Autocomplete.LoginEntry> loginsFor(String domain) {
        String d = domain.toLowerCase(Locale.ROOT);
        List<Autocomplete.LoginEntry> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT guid, origin, form_action, http_realm, username, password FROM logins "
                        + "WHERE host=? OR host LIKE ? OR ? LIKE '%.' || host",
                new String[]{d, "%." + d, d})) {
            while (c.moveToNext()) {
                String pass = Crypto.decrypt(c.getString(5));
                if (pass == null) continue;
                out.add(new Autocomplete.LoginEntry.Builder()
                        .guid(c.getString(0))
                        .origin(c.getString(1))
                        .formActionOrigin(c.getString(2))
                        .httpRealm(c.getString(3))
                        .username(c.getString(4))
                        .password(pass)
                        .build());
            }
        }
        return out;
    }

    List<Login> logins(String filter) {
        List<Login> out = new ArrayList<>();
        String sql = "SELECT guid, origin, username, used FROM logins";
        String[] args = null;
        if (filter != null && !filter.isEmpty()) {
            sql += " WHERE host LIKE ? OR username LIKE ?";
            String f = "%" + filter + "%";
            args = new String[]{f, f};
        }
        try (Cursor c = getReadableDatabase().rawQuery(sql + " ORDER BY host, username", args)) {
            while (c.moveToNext()) out.add(new Login(c.getString(0), c.getString(1), c.getString(2), c.getLong(3)));
        }
        return out;
    }

    String password(String guid) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT password FROM logins WHERE guid=?",
                new String[]{guid})) {
            return c.moveToFirst() ? Crypto.decrypt(c.getString(0)) : null;
        }
    }

    Login existing(String origin, String username) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT guid, origin, username, used FROM logins WHERE host=? AND username=?",
                new String[]{hostOf(origin), nz(username)})) {
            return c.moveToFirst() ? new Login(c.getString(0), c.getString(1), c.getString(2), c.getLong(3)) : null;
        }
    }

    void saveLogin(Autocomplete.LoginEntry e) {
        if (e.origin == null || e.password == null || e.password.isEmpty()) return;
        long now = System.currentTimeMillis();
        ContentValues v = new ContentValues();
        v.put("origin", e.origin);
        v.put("host", hostOf(e.origin));
        v.put("form_action", e.formActionOrigin);
        v.put("http_realm", e.httpRealm);
        v.put("username", nz(e.username));
        v.put("password", Crypto.encrypt(e.password));
        v.put("used", now);
        SQLiteDatabase db = getWritableDatabase();
        String id = e.guid;
        if (id == null) {
            Login same = existing(e.origin, e.username);
            if (same != null) id = same.guid;
        }
        if (id != null && db.update("logins", v, "guid=?", new String[]{id}) > 0) return;
        v.put("guid", id != null ? id : guid());
        v.put("created", now);
        db.insert("logins", null, v);
    }

    void editLogin(String guid, String username, String password) {
        ContentValues v = new ContentValues();
        v.put("username", nz(username));
        v.put("password", Crypto.encrypt(password));
        getWritableDatabase().update("logins", v, "guid=?", new String[]{guid});
    }

    void loginUsed(String guid) {
        if (guid == null) return;
        getWritableDatabase().execSQL("UPDATE logins SET used=?, times=times+1 WHERE guid=?",
                new Object[]{System.currentTimeMillis(), guid});
    }

    void deleteLogin(String guid) {
        getWritableDatabase().delete("logins", "guid=?", new String[]{guid});
    }

    boolean isNever(String origin) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT 1 FROM never WHERE host=?",
                new String[]{hostOf(origin)})) {
            return c.moveToFirst();
        }
    }

    void addNever(String origin) {
        ContentValues v = new ContentValues();
        v.put("host", hostOf(origin));
        getWritableDatabase().insertWithOnConflict("never", null, v, SQLiteDatabase.CONFLICT_IGNORE);
    }

    void removeNever(String host) {
        getWritableDatabase().delete("never", "host=?", new String[]{host});
    }

    List<String> nevers() {
        List<String> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT host FROM never ORDER BY host", null)) {
            while (c.moveToNext()) out.add(c.getString(0));
        }
        return out;
    }

    List<Autocomplete.Address> addresses() {
        List<Autocomplete.Address> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT guid, data FROM addresses ORDER BY used DESC", null)) {
            while (c.moveToNext()) {
                String json = Crypto.decrypt(c.getString(1));
                if (json == null) continue;
                try {
                    out.add(addressFromJson(c.getString(0), new JSONObject(json)));
                } catch (JSONException ignored) {
                }
            }
        }
        return out;
    }

    void saveAddress(Autocomplete.Address a) {
        ContentValues v = new ContentValues();
        v.put("data", Crypto.encrypt(addressToJson(a).toString()));
        v.put("used", System.currentTimeMillis());
        SQLiteDatabase db = getWritableDatabase();
        if (a.guid != null && db.update("addresses", v, "guid=?", new String[]{a.guid}) > 0) return;
        v.put("guid", a.guid != null ? a.guid : guid());
        db.insert("addresses", null, v);
    }

    void deleteAddress(String guid) {
        getWritableDatabase().delete("addresses", "guid=?", new String[]{guid});
    }

    List<Card> cards() {
        List<Card> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT guid, name, last4, month, year FROM cards ORDER BY used DESC", null)) {
            while (c.moveToNext()) {
                out.add(new Card(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4)));
            }
        }
        return out;
    }

    List<Autocomplete.CreditCard> fullCards() {
        List<Autocomplete.CreditCard> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT guid, name, number, month, year FROM cards ORDER BY used DESC", null)) {
            while (c.moveToNext()) {
                String number = Crypto.decrypt(c.getString(2));
                if (number == null) continue;
                out.add(new Autocomplete.CreditCard.Builder()
                        .guid(c.getString(0))
                        .name(c.getString(1))
                        .number(number)
                        .expirationMonth(c.getString(3))
                        .expirationYear(c.getString(4))
                        .build());
            }
        }
        return out;
    }

    void saveCard(Autocomplete.CreditCard card) {
        String digits = nz(card.number).replaceAll("\\D", "");
        if (digits.length() < 12) return;
        ContentValues v = new ContentValues();
        v.put("name", card.name);
        v.put("number", Crypto.encrypt(digits));
        v.put("last4", digits.substring(digits.length() - 4));
        v.put("month", card.expirationMonth);
        v.put("year", card.expirationYear);
        v.put("used", System.currentTimeMillis());
        SQLiteDatabase db = getWritableDatabase();
        if (card.guid != null && db.update("cards", v, "guid=?", new String[]{card.guid}) > 0) return;
        v.put("guid", card.guid != null ? card.guid : guid());
        db.insert("cards", null, v);
    }

    void deleteCard(String guid) {
        getWritableDatabase().delete("cards", "guid=?", new String[]{guid});
    }

    private static final String[] ADDRESS_KEYS = {"name", "givenName", "additionalName", "familyName",
            "organization", "streetAddress", "addressLevel1", "addressLevel2", "addressLevel3", "postalCode",
            "country", "tel", "email"};

    static String[] addressValues(Autocomplete.Address a) {
        return new String[]{a.name, a.givenName, a.additionalName, a.familyName, a.organization, a.streetAddress,
                a.addressLevel1, a.addressLevel2, a.addressLevel3, a.postalCode, a.country, a.tel, a.email};
    }

    private static JSONObject addressToJson(Autocomplete.Address a) {
        JSONObject o = new JSONObject();
        String[] values = addressValues(a);
        try {
            for (int i = 0; i < ADDRESS_KEYS.length; i++) o.put(ADDRESS_KEYS[i], nz(values[i]));
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return o;
    }

    private static Autocomplete.Address addressFromJson(String guid, JSONObject o) {
        return new Autocomplete.Address.Builder()
                .guid(guid)
                .name(o.optString("name"))
                .givenName(o.optString("givenName"))
                .additionalName(o.optString("additionalName"))
                .familyName(o.optString("familyName"))
                .organization(o.optString("organization"))
                .streetAddress(o.optString("streetAddress"))
                .addressLevel1(o.optString("addressLevel1"))
                .addressLevel2(o.optString("addressLevel2"))
                .addressLevel3(o.optString("addressLevel3"))
                .postalCode(o.optString("postalCode"))
                .country(o.optString("country"))
                .tel(o.optString("tel"))
                .email(o.optString("email"))
                .build();
    }

    int importCsv(InputStream in) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        List<String> header = Csv.readRow(r);
        if (header == null) return 0;
        int url = -1, user = -1, pass = -1;
        for (int i = 0; i < header.size(); i++) {
            String h = header.get(i).trim().toLowerCase(Locale.ROOT).replace("\uFEFF", "");
            if (url < 0 && (h.equals("url") || h.equals("origin") || h.equals("login_uri"))) url = i;
            if (user < 0 && (h.equals("username") || h.equals("login_username"))) user = i;
            if (pass < 0 && (h.equals("password") || h.equals("login_password"))) pass = i;
        }
        if (url < 0 || pass < 0) throw new IOException("no url/password columns");
        int n = 0;
        List<String> row;
        while ((row = Csv.readRow(r)) != null) {
            String u = row.size() > url ? row.get(url).trim() : "";
            String p = row.size() > pass ? row.get(pass) : "";
            if (u.isEmpty() || p.isEmpty()) continue;
            Uri parsed = Uri.parse(u.contains("://") ? u : "https://" + u);
            if (parsed.getHost() == null) continue;
            String origin = parsed.getScheme() + "://" + parsed.getHost()
                    + (parsed.getPort() > 0 ? ":" + parsed.getPort() : "");
            saveLogin(new Autocomplete.LoginEntry.Builder()
                    .origin(origin)
                    .formActionOrigin(origin)
                    .username(user >= 0 && row.size() > user ? row.get(user) : "")
                    .password(p)
                    .build());
            n++;
        }
        return n;
    }

    int exportCsv(OutputStream out) throws IOException {
        Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        w.write("name,url,username,password,note\n");
        int n = 0;
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT host, origin, username, password FROM logins ORDER BY host", null)) {
            while (c.moveToNext()) {
                String pass = Crypto.decrypt(c.getString(3));
                if (pass == null) continue;
                w.write(Csv.row(c.getString(0), c.getString(1), c.getString(2), pass, ""));
                n++;
            }
        }
        w.flush();
        return n;
    }
}
