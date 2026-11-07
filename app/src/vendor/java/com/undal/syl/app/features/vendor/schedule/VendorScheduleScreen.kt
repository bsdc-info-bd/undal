@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.undal.syl.app.features.vendor.schedule

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.undal.syl.app.core.design.UndalActionGroup
import com.undal.syl.app.core.design.UndalAlertBanner
import com.undal.syl.app.core.design.UndalCard
import com.undal.syl.app.core.design.UndalPrimaryButton
import com.undal.syl.app.core.design.UndalScaffold
import com.undal.syl.app.core.design.UndalSecondaryButton
import com.undal.syl.app.core.design.UndalStatusPill
import com.undal.syl.app.core.design.UndalTextField
import com.undal.syl.app.core.design.UndalTextButton
import com.undal.syl.app.core.theme.UndalDimens
import com.undal.syl.app.core.theme.undalSemantics
import com.undal.syl.app.core.util.TimeDhaka
import com.undal.syl.app.core.util.UndalDigits
import com.undal.syl.app.core.util.Validators
import com.undal.syl.app.features.vendor.VendorChip
import com.undal.syl.app.features.vendor.VendorSection
import com.undal.syl.app.features.vendor.menu.VendorDateField
import com.undal.syl.app.features.vendor.tick

/**
 * The schedule builder (V-071 … V-074, V-088 … V-090, V-093).
 *
 * Seven rows an owner can edit with a thumb, four templates for the days nobody
 * wants to type, and a parity line that says what a customer would read at 1 pm
 * if this exact week were saved.
 */
@Composable
fun VendorScheduleScreen(
    onBack: () -> Unit,
    viewModel: VendorScheduleViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    var addingSpecial by rememberSaveable { mutableStateOf(false) }
    var specialClosed by rememberSaveable { mutableStateOf(true) }
    var specialNote by rememberSaveable { mutableStateOf("") }
    var specialMillis by rememberSaveable { mutableStateOf(System.currentTimeMillis()) }

    UndalScaffold(
        title = "Opening hours",
        subtitle = state.statusLine.ifBlank { "Asia/Dhaka · the only clock this store uses" },
        onBack = onBack,
        contentMaxWidth = 760.dp,
        bottomBar = {
            UndalPrimaryButton(
                text = if (state.dirty) "Save schedule changes" else "Save schedule",
                onClick = viewModel::save, enabled = !state.busy, loading = state.busy,
                icon = Icons.Filled.Check,
                modifier = Modifier.padding(horizontal = UndalDimens.screenPadding, vertical = UndalDimens.sm)
            )
        },
        actions = {
            if (state.hasSnapshot) {
                UndalTextButton(
                    text = "Undo",
                    onClick = viewModel::undoLastSave,
                    icon = Icons.Filled.Refresh
                )
            }
        }
    ) { _ ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(UndalDimens.screenPadding),
            verticalArrangement = Arrangement.spacedBy(UndalDimens.sm)
        ) {
            item {
                UndalCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(UndalDimens.sm)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(UndalDimens.sm)) {
                            UndalStatusPill(
                                text = if (state.store.autoClosed) "Auto-closed" else "Schedule",
                                containerColour = if (state.store.autoClosed) {
                                    MaterialTheme.undalSemantics.warningContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                                contentColour = if (state.store.autoClosed) {
                                    MaterialTheme.undalSemantics.onWarningContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                            Spacer(Modifier.width(UndalDimens.xs))
                            Text(
                                text = if (state.dirty) "Unsaved changes" else "Everything saved",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (state.dirty) {
                                    MaterialTheme.undalSemantics.warning
                                } else {
                                    MaterialTheme.undalSemantics.success
                                }
                            )
                        }
                        Spacer(Modifier.height(UndalDimens.xs))
                        Text(
                            text = state.parityLine.ifBlank { "Reading the store status…" },
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(UndalDimens.xs))
                        Text(
                            text = viewModel.previewLine(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
                                text = "Hours are always Asia/Dhaka, and a closing time earlier than the " +
                                    "opening time means the kitchen serves past midnight.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (state.issues.isNotEmpty()) {
                item {
                    UndalAlertBanner(
                        text = state.issues.first() +
                            if (state.issues.size > 1) " (+${state.issues.size - 1} more warning(s))" else "",
                        icon = Icons.Filled.Warning
                    )
                }
            }

            item {
                VendorSection(
                    title = "Start from a template",
                    subtitle = "One tap fills the whole week; you can still edit any day afterwards."
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(UndalDimens.xs)) {
                        VendorScheduleViewModel.Template.entries.forEach { template ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = template.label, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        text = template.detail,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                UndalSecondaryButton(
                                    text = "Apply",
                                    onClick = {
                                        haptics.tick()
                                        viewModel.applyTemplate(template)
                                    }
                                )
                            }
                        }
                    }
                }
            }

            item {
                VendorSection(
                    title = "The week",
                    subtitle = "Leave a day empty to stay closed that day. Two windows are fine — " +
                        "lunch and dinner."
                ) {
                    Column {
                        viewModel.days.forEach { day ->
                            val slots = state.weekly[day.key].orEmpty()
                            Column(modifier = Modifier.padding(vertical = UndalDimens.xs)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = day.label,
                                        style = MaterialTheme.typography.titleSmall,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (day.key == viewModel.todayKey()) {
                                        UndalStatusPill(
                                            text = "Today",
                                            containerColour = MaterialTheme.colorScheme.primaryContainer,
                                            contentColour = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                    Spacer(Modifier.width(UndalDimens.xs))
                                    Text(
                                        text = if (slots.isEmpty()) "Closed" else "${slots.size} window(s)",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                slots.forEachIndexed { index, slot ->
                                    Column(verticalArrangement = Arrangement.spacedBy(UndalDimens.sm)) {
                                        UndalActionGroup(primary = {
                                            UndalTextField(
                                                value = slot.open,
                                                onValueChange = {
                                                    viewModel.onWeekdaySlot(day.key, index, UndalDigits.cleanClock(it), null)
                                                },
                                                label = "Opens",
                                                hint = "09:00",
                                                error = Validators.clockTimeError("opening", slot.open)
                                                    .takeIf { slot.open.isNotBlank() },
                                                // Text, not Number: this field is "09:00" and a
                                                // numeric keypad has no colon key. The value is
                                                // normalised to HH:MM as it is typed.
                                                keyboardType = KeyboardType.Text,
                                                singleLine = true
                                            )
                                        }, secondary = {
                                            UndalTextField(
                                                value = slot.close,
                                                onValueChange = {
                                                    viewModel.onWeekdaySlot(day.key, index, null, UndalDigits.cleanClock(it))
                                                },
                                                label = "Closes",
                                                hint = "22:00",
                                                error = Validators.clockTimeError("closing", slot.close)
                                                    .takeIf { slot.close.isNotBlank() },
                                                keyboardType = KeyboardType.Text,
                                                singleLine = true
                                            )
                                        })
                                        UndalTextButton(
                                            text = "Remove",
                                            onClick = { viewModel.removeWeekdaySlot(day.key, index) },
                                            icon = Icons.Filled.Delete
                                        )
                                    }
                                    Text(
                                        text = slot.pretty() +
                                            if (TimeDhaka.parseHhMm(slot.open) == null || TimeDhaka.parseHhMm(slot.close) == null) {
                                                " · write times like 09:00"
                                            } else {
                                                ""
                                            },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                UndalTextButton(
                                    text = "Add a window",
                                    onClick = { viewModel.addWeekdaySlot(day.key) }
                                )
                            }
                        }
                    }
                }
            }

            item {
                VendorSection(
                    title = "Special dates",
                    subtitle = "Closed days like Eid, or one-off hours like a late Friday."
                ) {
                    Column {
                        state.specialDates.forEach { special ->
                            Column(modifier = Modifier.padding(vertical = UndalDimens.xs)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Filled.DateRange,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(UndalDimens.iconMd)
                                    )
                                    Spacer(Modifier.width(UndalDimens.xs))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(text = special.date, style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            text = if (special.closed) {
                                                "Closed all day"
                                            } else if (special.slots.isEmpty()) {
                                                "Open with no hours set"
                                            } else {
                                                special.slots.joinToString(" · ") { it.pretty() }
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Switch(
                                        checked = special.closed,
                                        onCheckedChange = { viewModel.toggleSpecialClosed(special.date) }
                                    )
                                    UndalTextButton(
                                        text = "Remove",
                                        onClick = { viewModel.removeSpecialDate(special.date) },
                                        icon = Icons.Filled.Delete
                                    )
                                }
                                if (special.note.isNotBlank()) {
                                    Text(
                                        text = "Note: ${special.note}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (!special.closed) {
                                    special.slots.forEachIndexed { index, slot ->
                                        Column(verticalArrangement = Arrangement.spacedBy(UndalDimens.sm)) {
                                            UndalActionGroup(primary = {
                                                UndalTextField(
                                                    value = slot.open,
                                                    onValueChange = {
                                                        viewModel.onSpecialSlot(
                                                            special.date, index,
                                                            UndalDigits.cleanClock(it), null
                                                        )
                                                    },
                                                    label = "Opens",
                                                    hint = "09:00",
                                                    error = Validators.clockTimeError("opening", slot.open)
                                                        .takeIf { slot.open.isNotBlank() },
                                                    keyboardType = KeyboardType.Text,
                                                    singleLine = true
                                                )
                                            }, secondary = {
                                                UndalTextField(
                                                    value = slot.close,
                                                    onValueChange = {
                                                        viewModel.onSpecialSlot(
                                                            special.date, index, null,
                                                            UndalDigits.cleanClock(it)
                                                        )
                                                    },
                                                    label = "Closes",
                                                    hint = "22:00",
                                                    error = Validators.clockTimeError("closing", slot.close)
                                                        .takeIf { slot.close.isNotBlank() },
                                                    keyboardType = KeyboardType.Text,
                                                    singleLine = true
                                                )
                                            })
                                            UndalTextButton(
                                                text = "Remove",
                                                onClick = { viewModel.removeSpecialSlot(special.date, index) },
                                                icon = Icons.Filled.Delete
                                            )
                                        }
                                    }
                                    UndalTextButton(
                                        text = "Add a window",
                                        onClick = { viewModel.addSpecialSlot(special.date) }
                                    )
                                }
                            }
                        }

                        if (addingSpecial) {
                            VendorDateField(
                                label = "Date",
                                millis = specialMillis,
                                onPicked = { picked -> specialMillis = picked }
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = "Closed all day", style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        text = "Off means the kitchen works these special hours instead.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(checked = specialClosed, onCheckedChange = { specialClosed = it })
                            }
                            UndalTextField(
                                value = specialNote,
                                onValueChange = { specialNote = it.take(80) },
                                label = "What customers should know (optional)",
                                hint = "Closed for Eid — back tomorrow at 9.",
                                singleLine = true
                            )
                            Spacer(Modifier.height(UndalDimens.xs))
                            UndalActionGroup(primary = {
                                UndalPrimaryButton(
                                    text = "Add this date",
                                    onClick = {
                                        viewModel.addSpecialDate(specialMillis, specialClosed, specialNote)
                                        addingSpecial = false
                                        specialNote = ""
                                        specialClosed = true
                                    },
                                    icon = Icons.Filled.Check
                                )
                            }, secondary = {
                                UndalSecondaryButton(
                                    text = "Cancel",
                                    onClick = { addingSpecial = false },
                                    icon = Icons.Filled.Close
                                )
                            })
                        } else {
                            UndalSecondaryButton(
                                text = "Add a special date",
                                onClick = { addingSpecial = true },
                                icon = Icons.Filled.DateRange
                            )
                        }
                    }
                }
            }

            item {
                VendorSection(
                    title = "Save the week",
                    subtitle = "Saving writes one document and keeps the previous version on this phone."
                ) {
                    Column {
                        if (state.hasSnapshot) {
                            UndalSecondaryButton(
                                text = "Undo the last save",
                                onClick = viewModel::undoLastSave,
                                icon = Icons.Filled.Refresh
                            )
                        }
                        Spacer(Modifier.height(UndalDimens.xs))
                        Text(
                            text = "Today the kitchen would read: " +
                                if (viewModel.todayLines().isEmpty()) "closed" else {
                                    viewModel.todayLines().joinToString(", ")
                                },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        state.message?.let { message ->
            UndalAlertBanner(
                text = message,
                icon = Icons.Filled.Check,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(UndalDimens.screenPadding)
            )
        }
    }
}
