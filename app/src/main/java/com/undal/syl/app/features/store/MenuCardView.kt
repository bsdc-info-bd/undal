package com.undal.syl.app.features.store

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.undal.syl.app.R
import com.undal.syl.app.core.design.UndalStatusPill
import com.undal.syl.app.core.model.MenuCard
import com.undal.syl.app.core.model.MenuItem
import com.undal.syl.app.core.theme.UndalDimens
import com.undal.syl.app.core.util.Money

/**
 * One dish or combo in the grid (C-028 … C-035).
 *
 * The badges answer the four questions a customer asks before tapping: is it
 * available, is it vegetarian, how hot is it, is it popular. The stepper appears
 * in place of Add the moment there is one in the cart, so nobody loses track of
 * what they have already chosen.
 */
@Composable
fun UndalMenuCard(
    card: MenuCard,
    quantity: Int,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val available = card.available
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(UndalDimens.radiusMd))
            .clickable(enabled = true, onClick = onOpen),
        shape = RoundedCornerShape(UndalDimens.radiusMd),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = UndalDimens.elevationCard)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.25f)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                if (card.imageUrl.isNotBlank()) {
                    AsyncImage(
                        model = card.imageUrl,
                        contentDescription = card.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1.25f)
                    )
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_undal_mark),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(UndalDimens.iconLg)
                    )
                }

                if (!available) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().align(Alignment.Center),
                        color = Color(0xCC1B1B1B)
                    ) {
                        Text(
                            text = "Finished for today",
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White,
                            modifier = Modifier.padding(vertical = 6.dp, horizontal = 8.dp)
                        )
                    }
                }

                val badges = buildList {
                    if (card is MenuCard.Bundle) add("Combo")
                    val dish = (card as? MenuCard.Dish)?.item
                    if (dish?.isPopular == true) add("Popular")
                }
                if (badges.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        badges.forEach { label ->
                            Surface(
                                shape = RoundedCornerShape(UndalDimens.radiusSm),
                                color = MaterialTheme.colorScheme.primary
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                }
            }

            Column(modifier = Modifier.padding(UndalDimens.sm)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val dish = (card as? MenuCard.Dish)?.item
                    if (dish != null && dish.veg) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF1B7A3D))
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = card.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                val description = when (card) {
                    is MenuCard.Dish -> card.item.description
                    is MenuCard.Bundle -> card.combo.description.ifBlank { card.combo.savingsLabel }
                }
                if (description.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                val dish = (card as? MenuCard.Dish)?.item
                if (dish != null && dish.spiceLevel > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = spiceLabel(dish.spiceLevel),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }

                Spacer(Modifier.height(UndalDimens.sm))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = Money.format(card.amountPaisa),
                            style = MaterialTheme.typography.titleMedium
                        )
                        when (card) {
                            is MenuCard.Dish -> if (card.item.hasDiscount) {
                                Text(
                                    text = Money.format(card.item.amountPaisa),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textDecoration = TextDecoration.LineThrough
                                )
                            }

                            is MenuCard.Bundle -> if (card.combo.savingsPaisa > 0) {
                                Text(
                                    text = card.combo.savingsLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    if (quantity > 0) {
                        QuantityStepper(
                            quantity = quantity,
                            onAdd = onAdd,
                            onRemove = onRemove,
                            enabled = available
                        )
                    } else {
                        Surface(
                            shape = RoundedCornerShape(UndalDimens.radiusSm),
                            color = if (available) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                        ) {
                            TextButton(onClick = onAdd, enabled = available) {
                                Text(
                                    text = "Add",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (available) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The minus / count / plus control used on cards, in the cart and on the item page. */
@Composable
fun QuantityStepper(
    quantity: Int,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(UndalDimens.radiusSm),
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onRemove, enabled = enabled) {
                Icon(
                    Icons.Filled.Clear,
                    contentDescription = "One less",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Text(
                text = quantity.toString(),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.width(20.dp)
            )
            TextButton(onClick = onAdd, enabled = enabled) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = "One more",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

/** "Mild", "Medium", "Hot" — words, not three flame icons nobody can rank. */
fun spiceLabel(level: Int): String = when (level) {
    1 -> "Mild"
    2 -> "Medium"
    else -> "Hot"
}

/** Small badge used on the item page for the dish's own attributes. */
@Composable
fun MenuAttributeRow(item: MenuItem, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(UndalDimens.xs)
    ) {
        UndalStatusPill(
            text = if (item.veg) "Vegetarian" else "Non-veg",
            containerColour = if (item.veg) Color(0xFFE3F3E8) else MaterialTheme.colorScheme.surfaceVariant,
            contentColour = if (item.veg) Color(0xFF14532D) else MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (item.spiceLevel > 0) {
            UndalStatusPill(
                text = spiceLabel(item.spiceLevel),
                containerColour = MaterialTheme.colorScheme.secondaryContainer,
                contentColour = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
        if (item.isPopular) {
            UndalStatusPill(
                text = "Popular",
                containerColour = MaterialTheme.colorScheme.primaryContainer,
                contentColour = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}
