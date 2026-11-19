package dev.antite.browser.ui

import androidx.compose.ui.focus.FocusRequester

// A requester whose field is not laid out yet throws; the field then simply stays unfocused.
internal fun focusQuietly(f: FocusRequester) {
    try {
        f.requestFocus()
    } catch (e: IllegalStateException) {
        return
    }
}
