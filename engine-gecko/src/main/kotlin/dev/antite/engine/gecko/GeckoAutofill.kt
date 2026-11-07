package dev.antite.engine.gecko

import dev.antite.engine.Address
import dev.antite.engine.AutofillStore
import dev.antite.engine.Card
import dev.antite.engine.Login
import org.mozilla.geckoview.Autocomplete
import org.mozilla.geckoview.GeckoResult

internal object GeckoAutofill {
    fun login(e: Autocomplete.LoginEntry) = Login(
        e.guid, e.origin ?: "", e.formActionOrigin, e.httpRealm, e.username ?: "", e.password ?: "",
    )

    fun entry(l: Login): Autocomplete.LoginEntry = Autocomplete.LoginEntry.Builder()
        .guid(l.guid)
        .origin(l.origin)
        .formActionOrigin(l.formActionOrigin)
        .httpRealm(l.httpRealm)
        .username(l.username)
        .password(l.password)
        .build()

    fun card(c: Autocomplete.CreditCard) = Card(
        c.guid, c.name ?: "", c.number ?: "", c.expirationMonth ?: "", c.expirationYear ?: "",
    )

    fun creditCard(c: Card): Autocomplete.CreditCard = Autocomplete.CreditCard.Builder()
        .guid(c.guid)
        .name(c.name)
        .number(c.number)
        .expirationMonth(c.expirationMonth)
        .expirationYear(c.expirationYear)
        .build()

    fun address(a: Autocomplete.Address) = Address(
        guid = a.guid,
        name = a.name ?: "",
        givenName = a.givenName ?: "",
        additionalName = a.additionalName ?: "",
        familyName = a.familyName ?: "",
        organization = a.organization ?: "",
        streetAddress = a.streetAddress ?: "",
        addressLevel1 = a.addressLevel1 ?: "",
        addressLevel2 = a.addressLevel2 ?: "",
        addressLevel3 = a.addressLevel3 ?: "",
        postalCode = a.postalCode ?: "",
        country = a.country ?: "",
        tel = a.tel ?: "",
        email = a.email ?: "",
    )

    fun geckoAddress(a: Address): Autocomplete.Address = Autocomplete.Address.Builder()
        .guid(a.guid)
        .name(a.name)
        .givenName(a.givenName)
        .additionalName(a.additionalName)
        .familyName(a.familyName)
        .organization(a.organization)
        .streetAddress(a.streetAddress)
        .addressLevel1(a.addressLevel1)
        .addressLevel2(a.addressLevel2)
        .addressLevel3(a.addressLevel3)
        .postalCode(a.postalCode)
        .country(a.country)
        .tel(a.tel)
        .email(a.email)
        .build()

    fun storage(store: AutofillStore) = object : Autocomplete.StorageDelegate {
        override fun onLoginFetch(domain: String): GeckoResult<Array<Autocomplete.LoginEntry>> {
            val r = GeckoResult<Array<Autocomplete.LoginEntry>>()
            store.logins(domain) { list -> r.complete(list.map(::entry).toTypedArray()) }
            return r
        }

        override fun onCreditCardFetch(): GeckoResult<Array<Autocomplete.CreditCard>> {
            val r = GeckoResult<Array<Autocomplete.CreditCard>>()
            store.cards { list -> r.complete(list.map(::creditCard).toTypedArray()) }
            return r
        }

        override fun onAddressFetch(): GeckoResult<Array<Autocomplete.Address>> {
            val r = GeckoResult<Array<Autocomplete.Address>>()
            store.addresses { list -> r.complete(list.map(::geckoAddress).toTypedArray()) }
            return r
        }

        override fun onLoginSave(login: Autocomplete.LoginEntry) = store.saveLogin(login(login))

        override fun onCreditCardSave(card: Autocomplete.CreditCard) = store.saveCard(card(card))

        override fun onAddressSave(address: Autocomplete.Address) = store.saveAddress(address(address))

        override fun onLoginUsed(login: Autocomplete.LoginEntry, usedFields: Int) = store.loginUsed(login(login))
    }
}
