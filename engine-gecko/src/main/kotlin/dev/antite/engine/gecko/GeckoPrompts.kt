package dev.antite.engine.gecko

import android.content.Context
import dev.antite.engine.Prompt
import dev.antite.engine.TabCallbacks
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.Autocomplete
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PromptDelegate
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse

internal class GeckoPrompts(private val context: Context, private val callbacks: TabCallbacks) : PromptDelegate {
    private fun ask(build: ((PromptResponse) -> Unit) -> Prompt): GeckoResult<PromptResponse> {
        val r = GeckoResult<PromptResponse>()
        var done = false
        callbacks.onPrompt(build { resp ->
            if (!done) {
                done = true
                r.complete(resp)
            }
        })
        return r
    }

    override fun onAlertPrompt(s: GeckoSession, p: PromptDelegate.AlertPrompt) =
        ask { reply -> Prompt.Alert(p.title, p.message) { reply(p.dismiss()) } }

    override fun onButtonPrompt(s: GeckoSession, p: PromptDelegate.ButtonPrompt) = ask { reply ->
        Prompt.Confirm(p.title, p.message) { positive ->
            reply(
                when (positive) {
                    null -> p.dismiss()
                    true -> p.confirm(PromptDelegate.ButtonPrompt.Type.POSITIVE)
                    false -> p.confirm(PromptDelegate.ButtonPrompt.Type.NEGATIVE)
                },
            )
        }
    }

    override fun onTextPrompt(s: GeckoSession, p: PromptDelegate.TextPrompt) = ask { reply ->
        Prompt.Text(p.title, p.message, p.defaultValue) { text -> reply(if (text == null) p.dismiss() else p.confirm(text)) }
    }

    override fun onAuthPrompt(s: GeckoSession, p: PromptDelegate.AuthPrompt) = ask { reply ->
        val o = p.authOptions
        val onlyPassword = (o.flags and PromptDelegate.AuthPrompt.AuthOptions.Flags.ONLY_PASSWORD) != 0
        Prompt.Auth(p.title, p.message, o.username, onlyPassword) { creds ->
            reply(
                when {
                    creds == null -> p.dismiss()
                    onlyPassword -> p.confirm(creds.second)
                    else -> p.confirm(creds.first, creds.second)
                },
            )
        }
    }

    override fun onChoicePrompt(s: GeckoSession, p: PromptDelegate.ChoicePrompt) = ask { reply ->
        val flat = mutableListOf<PromptDelegate.ChoicePrompt.Choice>()
        fun walk(items: Array<PromptDelegate.ChoicePrompt.Choice>) {
            for (c in items) {
                if (c.separator) continue
                val sub = c.items
                if (sub != null) walk(sub) else flat += c
            }
        }
        walk(p.choices)
        val multiple = p.type == PromptDelegate.ChoicePrompt.Type.MULTIPLE
        Prompt.Choices(
            p.title,
            p.message,
            multiple,
            flat.map { Prompt.Choice(it.id, it.label, it.selected, !it.disabled) },
        ) { ids ->
            reply(
                when {
                    ids == null -> p.dismiss()
                    multiple -> p.confirm(ids.toTypedArray())
                    else -> flat.firstOrNull { it.id == ids.firstOrNull() }?.let { p.confirm(it) } ?: p.dismiss()
                },
            )
        }
    }

    override fun onDateTimePrompt(s: GeckoSession, p: PromptDelegate.DateTimePrompt) = ask { reply ->
        val kind = when (p.type) {
            PromptDelegate.DateTimePrompt.Type.TIME -> Prompt.DateKind.TIME
            PromptDelegate.DateTimePrompt.Type.DATETIME_LOCAL -> Prompt.DateKind.DATETIME
            PromptDelegate.DateTimePrompt.Type.MONTH -> Prompt.DateKind.MONTH
            PromptDelegate.DateTimePrompt.Type.WEEK -> Prompt.DateKind.WEEK
            else -> Prompt.DateKind.DATE
        }
        Prompt.DateTime(kind, p.defaultValue, p.minValue, p.maxValue) { v ->
            reply(if (v == null) p.dismiss() else p.confirm(v))
        }
    }

    override fun onColorPrompt(s: GeckoSession, p: PromptDelegate.ColorPrompt) = ask { reply ->
        Prompt.Color(p.defaultValue) { v -> reply(if (v == null) p.dismiss() else p.confirm(v)) }
    }

    override fun onFilePrompt(s: GeckoSession, p: PromptDelegate.FilePrompt) = ask { reply ->
        Prompt.File(p.mimeTypes?.toList().orEmpty(), p.type == PromptDelegate.FilePrompt.Type.MULTIPLE) { uris ->
            reply(if (uris.isNullOrEmpty()) p.dismiss() else p.confirm(context, uris.toTypedArray()))
        }
    }

    override fun onPopupPrompt(s: GeckoSession, p: PromptDelegate.PopupPrompt) = ask { reply ->
        Prompt.Popup(p.targetUri) { ok -> reply(p.confirm(if (ok) AllowOrDeny.ALLOW else AllowOrDeny.DENY)) }
    }

    override fun onBeforeUnloadPrompt(s: GeckoSession, p: PromptDelegate.BeforeUnloadPrompt) = ask { reply ->
        Prompt.BeforeUnload { ok -> reply(p.confirm(if (ok) AllowOrDeny.ALLOW else AllowOrDeny.DENY)) }
    }

    override fun onRepostConfirmPrompt(s: GeckoSession, p: PromptDelegate.RepostConfirmPrompt) = ask { reply ->
        Prompt.Repost { ok -> reply(p.confirm(if (ok) AllowOrDeny.ALLOW else AllowOrDeny.DENY)) }
    }

    override fun onLoginSave(
        s: GeckoSession,
        r: PromptDelegate.AutocompleteRequest<Autocomplete.LoginSaveOption>,
    ): GeckoResult<PromptResponse> {
        val opt = r.options.firstOrNull() ?: return GeckoResult.fromValue(r.dismiss())
        return ask { reply ->
            Prompt.SaveLogin(GeckoAutofill.login(opt.value)) { ok -> reply(if (ok) r.confirm(opt) else r.dismiss()) }
        }
    }

    override fun onLoginSelect(
        s: GeckoSession,
        r: PromptDelegate.AutocompleteRequest<Autocomplete.LoginSelectOption>,
    ) = ask { reply ->
        Prompt.SelectLogin(r.options.map {
            Prompt.LoginOption(GeckoAutofill.login(it.value), (it.hint and Autocomplete.SelectOption.Hint.GENERATED) != 0)
        }) { i -> reply(if (i == null) r.dismiss() else r.confirm(r.options[i])) }
    }

    override fun onCreditCardSave(
        s: GeckoSession,
        r: PromptDelegate.AutocompleteRequest<Autocomplete.CreditCardSaveOption>,
    ): GeckoResult<PromptResponse> {
        val opt = r.options.firstOrNull() ?: return GeckoResult.fromValue(r.dismiss())
        return ask { reply ->
            Prompt.SaveCard(GeckoAutofill.card(opt.value)) { ok -> reply(if (ok) r.confirm(opt) else r.dismiss()) }
        }
    }

    override fun onCreditCardSelect(
        s: GeckoSession,
        r: PromptDelegate.AutocompleteRequest<Autocomplete.CreditCardSelectOption>,
    ) = ask { reply ->
        Prompt.SelectCard(r.options.map { GeckoAutofill.card(it.value) }) { i ->
            reply(if (i == null) r.dismiss() else r.confirm(r.options[i]))
        }
    }

    override fun onAddressSave(
        s: GeckoSession,
        r: PromptDelegate.AutocompleteRequest<Autocomplete.AddressSaveOption>,
    ): GeckoResult<PromptResponse> {
        val opt = r.options.firstOrNull() ?: return GeckoResult.fromValue(r.dismiss())
        return ask { reply ->
            Prompt.SaveAddress(GeckoAutofill.address(opt.value)) { ok -> reply(if (ok) r.confirm(opt) else r.dismiss()) }
        }
    }

    override fun onAddressSelect(
        s: GeckoSession,
        r: PromptDelegate.AutocompleteRequest<Autocomplete.AddressSelectOption>,
    ) = ask { reply ->
        Prompt.SelectAddress(r.options.map { GeckoAutofill.address(it.value) }) { i ->
            reply(if (i == null) r.dismiss() else r.confirm(r.options[i]))
        }
    }
}
