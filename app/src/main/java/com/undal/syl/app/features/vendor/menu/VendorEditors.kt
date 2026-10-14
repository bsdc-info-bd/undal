package com.undal.syl.app.features.vendor.menu

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.undal.syl.app.core.design.UndalAlertBanner
import com.undal.syl.app.core.design.UndalPrimaryButton
import com.undal.syl.app.core.design.UndalSecondaryButton
import com.undal.syl.app.core.design.UndalStatusPill
import com.undal.syl.app.core.design.UndalTextField
import com.undal.syl.app.core.design.UndalTextButton
import com.undal.syl.app.core.model.CampaignDraft
import com.undal.syl.app.core.model.ComboDraft
import com.undal.syl.app.core.model.CouponDraft
import com.undal.syl.app.core.model.MenuDraft
import com.undal.syl.app.core.theme.UndalDimens
import com.undal.syl.app.core.theme.undalSemantics
import com.undal.syl.app.core.util.Money
import com.undal.syl.app.core.util.TimeDhaka
import com.undal.syl.app.features.vendor.VendorChip
import java.time.Instant
import java.time.LocalDate

/**
 * The five editors behind the menu manager (V-052 … V-065).
 *
 * Each one is a dialog rather than a new screen, so the manager's live list stays
 * visible behind it and a save lands back on the list — no navigation, no lost
 * scroll position, no half-saved document.
 */

private val SPICE_LABELS = listOf("Mild", "Medium", "Hot", "Extra hot")
private val PREP_CHOICES = listOf(10, 15, 20, 25, 30, 45)

@Composable
private fun EditorDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmLabel: String,
    busy: Boolean,
    confirmEnabled: Boolean,
    onConfirm: () -> Unit,
    content: @Composable () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                content()
            }
        },
        confirmButton = {
            UndalPrimaryButton(
                text = confirmLabel,
                onClick = onConfirm,
                enabled = confirmEnabled,
                loading = busy
            )
        },
        dismissButton = {
            UndalTextButton(text = "Close", onClick = onDismiss)
        }
    )
}

/** Photo pick → Cloudinary, with the progress the owner needs on mobile data. */
@Composable
internal fun VendorPhotoRow(
    currentUrl: String,
    progress: Float?,
    label: String,
    onPicked: (Uri) -> Unit
) {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) onPicked(uri)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(
            model = currentUrl.ifBlank { null },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(UndalDimens.radiusSm))
        )
        Spacer(Modifier.width(UndalDimens.sm))
        Column(modifier = Modifier.weight(1f)) {
            UndalSecondaryButton(
                text = if (currentUrl.isBlank()) "Pick a photo" else "Replace photo",
                onClick = {
                    launcher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }
            )
            if (progress != null) {
                Spacer(Modifier.height(UndalDimens.xs))
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "$label uploading… ${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (currentUrl.isNotBlank()) {
                Text(
                    text = "$label is set.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** A date field backed by the platform picker; stored as epoch millis. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VendorDateField(
    label: String,
    millis: Long?,
    onPicked: (Long) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val shown = millis?.let { TimeDhaka.formatDate(TimeDhaka.fromInstant(Instant.ofEpochMilli(it)).toLocalDate()) }
        .orEmpty()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = shown.ifBlank { "not set" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        UndalTextButton(text = if (shown.isBlank()) "Pick" else "Change", onClick = { open = true })
    }
    if (open) {
        val pickerState = androidx.compose.material3.rememberDatePickerState(
            initialSelectedDateMillis = millis
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { picked ->
                            // Firestore dates are Dhaka dates: noon keeps the day stable.
                            onPicked(picked)
                        }
                        open = false
                    }
                ) { Text("Use this date") }
            },
            dismissButton = {
                TextButton(onClick = { open = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = pickerState)
        }
    }
}

private fun LocalDate.endOfDayMillis(): Long =
    atStartOfDay(TimeDhaka.zone).plusDays(1).minusSeconds(1).toInstant().toEpochMilli()

// -------------------------------------------------------------------------- dish

@Composable
fun DishEditorSheet(
    draft: MenuDraft,
    state: VendorMenuViewModel.State,
    viewModel: VendorMenuViewModel
) {
    val categories = state.menu.categories.sortedBy { it.order }
    val subcategories = state.menu.subcategories
        .filter { it.categoryId == draft.categoryId }
        .sortedBy { it.order }

    EditorDialog(
        title = if (draft.isNew) "New dish" else "Edit ${draft.name.ifBlank { "dish" }}",
        onDismiss = viewModel::requestCloseEditor,
        confirmLabel = if (draft.isNew) "Put it on the menu" else "Save changes",
        busy = state.busy,
        confirmEnabled = !state.busy && state.uploadProgress == null,
        onConfirm = viewModel::saveDish
    ) {
        UndalTextField(
            value = draft.name,
            onValueChange = { viewModel.onItemDraftChange(draft.copy(name = it)) },
            label = "Dish name",
            hint = "Chicken bhuna",
            error = draft.nameError,
            singleLine = true
        )
        Spacer(Modifier.height(UndalDimens.xs))
        UndalTextField(
            value = draft.description,
            onValueChange = { viewModel.onItemDraftChange(draft.copy(description = it)) },
            label = "What is in it",
            hint = "Slow-cooked chicken with onion, ginger and mustard oil.",
            maxLines = 3
        )
        Spacer(Modifier.height(UndalDimens.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(UndalDimens.xs)) {
            UndalTextField(
                value = draft.priceText,
                onValueChange = { viewModel.onItemDraftChange(draft.copy(priceText = it)) },
                label = "Price (taka)",
                hint = "180",
                error = draft.priceError,
                keyboardType = KeyboardType.Number,
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            UndalTextField(
                value = draft.compareText,
                onValueChange = { viewModel.onItemDraftChange(draft.copy(compareText = it)) },
                label = "Was (optional)",
                hint = "220",
                error = draft.compareError,
                keyboardType = KeyboardType.Number,
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }
        if (draft.discountAmountPaisa > 0L) {
            Spacer(Modifier.height(UndalDimens.xs))
            UndalStatusPill(
                text = "Customers save ${Money.format(draft.discountAmountPaisa)}",
                containerColour = MaterialTheme.undalSemantics.successContainer,
                contentColour = MaterialTheme.undalSemantics.onSuccessContainer
            )
        }
        Spacer(Modifier.height(UndalDimens.xs))
        VendorPhotoRow(
            currentUrl = draft.imageUrl,
            progress = state.uploadProgress,
            label = "Dish photo",
            onPicked = viewModel::uploadDishImage
        )
        Spacer(Modifier.height(UndalDimens.xs))
        Text(text = "Section", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(UndalDimens.xs)) {
            categories.forEach { category ->
                VendorChip(
                    label = category.name,
                    selected = draft.categoryId == category.id,
                    onClick = {
                        viewModel.onItemDraftChange(draft.copy(categoryId = category.id, subcategoryId = ""))
                    }
                )
            }
        }
        if (subcategories.isNotEmpty()) {
            Spacer(Modifier.height(UndalDimens.xs))
            Text(text = "Subcategory", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(UndalDimens.xs)) {
                subcategories.forEach { sub ->
                    VendorChip(
                        label = sub.name,
                        selected = draft.subcategoryId == sub.id,
                        onClick = {
                            viewModel.onItemDraftChange(
                                draft.copy(subcategoryId = if (draft.subcategoryId == sub.id) "" else sub.id)
                            )
                        }
                    )
                }
            }
        }
        Spacer(Modifier.height(UndalDimens.xs))
        Text(text = "Spice level", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(UndalDimens.xs)) {
            SPICE_LABELS.forEachIndexed { level, spiceLabel ->
                VendorChip(
                    label = spiceLabel,
                    selected = draft.spiceLevel == level,
                    onClick = { viewModel.onItemDraftChange(draft.copy(spiceLevel = level)) }
                )
            }
        }
        Spacer(Modifier.height(UndalDimens.xs))
        Text(text = "Prep time", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(UndalDimens.xs)) {
            PREP_CHOICES.forEach { minutes ->
                VendorChip(
                    label = "$minutes min",
                    selected = draft.prepMinutes == minutes,
                    onClick = { viewModel.onItemDraftChange(draft.copy(prepMinutes = minutes)) }
                )
            }
        }
        Spacer(Modifier.height(UndalDimens.xs))
        UndalTextField(
            value = draft.tagsText,
            onValueChange = { viewModel.onItemDraftChange(draft.copy(tagsText = it)) },
            label = "Tags (comma separated)",
            hint = "bhorta, spicy, rice",
            singleLine = true
        )
        Spacer(Modifier.height(UndalDimens.xs))
        SwitchRow(
            label = "Vegetarian",
            detail = "Shows the green dot beside the dish.",
            checked = draft.veg,
            onChange = { viewModel.onItemDraftChange(draft.copy(veg = it)) }
        )
        SwitchRow(
            label = "Available today",
            detail = "Switch off instead of deleting when the kitchen runs out.",
            checked = draft.available,
            onChange = { viewModel.onItemDraftChange(draft.copy(available = it)) }
        )
        SwitchRow(
            label = "Chef's pick",
            detail = "Pins the dish to the top of the home screen.",
            checked = draft.featured,
            onChange = { viewModel.onItemDraftChange(draft.copy(featured = it)) }
        )
        if (!draft.isValid) {
            Spacer(Modifier.height(UndalDimens.xs))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = null,
                    tint = MaterialTheme.undalSemantics.warning,
                    modifier = Modifier.size(UndalDimens.iconSm)
                )
                Spacer(Modifier.width(UndalDimens.xs))
                Text(
                    text = draft.nameError ?: draft.priceError ?: draft.compareError ?: draft.categoryError.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.undalSemantics.warning
                )
            }
        }
    }
}

// ----------------------------------------------------------------------- section

@Composable
fun SectionEditorSheet(
    draft: VendorMenuViewModel.SectionDraft,
    state: VendorMenuViewModel.State,
    viewModel: VendorMenuViewModel
) {
    EditorDialog(
        title = when {
            draft.isCategory && draft.id == null -> "New section"
            draft.isCategory -> "Edit ${draft.name}"
            draft.id == null -> "New subcategory"
            else -> "Edit ${draft.name}"
        },
        onDismiss = viewModel::closeSectionEditor,
        confirmLabel = "Save",
        busy = state.busy,
        confirmEnabled = !state.busy && draft.error == null,
        onConfirm = viewModel::saveSection
    ) {
        UndalTextField(
            value = draft.name,
            onValueChange = { viewModel.onSectionDraftChange(draft.copy(name = it)) },
            label = if (draft.isCategory) "Section name" else "Subcategory name",
            hint = if (draft.isCategory) "Rice" else "Polao",
            singleLine = true
        )
        Spacer(Modifier.height(UndalDimens.xs))
        UndalTextField(
            value = draft.orderText,
            onValueChange = { viewModel.onSectionDraftChange(draft.copy(orderText = it.filter { ch -> ch.isDigit() }.take(3))) },
            label = "Position",
            hint = "0 comes first",
            keyboardType = KeyboardType.Number,
            singleLine = true
        )
        if (draft.isCategory) {
            Spacer(Modifier.height(UndalDimens.xs))
            VendorPhotoRow(
                currentUrl = draft.iconUrl,
                progress = state.uploadProgress,
                label = "Section image",
                onPicked = viewModel::uploadSectionIcon
            )
        } else {
            Spacer(Modifier.height(UndalDimens.xs))
            Text(text = "Belongs to", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(UndalDimens.xs)) {
                state.menu.categories.sortedBy { it.order }.forEach { category ->
                    VendorChip(
                        label = category.name,
                        selected = draft.categoryId == category.id,
                        onClick = { viewModel.onSectionDraftChange(draft.copy(categoryId = category.id)) }
                    )
                }
            }
        }
        Spacer(Modifier.height(UndalDimens.xs))
        SwitchRow(
            label = "Visible to customers",
            detail = "A hidden section keeps its dishes but leaves the store page.",
            checked = draft.active,
            onChange = { viewModel.onSectionDraftChange(draft.copy(active = it)) }
        )
        if (draft.error != null) {
            Spacer(Modifier.height(UndalDimens.xs))
            Text(
                text = draft.error.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.undalSemantics.warning
            )
        }
    }
}

// -------------------------------------------------------------------------- combo

@Composable
fun ComboEditorSheet(
    draft: ComboDraft,
    state: VendorMenuViewModel.State,
    viewModel: VendorMenuViewModel
) {
    EditorDialog(
        title = if (draft.id == null) "New combo" else "Edit ${draft.name}",
        onDismiss = viewModel::closeComboEditor,
        confirmLabel = if (draft.id == null) "Create the combo" else "Save the combo",
        busy = state.busy,
        confirmEnabled = !state.busy && state.uploadProgress == null,
        onConfirm = viewModel::saveCombo
    ) {
        UndalTextField(
            value = draft.name,
            onValueChange = { viewModel.onComboDraftChange(draft.copy(name = it)) },
            label = "Combo name",
            hint = "Rice, dal and bhorta for two",
            singleLine = true
        )
        Spacer(Modifier.height(UndalDimens.xs))
        UndalTextField(
            value = draft.description,
            onValueChange = { viewModel.onComboDraftChange(draft.copy(description = it)) },
            label = "What the customer gets",
            hint = "Two plates of rice, dal, aloo bhorta and a boiled egg each.",
            maxLines = 3
        )
        Spacer(Modifier.height(UndalDimens.xs))
        UndalTextField(
            value = draft.priceText,
            onValueChange = { viewModel.onComboDraftChange(draft.copy(priceText = it)) },
            label = "Combo price (taka)",
            hint = Money.formatPlain(draft.regularPaisa),
            keyboardType = KeyboardType.Number,
            singleLine = true
        )
        Spacer(Modifier.height(UndalDimens.xs))
        VendorPhotoRow(
            currentUrl = draft.imageUrl,
            progress = state.uploadProgress,
            label = "Combo photo",
            onPicked = viewModel::uploadComboImage
        )
        Spacer(Modifier.height(UndalDimens.xs))
        Text(
            text = "Dishes in this combo (at least two)",
            style = MaterialTheme.typography.labelLarge
        )
        if (draft.refs.isEmpty()) {
            Text(
                text = "Nothing added yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        draft.refs.forEach { ref ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = ref.name, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "${Money.format(ref.unitAmountPaisa)} each",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                UndalTextButton(text = "−", onClick = { viewModel.changeComboQty(ref.menuItemId, -1) })
                Text(text = "${ref.qty}", style = MaterialTheme.typography.titleSmall)
                UndalTextButton(text = "+", onClick = { viewModel.changeComboQty(ref.menuItemId, 1) })
                UndalTextButton(
                    text = "Remove",
                    onClick = { viewModel.removeComboRef(ref.menuItemId) },
                    icon = Icons.Filled.Delete
                )
            }
        }
        Spacer(Modifier.height(UndalDimens.xs))
        Text(text = "Add a dish", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(UndalDimens.xs)) {
            state.menu.items
                .filter { item -> draft.refs.none { it.menuItemId == item.id } }
                .take(12)
                .forEach { item ->
                    VendorChip(
                        label = item.name,
                        selected = false,
                        onClick = { viewModel.addComboRef(item) }
                    )
                }
        }
        Spacer(Modifier.height(UndalDimens.xs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Star,
                contentDescription = null,
                tint = MaterialTheme.undalSemantics.success,
                modifier = Modifier.size(UndalDimens.iconSm)
            )
            Spacer(Modifier.width(UndalDimens.xs))
            Text(
                text = "Regular price ${Money.format(draft.regularPaisa)} · " +
                    if (draft.savingsPaisa > 0L) {
                        "customer saves ${Money.format(draft.savingsPaisa)}"
                    } else {
                        "no saving yet"
                    },
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (draft.error != null) {
            Spacer(Modifier.height(UndalDimens.xs))
            Text(
                text = draft.error.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.undalSemantics.warning
            )
        }
        Spacer(Modifier.height(UndalDimens.xs))
        SwitchRow(
            label = "Available",
            detail = "Switch off to keep it out of the store without deleting it.",
            checked = draft.available,
            onChange = { viewModel.onComboDraftChange(draft.copy(available = it)) }
        )
    }
}

// ----------------------------------------------------------------------- campaign

@Composable
fun CampaignEditorSheet(
    draft: CampaignDraft,
    state: VendorMenuViewModel.State,
    viewModel: VendorMenuViewModel
) {
    EditorDialog(
        title = if (draft.id == null) "New campaign" else "Edit campaign",
        onDismiss = viewModel::closeCampaignEditor,
        confirmLabel = "Save campaign",
        busy = state.busy,
        confirmEnabled = !state.busy && state.uploadProgress == null,
        onConfirm = viewModel::saveCampaign
    ) {
        UndalTextField(
            value = draft.title,
            onValueChange = { viewModel.onCampaignDraftChange(draft.copy(title = it)) },
            label = "Banner headline",
            hint = "Friday rice offer",
            singleLine = true
        )
        Spacer(Modifier.height(UndalDimens.xs))
        UndalTextField(
            value = draft.subtitle,
            onValueChange = { viewModel.onCampaignDraftChange(draft.copy(subtitle = it)) },
            label = "Supporting line",
            hint = "10% off every rice dish until Friday midnight.",
            maxLines = 2
        )
        Spacer(Modifier.height(UndalDimens.xs))
        Text(text = "Discount type", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(UndalDimens.xs)) {
            VendorChip(
                label = "Percent",
                selected = draft.type == "percent",
                onClick = { viewModel.onCampaignDraftChange(draft.copy(type = "percent")) }
            )
            VendorChip(
                label = "Fixed taka",
                selected = draft.type == "fixed",
                onClick = { viewModel.onCampaignDraftChange(draft.copy(type = "fixed")) }
            )
        }
        Spacer(Modifier.height(UndalDimens.xs))
        UndalTextField(
            value = draft.valueText,
            onValueChange = { viewModel.onCampaignDraftChange(draft.copy(valueText = it)) },
            label = if (draft.type == "percent") "Percent off (1–90)" else "Taka off",
            hint = if (draft.type == "percent") "10" else "50",
            keyboardType = KeyboardType.Number,
            singleLine = true
        )
        Spacer(Modifier.height(UndalDimens.xs))
        VendorDateField(
            label = "Starts",
            millis = draft.startAtMillis,
            onPicked = { picked ->
                viewModel.onCampaignDraftChange(
                    draft.copy(startAtMillis = LocalDate.ofEpochDay(picked / 86_400_000L).atStartOfDay(TimeDhaka.zone).toInstant().toEpochMilli())
                )
            }
        )
        VendorDateField(
            label = "Ends",
            millis = draft.endAtMillis,
            onPicked = { picked ->
                viewModel.onCampaignDraftChange(
                    draft.copy(endAtMillis = LocalDate.ofEpochDay(picked / 86_400_000L).endOfDayMillis())
                )
            }
        )
        Spacer(Modifier.height(UndalDimens.xs))
        VendorPhotoRow(
            currentUrl = draft.bannerUrl,
            progress = state.uploadProgress,
            label = "Banner",
            onPicked = viewModel::uploadCampaignBanner
        )
        Spacer(Modifier.height(UndalDimens.xs))
        UndalTextField(
            value = draft.code,
            onValueChange = { viewModel.onCampaignDraftChange(draft.copy(code = it.uppercase())) },
            label = "Coupon code shown on the banner (optional)",
            hint = "FRIYAY10",
            singleLine = true
        )
        Spacer(Modifier.height(UndalDimens.xs))
        SwitchRow(
            label = "Active",
            detail = "An inactive campaign stays on this list and nowhere else.",
            checked = draft.active,
            onChange = { viewModel.onCampaignDraftChange(draft.copy(active = it)) }
        )
        if (draft.error != null) {
            Spacer(Modifier.height(UndalDimens.xs))
            Text(
                text = draft.error.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.undalSemantics.warning
            )
        }
        Spacer(Modifier.height(UndalDimens.xs))
        Text(
            text = "The customer app validates the same limits: a percent campaign cannot exceed 90, " +
                "and the end must be after the start.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ------------------------------------------------------------------------- coupon

@Composable
fun CouponEditorSheet(
    draft: CouponDraft,
    state: VendorMenuViewModel.State,
    viewModel: VendorMenuViewModel
) {
    EditorDialog(
        title = if (draft.originalCode == null) "New coupon" else "Edit ${draft.originalCode}",
        onDismiss = viewModel::closeCouponEditor,
        confirmLabel = "Save coupon",
        busy = state.busy,
        confirmEnabled = !state.busy,
        onConfirm = viewModel::saveCoupon
    ) {
        UndalTextField(
            value = draft.code,
            onValueChange = { viewModel.onCouponDraftChange(draft.copy(code = it.uppercase())) },
            label = "Code",
            hint = "WELCOME10",
            error = draft.codeError,
            singleLine = true
        )
        Spacer(Modifier.height(UndalDimens.xs))
        Text(text = "Type", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(UndalDimens.xs)) {
            VendorChip(
                label = "Percent",
                selected = draft.type == "percent",
                onClick = { viewModel.onCouponDraftChange(draft.copy(type = "percent")) }
            )
            VendorChip(
                label = "Fixed taka",
                selected = draft.type == "fixed",
                onClick = { viewModel.onCouponDraftChange(draft.copy(type = "fixed")) }
            )
            VendorChip(
                label = "Free delivery",
                selected = draft.type == "freedelivery",
                onClick = { viewModel.onCouponDraftChange(draft.copy(type = "freedelivery")) }
            )
        }
        if (draft.type != "freedelivery") {
            Spacer(Modifier.height(UndalDimens.xs))
            UndalTextField(
                value = draft.valueText,
                onValueChange = { viewModel.onCouponDraftChange(draft.copy(valueText = it)) },
                label = if (draft.type == "percent") "Percent off (1–90)" else "Taka off",
                hint = if (draft.type == "percent") "10" else "50",
                error = draft.valueError,
                keyboardType = KeyboardType.Number,
                singleLine = true
            )
        } else {
            Spacer(Modifier.height(UndalDimens.xs))
            Text(
                text = "The kitchen pays the whole ride — the single most effective coupon when " +
                    "delivery is charged by the kilometre. Cap it below to stop a far-away order " +
                    "from eating the margin.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(UndalDimens.xs))
        UndalTextField(
            value = draft.maxDiscountText,
            onValueChange = { viewModel.onCouponDraftChange(draft.copy(maxDiscountText = it)) },
            label = "Maximum discount (optional)",
            hint = "100",
            error = draft.maxDiscountError,
            keyboardType = KeyboardType.Number,
            singleLine = true
        )
        Spacer(Modifier.height(UndalDimens.xs))
        // The same rule the customer's cart runs: what this coupon is worth on a
        // normal basket, said in money, before it is published.
        UndalAlertBanner(
            text = draft.preview(),
            icon = Icons.Filled.Star
        )
        Spacer(Modifier.height(UndalDimens.xs))
        UndalTextField(
            value = draft.minOrderText,
            onValueChange = { viewModel.onCouponDraftChange(draft.copy(minOrderText = it)) },
            label = "Minimum basket (optional)",
            hint = "500",
            keyboardType = KeyboardType.Number,
            singleLine = true
        )
        Spacer(Modifier.height(UndalDimens.xs))
        UndalTextField(
            value = draft.usageLimitText,
            onValueChange = { viewModel.onCouponDraftChange(draft.copy(usageLimitText = it.filter { ch -> ch.isDigit() }.take(5))) },
            label = "Usage limit (0 means unlimited)",
            hint = "50",
            keyboardType = KeyboardType.Number,
            singleLine = true
        )
        Spacer(Modifier.height(UndalDimens.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(UndalDimens.xs)) {
            Box(modifier = Modifier.weight(1f)) {
                UndalTextField(
                    value = draft.startsAt,
                    onValueChange = { viewModel.onCouponDraftChange(draft.copy(startsAt = it)) },
                    label = "From (HH:MM)",
                    hint = "18:00",
                    error = if (draft.windowError != null && draft.startsAt.isNotBlank()) draft.windowError else null,
                    singleLine = true
                )
            }
            Box(modifier = Modifier.weight(1f)) {
                UndalTextField(
                    value = draft.endsAt,
                    onValueChange = { viewModel.onCouponDraftChange(draft.copy(endsAt = it)) },
                    label = "To (HH:MM)",
                    hint = "22:00",
                    error = if (draft.windowError != null && draft.endsAt.isNotBlank()) draft.windowError else null,
                    singleLine = true
                )
            }
        }
        Text(
            text = "Leave both blank for all day. A window that ends before it starts is a " +
                "late-night offer — 20:00 to 01:00 works.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(UndalDimens.xs))
        Text(text = "Days", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(UndalDimens.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(UndalDimens.xs)) {
            listOf("fri", "sat", "sun").forEach { day ->
                VendorChip(
                    label = day.uppercase(),
                    selected = day in draft.days,
                    onClick = {
                        val next = if (day in draft.days) draft.days - day else draft.days + day
                        viewModel.onCouponDraftChange(draft.copy(days = next))
                    }
                )
            }
        }
        Spacer(Modifier.height(UndalDimens.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(UndalDimens.xs)) {
            listOf("mon", "tue", "wed", "thu").forEach { day ->
                VendorChip(
                    label = day.uppercase(),
                    selected = day in draft.days,
                    onClick = {
                        val next = if (day in draft.days) draft.days - day else draft.days + day
                        viewModel.onCouponDraftChange(draft.copy(days = next))
                    }
                )
            }
        }
        Text(
            text = if (draft.days.isEmpty()) "Every day." else "Only on ${draft.days.sorted().joinToString(", ")}.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(UndalDimens.xs))
        VendorDateField(
            label = "Expires",
            millis = draft.expiresAtMillis,
            onPicked = { picked ->
                viewModel.onCouponDraftChange(
                    draft.copy(
                        expiresAtMillis = LocalDate.ofEpochDay(picked / 86_400_000L).endOfDayMillis()
                    )
                )
            }
        )
        Spacer(Modifier.height(UndalDimens.xs))
        UndalTextField(
            value = draft.description,
            onValueChange = { viewModel.onCouponDraftChange(draft.copy(description = it)) },
            label = "Note for your own records (optional)",
            hint = "First-order code, printed on the fridge.",
            singleLine = true
        )
        Spacer(Modifier.height(UndalDimens.xs))
        SwitchRow(
            label = "Apply automatically",
            detail = "On: every basket it can help gets it, with no code typed — the app and the web " +
                "store both do this. Off: a secret code that only works when a customer types it.",
            checked = draft.autoApply,
            onChange = { viewModel.onCouponDraftChange(draft.copy(autoApply = it)) }
        )
        Spacer(Modifier.height(UndalDimens.xs))
        SwitchRow(
            label = "Show on the web store",
            detail = "On: the coupon appears as a banner on your web page and in the cart. " +
                "Off: it exists but nobody is told about it.",
            checked = draft.showOnWeb,
            onChange = { viewModel.onCouponDraftChange(draft.copy(showOnWeb = it)) }
        )
        Spacer(Modifier.height(UndalDimens.xs))
        SwitchRow(
            label = "Active",
            detail = "Off means the customer sees \"this code is not active\" instead of a discount.",
            checked = draft.active,
            onChange = { viewModel.onCouponDraftChange(draft.copy(active = it)) }
        )
        if (draft.originalCode != null) {
            Spacer(Modifier.height(UndalDimens.xs))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(UndalDimens.iconSm)
                )
                Spacer(Modifier.width(UndalDimens.xs))
                Text(
                    text = "Editing keeps the used count. Renaming the code moves the coupon and retires the old one.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (state.coupons.isNotEmpty()) {
            Spacer(Modifier.height(UndalDimens.xs))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.undalSemantics.success,
                    modifier = Modifier.size(UndalDimens.iconSm)
                )
                Spacer(Modifier.width(UndalDimens.xs))
                Text(
                    text = "A used coupon cannot be deleted — switch it off and the order history stays honest.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(UndalDimens.xs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Close,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(UndalDimens.iconSm)
            )
            Spacer(Modifier.width(UndalDimens.xs))
            Text(
                text = "Codes are checked on the customer's device and again inside the placing transaction, " +
                    "so the same code cannot be spent twice at the same second.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(UndalDimens.xs))
        UndalTextButton(
            text = "Add another later",
            onClick = viewModel::closeCouponEditor,
            icon = Icons.Filled.Add
        )
    }
}
