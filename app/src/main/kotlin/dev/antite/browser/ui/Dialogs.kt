package dev.antite.browser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.antite.browser.AutofillPrompts
import dev.antite.browser.Browser
import dev.antite.browser.DialogButton
import dev.antite.browser.FormField
import dev.antite.browser.R
import dev.antite.browser.UiDialog
import dev.antite.engine.Prompt
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Composable
fun DialogHost(b: Browser) {
    val d = b.dialogs.firstOrNull() ?: return
    val close: () -> Unit = {
        b.dialogs.remove(d)
        d.onDismiss()
    }
    when (d) {
        is UiDialog.Message -> MessageDialog(d.title, d.message, d.icon, d.buttons, d.stacked, close)
        is UiDialog.Items -> ItemsDialog(d.title, d.items, close)
        is UiDialog.Checks -> ChecksDialog(d, close)
        is UiDialog.Form -> FormDialog(d.title, d.message, d.fields, d.confirm, { d.onConfirm(it) }, close)
        is UiDialog.Engine -> EnginePromptDialog(d.prompt) { b.dialogs.remove(d) }
    }
}

@Composable
fun DialogCard(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.padding(24.dp).widthIn(max = 560.dp).fillMaxWidth()
                .background(p.menu, RoundedCornerShape(28.dp)).padding(top = 24.dp, bottom = 12.dp),
        ) { content() }
    }
}

@Composable
private fun DialogTitle(text: String?, centered: Boolean = false) {
    if (text.isNullOrEmpty()) return
    Text(
        text, color = LocalPalette.current.text, fontSize = 22.sp,
        textAlign = if (centered) TextAlign.Center else TextAlign.Start,
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
    )
}

@Composable
private fun DialogText(text: String?) {
    if (text.isNullOrEmpty()) return
    Text(
        text, color = LocalPalette.current.textSecondary, fontSize = 14.sp, lineHeight = 20.sp,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun Buttons(buttons: List<Pair<String, () -> Unit>>, stacked: Boolean) {
    val p = LocalPalette.current
    val content: @Composable (Pair<String, () -> Unit>) -> Unit = { (label, act) ->
        TextButton(onClick = act) { Text(label, color = p.accent, fontWeight = FontWeight.Medium) }
    }
    if (stacked) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalAlignment = Alignment.End) {
            buttons.forEach { content(it) }
        }
    } else {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
            buttons.forEach { content(it) }
        }
    }
}

@Composable
fun MessageDialog(
    title: String?, message: String?, icon: Int, buttons: List<DialogButton>, stacked: Boolean, close: () -> Unit,
) {
    val p = LocalPalette.current
    DialogCard(close) {
        Column {
            if (icon != 0) {
                Box(Modifier.fillMaxWidth().padding(bottom = 16.dp), contentAlignment = Alignment.Center) {
                    Ico(icon, p.accent)
                }
            }
            DialogTitle(title, centered = icon != 0)
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) { DialogText(message) }
            Buttons(buttons.map { btn -> btn.label to { btn.action(); close() } }, stacked)
        }
    }
}

@Composable
fun ItemsDialog(title: String?, items: List<DialogButton>, close: () -> Unit) {
    val p = LocalPalette.current
    DialogCard(close) {
        Column {
            DialogTitle(title)
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                items.forEach { item ->
                    Text(
                        item.label, color = p.text, fontSize = 16.sp, maxLines = 3,
                        modifier = Modifier.fillMaxWidth().clickable { item.action(); close() }
                            .padding(horizontal = 24.dp, vertical = 14.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ChecksDialog(d: UiDialog.Checks, close: () -> Unit) {
    val p = LocalPalette.current
    val state = remember { mutableStateListOf(*d.checked.toTypedArray()) }
    DialogCard(close) {
        Column {
            DialogTitle(d.title)
            d.items.forEachIndexed { i, label ->
                Row(
                    Modifier.fillMaxWidth().clickable { state[i] = !state[i] }.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(state[i], null, Modifier.padding(12.dp),
                        colors = CheckboxDefaults.colors(checkedColor = p.accent, checkmarkColor = p.onAccent, uncheckedColor = p.icon))
                    Text(label, color = p.text, fontSize = 16.sp)
                }
            }
            Buttons(listOf("Отмена" to close, d.confirm to { d.onConfirm(state.toList()); close() }), false)
        }
    }
}

@Composable
fun FormDialog(
    title: String?,
    message: String?,
    fields: List<FormField>,
    confirm: String,
    onConfirm: (List<String>) -> Boolean,
    close: () -> Unit,
) {
    val values = remember { mutableStateListOf(*fields.map { it.value }.toTypedArray()) }
    val focus = remember { FocusRequester() }
    DialogCard(close) {
        Column {
            DialogTitle(title)
            DialogText(message)
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                fields.forEachIndexed { i, f ->
                    Field(f, values[i], { values[i] = it }, if (i == 0) Modifier.focusRequester(focus) else Modifier)
                }
            }
            Spacer(Modifier.size(8.dp))
            Buttons(listOf("Отмена" to close, confirm to { if (onConfirm(values.toList())) close() }), false)
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

@Composable
private fun Field(f: FormField, value: String, onChange: (String) -> Unit, modifier: Modifier) {
    val p = LocalPalette.current
    val (type, caps) = when (f.kind) {
        FormField.Kind.URL -> KeyboardType.Uri to KeyboardCapitalization.None
        FormField.Kind.NUMBER -> KeyboardType.Number to KeyboardCapitalization.None
        FormField.Kind.PHONE -> KeyboardType.Phone to KeyboardCapitalization.None
        FormField.Kind.EMAIL -> KeyboardType.Email to KeyboardCapitalization.None
        FormField.Kind.PASSWORD, FormField.Kind.VISIBLE_PASSWORD -> KeyboardType.Password to KeyboardCapitalization.None
        FormField.Kind.CAPS -> KeyboardType.Text to KeyboardCapitalization.Characters
        FormField.Kind.NAME -> KeyboardType.Text to KeyboardCapitalization.Words
        FormField.Kind.TEXT -> KeyboardType.Text to KeyboardCapitalization.Sentences
    }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(f.label) },
        singleLine = true,
        visualTransformation = if (f.kind == FormField.Kind.PASSWORD) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = type, capitalization = caps),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = p.accent, focusedLabelColor = p.accent, cursorColor = p.accent,
            focusedTextColor = p.text, unfocusedTextColor = p.text, unfocusedBorderColor = p.divider,
            unfocusedLabelColor = p.textSecondary,
        ),
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun EnginePromptDialog(prompt: Prompt, remove: () -> Unit) {
    val p = LocalPalette.current
    fun done(action: () -> Unit): () -> Unit = { remove(); action() }
    val dismiss = done { prompt.dismiss() }
    when (prompt) {
        is Prompt.Alert -> MessageDialog(prompt.title, prompt.message, 0, listOf(DialogButton("OK")), false, dismiss)
        is Prompt.Confirm -> MessageDialog(prompt.title, prompt.message, 0, listOf(
            DialogButton("Отмена") { prompt.confirm(false) },
            DialogButton("OK") { prompt.confirm(true) },
        ), false, dismiss)
        is Prompt.Text -> FormDialog(prompt.title, prompt.message, listOf(FormField("", prompt.defaultValue ?: "")), "OK",
            { v -> remove(); prompt.confirm(v[0]); false }, dismiss)
        is Prompt.Auth -> FormDialog(
            prompt.title ?: "Требуется вход", prompt.message,
            if (prompt.onlyPassword) listOf(FormField("Пароль", "", FormField.Kind.PASSWORD))
            else listOf(FormField("Имя пользователя", prompt.username ?: ""), FormField("Пароль", "", FormField.Kind.PASSWORD)),
            "Войти",
            { v -> remove(); if (prompt.onlyPassword) prompt.confirm("", v[0]) else prompt.confirm(v[0], v[1]); false },
            dismiss,
        )
        is Prompt.Choices -> if (prompt.multiple) {
            ChecksDialog(UiDialog.Checks(prompt.title, prompt.choices.map { it.label }, prompt.choices.map { it.selected },
                "OK", { checked -> remove(); prompt.confirm(prompt.choices.filterIndexed { i, _ -> checked[i] }.map { it.id }) },
                { prompt.dismiss() }), dismiss)
        } else {
            ItemsDialog(prompt.title, prompt.choices.filter { it.enabled }.map { c ->
                DialogButton((if (c.selected) "✓ " else "") + c.label) { remove(); prompt.confirm(listOf(c.id)) }
            }, dismiss)
        }
        is Prompt.DateTime -> DateTimeDialog(prompt, remove, dismiss)
        is Prompt.Color -> ColorDialog(prompt, remove, dismiss)
        is Prompt.BeforeUnload -> MessageDialog(null, "Покинуть страницу? Изменения могут не сохраниться.", 0, listOf(
            DialogButton("Остаться"), DialogButton("Покинуть") { prompt.leave() }), false, dismiss)
        is Prompt.Repost -> MessageDialog(null, "Отправить данные формы повторно?", 0, listOf(
            DialogButton("Отмена"), DialogButton("Отправить") { prompt.resend() }), false, dismiss)
        is Prompt.SelectCard -> ItemsDialog("Выберите карту", prompt.cards.mapIndexed { i, c ->
            DialogButton("•••• " + c.number.filter { it.isDigit() }.takeLast(4) + if (c.name.isEmpty()) "" else "  ${c.name}") {
                remove(); prompt.select(i)
            }
        }, dismiss)
        is Prompt.SelectAddress -> ItemsDialog("Выберите адрес", prompt.addresses.mapIndexed { i, a ->
            DialogButton(addressLine(a)) { remove(); prompt.select(i) }
        }, dismiss)
        is Prompt.SaveCard -> {
            val n = prompt.card.number.filter { it.isDigit() }
            MessageDialog("Сохранить карту?", "•••• ${n.takeLast(4)}" +
                if (prompt.card.expirationMonth.isEmpty()) "" else "\nДействует до ${prompt.card.expirationMonth}/${prompt.card.expirationYear}",
                R.drawable.ic_credit_card, listOf(DialogButton("Сохранить") { prompt.save() }, DialogButton("Не сейчас")),
                true, dismiss)
        }
        is Prompt.SaveAddress -> MessageDialog("Сохранить адрес?", addressLine(prompt.address), 0,
            listOf(DialogButton("Сохранить") { prompt.save() }, DialogButton("Не сейчас")), true, dismiss)
        is Prompt.SaveLogin, is Prompt.SelectLogin, is Prompt.File, is Prompt.Popup -> LaunchedEffect(prompt) { dismiss() }
    }
}

fun addressLine(a: dev.antite.engine.Address): String =
    listOf(a.name, a.streetAddress, a.addressLevel2, a.postalCode, a.tel, a.email)
        .filter { it.isNotEmpty() }.joinToString(", ") { it.replace('\n', ' ') }.ifEmpty { "Пустой адрес" }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateTimeDialog(prompt: Prompt.DateTime, remove: () -> Unit, dismiss: () -> Unit) {
    val p = LocalPalette.current
    when (prompt.kind) {
        Prompt.DateKind.TIME -> {
            val parts = prompt.value?.split(':')
            val state = rememberTimePickerState(parts?.getOrNull(0)?.toIntOrNull() ?: 12, parts?.getOrNull(1)?.toIntOrNull() ?: 0, true)
            DialogCard(dismiss) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    TimePicker(state)
                    Buttons(listOf("Отмена" to dismiss, "OK" to {
                        remove(); prompt.confirm("%02d:%02d".format(state.hour, state.minute))
                    }), false)
                }
            }
        }
        else -> {
            val initial = prompt.value?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val state = rememberDatePickerState(initial?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli())
            DatePickerDialog(onDismissRequest = dismiss, confirmButton = {
                TextButton(onClick = {
                    val millis = state.selectedDateMillis
                    remove()
                    if (millis == null) return@TextButton prompt.dismiss()
                    val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                    prompt.confirm(
                        when (prompt.kind) {
                            Prompt.DateKind.MONTH -> "%04d-%02d".format(date.year, date.monthValue)
                            Prompt.DateKind.DATETIME -> date.toString() + "T" + (prompt.value?.substringAfter('T', "12:00") ?: "12:00")
                            Prompt.DateKind.WEEK -> "%04d-W%02d".format(date.year,
                                date.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR))
                            else -> date.toString()
                        },
                    )
                }) { Text("OK", color = p.accent) }
            }, dismissButton = { TextButton(onClick = dismiss) { Text("Отмена", color = p.accent) } }) {
                DatePicker(state)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorDialog(prompt: Prompt.Color, remove: () -> Unit, dismiss: () -> Unit) {
    val colors = listOf(
        "#000000", "#5f6368", "#9aa0a6", "#ffffff", "#ea4335", "#fbbc04", "#34a853", "#4285f4",
        "#a142f4", "#f439a0", "#ff6d01", "#46bdc6", "#185abc", "#b31412", "#0d652d", "#e37400",
    )
    var hex by remember { mutableStateOf(prompt.value ?: "#000000") }
    DialogCard(dismiss) {
        Column {
            DialogTitle("Цвет")
            FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                colors.forEach { c ->
                    Box(Modifier.size(40.dp).background(Color(android.graphics.Color.parseColor(c)), CircleShape)
                        .border(if (c.equals(hex, true)) 3.dp else 1.dp, LocalPalette.current.accent, CircleShape)
                        .clickable { hex = c })
                }
            }
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Field(FormField("HEX"), hex, { hex = it }, Modifier)
            }
            Buttons(listOf("Отмена" to dismiss, "OK" to {
                remove()
                if (Regex("#[0-9a-fA-F]{6}").matches(hex)) prompt.confirm(hex.lowercase()) else prompt.dismiss()
            }), false)
        }
    }
}
