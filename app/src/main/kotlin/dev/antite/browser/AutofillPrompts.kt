package dev.antite.browser

import androidx.lifecycle.lifecycleScope
import dev.antite.engine.Prompt
import kotlinx.coroutines.launch

object AutofillPrompts {
    fun saveLogin(b: Browser, p: Prompt.SaveLogin) {
        if (!b.prefs.bool("passwordsSave", true)) return p.dismiss()
        val e = p.login
        b.activity.lifecycleScope.launch {
            val (never, existing) = b.vault.io { isNever(e.origin) to existing(e.origin, e.username) }
            if (never) return@launch p.dismiss()
            val update = existing != null
            val user = e.username.ifEmpty { "Без имени пользователя" }
            var answer: String? = null
            val buttons = buildList {
                add(DialogButton(if (update) "Обновить" else "Сохранить") { answer = "save" })
                if (!update) add(DialogButton("Никогда для этого сайта") { answer = "never" })
                add(DialogButton("Не сейчас"))
            }
            b.show(UiDialog.Message(
                title = if (update) "Обновить пароль?" else "Сохранить пароль?",
                message = Urls.host(e.origin) + "\n" + user + "\n" + "•".repeat(e.password.length.coerceIn(6, 12)),
                icon = R.drawable.ic_key,
                stacked = true,
                buttons = buttons,
                onDismiss = {
                    when (answer) {
                        "save" -> p.save()
                        "never" -> {
                            b.activity.lifecycleScope.launch { b.vault.io { addNever(e.origin) } }
                            p.dismiss()
                        }
                        else -> p.dismiss()
                    }
                },
            ))
        }
    }
}
