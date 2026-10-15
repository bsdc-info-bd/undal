package com.undal.syl.app.features.order

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.undal.syl.app.core.theme.UndalDimens
import com.undal.syl.app.core.theme.UndalPalette
import com.undal.syl.app.core.util.Distance
import com.undal.syl.app.core.util.Money
import com.undal.syl.app.core.util.TimeDhaka
import com.undal.syl.app.data.repo.OrderRepo

/**
 * The moment the order lands (C-061 · C-062).
 *
 * This is the customer's whole answer in one card: a tick, the two sentences
 * that say the kitchen has it, and the printed bill — every dish, the ride, the
 * coupon if there is one, and the total, in the same order a paper bill is
 * read. It is the first thing on the tracking screen while the kitchen has not
 * accepted yet, which is exactly when a customer wants to check what they just
 * sent and to whom.
 *
 * Two decisions are deliberate:
 *
 *  · **It is a dark card in both themes.** A receipt reads like paper: high
 *    contrast, one surface, hairlines between the rows. Keeping it dark in the
 *    light theme as well makes it unmistakably *the bill* rather than another
 *    panel of the page, and it is the one surface in the app that never sits in
 *    the middle of reading text.
 *  · **It repeats the total three times** — per row, as a subtotal, and as the
 *    one number the customer is actually going to hand over in cash. On a cash
 *    order the total is the thing people mis-remember, so it is the largest text
 *    on the card and the last thing on it.
 *
 * Nothing here is interactive: the actions of the moment (talk to the kitchen,
 * call, reorder) live on the cards below, where they are reachable with a thumb.
 */
@Composable
fun UndalOrderConfirmationCard(
    detail: OrderRepo.OrderDetail,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(UndalDimens.radiusLg))
            .background(UndalPalette.Ink)
            .padding(UndalDimens.cardPadding)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            // The taka mark, oversized and barely there: the watermark that makes
            // the card read as a bill at a glance, in the corner of an invoice.
            Text(
                text = "৳",
                fontSize = 132.sp,
                fontWeight = FontWeight.Bold,
                color = UndalPalette.NightInk.copy(alpha = 0.05f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 26.dp, y = (-56).dp)
            )

            Column(modifier = Modifier.fillMaxWidth()) {
                PlacedHero(detail = detail)
                Spacer(Modifier.height(UndalDimens.lg))
                ReceiptWell(detail = detail)
                Spacer(Modifier.height(UndalDimens.md))
                Text(
                    text = "Cash on delivery only — no card, no wallet, no online payment. " +
                        "Keep ${Money.format(detail.totalAmountPaisa)} ready for the rider.",
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = UndalPalette.NightMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun PlacedHero(detail: OrderRepo.OrderDetail) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        // A tick in a circle, with a soft glow behind it: the one glyph everybody
        // already knows means "that worked".
        Box(modifier = Modifier.size(104.dp), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(104.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                UndalPalette.NightBrand.copy(alpha = 0.30f),
                                UndalPalette.NightBrand.copy(alpha = 0f)
                            )
                        ),
                        CircleShape
                    )
            )
            Box(
                modifier = Modifier
                    .size(62.dp)
                    .background(UndalPalette.NightBrand, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = UndalPalette.Ink,
                    modifier = Modifier.size(34.dp)
                )
            }
        }

        Spacer(Modifier.height(UndalDimens.md))
        Text(
            text = "Order placed. We’re on it.",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = UndalPalette.NightInk,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                // Announced the moment it appears: the customer arrives on this
                // screen straight from the checkout, and a screen reader should
                // hear the confirmation without having to go looking for it.
                .semantics { liveRegion = LiveRegionMode.Polite }
        )
        Spacer(Modifier.height(UndalDimens.xs))
        Text(
            text = heroSubline(detail),
            style = MaterialTheme.typography.bodySmall,
            color = UndalPalette.NightMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** "Order UND-1001 · cash on delivery · placed 8:34 PM" — and nothing it cannot back up. */
private fun heroSubline(detail: OrderRepo.OrderDetail): String {
    val parts = mutableListOf<String>()
    if (detail.shortId.isNotBlank()) parts.add("Order ${detail.shortId}")
    parts.add("cash on delivery")
    val placedAt = detail.createdAt?.let { TimeDhaka.formatInstant(it) }
    if (placedAt != null && placedAt != "—") parts.add("placed $placedAt")
    return parts.joinToString(" · ")
}

@Composable
private fun ReceiptWell(detail: OrderRepo.OrderDetail) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(UndalDimens.radiusMd))
            .background(UndalPalette.NightInk.copy(alpha = 0.05f))
            .padding(horizontal = UndalDimens.md, vertical = UndalDimens.sm)
    ) {
        detail.items.forEachIndexed { index, line ->
            if (index > 0) ReceiptDivider()
            ReceiptLine(
                label = "${line.quantity} × ${line.name}",
                note = line.note.takeIf { it.isNotBlank() },
                detail = if (line.quantity > 1) "${Money.format(line.unitAmountPaisa)} each" else null,
                value = Money.format(line.lineTotalPaisa)
            )
        }

        ReceiptDivider()
        ReceiptLine(
            label = "Delivery · ${Distance.formatKm(detail.distanceKm)}",
            value = if (detail.deliveryAmountPaisa > 0L) {
                Money.format(detail.deliveryAmountPaisa)
            } else {
                "Free"
            }
        )

        ReceiptLine(
            label = "Food subtotal",
            value = Money.format(detail.subtotalAmountPaisa),
            muted = true
        )

        if (detail.discountAmountPaisa > 0L) {
            ReceiptLine(
                label = if (detail.couponCode.isNotBlank()) {
                    "Coupon ${detail.couponCode}"
                } else {
                    "Discount"
                },
                value = "− ${Money.format(detail.discountAmountPaisa)}",
                valueColour = UndalPalette.NightBrand
            )
        }

        Spacer(Modifier.height(UndalDimens.sm))
        ReceiptDivider(strong = true)
        Spacer(Modifier.height(UndalDimens.sm))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(UndalDimens.radiusSm))
                .background(UndalPalette.NightInk.copy(alpha = 0.06f))
                .padding(horizontal = UndalDimens.sm, vertical = UndalDimens.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "CASH TO THE RIDER",
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.14.sp),
                color = UndalPalette.NightMuted,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = Money.format(detail.totalAmountPaisa),
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = UndalPalette.NightInk
            )
        }
    }
}

/**
 * One row of the bill: what it was, what it cost, and the two footnotes that
 * belong to the row rather than the card — a per-dish note for the kitchen, and
 * the unit price, but only when the quantity makes the arithmetic worth showing.
 */
@Composable
private fun ReceiptLine(
    label: String,
    value: String,
    detail: String? = null,
    note: String? = null,
    muted: Boolean = false,
    valueColour: Color = UndalPalette.NightInk
) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = UndalDimens.xs)) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (muted) UndalPalette.NightMuted else UndalPalette.NightInk
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = UndalPalette.NightMuted
                )
            }
            if (note != null) {
                Text(
                    text = "Note: $note",
                    style = MaterialTheme.typography.labelSmall,
                    color = UndalPalette.NightAccent
                )
            }
        }
        Spacer(Modifier.width(UndalDimens.md))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColour,
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun ReceiptDivider(strong: Boolean = false) {
    Spacer(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(UndalPalette.NightInk.copy(alpha = if (strong) 0.22f else 0.10f))
    )
}
