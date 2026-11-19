package dev.antite.browser

import android.app.KeyguardManager
import android.util.Log
import dev.antite.browser.data.TabCategory
import dev.antite.browser.l10n.T
import dev.antite.keys.KeyMode

// Locked categories (decision 0011): what locks a category's key, opening it, choosing its lock. The device part is
// asked right before the key is used: the device key store lets that key out only for a short time after the unlock.

fun Browser.lockOf(c: TabCategory): KeyMode? = categoryKeys.lockOf(c.id.value)

fun Browser.deviceSecure(): Boolean = activity.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

// A ring left by a lock put on just before a stop marks the category locked, so it never shows open by mistake.
fun Browser.reconcileCategoryLocks() {
    val withRing = try {
        categoryKeys.locked().toSet()
    } catch (e: java.io.IOException) {
        Log.w("Antite", "category locks unreadable", e)
        emptySet()
    }
    var changed = false
    for (c in categories.list) if (c.id.value in withRing && !c.locked) {
        c.locked = true
        changed = true
    }
    if (changed) categories.save()
}

// After leaving the browser the keys of locked categories go, and the history read with them.
fun Browser.closeLockedCategories() {
    history.close()
    categoryKeys.closeLocked()
}

private fun Browser.withDevice(c: TabCategory, work: () -> Unit, done: () -> Unit) =
    Auth.require(activity, T.categoryUnlockReason(name = c.name), {
        keyWork(work) { e -> if (e == null) done() else toast(e) }
    }, {}, strict = true)

// The category opened with what its lock asks for. One locked before its key had a lock of its own gets the device
// lock at its first opening.
fun Browser.unlockCategory(c: TabCategory, then: () -> Unit = {}) {
    if (!categoryLocked(c.id)) return then()
    val name = c.id.value
    fun opened() {
        if (c.id !in unlockedCategories) unlockedCategories += c.id
        then()
    }
    when (lockOf(c)) {
        null -> withDevice(c, { categoryKeys.setLock(name, KeyMode.DEVICE, null) }, ::opened)
        KeyMode.DEVICE -> withDevice(c, { categoryKeys.open(name, null) }, ::opened)
        KeyMode.PASSWORD -> show(UiDialog.Password(T.categoryUnlockReason(name = c.name), onSubmit = { chars, done ->
            val bytes = chars.toSecretBytes()
            keyWork({ categoryKeys.open(name, bytes) }) { e ->
                done(e)
                if (e == null) opened()
            }
        }))
        KeyMode.BOTH -> show(UiDialog.Password(T.categoryUnlockReason(name = c.name), onSubmit = { chars, done ->
            val bytes = chars.toSecretBytes()
            done(null)
            withDevice(c, { categoryKeys.open(name, bytes) }, ::opened)
        }))
    }
}

// A new lock for the category, or none. The category is opened first; a password is asked twice; the device part
// is asked last, right before the key is wrapped again.
fun Browser.setCategoryLock(c: TabCategory, mode: KeyMode?) {
    if (mode?.usesDevice == true && !deviceSecure()) return toast(T.authNeedScreenLock)
    if (mode == lockOf(c) && (mode != null || !c.locked)) return
    val name = c.id.value
    fun applied() {
        c.locked = mode != null
        if (mode != null && c.id !in unlockedCategories) unlockedCategories += c.id
        categories.save()
        toast(T.keysApplied)
    }
    fun apply(password: ByteArray?) {
        val work = { categoryKeys.setLock(name, mode, password) }
        if (mode?.usesDevice == true) withDevice(c, work, ::applied) else keyWork(work) { e -> if (e == null) applied() else toast(e) }
    }
    unlockCategory(c) {
        if (mode?.usesPassword != true) return@unlockCategory apply(null)
        show(UiDialog.Password(T.categoryPasswordNew, T.categoryPasswordNewWarning, twice = true, onSubmit = { chars, done ->
            done(null)
            apply(chars.toSecretBytes())
        }))
    }
}

class LockChoice(val title: String, val summary: String, val selected: Boolean, val choose: () -> Unit)

// The four locks with what each means; one locked before its key had a lock of its own shows as the device lock.
fun Browser.lockChoices(c: TabCategory): List<LockChoice> {
    val current = if (c.locked) lockOf(c) ?: KeyMode.DEVICE else null
    val secure = deviceSecure()
    return listOf(
        Triple(null, T.categoryLockNone, T.categoryLockNoneSummary),
        Triple(KeyMode.DEVICE, T.categoryLockDevice, if (secure) T.categoryLockDeviceSummary else T.authNeedScreenLock),
        Triple(KeyMode.PASSWORD, T.categoryLockPassword, T.categoryLockPasswordSummary),
        Triple(KeyMode.BOTH, T.categoryLockBoth, if (secure) T.categoryLockBothSummary else T.authNeedScreenLock),
    ).map { (mode, title, summary) -> LockChoice(title, summary, mode == current) { setCategoryLock(c, mode) } }
}
