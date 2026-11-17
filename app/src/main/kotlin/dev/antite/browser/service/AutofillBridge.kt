package dev.antite.browser.service

import dev.antite.browser.data.Vault
import dev.antite.engine.Address
import dev.antite.engine.AutofillStore
import dev.antite.engine.Card
import dev.antite.engine.Login
import dev.antite.keys.KeyRing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// With a password key mode the secrets may be locked; unlock asks for the password on the main thread and the call
// is tried once more, or given up with nothing.
class AutofillBridge(
    private val vault: Vault,
    private val scope: CoroutineScope,
    private val unlock: (then: () -> Unit, cancelled: () -> Unit) -> Unit,
) : AutofillStore {
    private fun <T> run(empty: T, done: (T) -> Unit, block: Vault.() -> T) {
        scope.launch {
            try {
                done(vault.io(block))
            } catch (e: KeyRing.LockedException) {
                unlock({ scope.launch { done(runCatching { vault.io(block) }.getOrDefault(empty)) } }, { done(empty) })
            }
        }
    }

    override fun logins(domain: String, done: (List<Login>) -> Unit) = run(emptyList(), done) { loginsFor(domain) }

    override fun cards(done: (List<Card>) -> Unit) = run(emptyList(), done) { fullCards() }

    override fun addresses(done: (List<Address>) -> Unit) = run(emptyList(), done) { addresses() }

    override fun saveLogin(login: Login) = run(Unit, {}) { saveLogin(login) }

    override fun saveCard(card: Card) = run(Unit, {}) { saveCard(card) }

    override fun saveAddress(address: Address) = run(Unit, {}) { saveAddress(address) }

    override fun loginUsed(login: Login) {
        scope.launch { vault.io { loginUsed(login.guid) } }
    }
}
