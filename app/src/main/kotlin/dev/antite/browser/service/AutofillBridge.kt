package dev.antite.browser.service

import dev.antite.browser.data.Vault
import dev.antite.engine.Address
import dev.antite.engine.AutofillStore
import dev.antite.engine.Card
import dev.antite.engine.Login
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class AutofillBridge(private val vault: Vault, private val scope: CoroutineScope) : AutofillStore {
    override fun logins(domain: String, done: (List<Login>) -> Unit) {
        scope.launch { done(vault.io { loginsFor(domain) }) }
    }

    override fun cards(done: (List<Card>) -> Unit) {
        scope.launch { done(vault.io { fullCards() }) }
    }

    override fun addresses(done: (List<Address>) -> Unit) {
        scope.launch { done(vault.io { addresses() }) }
    }

    override fun saveLogin(login: Login) {
        scope.launch { vault.io { saveLogin(login) } }
    }

    override fun saveCard(card: Card) {
        scope.launch { vault.io { saveCard(card) } }
    }

    override fun saveAddress(address: Address) {
        scope.launch { vault.io { saveAddress(address) } }
    }

    override fun loginUsed(login: Login) {
        scope.launch { vault.io { loginUsed(login.guid) } }
    }
}
