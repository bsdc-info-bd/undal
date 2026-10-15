package com.undal.syl.app.features.item

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.undal.syl.app.R
import com.undal.syl.app.core.config.UndalBuildInfo
import com.undal.syl.app.core.design.UndalAlertBanner
import com.undal.syl.app.core.design.UndalCard
import com.undal.syl.app.core.design.UndalKeyValueRow
import com.undal.syl.app.core.design.UndalPrimaryButton
import com.undal.syl.app.core.design.UndalScaffold
import com.undal.syl.app.core.design.UndalSecondaryButton
import com.undal.syl.app.core.design.UndalStatusPill
import com.undal.syl.app.core.design.UndalTextField
import com.undal.syl.app.core.model.MenuCard
import com.undal.syl.app.core.theme.UndalDimens
import com.undal.syl.app.core.util.Money
import com.undal.syl.app.features.store.MenuAttributeRow
import com.undal.syl.app.features.store.QuantityStepper
import com.undal.syl.app.features.store.UndalMenuCard

/**
 * The dish page (C-039 … C-045).
 *
 * Sharing is deliberately part of the page: the deep link opens the Worker's
 * dish page with an Open Graph card, so a customer can send a dish to a friend
 * on Facebook or WhatsApp and the preview shows the real photo and price.
 */
@Composable
fun ItemDetailScreen(
    itemId: String,
    onBack: () -> Unit,
    onOpenItem: (String) -> Unit,
    onOpenCart: () -> Unit,
    headerImageModifier: Modifier = Modifier,
    viewModel: ItemDetailViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(itemId) { viewModel.bind(itemId) }

    val card = state.card

    UndalScaffold(
        title = card?.name ?: "Dish",
        onBack = onBack,
        actions = {
            if (card != null) {
                TextButton(
                    onClick = {
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, card.name)
                            putExtra(
                                Intent.EXTRA_TEXT,
                                viewModel.shareText() + "\n" + UndalBuildInfo.workerBaseUrl +
                                    (if (card is MenuCard.Bundle) "/combo/" else "/dish/") + card.id
                            )
                        }
                        runCatching {
                            context.startActivity(Intent.createChooser(share, "Share this dish"))
                        }
                    }
                ) {
                    Icon(Icons.Filled.Share, contentDescription = "Share this dish", modifier = Modifier.size(UndalDimens.iconSm))
                }
            }
        }
    ) { _ ->
        if (card == null) {
            Column(modifier = Modifier.fillMaxSize().padding(UndalDimens.screenPadding)) {
                Text(
                    text = if (state.loading) "Loading the dish…" else "This dish is no longer on the menu.",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            return@UndalScaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = UndalDimens.xxl)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                if (card.imageUrl.isNotBlank()) {
                    AsyncImage(
                        model = card.imageUrl,
                        contentDescription = card.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().then(headerImageModifier)
                    )
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_undal_mark),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.Center).size(72.dp)
                    )
                }
                if (!card.available) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter),
                        color = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Text(
                            text = "Finished for today — the kitchen will cook it again tomorrow.",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(UndalDimens.sm)
                        )
                    }
                }
            }

            Column(modifier = Modifier.padding(UndalDimens.screenPadding)) {
                Text(card.name, style = MaterialTheme.typography.headlineSmall)

                val dish = (card as? MenuCard.Dish)?.item
                if (dish != null) {
                    Spacer(Modifier.height(UndalDimens.sm))
                    MenuAttributeRow(item = dish)
                }

                if (state.message != null) {
                    Spacer(Modifier.height(UndalDimens.sm))
                    UndalAlertBanner(
                        text = state.message.orEmpty(),
                        icon = Icons.Filled.Info,
                        containerColour = MaterialTheme.colorScheme.errorContainer,
                        contentColour = MaterialTheme.colorScheme.onErrorContainer,
                        action = {
                            TextButton(onClick = viewModel::clearMessage) {
                                Text("Close", color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        }
                    )
                }

                Spacer(Modifier.height(UndalDimens.md))

                UndalCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = Money.format(card.amountPaisa),
                                style = MaterialTheme.typography.headlineSmall
                            )
                            if (dish != null && dish.hasDiscount) {
                                Text(
                                    text = "${Money.format(dish.amountPaisa)} · you save " +
                                        Money.format(dish.discountAmountPaisa),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textDecoration = TextDecoration.LineThrough
                                )
                            }
                            if (card is MenuCard.Bundle && card.combo.savingsPaisa > 0) {
                                Text(
                                    text = card.combo.savingsLabel + " against ordering separately",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        UndalStatusPill(
                            text = "Cash on delivery",
                            containerColour = MaterialTheme.colorScheme.surfaceVariant,
                            contentColour = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                val description = when (card) {
                    is MenuCard.Dish -> card.item.description
                    is MenuCard.Bundle -> card.combo.description
                }
                if (description.isNotBlank()) {
                    Spacer(Modifier.height(UndalDimens.md))
                    Text(description, style = MaterialTheme.typography.bodyLarge)
                }

                if (state.comboContents.isNotEmpty()) {
                    Spacer(Modifier.height(UndalDimens.md))
                    UndalCard {
                        Text("What is inside", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(UndalDimens.xs))
                        state.comboContents.forEach { (dish, qty) ->
                            UndalKeyValueRow(
                                label = dish.name,
                                value = if (qty > 1) "$qty × ${Money.format(dish.payablePaisa)}" else Money.format(dish.payablePaisa)
                            )
                        }
                        Spacer(Modifier.height(UndalDimens.sm))
                        val regular = (card as? MenuCard.Bundle)?.combo?.regularAmountPaisa ?: 0L
                        if (regular > 0) {
                            UndalKeyValueRow(
                                label = "Ordered separately",
                                value = Money.format(regular)
                            )
                            UndalKeyValueRow(
                                label = "Combo price",
                                value = Money.format(card.amountPaisa),
                                emphasise = true
                            )
                        }
                    }
                }

                Spacer(Modifier.height(UndalDimens.md))

                UndalCard {
                    Text("How much?", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(UndalDimens.sm))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        QuantityStepper(
                            quantity = state.quantity,
                            onAdd = viewModel::increment,
                            onRemove = viewModel::decrement,
                            enabled = card.available
                        )
                        Spacer(Modifier.width(UndalDimens.md))
                        val line = Money.lineTotal(card.amountPaisa, state.quantity)
                        Text(
                            text = "Line total ${Money.format(line)}",
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                    Spacer(Modifier.height(UndalDimens.md))
                    UndalTextField(
                        value = state.note,
                        onValueChange = viewModel::onNoteChange,
                        label = "Cooking note for this dish",
                        hint = "Less oil, extra spicy, no coriander…",
                        maxLines = 2,
                        singleLine = false,
                        helper = "Optional, but the kitchen reads it while cooking.",
                        enabled = card.available
                    )
                }

                Spacer(Modifier.height(UndalDimens.md))

                UndalPrimaryButton(
                    text = if (state.inCartQuantity > 0) {
                        "Add to cart · ${state.inCartQuantity} already in"
                    } else {
                        "Add to cart · ${Money.format(Money.lineTotal(card.amountPaisa, state.quantity))}"
                    },
                    onClick = { viewModel.addToCart(onOpenCart) },
                    enabled = card.available,
                    icon = Icons.Filled.Add
                )

                if (state.related.isNotEmpty()) {
                    Spacer(Modifier.height(UndalDimens.lg))
                    Text("Often ordered together", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(UndalDimens.sm))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(UndalDimens.sm)) {
                        items(state.related, key = { it.id }) { related ->
                            Box(modifier = Modifier.width(180.dp)) {
                                UndalMenuCard(
                                    card = related,
                                    quantity = 0,
                                    onAdd = { onOpenItem(related.id) },
                                    onRemove = { },
                                    onOpen = { onOpenItem(related.id) }
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(UndalDimens.lg))

                UndalSecondaryButton(
                    text = "See the whole cart",
                    onClick = onOpenCart,
                    modifier = Modifier.clickable { onOpenCart() }
                )
            }
        }
    }
}
