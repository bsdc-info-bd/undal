package com.undal.syl.app.core.design

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.undal.syl.app.core.theme.UndalDimens

/**
 * Confirmation dialog used for every destructive or money-moving action.
 * The confirm label is always a verb ("Cancel order", "Delete item") so nobody
 * has to guess which button does what.
 */
@Composable
fun UndalConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String = "Keep it",
    destructive: Boolean = false
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = confirmLabel,
                    color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(dismissLabel, style = MaterialTheme.typography.labelLarge)
            }
        }
    )
}

/** Explanation dialog used before a runtime permission request (rationale sheet). */
@Composable
fun UndalRationaleDialog(
    title: String,
    body: String,
    bullets: List<String>,
    continueLabel: String,
    onContinue: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                Text(body, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(UndalDimens.sm))
                bullets.forEach { line ->
                    Text(
                        text = "•  $line",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onContinue) {
                Text(continueLabel, style = MaterialTheme.typography.labelLarge)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Not now", style = MaterialTheme.typography.labelLarge)
            }
        }
    )
}

/** Formatted dialog used to show long server-provided text (vendor notes, errors). */
@Composable
fun UndalMessageDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    confirmLabel: String = "Close"
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(confirmLabel, style = MaterialTheme.typography.labelLarge)
            }
        }
    )
}
