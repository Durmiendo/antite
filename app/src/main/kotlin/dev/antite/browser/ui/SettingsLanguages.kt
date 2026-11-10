package dev.antite.browser.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.antite.browser.Browser
import dev.antite.browser.DialogButton
import dev.antite.browser.UiDialog
import dev.antite.browser.Urls
import dev.antite.browser.data.Options
import dev.antite.browser.l10n.L10n
import dev.antite.browser.l10n.T
import dev.antite.engine.LanguageModel
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

@Composable
internal fun LanguagesPage(b: Browser, nav: Nav) {
    val tr = b.translation
    val cur = b.prefs.locales
    fun save(list: List<String>) {
        b.prefs.edit { putString("langs", list.joinToString(",")) }
        b.applyEngineSettings()
    }
    Screen(b, nav, T.settingsLanguages) {
        item(key = "uiLanguage") {
            val options = listOf("" to T.languagesSystem) + L10n.languages
            fun current() = options.indexOfFirst { it.first == b.prefs.string(L10n.PREF, "") }.coerceAtLeast(0)
            SettingRow(T.languagesInterface, options[current()].second, onClick = {
                nav.push(T.languagesInterface) { n ->
                    RadioPage(b, n, T.languagesInterface, options.map { it.second }, ::current) { i ->
                        b.prefs.edit { putString(L10n.PREF, options[i].first) }
                        L10n.init(b.activity)
                    }
                }
            })
        }
        section(T.languagesSites)
        note(T.languagesSitesNote)
        if (cur.isEmpty()) item { SettingRow(Urls.langName(Locale.getDefault().language), T.languagesSystem) }
        cur.forEachIndexed { i, code ->
            item(key = "lang:$code") {
                SettingRow(Urls.langName(code), if (i == 0) T.languagesPrimary else null, onClick = {
                    b.show(UiDialog.Items(Urls.langName(code), buildList {
                        if (i > 0) add(DialogButton(T.languagesMakePrimary) { save(listOf(code) + (cur - code)) })
                        add(DialogButton(T.actionDelete) { save(cur - code) })
                    }))
                })
            }
        }
        item {
            SettingRow(T.languagesAdd, onClick = {
                val have = cur.ifEmpty { listOf(Locale.getDefault().language) }
                nav.push(T.languagesAdd) { n ->
                    Screen(b, n, T.languagesAdd) {
                        Options.languages.filter { it !in have }.forEach { code ->
                            item { SettingRow(Urls.langName(code), onClick = { save(have + code); n.back() }) }
                        }
                    }
                }
            })
        }
        if (tr != null) {
            section(T.languagesTranslation)
            toggle(b, T.languagesOfferTranslation, T.languagesOfferTranslationSummary,
                "translateOffer", true, true)
            item {
                SettingRow(T.languagesTranslateTo, Urls.langName(b.prefs.translateTarget), onClick = {
                    tr.supported({ _, to ->
                        nav.push(T.languagesTranslateTo) { n ->
                            RadioPage(b, n, T.languagesTranslateTo, to.map { it.name }, { to.indexOfFirst { it.code == b.prefs.translateTarget } }) { i ->
                                b.prefs.edit { putString("translateTo", to[i].code) }
                            }
                        }
                    }, { b.toast(T.translateUnavailable) })
                })
            }
            nav(nav, T.languagesModels, T.languagesModelsSummary) { ModelsPage(b, it) }
            nav(nav, T.languagesNeverTranslate, null) { NeverTranslatePage(b, it) }
        }
    }
}

@Composable
internal fun ModelsPage(b: Browser, nav: Nav) {
    val tr = b.translation ?: return
    var revision by remember { mutableIntStateOf(0) }
    val models by produceState<List<LanguageModel>?>(null, revision) {
        value = suspendCancellableCoroutine { cont -> tr.models({ cont.resume(it) }, { cont.resume(emptyList()) }) }
    }
    Screen(b, nav, T.languagesModels) {
        note(T.modelsNote)
        val list = models ?: return@Screen
        if (list.isEmpty()) note(T.modelsEmpty)
        list.forEach { m ->
            item(key = m.language.code) {
                val size = android.text.format.Formatter.formatShortFileSize(b.activity, m.size)
                SettingRow(m.language.name, if (m.downloaded) T.modelDownloaded(size = size) else T.modelNotDownloaded(size = size), onClick = {
                    val done: (Boolean) -> Unit = { ok -> if (ok) revision++ else b.toast(T.modelActionFailed) }
                    if (m.downloaded) tr.deleteModel(m.language.code, done) else tr.downloadModel(m.language.code, done)
                })
            }
        }
    }
}

@Composable
internal fun NeverTranslatePage(b: Browser, nav: Nav) {
    val tr = b.translation ?: return
    var revision by remember { mutableIntStateOf(0) }
    val sites by produceState(emptyList<String>(), revision) {
        value = suspendCancellableCoroutine { cont -> tr.neverTranslateSites { cont.resume(it) } }
    }
    Screen(b, nav, T.languagesNeverTranslate) {
        if (sites.isEmpty()) note(T.neverTranslateEmpty)
        sites.forEach { s ->
            item(key = s) { SettingRow(s, T.neverTranslateRow, onClick = { tr.allowTranslateSite(s) { revision++ } }) }
        }
    }
}
