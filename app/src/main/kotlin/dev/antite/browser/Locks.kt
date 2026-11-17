package dev.antite.browser

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

// What is open behind a lock right now: incognito, each locked category, the secure storage; and when they close.
class Locks(private val settings: Settings, private val now: () -> Long = System::currentTimeMillis) {
    interface Settings {
        val incognitoLock: Boolean
        fun categoryLock(id: String): Boolean
        val vaultLockOnExit: Boolean
        // Minutes away after which the storage closes on return; null when it does not close by time.
        val vaultLockMinutes: Int?
    }

    var incognitoUnlocked by mutableStateOf(false)
    val unlockedCategories = mutableStateListOf<String>()
    var vaultUnlocked by mutableStateOf(false)
    private var leftAt = 0L

    val incognitoLocked: Boolean get() = settings.incognitoLock && !incognitoUnlocked

    fun categoryLocked(id: String?): Boolean = id != null && id !in unlockedCategories && settings.categoryLock(id)

    fun tabLocked(incognito: Boolean, category: String?): Boolean = if (incognito) incognitoLocked else categoryLocked(category)

    // The browser left the screen: incognito and the categories close at once, the storage only with "on exit".
    // Returns whether the storage closed.
    fun left(): Boolean {
        incognitoUnlocked = false
        unlockedCategories.clear()
        leftAt = now()
        if (!settings.vaultLockOnExit) return false
        vaultUnlocked = false
        return true
    }

    // Back on the screen: the storage closes if it was away longer than its minutes. Checked once per return, so a
    // pause without leaving the browser (the system unlock dialog, a permission prompt) does not close it again.
    fun returned(): Boolean {
        if (leftAt == 0L) return false
        val away = now() - leftAt
        leftAt = 0L
        val minutes = settings.vaultLockMinutes ?: return false
        if (away <= minutes * 60_000L) return false
        vaultUnlocked = false
        return true
    }
}
