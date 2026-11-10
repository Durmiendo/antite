package dev.antite.browser

import androidx.annotation.DrawableRes
import dev.antite.browser.l10n.T
import dev.antite.engine.Prompt

class DialogButton(val label: String, val action: () -> Unit = {})

class FormField(
    val label: String,
    val value: String = "",
    val kind: Kind = Kind.TEXT,
) {
    enum class Kind { TEXT, URL, NUMBER, PHONE, EMAIL, PASSWORD, VISIBLE_PASSWORD, CAPS, NAME }
}

sealed interface UiDialog {
    val onDismiss: () -> Unit

    class Message(
        val title: String? = null,
        val message: String? = null,
        @DrawableRes val icon: Int = 0,
        val buttons: List<DialogButton> = emptyList(),
        val stacked: Boolean = false,
        override val onDismiss: () -> Unit = {},
    ) : UiDialog

    class Items(
        val title: String?,
        val items: List<DialogButton>,
        override val onDismiss: () -> Unit = {},
    ) : UiDialog

    class Checks(
        val title: String?,
        val items: List<String>,
        val checked: List<Boolean>,
        val confirm: String,
        val onConfirm: (List<Boolean>) -> Unit,
        override val onDismiss: () -> Unit = {},
    ) : UiDialog

    class Form(
        val title: String,
        val message: String? = null,
        val fields: List<FormField>,
        val confirm: String = T.actionSave,
        val onConfirm: (List<String>) -> Boolean,
        override val onDismiss: () -> Unit = {},
    ) : UiDialog

    class Engine(val prompt: Prompt) : UiDialog {
        override val onDismiss: () -> Unit = { prompt.dismiss() }
    }
}
