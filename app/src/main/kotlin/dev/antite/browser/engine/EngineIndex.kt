package dev.antite.browser.engine

import org.json.JSONObject
import java.io.IOException
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

class IndexException(message: String) : IOException(message)

class Artifact(val urls: List<String>, val size: Long, val sha256: String)

class EngineEntry(
    val id: String,
    val type: String,
    val api: Int,
    val channel: String,
    val version: String,
    val minShell: Int,
    val adapter: Artifact,
    val upstream: Map<String, Artifact>,
) {
    fun dirName(abi: String) = "$id-$version-$abi"
}

class ShellEntry(val version: Int, val apk: Map<String, Artifact>)

class EngineIndex(val serial: Long, val engines: List<EngineEntry>, val shell: ShellEntry? = null) {
    companion object {
        const val FORMAT = 1

        fun publicKey(base64Der: String): PublicKey =
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(base64Der)))

        fun verify(json: ByteArray, signature: ByteArray, key: PublicKey, lastSerial: Long): EngineIndex {
            val ok = try {
                Signature.getInstance("SHA256withECDSA").run {
                    initVerify(key)
                    update(json)
                    verify(signature)
                }
            } catch (e: java.security.SignatureException) {
                false
            }
            if (!ok) throw IndexException("index signature is not valid")
            val index = parse(String(json, Charsets.UTF_8))
            if (index.serial < lastSerial) throw IndexException("index is older than the one already seen")
            return index
        }

        private fun artifact(o: JSONObject): Artifact {
            val urls = o.getJSONArray("url")
            return Artifact(List(urls.length()) { urls.getString(it) }, o.getLong("size"), o.getString("sha256").lowercase())
        }

        private fun parse(text: String): EngineIndex {
            val root = JSONObject(text)
            if (root.getInt("format") != FORMAT) throw IndexException("unsupported index format")
            val list = root.getJSONArray("engines")
            val engines = List(list.length()) { i ->
                val e = list.getJSONObject(i)
                val up = e.getJSONObject("upstream")
                EngineEntry(
                    id = e.getString("id"),
                    type = e.getString("type"),
                    api = e.getInt("api"),
                    channel = e.optString("channel", "release"),
                    version = e.getString("version"),
                    minShell = e.optInt("minShell", 1),
                    adapter = artifact(e.getJSONObject("adapter")),
                    upstream = up.keys().asSequence().associateWith { artifact(up.getJSONObject(it)) },
                )
            }
            val shell = root.optJSONObject("shell")?.let { s ->
                val apk = s.getJSONObject("apk")
                ShellEntry(s.getInt("version"), apk.keys().asSequence().associateWith { artifact(apk.getJSONObject(it)) })
            }
            return EngineIndex(root.getLong("serial"), engines, shell)
        }
    }

    fun choose(abi: String, api: Int, shellVersion: Int, channel: String = "release"): EngineEntry? =
        engines.firstOrNull {
            it.type == "gecko" && it.api == api && it.minShell <= shellVersion && it.channel == channel && abi in it.upstream
        }
}
