package dev.antite.browser.ui

import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.antite.browser.UiDialog
import dev.antite.browser.l10n.T

// A password field that never turns its text into a String (docs/decisions/0008): the text lives in the EditText's
// char buffer and comes out as a CharArray, after which the buffer is overwritten. The keyboard is asked not to
// learn from it, not to suggest and not to autofill; what the keyboard itself keeps is beyond the app.
class SecretInput {
    internal var view: EditText? = null

    fun take(): CharArray {
        val e = view?.editableText ?: return CharArray(0)
        val out = CharArray(e.length)
        for (i in out.indices) out[i] = e[i]
        wipe()
        return out
    }

    fun wipe() {
        val e = view?.editableText ?: return
        // Same length in place first, so the chars are overwritten rather than only unlinked.
        e.replace(0, e.length, "\u0000".repeat(e.length))
        e.clear()
    }
}

@Composable
fun SecretField(input: SecretInput, hint: String, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    AndroidView(
        factory = { ctx ->
            EditText(ctx).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                isSaveEnabled = false
                isLongClickable = false
                setTextIsSelectable(false)
                this.hint = hint
                contentDescription = hint
                setTextColor(p.text.toArgb())
                setHintTextColor(p.textSecondary.toArgb())
                isSingleLine = true
                input.view = this
            }
        },
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp).semantics { contentDescription = hint },
    )
    DisposableEffect(input) {
        onDispose {
            input.wipe()
            input.view = null
        }
    }
}

@Composable
internal fun PasswordDialog(d: UiDialog.Password, close: () -> Unit) {
    val p = LocalPalette.current
    val first = remember { SecretInput() }
    val second = remember { SecretInput() }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // A tap on the field's own view counted as one outside the dialog and closed it.
    DialogCard({ if (!busy) close() }, dismissOutside = false) {
        Column {
            DialogTitleText(d.title)
            if (d.message != null) DialogMessage(d.message)
            Column(Modifier.padding(horizontal = 24.dp)) {
                SecretField(first, T.keysPassword)
                if (d.twice) SecretField(second, T.keysPasswordAgain)
                error?.let { Text(it, color = p.insecure, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp)) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp), color = p.accent)
            }
            Spacer(Modifier.size(8.dp))
            DialogButtons(listOf(T.actionCancel to { if (!busy) close() }, d.confirm to confirm@{
                if (busy) return@confirm
                val a = first.take()
                if (d.twice) {
                    val b = second.take()
                    val same = a.contentEquals(b)
                    b.fill('\u0000')
                    if (!same) {
                        a.fill('\u0000')
                        error = T.keysPasswordMismatch
                        return@confirm
                    }
                }
                if (a.isEmpty()) {
                    error = T.keysPasswordEmpty
                    return@confirm
                }
                busy = true
                error = null
                d.onSubmit(a) { e ->
                    busy = false
                    if (e == null) close() else error = e
                }
            }))
        }
    }
}
