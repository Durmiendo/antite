package dev.antite.browser.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.antite.browser.DialogButton
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DialogsTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun itemRunsItsActionBeforeTheDialogCloses() {
        val calls = mutableListOf<String>()
        rule.setContent {
            AntiteTheme(true) {
                ItemsDialog("Войти как", listOf(DialogButton("alice") { calls += "select" })) { calls += "close" }
            }
        }
        rule.onNodeWithText("alice").performClick()
        assertEquals(listOf("select", "close"), calls)
    }
}
