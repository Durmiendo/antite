package dev.antite.engine

data class Login(
    val guid: String?,
    val origin: String,
    val formActionOrigin: String?,
    val httpRealm: String?,
    val username: String,
    val password: String,
)

data class Card(
    val guid: String?,
    val name: String,
    val number: String,
    val expirationMonth: String,
    val expirationYear: String,
)

data class Address(
    val guid: String?,
    val name: String = "",
    val givenName: String = "",
    val additionalName: String = "",
    val familyName: String = "",
    val organization: String = "",
    val streetAddress: String = "",
    val addressLevel1: String = "",
    val addressLevel2: String = "",
    val addressLevel3: String = "",
    val postalCode: String = "",
    val country: String = "",
    val tel: String = "",
    val email: String = "",
)

interface AutofillStore {
    fun logins(domain: String, done: (List<Login>) -> Unit)

    fun cards(done: (List<Card>) -> Unit)

    fun addresses(done: (List<Address>) -> Unit)

    fun saveLogin(login: Login)

    fun saveCard(card: Card)

    fun saveAddress(address: Address)

    fun loginUsed(login: Login)
}
