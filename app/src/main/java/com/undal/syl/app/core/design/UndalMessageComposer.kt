package com.undal.syl.app.core.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalDensity
import com.undal.syl.app.core.theme.UndalDimens

/** A short send action never squeezes the message field to zero width. Insets belong to the scaffold. */
@Composable
fun UndalMessageComposer(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    sending: Boolean,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val fontScale = LocalDensity.current.fontScale
    val field: @Composable (Modifier) -> Unit = { fieldModifier ->
        UndalTextField(value = draft, onValueChange = onDraftChange,
            label = "Message", hint = "Write an update for the customer", maxLines = 3, singleLine = false, imeAction = ImeAction.Default,
            enabled = enabled && !sending, modifier = fieldModifier)
    }
    val action: @Composable (Boolean) -> Unit = { fillWidth ->
        UndalPrimaryButton(text = if (sending) "Sending…" else "Send", onClick = onSend,
            enabled = enabled && draft.isNotBlank() && !sending, loading = sending,
            icon = Icons.Filled.Send, fillWidth = fillWidth)
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (UndalLayoutPolicy.useInlineComposer(maxWidth.value, fontScale)) {
            Row(horizontalArrangement = Arrangement.spacedBy(UndalDimens.sm),
                verticalAlignment = Alignment.CenterVertically) {
                field(Modifier.weight(1f))
                action(false)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(UndalDimens.sm)) {
                field(Modifier.fillMaxWidth())
                action(true)
            }
        }
    }
}
