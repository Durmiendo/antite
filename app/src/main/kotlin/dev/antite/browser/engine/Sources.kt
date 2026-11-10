package dev.antite.browser.engine

import android.content.Context
import dev.antite.browser.BuildConfig
import dev.antite.browser.l10n.T
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64

class Source(
    val id: String,
    val name: String,
    val url: String,
    val key: String,
    val enabled: Boolean,
    val builtin: Boolean = false,
) {
    val fingerprint: String get() = Sources.fingerprint(key)
}

class SourceException(message: String) : Exception(message)

object Sources {
    const val BUILTIN = "builtin"
    private const val UPDATE = "updateSource"

    fun builtin(enabled: Boolean = true) =
        Source(BUILTIN, "Antite", BuildConfig.ENGINE_INDEX_URL, BuildConfig.PUBLISHER_KEY, enabled, builtin = true)

    fun fingerprint(key: String): String = MessageDigest.getInstance("SHA-256")
        .digest(Base64.getDecoder().decode(key))
        .joinToString(":") { "%02X".format(it) }

    // Accepts a PEM block or bare base64 of an EC P-256 public key, returns bare base64 of its DER.
    fun parseKey(text: String): String {
        val b64 = text.replace(Regex("-----[A-Z ]+-----"), "").filterNot(Char::isWhitespace)
        try {
            val key = EngineIndex.publicKey(b64)
            if (key.algorithm != "EC") throw SourceException(T.sourceErrorNotEc)
        } catch (e: SourceException) {
            throw e
        } catch (e: Exception) {
            throw SourceException(T.sourceErrorNotP256)
        }
        return b64
    }

    private fun state(context: Context) = EngineUpdates.state(context)

    fun list(context: Context): List<Source> {
        val raw = state(context).getString("sources", null) ?: return listOf(builtin())
        val arr = JSONArray(raw)
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            if (o.getString("id") == BUILTIN) builtin(o.optBoolean("enabled", true))
            else Source(o.getString("id"), o.getString("name"), o.getString("url"), o.getString("key"), o.optBoolean("enabled", true))
        }
    }

    fun enabled(context: Context) = list(context).filter { it.enabled }

    fun find(context: Context, id: String?) = list(context).firstOrNull { it.id == id }

    // The only source asked for updates; any other source could otherwise win them with a bigger version.
    fun updateSource(context: Context): Source {
        val enabled = enabled(context)
        val id = state(context).getString(UPDATE, BUILTIN)
        return enabled.firstOrNull { it.id == id } ?: enabled.first()
    }

    fun setUpdateSource(context: Context, id: String) {
        if (enabled(context).none { it.id == id }) throw SourceException(T.sourceErrorDisabled)
        state(context).edit().putString(UPDATE, id).remove("available").remove("shellAvailable").commit()
    }

    @Synchronized
    fun save(context: Context, sources: List<Source>) {
        if (sources.none { it.enabled }) throw SourceException(T.sourceErrorLast)
        val arr = JSONArray()
        for (s in sources) {
            val o = JSONObject().put("id", s.id).put("enabled", s.enabled)
            if (!s.builtin) o.put("name", s.name).put("url", s.url).put("key", s.key)
            arr.put(o)
        }
        state(context).edit().putString("sources", arr.toString()).commit()
    }

    fun add(context: Context, name: String, url: String, key: String, onlyThis: Boolean = false): Source {
        if (!url.startsWith("https://") && !url.startsWith("http://localhost:") && !url.startsWith("http://127.0.0.1:")) {
            throw SourceException(T.sourceErrorHttps)
        }
        val current = list(context)
        val s = Source("s" + System.currentTimeMillis(), name.ifBlank { android.net.Uri.parse(url).host ?: url }, url, parseKey(key), true)
        save(context, current.map { if (onlyThis) it.copy(enabled = false) else it } + s)
        if (onlyThis) setUpdateSource(context, s.id)
        return s
    }

    fun setEnabled(context: Context, id: String, enabled: Boolean) {
        if (!enabled) requireNotUpdateSource(context, id)
        save(context, list(context).map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    fun remove(context: Context, id: String) {
        requireNotUpdateSource(context, id)
        save(context, list(context).filter { it.id != id || it.builtin })
        state(context).edit().remove("serial.$id").commit()
    }

    private fun requireNotUpdateSource(context: Context, id: String) {
        if (updateSource(context).id == id) throw SourceException(T.sourceErrorIsUpdateSource)
    }

    private fun Source.copy(enabled: Boolean) = Source(id, name, url, key, enabled, builtin)
}
