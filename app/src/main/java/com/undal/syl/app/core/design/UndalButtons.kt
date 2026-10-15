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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.undal.syl.app.core.theme.UndalDimens

/**
 * The three button ranks of the Undal app.
 *
 * Every button keeps a 52 dp height and a 48 dp minimum touch target so the
 * kitchen can hit Accept with a wet thumb, and every button draws its own
 * progress indicator so a slow network can never be mistaken for a dead tap.
 *
 * By default a button takes the whole width of its parent, which is what a form
 * and a dialog want. `fillWidth = false` is for the one place that is *not* a
 * form: two buttons side by side in a `Row` (Call and Chat, Sign out and Delete,
 * a text field and its Send). Two children that each ask for the full width
 * cannot both have it — the second one is measured off the edge of the screen,
 * which is how a "Delete my account" button came to be invisible and how the
 * chat's message box came to be zero pixels wide.
 */
@Composable
fun UndalPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
    fillWidth: Boolean = true
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .heightIn(min = UndalDimens.touchTarget)
            .semantics { role = Role.Button },
        enabled = enabled && !loading,
        shape = RoundedCornerShape(UndalDimens.radiusMd),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ),
        // The button that starts an order, accepts an order or rings a phone
        // should feel like a physical control: it lifts when it is available and
        // sits flat under the finger.
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 3.dp,
            pressedElevation = 0.dp,
            focusedElevation = 3.dp,
            hoveredElevation = 4.dp,
            disabledElevation = 0.dp
        )
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(UndalDimens.iconSm),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary
            )
            Spacer(Modifier.width(UndalDimens.sm))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(UndalDimens.iconSm))
            Spacer(Modifier.width(UndalDimens.sm))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun UndalSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
    fillWidth: Boolean = true
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .heightIn(min = UndalDimens.touchTarget),
        enabled = enabled && !loading,
        shape = RoundedCornerShape(UndalDimens.radiusMd)
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(UndalDimens.iconSm),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(UndalDimens.sm))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(UndalDimens.iconSm))
            Spacer(Modifier.width(UndalDimens.sm))
        }
        Text(text = text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

@Composable
fun UndalTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = UndalDimens.touchTarget),
        enabled = enabled
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(UndalDimens.iconSm))
            Spacer(Modifier.width(UndalDimens.xs))
        }
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun UndalDangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    fillWidth: Boolean = true
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .heightIn(min = UndalDimens.touchTarget),
        enabled = enabled && !loading,
        shape = RoundedCornerShape(UndalDimens.radiusMd),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError
        )
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(UndalDimens.iconSm),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onError
            )
            Spacer(Modifier.width(UndalDimens.sm))
        }
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

/** A vertical stack of actions used at the bottom of auth and location screens. */
@Composable
fun UndalActionColumn(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(UndalDimens.sm)
    ) {
        content()
    }
}

/** Row of two equal actions, used for Cancel/Confirm pairs. */
@Composable
fun UndalActionRow(
    modifier: Modifier = Modifier,
    start: @Composable () -> Unit,
    end: @Composable () -> Unit
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(UndalDimens.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) { start() }
        Column(modifier = Modifier.weight(1f)) { end() }
    }
    Spacer(Modifier.height(UndalDimens.sm))
}

/** Wrapper so screens can pad their action area consistently above the nav bar. */
@Composable
fun UndalActionBar(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier = modifier.padding(UndalDimens.screenPadding)) { content() }
}
