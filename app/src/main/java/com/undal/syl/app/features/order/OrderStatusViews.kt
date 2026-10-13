package com.undal.syl.app.features.order

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.undal.syl.app.core.design.UndalStatusPill
import com.undal.syl.app.core.theme.UndalDimens
import com.undal.syl.app.core.theme.undalSemantics
import com.undal.syl.app.core.util.OrderStateMachine
import com.undal.syl.app.core.util.OrderStatus

/**
 * The customer's view of an order's state, in one place (C-063 … C-068).
 *
 * Colours come from the theme's semantic palette rather than hard-coded values,
 * so "cooking", "on the way" and "cancelled" stay distinguishable in both light
 * and dark mode, and the same vocabulary is used on the tracking screen, the
 * history list and the inbox.
 */

/** Container / content colours for a status pill. */
@Composable
fun statusColours(status: OrderStatus): Pair<Color, Color> {
    val semantics = MaterialTheme.undalSemantics
    return when (status) {
        OrderStatus.DELIVERED -> semantics.successContainer to semantics.onSuccessContainer
        OrderStatus.CANCELLED -> semantics.closedContainer to semantics.onClosedContainer
        OrderStatus.PLACED -> MaterialTheme.colorScheme.secondaryContainer to
            MaterialTheme.colorScheme.onSecondaryContainer
        OrderStatus.ACCEPTED, OrderStatus.READY, OrderStatus.IN_DELIVERY ->
            MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
    }
}

fun orderStatusIcon(status: OrderStatus): ImageVector = when (status) {
    OrderStatus.PLACED -> Icons.Filled.Send
    OrderStatus.ACCEPTED -> Icons.Filled.ThumbUp
    OrderStatus.READY -> Icons.Filled.ShoppingCart
    OrderStatus.IN_DELIVERY -> Icons.Filled.Place
    OrderStatus.DELIVERED -> Icons.Filled.CheckCircle
    OrderStatus.CANCELLED -> Icons.Filled.Close
}

/** One-line, rider-neutral copy for each state. */
fun orderStatusCaption(status: OrderStatus): String = when (status) {
    OrderStatus.PLACED -> "Sent to the kitchen. They have been alerted on their phone."
    OrderStatus.ACCEPTED -> "The kitchen accepted your order and started cooking."
    OrderStatus.READY -> "Packed and waiting for the rider to collect it."
    OrderStatus.IN_DELIVERY -> "Your food has left the kitchen and is on the way to you."
    OrderStatus.DELIVERED -> "Delivered. Please keep the cash ready for the rider."
    OrderStatus.CANCELLED -> "The kitchen could not take this order. Their note is below."
}

@Composable
fun OrderStatusPill(status: OrderStatus, modifier: Modifier = Modifier) {
    val (container, content) = statusColours(status)
    UndalStatusPill(
        text = OrderStateMachine.label(status),
        containerColour = container,
        contentColour = content,
        icon = orderStatusIcon(status),
        modifier = modifier
    )
}

/**
 * The live timeline (C-063): every step the order will pass, with the ones
 * already done filled in, the present one ringed, and the future ones muted.
 */
@Composable
fun OrderTimelineStepper(
    steps: List<OrderStatus>,
    currentIndex: Int,
    cancelled: Boolean,
    modifier: Modifier = Modifier
) {
    val doneColour = MaterialTheme.colorScheme.primary
    val pendingColour = MaterialTheme.colorScheme.outlineVariant

    Column(modifier = modifier) {
        steps.forEachIndexed { index, step ->
            val isDone = currentIndex >= 0 && index < currentIndex
            val isCurrent = currentIndex >= 0 && index == currentIndex && !cancelled
            val dotColour = when {
                cancelled && index <= currentIndex -> pendingColour
                isDone || isCurrent -> doneColour
                else -> pendingColour
            }
            Row {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(if (isCurrent) 22.dp else 16.dp)
                            .background(dotColour, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isDone || isCurrent) {
                            Icon(
                                imageVector = if (isDone) Icons.Filled.Check else orderStatusIcon(step),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(if (isCurrent) 14.dp else 11.dp)
                            )
                        }
                    }
                    if (index != steps.lastIndex) {
                        Box(
                            modifier = Modifier
                                .width(2.dp)
                                .height(34.dp)
                                .background(if (isDone) doneColour else pendingColour)
                        )
                    }
                }
                Spacer(Modifier.width(UndalDimens.sm))
                Column(modifier = Modifier.padding(top = 1.dp)) {
                    Text(
                        text = OrderStateMachine.label(step),
                        style = MaterialTheme.typography.titleSmall,
                        color = if (isDone || isCurrent) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    if (isCurrent && !cancelled) {
                        Text(
                            text = orderStatusCaption(step),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (cancelled && index == currentIndex) {
                        Text(
                            text = orderStatusCaption(OrderStatus.CANCELLED),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.undalSemantics.closed
                        )
                    }
                }
            }
        }
    }
}

/**
 * The prep-time ring (C-064). It fills as the minutes pass and keeps counting
 * past the estimate rather than freezing, because "the kitchen is late" is
 * information the customer deserves to have.
 */
@Composable
fun PrepCountdownRing(
    progress: Float,
    timeLabel: String,
    headline: String,
    subline: String,
    overdue: Boolean,
    modifier: Modifier = Modifier
) {
    val ringColour = if (overdue) MaterialTheme.undalSemantics.warning else MaterialTheme.colorScheme.primary
    val trackColour = MaterialTheme.colorScheme.surfaceVariant
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(RING_SIZE)) {
                val stroke = 12.dp.toPx()
                val inset = stroke / 2f
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(
                    color = trackColour,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
                drawArc(
                    color = ringColour,
                    startAngle = -90f,
                    sweepAngle = 360f * progress.coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = timeLabel, style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = if (overdue) "a little longer" else "until ready",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(UndalDimens.sm))
        Text(text = headline, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (subline.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = subline,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** One labelled fact with an icon, used by the receipt and the delivery card. */
@Composable
fun OrderQuickFactRow(icon: ImageVector, label: String, value: String) {
    Row(
        modifier = Modifier.padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(UndalDimens.iconSm)
        )
        Spacer(Modifier.width(UndalDimens.sm))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.weight(1f))
        Text(text = value, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
    }
}

private val RING_SIZE = 156.dp
