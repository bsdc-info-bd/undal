package com.undal.syl.app.core.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.undal.syl.app.core.theme.UndalDimens

/**
 * One text field for the whole app.
 *
 * Errors are always shown inline under the field (never in a toast), the field
 * is always at least 56 dp tall, and the keyboard action is wired to the form's
 * submit so the vendor can move through a form with one hand.
 */
@Composable
fun UndalTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    helper: String? = null,
    hint: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    leadingIcon: ImageVector? = null,
    onImeAction: (() -> Unit)? = null,
    maxLines: Int = 1
) {
    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
            label = { Text(label) },
            placeholder = hint?.let { { Text(it) } },
            enabled = enabled,
            singleLine = singleLine,
            maxLines = maxLines,
            isError = error != null,
            shape = RoundedCornerShape(UndalDimens.radiusMd),
            leadingIcon = leadingIcon?.let {
                { Icon(it, contentDescription = null, modifier = Modifier.size(UndalDimens.iconSm)) }
            },
            trailingIcon = if (singleLine && value.isNotEmpty() && enabled) {
                {
                    IconButton(onClick = { onValueChange("") }) {
                        Icon(
                            Icons.Filled.Clear,
                            contentDescription = "Clear $label",
                            modifier = Modifier.size(UndalDimens.iconSm)
                        )
                    }
                }
            } else null,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
            keyboardActions = KeyboardActions(
                onDone = { onImeAction?.invoke() },
                onGo = { onImeAction?.invoke() },
                onNext = { onImeAction?.invoke() }
            ),
            supportingText = when {
                error != null -> { { Text(error, color = MaterialTheme.colorScheme.error) } }
                helper != null -> { { Text(helper) } }
                else -> null
            },
            colors = OutlinedTextFieldDefaults.colors()
        )
    }
}

@Composable
fun UndalPasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String = "Password",
    modifier: Modifier = Modifier,
    error: String? = null,
    helper: String? = null,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: (() -> Unit)? = null
) {
    var visible by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
            label = { Text(label) },
            singleLine = true,
            isError = error != null,
            shape = RoundedCornerShape(UndalDimens.radiusMd),
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            // A written Show / Hide beats an eye icon nobody recognises, and it
            // stays readable for someone who has never used a password manager.
            trailingIcon = {
                TextButton(onClick = { visible = !visible }) {
                    Text(
                        text = if (visible) "Hide" else "Show",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction),
            keyboardActions = KeyboardActions(
                onDone = { onImeAction?.invoke() },
                onGo = { onImeAction?.invoke() },
                onNext = { onImeAction?.invoke() }
            ),
            supportingText = when {
                error != null -> { { Text(error, color = MaterialTheme.colorScheme.error) } }
                helper != null -> { { Text(helper) } }
                else -> null
            }
        )
    }
}

/**
 * Password strength meter (C-006). Four segments plus a plain-English label so
 * the customer knows exactly what to add — never a vague "weak password".
 */
@Composable
fun PasswordStrengthMeter(password: String, modifier: Modifier = Modifier) {
    val assessment = remember(password) { com.undal.syl.app.core.util.Validators.assessPassword(password) }
    Column(modifier = modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(4) { index ->
                Spacer(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .padding(0.dp)
                        .then(
                            if (index < assessment.score) {
                                Modifier.background(
                                    color = assessment.colour(),
                                    shape = RoundedCornerShape(2.dp)
                                )
                            } else {
                                Modifier.background(
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                    shape = RoundedCornerShape(2.dp)
                                )
                            }
                        )
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = assessment.label,
            style = MaterialTheme.typography.labelMedium,
            color = if (assessment.score >= 3) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun com.undal.syl.app.core.util.PasswordAssessment.colour() = when (score) {
    0, 1 -> MaterialTheme.colorScheme.error
    2 -> MaterialTheme.colorScheme.secondary
    3 -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.primary
}

/** Small label + value row used inside cards (delivery fee, distance, hours). */
@Composable
fun UndalKeyValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    emphasise: Boolean = false,
    icon: ImageVector? = null
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(UndalDimens.iconSm),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(UndalDimens.sm))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = if (emphasise) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
