package dev.antite.browser.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import dev.antite.browser.Urls
import dev.antite.engine.Address
import dev.antite.engine.Card
import dev.antite.engine.Login
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.UUID

data class SavedLogin(val guid: String, val origin: String, val username: String, val used: Long) {
    val host: String get() = Urls.host(origin)
}

data class SavedCard(val guid: String, val name: String?, val last4: String, val month: String?, val year: String?) {
    val label: String get() = "•••• $last4" + if (month.isNullOrEmpty()) "" else "  $month/$year"
}

class Vault private constructor(context: Context) : SQLiteOpenHelper(context, "vault.db", null, 1) {
    companion object {
        @Volatile
        private var instance: Vault? = null

        fun get(context: Context): Vault =
            instance ?: synchronized(this) { instance ?: Vault(context.applicationContext).also { instance = it } }

        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

        fun hostOf(origin: String?): String {
            val h = origin?.let { Uri.parse(it).host }
            return (h ?: origin ?: "").lowercase(Locale.ROOT)
        }

        private fun newGuid() = "{${UUID.randomUUID()}}"

        private val ADDRESS_KEYS = listOf(
            "name", "givenName", "additionalName", "familyName", "organization", "streetAddress",
            "addressLevel1", "addressLevel2", "addressLevel3", "postalCode", "country", "tel", "email",
        )
    }

    init {
        setWriteAheadLoggingEnabled(true)
    }

    suspend fun <T> io(block: Vault.() -> T): T = withContext(dispatcher) { block() }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE logins (guid TEXT PRIMARY KEY, origin TEXT NOT NULL, host TEXT NOT NULL, " +
                "form_action TEXT, http_realm TEXT, username TEXT NOT NULL, password TEXT NOT NULL, " +
                "created INTEGER NOT NULL, used INTEGER NOT NULL, times INTEGER NOT NULL DEFAULT 0)",
        )
        db.execSQL("CREATE INDEX logins_host ON logins(host)")
        db.execSQL("CREATE TABLE never (host TEXT PRIMARY KEY)")
        db.execSQL("CREATE TABLE addresses (guid TEXT PRIMARY KEY, data TEXT NOT NULL, used INTEGER NOT NULL)")
        db.execSQL(
            "CREATE TABLE cards (guid TEXT PRIMARY KEY, name TEXT, number TEXT NOT NULL, last4 TEXT NOT NULL, " +
                "month TEXT, year TEXT, used INTEGER NOT NULL)",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, from: Int, to: Int) {}

    fun loginsFor(domain: String): List<Login> {
        val d = domain.lowercase(Locale.ROOT)
        return readableDatabase.rawQuery(
            "SELECT guid, origin, form_action, http_realm, username, password FROM logins " +
                "WHERE host=? OR host LIKE ? OR ? LIKE '%.' || host",
            arrayOf(d, "%.$d", d),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val pass = Crypto.decrypt(c.getString(5)) ?: continue
                    add(Login(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), pass))
                }
            }
        }
    }

    fun logins(filter: String?): List<SavedLogin> {
        var sql = "SELECT guid, origin, username, used FROM logins"
        var args: Array<String>? = null
        if (!filter.isNullOrEmpty()) {
            sql += " WHERE host LIKE ? OR username LIKE ?"
            args = arrayOf("%$filter%", "%$filter%")
        }
        return readableDatabase.rawQuery("$sql ORDER BY host, username", args).use { c ->
            buildList { while (c.moveToNext()) add(SavedLogin(c.getString(0), c.getString(1), c.getString(2), c.getLong(3))) }
        }
    }

    fun password(guid: String): String? =
        readableDatabase.rawQuery("SELECT password FROM logins WHERE guid=?", arrayOf(guid))
            .use { if (it.moveToFirst()) Crypto.decrypt(it.getString(0)) else null }

    fun existing(origin: String, username: String?): SavedLogin? =
        readableDatabase.rawQuery(
            "SELECT guid, origin, username, used FROM logins WHERE host=? AND username=?",
            arrayOf(hostOf(origin), username ?: ""),
        ).use { if (it.moveToFirst()) SavedLogin(it.getString(0), it.getString(1), it.getString(2), it.getLong(3)) else null }

    fun saveLogin(e: Login) {
        if (e.origin.isEmpty() || e.password.isEmpty()) return
        val now = System.currentTimeMillis()
        val v = ContentValues().apply {
            put("origin", e.origin)
            put("host", hostOf(e.origin))
            put("form_action", e.formActionOrigin)
            put("http_realm", e.httpRealm)
            put("username", e.username)
            put("password", Crypto.encrypt(e.password))
            put("used", now)
        }
        val db = writableDatabase
        val id = e.guid ?: existing(e.origin, e.username)?.guid
        if (id != null && db.update("logins", v, "guid=?", arrayOf(id)) > 0) return
        v.put("guid", id ?: newGuid())
        v.put("created", now)
        db.insert("logins", null, v)
    }

    fun editLogin(guid: String, username: String, password: String) {
        writableDatabase.update("logins", ContentValues().apply {
            put("username", username)
            put("password", Crypto.encrypt(password))
        }, "guid=?", arrayOf(guid))
    }

    fun loginUsed(guid: String?) {
        if (guid == null) return
        writableDatabase.execSQL("UPDATE logins SET used=?, times=times+1 WHERE guid=?",
            arrayOf<Any>(System.currentTimeMillis(), guid))
    }

    fun deleteLogin(guid: String) {
        writableDatabase.delete("logins", "guid=?", arrayOf(guid))
    }

    fun isNever(origin: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM never WHERE host=?", arrayOf(hostOf(origin))).use { it.moveToFirst() }

    fun addNever(origin: String) {
        writableDatabase.insertWithOnConflict("never", null,
            ContentValues().apply { put("host", hostOf(origin)) }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun removeNever(host: String) {
        writableDatabase.delete("never", "host=?", arrayOf(host))
    }

    fun nevers(): List<String> = readableDatabase.rawQuery("SELECT host FROM never ORDER BY host", null)
        .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    fun addresses(): List<Address> =
        readableDatabase.rawQuery("SELECT guid, data FROM addresses ORDER BY used DESC", null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val json = Crypto.decrypt(c.getString(1)) ?: continue
                    runCatching { add(addressFromJson(c.getString(0), JSONObject(json))) }
                }
            }
        }

    fun saveAddress(a: Address) {
        val v = ContentValues().apply {
            put("data", Crypto.encrypt(addressToJson(a).toString()))
            put("used", System.currentTimeMillis())
        }
        val db = writableDatabase
        if (a.guid != null && db.update("addresses", v, "guid=?", arrayOf(a.guid)) > 0) return
        v.put("guid", a.guid ?: newGuid())
        db.insert("addresses", null, v)
    }

    fun deleteAddress(guid: String) {
        writableDatabase.delete("addresses", "guid=?", arrayOf(guid))
    }

    fun cards(): List<SavedCard> =
        readableDatabase.rawQuery("SELECT guid, name, last4, month, year FROM cards ORDER BY used DESC", null).use { c ->
            buildList {
                while (c.moveToNext()) add(SavedCard(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4)))
            }
        }

    fun fullCards(): List<Card> =
        readableDatabase.rawQuery("SELECT guid, name, number, month, year FROM cards ORDER BY used DESC", null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val number = Crypto.decrypt(c.getString(2)) ?: continue
                    add(Card(c.getString(0), c.getString(1) ?: "", number, c.getString(3) ?: "", c.getString(4) ?: ""))
                }
            }
        }

    fun saveCard(card: Card) {
        val digits = card.number.filter { it.isDigit() }
        if (digits.length < 12) return
        val v = ContentValues().apply {
            put("name", card.name)
            put("number", Crypto.encrypt(digits))
            put("last4", digits.takeLast(4))
            put("month", card.expirationMonth)
            put("year", card.expirationYear)
            put("used", System.currentTimeMillis())
        }
        val db = writableDatabase
        if (card.guid != null && db.update("cards", v, "guid=?", arrayOf(card.guid)) > 0) return
        v.put("guid", card.guid ?: newGuid())
        db.insert("cards", null, v)
    }

    fun deleteCard(guid: String) {
        writableDatabase.delete("cards", "guid=?", arrayOf(guid))
    }

    private fun addressValues(a: Address) = listOf(
        a.name, a.givenName, a.additionalName, a.familyName, a.organization, a.streetAddress,
        a.addressLevel1, a.addressLevel2, a.addressLevel3, a.postalCode, a.country, a.tel, a.email,
    )

    private fun addressToJson(a: Address) =
        JSONObject().apply { ADDRESS_KEYS.zip(addressValues(a)).forEach { (k, v) -> put(k, v) } }

    private fun addressFromJson(guid: String, o: JSONObject) = Address(
        guid = guid,
        name = o.optString("name"),
        givenName = o.optString("givenName"),
        additionalName = o.optString("additionalName"),
        familyName = o.optString("familyName"),
        organization = o.optString("organization"),
        streetAddress = o.optString("streetAddress"),
        addressLevel1 = o.optString("addressLevel1"),
        addressLevel2 = o.optString("addressLevel2"),
        addressLevel3 = o.optString("addressLevel3"),
        postalCode = o.optString("postalCode"),
        country = o.optString("country"),
        tel = o.optString("tel"),
        email = o.optString("email"),
    )

    fun importCsv(input: InputStream): Int {
        val r = input.bufferedReader(Charsets.UTF_8)
        val header = Csv.readRow(r) ?: return 0
        fun col(vararg names: String) = header.indexOfFirst {
            it.trim().lowercase(Locale.ROOT).replace("\uFEFF", "") in names
        }
        val url = col("url", "origin", "login_uri")
        val user = col("username", "login_username")
        val pass = col("password", "login_password")
        if (url < 0 || pass < 0) throw IOException("no url/password columns")
        var n = 0
        while (true) {
            val row = Csv.readRow(r) ?: break
            val u = row.getOrNull(url)?.trim().orEmpty()
            val p = row.getOrNull(pass).orEmpty()
            if (u.isEmpty() || p.isEmpty()) continue
            val parsed = Uri.parse(if ("://" in u) u else "https://$u")
            val host = parsed.host ?: continue
            val origin = "${parsed.scheme}://$host" + if (parsed.port > 0) ":${parsed.port}" else ""
            saveLogin(Login(null, origin, origin, null, if (user >= 0) row.getOrNull(user).orEmpty() else "", p))
            n++
        }
        return n
    }

    fun exportCsv(out: OutputStream): Int {
        val w = out.bufferedWriter(Charsets.UTF_8)
        w.write("name,url,username,password,note\n")
        var n = 0
        readableDatabase.rawQuery("SELECT host, origin, username, password FROM logins ORDER BY host", null).use { c ->
            while (c.moveToNext()) {
                val pass = Crypto.decrypt(c.getString(3)) ?: continue
                w.write(Csv.row(c.getString(0), c.getString(1), c.getString(2), pass, ""))
                n++
            }
        }
        w.flush()
        return n
    }
}
