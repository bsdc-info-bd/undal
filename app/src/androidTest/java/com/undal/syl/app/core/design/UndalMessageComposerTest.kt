package com.undal.syl.app.core.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.undal.syl.app.core.theme.UndalTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class UndalMessageComposerTest {
    @get:Rule val compose = createComposeRule()

    private fun show(width: Int, fontScale: Float, draft: String = "An update for the customer") {
        compose.setContent {
            // A wide logical window still fits the test device physically; layout decisions use dp.
            CompositionLocalProvider(LocalDensity provides Density(0.5f, fontScale)) {
                UndalTheme {
                    Box(Modifier.requiredWidth(width.dp)) {
                        UndalMessageComposer(draft, {}, {}, sending = false)
                    }
                }
            }
        }
    }

    private fun verify(width: Int, scale: Float, inline: Boolean) {
        show(width, scale)
        val field = compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot
        val send = compose.onNodeWithText("Send").fetchSemanticsNode().boundsInRoot
        if (inline) assertTrue("Wide composer should reserve space beside the field", send.left >= field.right)
        else assertTrue("Compact or large-text composer should stack the send button", send.top >= field.bottom)
    }

    @Test fun compactComposerStacks() = verify(320, 1f, inline = false)
    @Test fun wideComposerKeepsFieldAndSendBesideEachOther() = verify(600, 1f, inline = true)
    @Test fun largeTextOverridesWideComposer() = verify(600, 2f, inline = false)
    @Test fun emptyDraftCannotBeSent() {
        show(320, 1f, draft = "   ")
        compose.onNodeWithText("Send").assertIsNotEnabled()
    }
}
