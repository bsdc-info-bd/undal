package com.undal.syl.app.features.store

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextButtonDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalFocusManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.undal.syl.app.R
import com.undal.syl.app.core.design.UndalAdaptivePair
import com.undal.syl.app.core.design.UndalLayoutPolicy
import com.undal.syl.app.core.design.UndalSectionHeader
import com.undal.syl.app.core.design.ShimmerListRow
import com.undal.syl.app.core.design.UndalAlertBanner
import com.undal.syl.app.core.design.UndalEmptyState
import com.undal.syl.app.core.design.UndalPrimaryButton
import com.undal.syl.app.core.design.UndalScaffold
import com.undal.syl.app.core.design.UndalStatusPill
import com.undal.syl.app.core.design.UndalTextButton
import com.undal.syl.app.core.design.UndalTextField
import com.undal.syl.app.core.model.Campaign
import com.undal.syl.app.core.model.MenuCard
import com.undal.syl.app.core.model.MenuSort
import com.undal.syl.app.core.theme.UndalDimens
import com.undal.syl.app.core.theme.UndalPalette
import com.undal.syl.app.core.util.CouponMath
import com.undal.syl.app.core.util.Distance
import com.undal.syl.app.core.util.Money
import com.undal.syl.app.data.repo.CouponRepo

/**
 * The store (C-021 … C-037).
 *
 * Reading order matches thinking order: who is cooking for me, can they reach me,
 * is there an offer, what do I fancy, and how much of it is already in my cart.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoreScreen(
    onOpenItem: (String) -> Unit,
    onOpenCart: () -> Unit,
    onOpenOrders: () -> Unit,
    onOpenInbox: () -> Unit,
    onOpenProfile: () -> Unit,
    onEditAddress: () -> Unit,
    sharedImageModifier: @Composable (String) -> Modifier = { Modifier },
    viewModel: StoreViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    var showAccountMenu by remember { mutableStateOf(false) }
    val menuCardWidth = UndalLayoutPolicy.menuCardWidth(LocalConfiguration.current.screenWidthDp.toFloat(), LocalDensity.current.fontScale).dp
    val heroCard = remember(state.menu) {
        state.menu.cards.firstOrNull { card ->
            card.available && (card as? MenuCard.Dish)?.item?.featured == true
        } ?: state.menu.cards.firstOrNull { it.available && it.imageUrl.isNotBlank() }
    }
    val hasActiveFilters = state.query.isNotBlank() || state.categoryId != null ||
        state.subcategoryId != null || state.vegetarianOnly || state.popularOnly ||
        state.hideUnavailable || state.sort != MenuSort.RECOMMENDED
    var refreshing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.refreshAddress()
        viewModel.refreshOffers()
    }
    LaunchedEffect(refreshing) {
        if (!refreshing) return@LaunchedEffect
        // The pull-to-refresh indicator turns off when the work finishes — and it
        // turns off even if this coroutine is cancelled on the way (the customer
        // swipes again, or walks to another screen), because a spinner that never
        // stops is worse than no spinner at all.
        try {
            viewModel.refreshAddress()
            viewModel.refreshOffers()
            kotlinx.coroutines.delay(350)
        } finally {
            refreshing = false
        }
    }

    UndalScaffold(
        title = state.store.storeName,
        subtitle = if (state.openState.serviceable) "Open · ${state.openState.todayLine}" else state.openState.headline,
        contentMaxWidth = 1200.dp,
        actions = {
            Box {
                IconButton(onClick = { showAccountMenu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Orders, notifications and profile")
                }
                DropdownMenu(expanded = showAccountMenu, onDismissRequest = { showAccountMenu = false }) {
                    DropdownMenuItem(text = { Text("My orders") }, onClick = { showAccountMenu = false; onOpenOrders() })
                    DropdownMenuItem(text = { Text("Notifications") }, onClick = { showAccountMenu = false; onOpenInbox() })
                    DropdownMenuItem(text = { Text("My profile") }, onClick = { showAccountMenu = false; onOpenProfile() })
                }
            }
        },
        bottomBar = {
            if (state.cartCount > 0) {
                CartSummaryBar(
                    itemCount = state.cartCount,
                    subtotalLabel = state.cartSubtotalLabel,
                    enabled = state.openState.serviceable,
                    onOpenCart = onOpenCart
                )
            }
        }
    ) { _ ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { refreshing = true },
            modifier = Modifier.fillMaxSize()
        ) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = menuCardWidth),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = UndalDimens.screenPadding,
                    end = UndalDimens.screenPadding,
                    top = UndalDimens.sm,
                    bottom = UndalDimens.xl
                ),
                horizontalArrangement = Arrangement.spacedBy(UndalDimens.md),
                verticalArrangement = Arrangement.spacedBy(UndalDimens.lg)
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    UndalAdaptivePair(
                        primary = {
                            StoreHeader(
                                state = state,
                                reachabilityLine = viewModel.reachabilityLine(state.reachability),
                                onEditAddress = onEditAddress
                            )
                        },
                        secondary = {
                            StoreHeroCard(
                                featured = heroCard,
                                onOpenFeatured = { heroCard?.let { onOpenItem(it.id) } }
                            )
                        }
                    )
                }

                if (state.errorMessage != null) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        UndalAlertBanner(
                            text = state.errorMessage.orEmpty(),
                            icon = Icons.Filled.Info,
                            containerColour = MaterialTheme.colorScheme.errorContainer,
                            contentColour = MaterialTheme.colorScheme.onErrorContainer,
                            action = {
                                TextButton(onClick = viewModel::clearMessage) {
                                    Text("Dismiss", color = MaterialTheme.colorScheme.onErrorContainer)
                                }
                            }
                        )
                    }
                }

                item(span = { GridItemSpan(maxLineSpan) }) {
                    UndalTextField(
                        value = state.query,
                        onValueChange = viewModel::onQueryChange,
                        label = "Search the menu",
                        hint = "kacchi, biryani, tea …",
                        leadingIcon = Icons.Filled.Search,
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Search,
                        onImeAction = { focus.clearFocus() }
                    )
                }

                if (state.offers.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        OfferStrip(offers = state.offers)
                    }
                }

                if (state.campaigns.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        CampaignStrip(campaigns = state.campaigns)
                    }
                }

                item(span = { GridItemSpan(maxLineSpan) }) {
                    UndalSectionHeader(
                        title = state.greeting.ifBlank { "What sounds good today?" },
                        subtitle = if (state.loading) "Reading the kitchen menu…" else "${state.cards.size} choices · find your favourite"
                    )
                }

                if (state.menu.categories.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        CategoryRow(
                            selectedId = state.categoryId,
                            categories = state.menu.categoriesWithSubcategories(),
                            hasCombos = state.menu.combos.isNotEmpty(),
                            onSelect = viewModel::onCategorySelected
                        )
                    }
                }

                if (state.menu.cards.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        QuickFilterRow(
                            vegetarianOnly = state.vegetarianOnly,
                            popularOnly = state.popularOnly,
                            hideUnavailable = state.hideUnavailable,
                            showPopular = state.menu.items.any { it.isPopular },
                            onVegetarianChange = viewModel::setVegetarianOnly,
                            onPopularChange = viewModel::setPopularOnly,
                            onHideUnavailableChange = viewModel::setHideUnavailable
                        )
                    }
                }

                val subcategories = state.menu.subcategoriesFor(state.categoryId)
                if (subcategories.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(UndalDimens.xs)) {
                            items(subcategories, key = { it.id }) { subcategory ->
                                FilterPill(
                                    label = subcategory.name,
                                    selected = state.subcategoryId == subcategory.id,
                                    onClick = { viewModel.onSubcategorySelected(subcategory.id) }
                                )
                            }
                        }
                    }
                }

                item(span = { GridItemSpan(maxLineSpan) }) {
                    SortRow(
                        selected = state.sort,
                        resultCount = state.cards.size,
                        hasFilters = hasActiveFilters,
                        onSelect = viewModel::onSortSelected,
                        onClear = viewModel::clearFilters
                    )
                }

                if (state.loading) {
                    items(count = 4, key = { "shimmer-$it" }) {
                        ShimmerListRow()
                    }
                } else if (state.cards.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        UndalEmptyState(
                            icon = Icons.Filled.ShoppingCart,
                            title = if (state.openState.serviceable) "Nothing matched that" else state.openState.headline,
                            message = if (state.openState.serviceable) {
                                "Try a different word, or clear the filters to see the whole menu."
                            } else {
                                state.openState.detail
                            },
                            actionLabel = if (state.openState.serviceable) "Clear filters" else null,
                            onAction = if (state.openState.serviceable) viewModel::clearFilters else null
                        )
                    }
                } else {
                    items(state.cards, key = { it.id }) { card ->
                        UndalMenuCard(
                            card = card,
                            quantity = state.quantities[card.id] ?: 0,
                            onAdd = { viewModel.quickAdd(card.id) },
                            onRemove = { viewModel.quickRemove(card.id) },
                            onOpen = { onOpenItem(card.id) },
                            imageModifier = sharedImageModifier(card.id)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StoreHeroCard(
    featured: MenuCard?,
    onOpenFeatured: () -> Unit
) {
    val fontScale = LocalDensity.current.fontScale
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(UndalDimens.radiusLg),
        color = MaterialTheme.colorScheme.primary
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, UndalPalette.BrandMid)))
        ) {
            val compact = maxWidth < 360.dp || fontScale > 1.3f
            if (compact) {
                Column(modifier = Modifier.fillMaxWidth().padding(UndalDimens.lg)) {
                    StoreHeroArtwork(
                        featured = featured,
                        modifier = Modifier.fillMaxWidth().height(132.dp)
                    )
                    Spacer(Modifier.height(UndalDimens.md))
                    StoreHeroCopy(featured = featured, onOpenFeatured = onOpenFeatured)
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(UndalDimens.lg),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StoreHeroCopy(
                        featured = featured,
                        onOpenFeatured = onOpenFeatured,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(UndalDimens.sm))
                    StoreHeroArtwork(
                        featured = featured,
                        modifier = Modifier
                            .width((maxWidth * 0.34f).coerceIn(112.dp, 170.dp))
                            .height(148.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun StoreHeroCopy(
    featured: MenuCard?,
    onOpenFeatured: () -> Unit,
    modifier: Modifier = Modifier
) {
    val white = Color.White
    Column(modifier = modifier) {
        Text(
            text = "UNDAL · SYLHET KITCHEN",
            style = MaterialTheme.typography.labelSmall,
            color = white
        )
        Spacer(Modifier.height(UndalDimens.xs))
        Text(
            text = "A little taste of home.",
            style = MaterialTheme.typography.headlineSmall,
            color = white
        )
        Spacer(Modifier.height(UndalDimens.xs))
        Text(
            text = "Home-cooked favourites, made with care.",
            style = MaterialTheme.typography.bodySmall,
            color = white,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (featured != null) {
            Spacer(Modifier.height(UndalDimens.sm))
            Text(
                text = "KITCHEN FAVOURITE",
                style = MaterialTheme.typography.labelSmall,
                color = white
            )
            Text(
                text = featured.name,
                style = MaterialTheme.typography.titleSmall,
                color = white,
                maxLines = if (LocalDensity.current.fontScale > 1.3f) 2 else 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = Money.format(featured.amountPaisa),
                style = MaterialTheme.typography.labelLarge,
                color = white
            )
            Spacer(Modifier.height(UndalDimens.xs))
            Surface(shape = RoundedCornerShape(UndalDimens.radiusPill), color = white) {
                TextButton(
                    onClick = onOpenFeatured,
                    contentPadding = PaddingValues(horizontal = UndalDimens.md),
                    colors = TextButtonDefaults.textButtonColors(contentColor = UndalPalette.BrandDark)
                ) {
                    Text("Explore dish  ›", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun StoreHeroArtwork(featured: MenuCard?, modifier: Modifier = Modifier) {
    val white = Color.White
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(UndalDimens.radiusMd))
            .background(white.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center
    ) {
        if (featured?.imageUrl?.isNotBlank() == true) {
            AsyncImage(
                model = featured.imageUrl,
                contentDescription = featured.name,
                error = painterResource(R.drawable.ic_undal_mark),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_undal_mark),
                contentDescription = null,
                tint = white,
                modifier = Modifier.size(56.dp)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StoreHeader(
    state: StoreViewModel.State,
    reachabilityLine: String,
    onEditAddress: () -> Unit
) {
    Column {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(UndalDimens.radiusMd),
            color = if (state.openState.serviceable) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            }
        ) {
            Column(modifier = Modifier.padding(UndalDimens.md)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(UndalDimens.sm),
                    verticalArrangement = Arrangement.spacedBy(UndalDimens.xs)) {
                    UndalStatusPill(
                        text = state.openState.headline,
                        containerColour = if (state.openState.serviceable) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        contentColour = Color.White
                    )
                    Spacer(Modifier.width(UndalDimens.sm))
                    Text(
                        text = state.openState.nextOpeningLine,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (state.openState.serviceable) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onErrorContainer
                        },
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(UndalDimens.xs))
                Text(
                    text = state.openState.detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.openState.serviceable) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onErrorContainer
                    }
                )
                Spacer(Modifier.height(UndalDimens.sm))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.LocationOn,
                        contentDescription = null,
                        modifier = Modifier.size(UndalDimens.iconSm),
                        tint = if (state.openState.serviceable) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onErrorContainer
                        }
                    )
                    Spacer(Modifier.width(6.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = state.address?.title?.ifBlank { "Your delivery address" } ?: "No address saved yet",
                            style = MaterialTheme.typography.titleSmall,
                            color = if (state.openState.serviceable) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onErrorContainer
                            }
                        )
                        Text(
                            text = reachabilityLine,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (state.openState.serviceable) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onErrorContainer
                            }
                        )
                    }
                    TextButton(onClick = onEditAddress) {
                        Text(
                            text = if (state.address == null) "Add" else "Edit",
                            color = if (state.openState.serviceable) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onErrorContainer
                            }
                        )
                    }
                }
                Spacer(Modifier.height(UndalDimens.xs))
                Text(
                    text = "Delivery ${state.feeLabel} · minimum order ${state.minimumOrderLabel} · cash on delivery only",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.openState.serviceable) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onErrorContainer
                    }
                )
            }
        }
    }
}

@Composable
private fun GreetingHeader(
    greeting: String,
    addressLabel: String,
    onEditAddress: () -> Unit
) {
    Column {
        Text(
            text = greeting.ifBlank { "Welcome back" },
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(UndalDimens.xs))
        Surface(
            shape = RoundedCornerShape(UndalDimens.radiusPill),
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.clickable(onClick = onEditAddress)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = UndalDimens.sm, vertical = UndalDimens.xs)
            ) {
                Icon(
                    Icons.Filled.LocationOn,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(UndalDimens.iconSm)
                )
                Spacer(Modifier.width(UndalDimens.xs))
                Text(
                    text = addressLabel.ifBlank { "Set your delivery address" },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * The kitchen's live offers, in the words the web store uses for the same list.
 *
 * This is the discovery half of a promotion. The basket already had somewhere to
 * *enter* a code, and an automatic offer already priced itself silently — but
 * nowhere in the app could a customer find out a code existed, which is what
 * "there is no coupon option in the app" actually meant. Same board, same
 * headline, same two reach states as the storefront's banner: one promotion, and
 * the phone and the browser describe it identically.
 *
 * Tapping copies the code, because the only thing worth doing with a code on this
 * screen is taking it to the basket — and a code the customer has to retype from
 * a small screen is a code that gets mistyped.
 */
@Composable
private fun OfferStrip(offers: List<CouponRepo.Coupon>) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf<String?>(null) }

    LazyRow(horizontalArrangement = Arrangement.spacedBy(UndalDimens.sm)) {
        items(offers, key = { it.code }) { offer ->
            Surface(
                modifier = Modifier.width(260.dp),
                shape = RoundedCornerShape(UndalDimens.radiusMd),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Column(modifier = Modifier.padding(UndalDimens.md)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        UndalStatusPill(
                            text = offer.labelFor(),
                            containerColour = MaterialTheme.colorScheme.primary,
                            contentColour = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.weight(1f))
                        UndalStatusPill(
                            // The same two sentences the web banner carries.
                            text = if (offer.autoApply) "Applies itself" else "Type this code",
                            containerColour = MaterialTheme.colorScheme.surface,
                            contentColour = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(UndalDimens.xs))
                    val condition = CouponMath.conditionLine(offer.toMath())
                    Text(
                        text = condition.ifBlank { "On everything we cook." },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (offer.description.isNotBlank()) {
                        Text(
                            text = offer.description,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(UndalDimens.xs))
                    UndalTextButton(
                        text = if (copied == offer.code) "${offer.code} copied" else "Copy ${offer.code}",
                        onClick = {
                            clipboard.setText(AnnotatedString(offer.code))
                            copied = offer.code
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun CampaignStrip(campaigns: List<Campaign>) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(UndalDimens.sm)) {
        items(campaigns, key = { it.id }) { campaign ->
            Surface(
                modifier = Modifier.width(260.dp),
                shape = RoundedCornerShape(UndalDimens.radiusMd),
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Column(modifier = Modifier.padding(UndalDimens.md)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        UndalStatusPill(
                            text = campaign.valueLabel(),
                            containerColour = MaterialTheme.colorScheme.secondary,
                            contentColour = MaterialTheme.colorScheme.onSecondary
                        )
                        if (campaign.code.isNotBlank()) {
                            Spacer(Modifier.width(UndalDimens.xs))
                            UndalStatusPill(
                                text = campaign.code,
                                containerColour = MaterialTheme.colorScheme.surface,
                                contentColour = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    Spacer(Modifier.height(UndalDimens.xs))
                    Text(
                        text = campaign.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (campaign.subtitle.isNotBlank()) {
                        Text(
                            text = campaign.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (campaign.bannerUrl.isNotBlank()) {
                        Spacer(Modifier.height(UndalDimens.xs))
                        AsyncImage(
                            model = campaign.bannerUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(90.dp)
                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(UndalDimens.radiusSm))
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryRow(
    selectedId: String?,
    categories: List<com.undal.syl.app.core.model.Category>,
    hasCombos: Boolean,
    onSelect: (String?) -> Unit
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(UndalDimens.sm)) {
        item(key = "all") {
            FoodCategoryTile(
                label = "All",
                imageUrl = "",
                selected = selectedId == null,
                onClick = { onSelect(null) }
            )
        }
        items(categories, key = { it.id }) { category ->
            FoodCategoryTile(
                label = category.name,
                imageUrl = category.iconUrl,
                selected = selectedId == category.id,
                onClick = { onSelect(category.id) }
            )
        }
        if (hasCombos) {
            item(key = MenuCard.COMBO_CATEGORY) {
                FoodCategoryTile(
                    label = "Combos",
                    imageUrl = "",
                    selected = selectedId == MenuCard.COMBO_CATEGORY,
                    onClick = { onSelect(MenuCard.COMBO_CATEGORY) }
                )
            }
        }
    }
}

@Composable
private fun QuickFilterRow(
    vegetarianOnly: Boolean,
    popularOnly: Boolean,
    hideUnavailable: Boolean,
    showPopular: Boolean,
    onVegetarianChange: (Boolean) -> Unit,
    onPopularChange: (Boolean) -> Unit,
    onHideUnavailableChange: (Boolean) -> Unit
) {
    Column {
        Text(
            text = "Quick filters",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(UndalDimens.xs)) {
            item(key = "vegetarian") {
                FilterChip(
                    selected = vegetarianOnly,
                    onClick = { onVegetarianChange(!vegetarianOnly) },
                    label = { Text("Vegetarian") }
                )
            }
            if (showPopular) {
                item(key = "popular") {
                    FilterChip(
                        selected = popularOnly,
                        onClick = { onPopularChange(!popularOnly) },
                        label = { Text("Popular dishes") }
                    )
                }
            }
            item(key = "hide-finished") {
                FilterChip(
                    selected = hideUnavailable,
                    onClick = { onHideUnavailableChange(!hideUnavailable) },
                    label = { Text("Hide finished") }
                )
            }
        }
    }
}

@Composable
private fun FoodCategoryTile(
    label: String,
    imageUrl: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val tileShape = RoundedCornerShape(UndalDimens.radiusMd)
    Column(
        modifier = Modifier.width(76.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(tileShape)
                .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label.take(1),
                style = MaterialTheme.typography.titleLarge,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (imageUrl.isNotBlank()) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = UndalDimens.xs)
        )
    }
}

@Composable
private fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(UndalDimens.radiusPill),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun SortRow(
    selected: MenuSort,
    resultCount: Int,
    hasFilters: Boolean,
    onSelect: (MenuSort) -> Unit,
    onClear: () -> Unit
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "$resultCount menu picks",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (hasFilters) {
                TextButton(onClick = onClear) {
                    Icon(Icons.Filled.Clear, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("Clear")
                }
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(UndalDimens.xs)) {
            items(MenuSort.entries.toList(), key = { it.name }) { sort ->
                FilterPill(
                    label = sort.label,
                    selected = sort == selected,
                    onClick = { onSelect(sort) }
                )
            }
        }
    }
}

/** The bar that keeps the cart one tap away, with the size of the order on it. */
@Composable
private fun CartSummaryBar(
    itemCount: Int,
    subtotalLabel: String,
    enabled: Boolean,
    onOpenCart: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = UndalDimens.elevationBar
    ) {
        Column(modifier = Modifier.padding(UndalDimens.screenPadding)) {
            UndalPrimaryButton(
                text = "$itemCount item${if (itemCount == 1) "" else "s"} · $subtotalLabel — view cart",
                onClick = onOpenCart,
                enabled = true,
                icon = Icons.Filled.ShoppingCart
            )
            if (!enabled) {
                Spacer(Modifier.height(UndalDimens.xs))
                Text(
                    text = "The kitchen is closed, so the cart is saved for when it opens.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
