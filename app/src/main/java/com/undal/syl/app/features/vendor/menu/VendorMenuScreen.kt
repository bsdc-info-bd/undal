@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.undal.syl.app.features.vendor.menu

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.undal.syl.app.core.design.UndalCard
import com.undal.syl.app.core.design.UndalConfirmDialog
import com.undal.syl.app.core.design.UndalScaffold
import com.undal.syl.app.core.design.UndalSecondaryButton
import com.undal.syl.app.core.design.UndalStatusPill
import com.undal.syl.app.core.design.UndalTextField
import com.undal.syl.app.core.design.UndalTextButton
import com.undal.syl.app.core.model.MenuItem
import com.undal.syl.app.core.model.MenuSort
import com.undal.syl.app.core.theme.UndalDimens
import com.undal.syl.app.core.theme.undalSemantics
import com.undal.syl.app.core.util.Money
import com.undal.syl.app.features.vendor.VendorChip
import com.undal.syl.app.features.vendor.VendorEmptyState
import com.undal.syl.app.features.vendor.VendorSection
import com.undal.syl.app.features.vendor.tick

/**
 * The menu manager (V-049 … V-070).
 *
 * Five tabs under one search field: dishes, sections, combos and offers. Every
 * row is a live document, every toggle writes immediately, and nothing needs a
 * "refresh" button because the listeners are already pushing.
 */
@Composable
fun VendorMenuScreen(
    onBack: () -> Unit,
    onPreviewAsCustomer: () -> Unit,
    /**
     * The tab to open on. The studio (`vendor/promos`) passes Offers so the
     * dashboard's "Offers" button lands on the promotions themselves; the manager
     * itself opens on Dishes.
     */
    startTab: VendorMenuViewModel.Tab? = null,
    viewModel: VendorMenuViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current

    LaunchedEffect(startTab) {
        if (startTab != null) viewModel.onTab(startTab)
    }

    UndalScaffold(
        title = if (state.tab == VendorMenuViewModel.Tab.OFFERS) "Coupons and discounts" else "Menu manager",
        subtitle = "${state.menu.items.size} dish(es) · ${state.menu.combos.size} combo(s) · " +
            "${state.menu.categories.size} section(s)",
        onBack = onBack,
        actions = {
            UndalTextButton(
                text = "Preview",
                onClick = onPreviewAsCustomer,
                icon = Icons.Filled.ShoppingCart
            )
        }
    ) { _ ->
        Column(modifier = Modifier.fillMaxSize()) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = UndalDimens.screenPadding, vertical = UndalDimens.xs),
                horizontalArrangement = Arrangement.spacedBy(UndalDimens.xs)
            ) {
                VendorMenuViewModel.Tab.entries.forEach { tab ->
                    VendorChip(
                        label = tab.label,
                        selected = state.tab == tab,
                        onClick = {
                            haptics.tick()
                            viewModel.onTab(tab)
                        }
                    )
                }
            }

            when (state.tab) {
                VendorMenuViewModel.Tab.DISHES -> DishesTab(state = state, viewModel = viewModel)

                VendorMenuViewModel.Tab.SECTIONS -> SectionsTab(state = state, viewModel = viewModel)
                VendorMenuViewModel.Tab.COMBOS -> CombosTab(state = state, viewModel = viewModel)
                VendorMenuViewModel.Tab.OFFERS -> OffersTab(state = state, viewModel = viewModel)
            }
        }

        // ---------------------------------------------------------------- editors
        state.itemDraft?.let { draft ->
            DishEditorSheet(draft = draft, state = state, viewModel = viewModel)
        }
        state.sectionDraft?.let { draft ->
            SectionEditorSheet(draft = draft, state = state, viewModel = viewModel)
        }
        state.comboDraft?.let { draft ->
            ComboEditorSheet(draft = draft, state = state, viewModel = viewModel)
        }
        state.campaignDraft?.let { draft ->
            CampaignEditorSheet(draft = draft, state = state, viewModel = viewModel)
        }
        state.couponDraft?.let { draft ->
            CouponEditorSheet(draft = draft, state = state, viewModel = viewModel)
        }

        if (state.dirtyWarning) {
            UndalConfirmDialog(
                title = "Leave without saving?",
                message = "This dish has unsaved changes. Leave the editor and lose them?",
                confirmLabel = "Discard changes",
                destructive = true,
                onConfirm = viewModel::discardEditor,
                onDismiss = viewModel::cancelDirtyWarning
            )
        }

        state.confirmDelete?.let { request ->
            val extra = when (request.kind) {
                VendorMenuViewModel.DeleteRequest.Kind.DISH -> when (val used = request.ordersAffected) {
                    null -> "Checking how many recent orders used it…"
                    0 -> "No order in the last 200 used this dish."
                    else -> "$used of the last 200 orders used this dish. The past orders keep their own copy, " +
                        "so deleting only changes the menu."
                }

                VendorMenuViewModel.DeleteRequest.Kind.COUPON ->
                    "A coupon that has never been used can go. One with uses stays for the record — switch it off instead."

                VendorMenuViewModel.DeleteRequest.Kind.SECTION ->
                    "A section holding dishes or subcategories refuses to go until it is emptied."

                else -> "This cannot be undone."
            }
            UndalConfirmDialog(
                title = "Delete ${request.name}?",
                message = extra,
                confirmLabel = "Delete",
                destructive = true,
                onConfirm = viewModel::confirmDelete,
                onDismiss = viewModel::dismissDelete
            )
        }
    }
}

// ------------------------------------------------------------------------ dishes

@Composable
private fun DishesTab(
    state: VendorMenuViewModel.State,
    viewModel: VendorMenuViewModel
) {
    val dishes = viewModel.visibleDishes()
    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(horizontal = UndalDimens.screenPadding)) {
            UndalTextField(
                value = state.query,
                onValueChange = viewModel::onQuery,
                label = "Search the menu",
                hint = "bhorta, chicken, tea…",
                singleLine = true,
                leadingIcon = Icons.Filled.List
            )
            Spacer(Modifier.height(UndalDimens.xs))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(UndalDimens.xs)) {
                state.menu.categories.sortedBy { it.order }.forEach { category ->
                    VendorChip(
                        label = "${category.name} · ${viewModel.countFor(category.id)}",
                        selected = state.categoryId == category.id,
                        onClick = { viewModel.onCategoryFilter(category.id) }
                    )
                }
            }
            Spacer(Modifier.height(UndalDimens.xs))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(UndalDimens.xs)) {
                MenuSort.entries.forEach { sort ->
                    VendorChip(
                        label = sort.label,
                        selected = state.sort == sort,
                        onClick = { viewModel.onSort(sort) }
                    )
                }
            }
            Spacer(Modifier.height(UndalDimens.sm))
            Row(verticalAlignment = Alignment.CenterVertically) {
                UndalSecondaryButton(
                    text = if (state.bulkMode) "Bulk: on" else "Bulk mode",
                    onClick = viewModel::toggleBulkMode,
                    icon = Icons.Filled.Check,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(UndalDimens.xs))
                UndalSecondaryButton(
                    text = "New dish",
                    onClick = viewModel::startNewDish,
                    icon = Icons.Filled.Add,
                    modifier = Modifier.weight(1f)
                )
            }
            if (state.bulkMode) {
                Spacer(Modifier.height(UndalDimens.xs))
                UndalCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(UndalDimens.sm)) {
                        Text(
                            text = "${state.selectedIds.size} selected",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(Modifier.height(UndalDimens.xs))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(UndalDimens.xs)) {
                            UndalTextButton(text = "Select all", onClick = viewModel::selectAllVisible)
                            UndalTextButton(text = "Clear", onClick = viewModel::clearSelection)
                        }
                        Spacer(Modifier.height(UndalDimens.xs))
                        Row(horizontalArrangement = Arrangement.spacedBy(UndalDimens.xs)) {
                            UndalSecondaryButton(
                                text = "Mark available",
                                onClick = { viewModel.applyBulk(true) },
                                enabled = state.selectedIds.isNotEmpty() && !state.busy,
                                modifier = Modifier.weight(1f)
                            )
                            UndalSecondaryButton(
                                text = "Mark sold out",
                                onClick = { viewModel.applyBulk(false) },
                                enabled = state.selectedIds.isNotEmpty() && !state.busy,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(UndalDimens.xs))
        }

        if (dishes.isEmpty()) {
            VendorEmptyState(
                icon = Icons.Filled.List,
                title = if (state.query.isBlank()) "No dish here yet" else "Nothing matches \"${state.query}\"",
                message = "Create a dish, or clear the filters. Dishes stay visible when they are sold out, " +
                    "so customers see what the kitchen cooks.",
                actionLabel = "New dish",
                onAction = viewModel::startNewDish
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = UndalDimens.screenPadding,
                    end = UndalDimens.screenPadding,
                    bottom = UndalDimens.xxl
                ),
                verticalArrangement = Arrangement.spacedBy(UndalDimens.xs)
            ) {
                items(dishes, key = { it.id }) { item ->
                    DishRow(
                        item = item,
                        bulkMode = state.bulkMode,
                        selected = item.id in state.selectedIds,
                        onToggleSelected = { viewModel.toggleSelected(item.id) },
                        onToggleAvailability = { viewModel.toggleAvailability(item) },
                        onToggleFeatured = { viewModel.toggleFeatured(item) },
                        onEdit = { viewModel.editDish(item) },
                        onDelete = { viewModel.askDeleteDish(item) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DishRow(
    item: MenuItem,
    bulkMode: Boolean,
    selected: Boolean,
    onToggleSelected: () -> Unit,
    onToggleAvailability: () -> Unit,
    onToggleFeatured: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    UndalCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(UndalDimens.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (bulkMode) {
                    Checkbox(checked = selected, onCheckedChange = { onToggleSelected() })
                    Spacer(Modifier.width(UndalDimens.xs))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = item.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = buildString {
                            if (item.priceFrom) append("Price needs exact options · from ")
                            append(Money.format(item.payablePaisa))
                            if (item.hasDiscount) {
                                append(" · was ")
                                append(Money.format(item.amountPaisa))
                            }
                            append(" · ")
                            append(if (item.prepMinutes > 0) "${item.prepMinutes} min" else "prep estimate not set")
                            if (item.tags.isNotEmpty()) {
                                append(" · ")
                                append(item.tags.joinToString(" "))
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Switch(
                    checked = item.available,
                    onCheckedChange = { onToggleAvailability() },
                    enabled = !item.priceFrom
                )
            }
            Spacer(Modifier.height(UndalDimens.xs))
            Row(verticalAlignment = Alignment.CenterVertically) {
                UndalStatusPill(
                    text = when {
                        item.priceFrom -> "Set exact price"
                        !item.available -> "Sold out"
                        item.featured -> "Chef's pick"
                        item.isPopular -> "Popular"
                        else -> "On the menu"
                    },
                    containerColour = when {
                        !item.available -> MaterialTheme.undalSemantics.closedContainer
                        item.featured -> MaterialTheme.colorScheme.secondaryContainer
                        item.isPopular -> MaterialTheme.undalSemantics.successContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                    contentColour = when {
                        !item.available -> MaterialTheme.undalSemantics.onClosedContainer
                        item.featured -> MaterialTheme.colorScheme.onSecondaryContainer
                        item.isPopular -> MaterialTheme.undalSemantics.onSuccessContainer
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Spacer(Modifier.width(UndalDimens.xs))
                Text(
                    text = "${item.orderCount} ordered",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                UndalTextButton(
                    text = if (item.featured) "Unfeature" else "Feature",
                    onClick = onToggleFeatured,
                    icon = Icons.Filled.Star
                )
                UndalTextButton(text = "Edit", onClick = onEdit, icon = Icons.Filled.Edit)
                UndalTextButton(text = "Delete", onClick = onDelete, icon = Icons.Filled.Delete)
            }
        }
    }
}

// ---------------------------------------------------------------------- sections

@Composable
private fun SectionsTab(
    state: VendorMenuViewModel.State,
    viewModel: VendorMenuViewModel
) {
    val categories = state.menu.categories.sortedBy { it.order }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.padding(horizontal = UndalDimens.screenPadding),
            horizontalArrangement = Arrangement.spacedBy(UndalDimens.xs)
        ) {
            UndalSecondaryButton(
                text = "New section",
                onClick = viewModel::startNewSection,
                icon = Icons.Filled.Add,
                modifier = Modifier.weight(1f)
            )
            UndalSecondaryButton(
                text = "New dish",
                onClick = viewModel::startNewDish,
                icon = Icons.Filled.Add,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(UndalDimens.xs))
        Text(
            text = "Hold the handle and drag a section to change the order customers see.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = UndalDimens.screenPadding)
        )
        Spacer(Modifier.height(UndalDimens.xs))
        if (categories.isEmpty()) {
            VendorEmptyState(
                icon = Icons.Filled.List,
                title = "No sections yet",
                message = "Sections are how customers browse: Breakfast, Rice, Curries, Drinks. " +
                    "A dish can live in one.",
                actionLabel = "New section",
                onAction = viewModel::startNewSection
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = UndalDimens.screenPadding,
                    end = UndalDimens.screenPadding,
                    bottom = UndalDimens.xxl
                ),
                verticalArrangement = Arrangement.spacedBy(UndalDimens.xs)
            ) {
                items(categories, key = { it.id }) { category ->
                    val index = categories.indexOfFirst { it.id == category.id }
                    SectionCard(
                        title = category.name,
                        subtitle = "${viewModel.countFor(category.id)} dish(es) · order ${category.order}",
                        active = category.active,
                        iconUrl = category.iconUrl,
                        onDrag = { delta -> onSectionDrag(index, delta, categories.size, viewModel) },
                        onEdit = { viewModel.editSection(category) },
                        onDelete = { viewModel.askDeleteSection(category) },
                        extra = {
                            Spacer(Modifier.height(UndalDimens.xs))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(UndalDimens.xs)) {
                                UndalTextButton(
                                    text = "Add subcategory",
                                    onClick = { viewModel.startNewSubcategory(category.id) }
                                )
                            }
                            viewModel.subcategoriesOf(category.id).forEach { sub ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Filled.Menu,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(UndalDimens.iconSm)
                                    )
                                    Spacer(Modifier.width(UndalDimens.xs))
                                    Text(
                                        text = "${sub.name} · order ${sub.order}",
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f)
                                    )
                                    UndalTextButton(
                                        text = "Edit",
                                        onClick = { viewModel.editSubcategory(sub) }
                                    )
                                    UndalTextButton(
                                        text = "Delete",
                                        onClick = { viewModel.askDeleteSubcategory(sub) }
                                    )
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

/** One full row of travel moves the row one place — the drop writes the order field. */
private fun onSectionDrag(
    index: Int,
    accumulated: Float,
    total: Int,
    viewModel: VendorMenuViewModel
) {
    val rowPx = 56f
    when {
        accumulated > rowPx && index < total - 1 -> viewModel.onDragSwap(index, index + 1, isCategory = true)
        accumulated < -rowPx && index > 0 -> viewModel.onDragSwap(index, index - 1, isCategory = true)
        else -> Unit
    }
}

@Composable
private fun SectionCard(
    title: String,
    subtitle: String,
    active: Boolean,
    iconUrl: String,
    onDrag: (Float) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    extra: @Composable () -> Unit = {}
) {
    val density = LocalDensity.current
    val rowHeightPx = with(density) { 56.dp.toPx() }
    var travelled by remember { mutableFloatStateOf(0f) }

    UndalCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(UndalDimens.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Menu,
                    contentDescription = "Drag to reorder",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(UndalDimens.iconMd)
                        .pointerInput(title, rowHeightPx) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { travelled = 0f },
                                onDragEnd = { travelled = 0f },
                                onDragCancel = { travelled = 0f },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    travelled += dragAmount.y
                                    if (travelled > rowHeightPx || travelled < -rowHeightPx) {
                                        onDrag(travelled)
                                        travelled = 0f
                                    }
                                }
                            )
                        }
                )
                Spacer(Modifier.width(UndalDimens.xs))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = title, style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                UndalStatusPill(
                    text = if (active) "Active" else "Hidden",
                    containerColour = if (active) {
                        MaterialTheme.undalSemantics.successContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    contentColour = if (active) {
                        MaterialTheme.undalSemantics.onSuccessContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            if (iconUrl.isNotBlank()) {
                Text(
                    text = "Image: ${iconUrl.substringBeforeLast('/').substringAfterLast('/')}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(UndalDimens.xs))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(UndalDimens.xs)) {
                UndalTextButton(text = "Edit", onClick = onEdit, icon = Icons.Filled.Edit)
                UndalTextButton(text = "Delete", onClick = onDelete, icon = Icons.Filled.Delete)
            }
            extra()
        }
    }
}

// ------------------------------------------------------------------------ combos

@Composable
private fun CombosTab(
    state: VendorMenuViewModel.State,
    viewModel: VendorMenuViewModel
) {
    Column(modifier = Modifier.fillMaxSize()) {
        UndalSecondaryButton(
            text = "New combo",
            onClick = viewModel::startNewCombo,
            icon = Icons.Filled.Add,
            modifier = Modifier.padding(horizontal = UndalDimens.screenPadding)
        )
        Spacer(Modifier.height(UndalDimens.xs))
        if (state.menu.combos.isEmpty()) {
            VendorEmptyState(
                icon = Icons.Filled.ShoppingCart,
                title = "No combos yet",
                message = "A combo is two or more dishes at one price: rice, dal, bhorta and a drink for " +
                    "less than the sum of its parts.",
                actionLabel = "New combo",
                onAction = viewModel::startNewCombo
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = UndalDimens.screenPadding,
                    end = UndalDimens.screenPadding,
                    bottom = UndalDimens.xxl
                ),
                verticalArrangement = Arrangement.spacedBy(UndalDimens.xs)
            ) {
                items(state.menu.combos, key = { it.id }) { combo ->
                    UndalCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(UndalDimens.sm)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = combo.name, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        text = "${combo.itemRefs.size} dish(es) · " +
                                            Money.format(combo.amountPaisa) +
                                            " instead of " + Money.format(combo.regularAmountPaisa),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = combo.available,
                                    onCheckedChange = { viewModel.toggleCombo(combo) }
                                )
                            }
                            if (combo.savingsLabel.isNotBlank()) {
                                Spacer(Modifier.height(UndalDimens.xs))
                                UndalStatusPill(
                                    text = combo.savingsLabel,
                                    containerColour = MaterialTheme.undalSemantics.successContainer,
                                    contentColour = MaterialTheme.undalSemantics.onSuccessContainer
                                )
                            }
                            Spacer(Modifier.height(UndalDimens.xs))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(UndalDimens.xs)) {
                                UndalTextButton(
                                    text = "Edit",
                                    onClick = { viewModel.editCombo(combo) },
                                    icon = Icons.Filled.Edit
                                )
                                UndalTextButton(
                                    text = "Delete",
                                    onClick = { viewModel.askDeleteCombo(combo) },
                                    icon = Icons.Filled.Delete
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------ offers

@Composable
private fun OffersTab(
    state: VendorMenuViewModel.State,
    viewModel: VendorMenuViewModel
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = UndalDimens.screenPadding,
            end = UndalDimens.screenPadding,
            bottom = UndalDimens.xxl
        ),
        verticalArrangement = Arrangement.spacedBy(UndalDimens.xs)
    ) {
        item {
            VendorSection(
                title = "Coupons and discounts",
                subtitle = "Percent, fixed taka or free delivery — with a cap, a daily window, " +
                    "chosen days and an expiry. Turn auto-apply on and every basket gets it " +
                    "without anyone typing a code."
            ) {
                Column {
                    UndalSecondaryButton(
                        text = "New coupon",
                        onClick = viewModel::startNewCoupon,
                        icon = Icons.Filled.Add
                    )
                    if (state.coupons.isEmpty()) {
                        Text(
                            text = "No coupon yet. A first-time code like WELCOME10 is a good start.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    state.coupons.forEach { coupon ->
                        Spacer(Modifier.height(UndalDimens.xs))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = coupon.code, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        // The headline is written by the shared coupon rule, so
                                        // what the kitchen reads here is what a customer reads on
                                        // the web store banner.
                                        text = "${coupon.headline} · ${coupon.usedLabel}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    // The switch says what the owner asked for; this line says
                                    // what a customer will actually get, with the date in it. A
                                    // coupon that is switched on and dated out is the one state
                                    // the switch alone cannot show, and it is the state that ends
                                    // in an argument at the checkout.
                                    Text(
                                        text = coupon.stateLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (coupon.onButDead) {
                                            MaterialTheme.colorScheme.error
                                        } else if (coupon.active) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                    if (coupon.conditionLabel.isNotBlank()) {
                                        Text(
                                            text = coupon.conditionLabel,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Text(
                                        text = coupon.reachLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (coupon.autoApply) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                }
                                Switch(
                                    checked = coupon.active,
                                    onCheckedChange = { viewModel.toggleCoupon(coupon) }
                                )
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(UndalDimens.xs)) {
                                UndalTextButton(
                                    text = "Edit",
                                    onClick = { viewModel.editCoupon(coupon) },
                                    icon = Icons.Filled.Edit
                                )
                                UndalTextButton(
                                    text = "Delete",
                                    onClick = { viewModel.askDeleteCoupon(coupon) },
                                    icon = Icons.Filled.Delete
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            VendorSection(
                title = "Campaigns",
                subtitle = "Banner offers with a start and an end. Percent up to 90, or a fixed amount."
            ) {
                Column {
                    UndalSecondaryButton(
                        text = "New campaign",
                        onClick = viewModel::startNewCampaign,
                        icon = Icons.Filled.Add
                    )
                    if (state.campaigns.isEmpty()) {
                        Text(
                            text = "No campaign yet. A Friday rice-offer banner is a good first one.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    state.campaigns.forEach { campaign ->
                        Spacer(Modifier.height(UndalDimens.xs))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = campaign.title, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        text = campaign.subtitle.ifBlank { campaign.type },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                UndalStatusPill(
                                    text = when {
                                        !campaign.active -> "Off"
                                        campaign.isLive() -> "Live"
                                        else -> "Scheduled"
                                    },
                                    containerColour = when {
                                        !campaign.active -> MaterialTheme.colorScheme.surfaceVariant
                                        campaign.isLive() -> MaterialTheme.undalSemantics.successContainer
                                        else -> MaterialTheme.undalSemantics.warningContainer
                                    },
                                    contentColour = when {
                                        !campaign.active -> MaterialTheme.colorScheme.onSurfaceVariant
                                        campaign.isLive() -> MaterialTheme.undalSemantics.onSuccessContainer
                                        else -> MaterialTheme.undalSemantics.onWarningContainer
                                    }
                                )
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(UndalDimens.xs)) {
                                UndalTextButton(
                                    text = "Edit",
                                    onClick = { viewModel.editCampaign(campaign) },
                                    icon = Icons.Filled.Edit
                                )
                                UndalTextButton(
                                    text = if (campaign.active) "Switch off" else "Switch on",
                                    onClick = { viewModel.toggleCampaign(campaign) }
                                )
                                UndalTextButton(
                                    text = "Delete",
                                    onClick = { viewModel.askDeleteCampaign(campaign) },
                                    icon = Icons.Filled.Delete
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            UndalCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(UndalDimens.sm)
                ) {
                    Icon(
                        Icons.Filled.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(UndalDimens.iconMd)
                    )
                    Spacer(Modifier.width(UndalDimens.xs))
                    Text(
                        text = "Saved changes are shared with the customer menu when connected. Preview the menu " +
                            "to check your changes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
