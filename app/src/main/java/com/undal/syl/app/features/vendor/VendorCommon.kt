package com.undal.syl.app.features.vendor

import java.util.Locale
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.undal.syl.app.core.design.UndalCard
import com.undal.syl.app.core.design.UndalEmptyState
import com.undal.syl.app.core.design.UndalStatusPill
import com.undal.syl.app.core.model.VendorDayPoint
import com.undal.syl.app.core.model.VendorOrder
import com.undal.syl.app.core.theme.UndalDimens
import com.undal.syl.app.core.theme.UndalPalette
import com.undal.syl.app.core.theme.undalSemantics
import com.undal.syl.app.core.util.Money
import com.undal.syl.app.core.util.OrderStateMachine
import com.undal.syl.app.core.util.OrderStatus

/**
 * The pieces every kitchen screen is built from (PART G).
 *
 * One vocabulary for status colours, one age ramp, one queue card — so a dish
 * that is "Ready" looks the same on the dashboard, in the queue and inside the
 * order, and the owner never has to re-learn the screen at 11 pm.
 */

/** Container + content colour for a status, shared by every vendor surface. */
@Composable
fun vendorStatusColours(status: OrderStatus): Pair<Color, Color> {
    val semantics = MaterialTheme.undalSemantics
    return when (status) {
        OrderStatus.PLACED -> UndalPalette.AccentLight to UndalPalette.AccentDark
        OrderStatus.ACCEPTED -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        OrderStatus.READY -> semantics.successContainer to semantics.onSuccessContainer
        OrderStatus.IN_DELIVERY -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        OrderStatus.DELIVERED -> semantics.successContainer to semantics.onSuccessContainer
        OrderStatus.CANCELLED -> semantics.closedContainer to semantics.onClosedContainer
    }
}

@Composable
fun VendorStatusPill(status: OrderStatus) {
    val (container, content) = vendorStatusColours(status)
    UndalStatusPill(
        text = OrderStateMachine.label(status),
        containerColour = container,
        contentColour = content
    )
}

/** The ticking age badge with its colour ramp (V-015, V-042). */
@Composable
fun VendorAgePill(order: VendorOrder) {
    val level = order.ageLevel()
    val (container, content) = when (level) {
        0 -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        1 -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        2 -> MaterialTheme.undalSemantics.warningContainer to MaterialTheme.undalSemantics.onWarningContainer
        else -> MaterialTheme.undalSemantics.closedContainer to MaterialTheme.undalSemantics.onClosedContainer
    }
    Surface(shape = RoundedCornerShape(UndalDimens.radiusPill), color = container) {
        Text(
            text = order.ageLabel(),
            style = MaterialTheme.typography.labelMedium,
            color = content,
            modifier = Modifier.padding(horizontal = UndalDimens.sm, vertical = 2.dp)
        )
    }
}

/** A number the owner glances at, with its caption (V-011 … V-013). */
@Composable
fun VendorStatCard(
    label: String,
    value: String,
    caption: String,
    modifier: Modifier = Modifier,
    highlight: Boolean = false
) {
    Surface(
        shape = RoundedCornerShape(UndalDimens.radiusMd),
        color = if (highlight) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(UndalDimens.sm)) {
            Text(
                text = label.uppercase(Locale.ENGLISH),
                style = MaterialTheme.typography.labelSmall,
                color = if (highlight) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Spacer(Modifier.height(UndalDimens.xs))
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                color = if (highlight) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
            if (caption.isNotBlank()) {
                Text(
                    text = caption,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (highlight) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

/** One queue row (V-015, V-025 lite). */
@Composable
fun VendorOrderCard(
    order: VendorOrder,
    onClick: () -> Unit,
    unread: Int = 0,
    trailing: @Composable (() -> Unit)? = null
) {
    UndalCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Column(modifier = Modifier.padding(UndalDimens.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = order.shortId.ifBlank { order.id.takeLast(4) },
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.width(UndalDimens.xs))
                VendorAgePill(order)
                Spacer(Modifier.weight(1f))
                VendorStatusPill(order.status)
            }
            Spacer(Modifier.height(UndalDimens.xs))
            Text(
                text = order.linesSummary.ifBlank { "${order.itemCount} item(s)" },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(UndalDimens.xs))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.LocationOn,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(UndalDimens.iconSm)
                )
                Spacer(Modifier.width(UndalDimens.xs))
                Text(
                    text = order.addressLine.ifBlank { order.customerName },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = Money.format(order.totalAmountPaisa),
                    style = MaterialTheme.typography.titleSmall
                )
            }
            if (order.isOverdue()) {
                Spacer(Modifier.height(UndalDimens.xs))
                Surface(
                    shape = RoundedCornerShape(UndalDimens.radiusSm),
                    color = MaterialTheme.undalSemantics.closedContainer
                ) {
                    Text(
                        text = "The promised prep time has passed — the customer is waiting.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.undalSemantics.onClosedContainer,
                        modifier = Modifier.padding(horizontal = UndalDimens.sm, vertical = 4.dp)
                    )
                }
            }
            if (unread > 0 || trailing != null) {
                Spacer(Modifier.height(UndalDimens.xs))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (unread > 0) {
                        UndalStatusPill(
                            text = "$unread new message(s)",
                            containerColour = MaterialTheme.colorScheme.secondaryContainer,
                            contentColour = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    trailing?.invoke()
                }
            }
        }
    }
}

/** A titled card, the shape most vendor screens use. */
@Composable
fun VendorSection(
    title: String,
    subtitle: String = "",
    content: @Composable () -> Unit
) {
    UndalCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(UndalDimens.sm)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(UndalDimens.xs))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(UndalDimens.xs))
            content()
        }
    }
}

/** Every quiet, empty or error state looks like this (V-019, V-038). */
@Composable
fun VendorEmptyState(
    title: String,
    message: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    UndalEmptyState(
        icon = icon,
        title = title,
        message = message,
        actionLabel = actionLabel,
        onAction = onAction
    )
}

/**
 * The week's orders as a bar chart drawn from real numbers (V-014).
 * A Canvas rather than a library: seven bars do not justify another dependency.
 */
@Composable
fun VendorWeekChart(points: List<VendorDayPoint>, modifier: Modifier = Modifier) {
    val peak = points.maxOfOrNull { it.revenuePaisa } ?: 0L
    val barColour = MaterialTheme.colorScheme.primary
    val emptyColour = MaterialTheme.colorScheme.surfaceVariant
    val labelStyle = MaterialTheme.typography.labelSmall

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(modifier = Modifier.fillMaxWidth().height(120.dp)) {
            if (points.isEmpty()) return@Canvas
            val gap = size.width * 0.02f
            val barWidth = (size.width - gap * (points.size + 1)) / points.size
            val baseLine = size.height - 4f
            points.forEachIndexed { index, point ->
                val ratio = if (peak <= 0L) 0f else point.revenuePaisa.toFloat() / peak.toFloat()
                val barHeight = (baseLine * ratio).coerceAtLeast(if (point.orders > 0) 6f else 2f)
                val left = gap + index * (barWidth + gap)
                drawRoundRect(
                    color = if (point.orders > 0) barColour else emptyColour,
                    topLeft = Offset(left, baseLine - barHeight),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(barWidth / 3f, barWidth / 3f)
                )
            }
        }
        Spacer(Modifier.height(UndalDimens.xs))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            points.forEach { point ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = point.label, style = labelStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = if (point.orders == 0) "—" else point.orders.toString(),
                        style = labelStyle,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
        if (peak > 0L) {
            Spacer(Modifier.height(UndalDimens.xs))
            Text(
                text = "Best day this week: ${Money.format(peak)} across " +
                    "${points.maxByOrNull { it.revenuePaisa }?.orders ?: 0} order(s).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** A small tappable chip used by the prep picker and the filter rows. */
@Composable
fun VendorChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(UndalDimens.radiusPill),
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = UndalDimens.sm, vertical = UndalDimens.xs)
        )
    }
}

/** Shared by the dashboard and the settings screen: the store link (V-110). */
fun storeShareText(storeName: String, workerHost: String): String =
    "$storeName takes orders at https://$workerHost/store — cash on delivery, delivered by the kitchen."

/** The short tick every status action plays under the finger (V-108). */
fun HapticFeedback.tick() {
    performHapticFeedback(HapticFeedbackType.LongPress)
}
