package dev.antite.browser.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import dev.antite.browser.Auth
import dev.antite.browser.Browser
import dev.antite.browser.DialogButton
import dev.antite.browser.FormField
import dev.antite.browser.UiDialog
import dev.antite.browser.data.SavedLogin
import dev.antite.browser.l10n.T
import dev.antite.engine.Address
import dev.antite.engine.Card
import kotlinx.coroutines.launch

@Composable
internal fun PasswordsPage(b: Browser, nav: Nav) {
    var revision by remember { mutableIntStateOf(0) }
    val data by produceState<Pair<List<SavedLogin>, List<String>>?>(null, revision) {
        value = b.vault.io { logins(null) to nevers() }
    }
    Screen(b, nav, T.settingsPasswords) {
        toggle(b, T.passwordsOfferSave, null, "passwordsSave", true, true)
        toggle(b, T.passwordsAutofill, T.passwordsAutofillSummary, "passwordsAutofill", true, true)
        val (logins, nevers) = data ?: return@Screen
        section(T.passwordsSection)
        if (logins.isEmpty()) note(T.passwordsEmpty)
        logins.forEach { l ->
            item(key = l.guid) {
                SettingRow(l.host, l.username.ifEmpty { T.passwordNoUsername }, onClick = {
                    Auth.require(b.activity, T.passwordsShow, { nav.push(l.host) { LoginPage(b, it, l) } }, {})
                })
            }
        }
        if (nevers.isNotEmpty()) {
            section(T.passwordsNeverSave)
            nevers.forEach { h ->
                item(key = "never:$h") {
                    SettingRow(h, T.passwordsNeverSaveRow, onClick = {
                        b.activity.lifecycleScope.launch { b.vault.io { removeNever(h) }; revision++ }
                    })
                }
            }
        }
        section(T.passwordsTransfer)
        item { SettingRow(T.passwordsImport, T.passwordsImportSummary, onClick = { importCsv(b) { revision++ } }) }
        item {
            SettingRow(T.passwordsExport, T.passwordsExportSummary, onClick = {
                Auth.require(b.activity, T.passwordsExportAuth, { exportCsv(b) }, {})
            })
        }
    }
}

internal fun importCsv(b: Browser, done: () -> Unit) = b.activity.openDocument(listOf("text/*", "text/csv", "text/comma-separated-values")) { uri ->
    if (uri == null) return@openDocument
    b.activity.lifecycleScope.launch {
        val n = b.vault.io {
            runCatching { b.activity.contentResolver.openInputStream(uri)!!.use { importCsv(it) } }.getOrDefault(-1)
        }
        b.toast(if (n < 0) T.passwordsReadFailed else T.passwordsImported(count = n))
        done()
    }
}

internal fun exportCsv(b: Browser) = b.activity.createDocument("Antite Passwords.csv", "text/csv") { uri ->
    if (uri == null) return@createDocument
    b.activity.lifecycleScope.launch {
        val n = b.vault.io {
            runCatching { b.activity.contentResolver.openOutputStream(uri)!!.use { exportCsv(it) } }.getOrDefault(-1)
        }
        b.toast(if (n < 0) T.passwordsWriteFailed else T.passwordsExported(count = n))
    }
}

@Composable
internal fun LoginPage(b: Browser, nav: Nav, l: SavedLogin) {
    var revision by remember { mutableIntStateOf(0) }
    val password by produceState<String?>(null, revision) { value = b.vault.io { password(l.guid) } }
    var shown by remember { mutableStateOf(false) }
    Screen(b, nav, l.host) {
        val pass = password ?: ""
        item { SettingRow(T.passwordSite, l.origin) }
        item { SettingRow(T.promptUsername, l.username.ifEmpty { T.passwordNone }, onClick = { b.activity.copy(l.username) }) }
        item { SettingRow(T.promptPassword, if (shown) pass else "••••••••", onClick = { shown = !shown }) }
        note(T.passwordTapToShow)
        item { SettingRow(T.passwordCopy, onClick = { b.activity.copy(pass, sensitive = true) }) }
        item {
            SettingRow(T.actionEdit, onClick = {
                b.show(UiDialog.Form(T.passwordEdit, null, listOf(
                    FormField(T.promptUsername, l.username),
                    FormField(T.promptPassword, pass, FormField.Kind.VISIBLE_PASSWORD),
                ), onConfirm = { v ->
                    if (v[1].isEmpty()) return@Form false
                    b.activity.lifecycleScope.launch { b.vault.io { editLogin(l.guid, v[0], v[1]) }; revision++ }
                    true
                }))
            })
        }
        item {
            SettingRow(T.actionDelete, onClick = {
                b.confirm(T.passwordDeleteConfirm(host = l.host), T.actionDelete) {
                    b.activity.lifecycleScope.launch { b.vault.io { deleteLogin(l.guid) } }
                    nav.back()
                }
            })
        }
    }
}

@Composable
internal fun CardsPage(b: Browser, nav: Nav) {
    var revision by remember { mutableIntStateOf(0) }
    val cards by produceState(emptyList<dev.antite.browser.data.SavedCard>(), revision) { value = b.vault.io { cards() } }
    fun form(card: Card?) = b.show(UiDialog.Form(if (card == null) T.cardNew else T.cardEdit, null, listOf(
        FormField(T.cardNumber, card?.number ?: "", FormField.Kind.NUMBER),
        FormField(T.cardName, card?.name ?: "", FormField.Kind.CAPS),
        FormField(T.cardMonth, card?.expirationMonth ?: "", FormField.Kind.NUMBER),
        FormField(T.cardYear, card?.expirationYear ?: "", FormField.Kind.NUMBER),
    ), onConfirm = { v ->
        val digits = v[0].filter { it.isDigit() }
        if (digits.length < 12) {
            b.toast(T.cardBadNumber)
            return@Form false
        }
        val c = Card(card?.guid, v[1].trim(), digits, v[2].trim(), v[3].trim())
        b.activity.lifecycleScope.launch { b.vault.io { saveCard(c) }; revision++ }
        true
    }))
    Screen(b, nav, T.settingsPayment) {
        toggle(b, T.cardsEnabled, T.cardsEnabledSummary,
            "cardsEnabled", true, true)
        section(T.cardsSection)
        if (cards.isEmpty()) note(T.cardsEmpty)
        cards.forEach { c ->
            item(key = c.guid) {
                SettingRow(c.label, c.name, onClick = {
                    b.show(UiDialog.Items(c.label, listOf(
                        DialogButton(T.actionEdit) {
                            Auth.require(b.activity, T.cardEdit, {
                                b.activity.lifecycleScope.launch {
                                    b.vault.io { fullCards() }.firstOrNull { it.guid == c.guid }?.let(::form)
                                }
                            }, {})
                        },
                        DialogButton(T.actionDelete) {
                            b.confirm(T.cardDeleteConfirm(card = c.label), T.actionDelete) {
                                b.activity.lifecycleScope.launch { b.vault.io { deleteCard(c.guid) }; revision++ }
                            }
                        },
                    )))
                })
            }
        }
        item { SettingRow(T.cardAdd, onClick = { form(null) }) }
    }
}

@Composable
internal fun AddressesPage(b: Browser, nav: Nav) {
    var revision by remember { mutableIntStateOf(0) }
    val list by produceState(emptyList<Address>(), revision) { value = b.vault.io { addresses() } }
    fun form(a: Address?) = b.show(UiDialog.Form(if (a == null) T.addressNew else T.addressEdit, null, listOf(
        FormField(T.addressName, a?.name ?: "", FormField.Kind.NAME),
        FormField(T.addressOrganization, a?.organization ?: "", FormField.Kind.NAME),
        FormField(T.addressStreet, a?.streetAddress ?: "", FormField.Kind.NAME),
        FormField(T.addressCity, a?.addressLevel2 ?: "", FormField.Kind.NAME),
        FormField(T.addressRegion, a?.addressLevel1 ?: "", FormField.Kind.NAME),
        FormField(T.addressPostalCode, a?.postalCode ?: "", FormField.Kind.NUMBER),
        FormField(T.addressCountry, a?.country ?: "", FormField.Kind.CAPS),
        FormField(T.addressPhone, a?.tel ?: "", FormField.Kind.PHONE),
        FormField(T.addressEmail, a?.email ?: "", FormField.Kind.EMAIL),
    ), onConfirm = { v ->
        val out = (a ?: Address(null)).copy(
            name = v[0].trim(), organization = v[1].trim(), streetAddress = v[2].trim(), addressLevel2 = v[3].trim(),
            addressLevel1 = v[4].trim(), postalCode = v[5].trim(), country = v[6].trim(), tel = v[7].trim(), email = v[8].trim(),
        )
        b.activity.lifecycleScope.launch { b.vault.io { saveAddress(out) }; revision++ }
        true
    }))
    Screen(b, nav, T.settingsAddresses) {
        toggle(b, T.addressesEnabled, T.addressesEnabledSummary, "addressesEnabled", true, true)
        section(T.addressesSection)
        if (list.isEmpty()) note(T.addressesEmpty)
        list.forEach { a ->
            item(key = a.guid) {
                SettingRow(addressLine(a), onClick = {
                    b.show(UiDialog.Items(addressLine(a), listOf(
                        DialogButton(T.actionEdit) { form(a) },
                        DialogButton(T.actionDelete) {
                            b.confirm(T.addressDeleteConfirm, T.actionDelete) {
                                b.activity.lifecycleScope.launch { b.vault.io { deleteAddress(a.guid!!) }; revision++ }
                            }
                        },
                    )))
                })
            }
        }
        item { SettingRow(T.addressAdd, onClick = { form(null) }) }
    }
}
