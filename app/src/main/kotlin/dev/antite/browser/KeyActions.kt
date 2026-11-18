package dev.antite.browser

import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.antite.browser.data.Secure
import dev.antite.browser.l10n.T
import dev.antite.keys.Cipher256
import dev.antite.keys.KeyRing
import java.nio.CharBuffer
import kotlin.concurrent.thread

// The key ring as the interface sees it: asking for the password, locking, changing the mode,
// the password and the cipher. Argon2 takes about a second, so everything that derives a key runs off the main thread.

val Browser.ring: KeyRing get() = Secure.ring(activity)

private val mainHandler = Handler(Looper.getMainLooper())

// UTF-8 bytes of the chars without a String in between; the chars and the encoder's buffer are wiped.
internal fun CharArray.toSecretBytes(): ByteArray {
    val buf = Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(this))
    val out = ByteArray(buf.remaining()).also { buf.get(it) }
    if (buf.hasArray()) buf.array().fill(0)
    fill('\u0000')
    return out
}

// Runs work off the main thread and reports its error text, or null, back on it.
private fun keyWork(work: () -> Unit, done: (String?) -> Unit) {
    thread(name = "keys") {
        val error = try {
            work()
            null
        } catch (e: KeyRing.WrongPasswordException) {
            T.keysPasswordWrong
        } catch (e: Exception) {
            T.keysFailed(reason = e.message ?: e.javaClass.simpleName)
        }
        mainHandler.post {
            Secure.revision.intValue++
            done(error)
        }
    }
}

fun Browser.askPassword(title: String, then: () -> Unit, cancelled: () -> Unit = {}) {
    var ok = false
    show(UiDialog.Password(title, onSubmit = { chars, done ->
        val bytes = chars.toSecretBytes()
        keyWork({ ring.unlock(bytes) }) { e ->
            if (e == null) ok = true
            done(e)
            if (e == null) {
                mediaVault.open()
                moveCipher()
                then()
            }
        }
    }, onDismiss = { if (!ok) cancelled() }))
}

// Passwords, addresses and cards: the device unlock as before, or the key ring's password when it has one.
fun Browser.unlockSecrets(reason: String, ok: () -> Unit, cancelled: () -> Unit = {}) {
    if (!ring.mode.usesPassword) return Auth.require(activity, reason, ok, cancelled)
    if (ring.unlocked) return ok()
    askPassword(T.keysUnlock, ok, cancelled)
}

// With a password the keys go when the vault locks: the vault is closed and every data key is wiped.
fun Browser.lockKeys() {
    if (!ring.mode.usesPassword) return
    mediaVault.close()
    history.close()
    categoryKeys.lock()
    ring.lock()
}

private fun Browser.newPassword(title: String, apply: (ByteArray) -> Unit) {
    show(UiDialog.Password(title, T.keysPasswordNewWarning, twice = true, onSubmit = { chars, done ->
        val bytes = chars.toSecretBytes()
        keyWork({ apply(bytes) }) { e ->
            done(e)
            if (e == null) toast(T.keysApplied)
        }
    }))
}

// Key settings: the device unlock when there is one to ask, the password with a password mode. Without a screen
// lock there is nothing to confirm with, and the keys would otherwise never leave the device mode.
private fun Browser.guardKeys(ok: () -> Unit) {
    val secure = activity.getSystemService(android.app.KeyguardManager::class.java)?.isDeviceSecure == true
    if (!ring.mode.usesPassword && !secure) ok() else unlockSecrets(T.keysTitle, ok)
}

fun Browser.setKeyMode(target: KeyRing.Mode, changed: () -> Unit) {
    guardKeys {
        if (target.usesPassword) newPassword(T.keysPasswordNew) { ring.setMode(target, it) }
        else keyWork({ ring.setMode(target, null) }) { e ->
            toast(e ?: T.keysApplied)
            changed()
        }
    }
}

fun Browser.changePassword() {
    guardKeys { newPassword(T.keysPasswordChange) { ring.setMode(ring.mode, it) } }
}

fun Browser.setCipher(c: Cipher256, changed: () -> Unit) {
    guardKeys {
        prefs.edit { putInt(Secure.PREF_CIPHER, c.id) }
        changed()
        toast(T.keysCipherWorking)
        recryptAll(c) { e, _ -> toast(e ?: T.keysApplied) }
    }
}

// Everything sealed with another cipher than c, sealed again: vault files and index, saved secrets, category keys,
// history logs and the key ring's password boxes. Each store survives a stop on its own. complete: nothing is left in
// another cipher.
private fun Browser.recryptAll(c: Cipher256, done: (error: String?, complete: Boolean) -> Unit) {
    mediaVault.recrypt(c) {
        var left = true
        keyWork({
            kotlinx.coroutines.runBlocking { vault.io { recrypt() } }
            categoryKeys.rewrap()
            history.recrypt()
            if (ring.needsRewrap()) ring.rewrap()
            left = !mediaVault.opened || mediaVault.waitsForCipher(c)
        }) { e -> done(e, e == null && !left) }
    }
}

private const val CIPHER_MOVED = "cipherMovedToXChaCha"

// Main thread only: a second activity must not start the move again while the first one runs.
private var cipherMoving = false

// Once, from AES as the main cipher to XChaCha20 (decision 0013): an AES setting moves, then everything written before
// is sealed again. Needs the keys, so with a password it waits for an unlock; until every store is done it is tried
// again at the next start or unlock.
fun Browser.moveCipher() {
    if (cipherMoving || prefs.bool(CIPHER_MOVED, false) || !ring.unlocked) return
    cipherMoving = true
    if (Secure.cipher(activity) == Cipher256.AES) prefs.edit { putInt(Secure.PREF_CIPHER, Cipher256.XCHACHA.id) }
    recryptAll(Secure.cipher(activity)) { e, complete ->
        cipherMoving = false
        if (complete) prefs.edit { putBoolean(CIPHER_MOVED, true) }
        else Log.w("Antite", "cipher move not finished: ${e ?: "vault files left"}")
    }
}
