package com.undal.syl.app.features.vendor.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.undal.syl.app.core.model.StoreConfig
import com.undal.syl.app.core.model.StoreOpenState
import com.undal.syl.app.core.model.StoreSlot
import com.undal.syl.app.core.model.StoreSpecialDate
import com.undal.syl.app.core.util.TimeDhaka
import com.undal.syl.app.core.util.UndalResult
import com.undal.syl.app.data.repo.StoreRepo
import com.undal.syl.app.data.repo.VendorStoreRepo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/**
 * The weekly schedule builder (V-071 … V-074, V-089, V-090, V-093).
 *
 * The owner edits a local copy of the week, sees exactly what the customer would
 * derive from it, and only then writes. Saving keeps the previous version on the
 * phone so a mistake is one tap away from being undone — and every conflict the
 * rules would not catch is caught here first.
 */
@HiltViewModel
class VendorScheduleViewModel @Inject constructor(
    private val storeRepo: StoreRepo,
    private val vendorStoreRepo: VendorStoreRepo
) : ViewModel() {

    data class DayRow(val key: String, val label: String)

    enum class Template(val label: String, val detail: String) {
        ALL_DAY("24/7", "One window from midnight to midnight, every day."),
        STANDARD("Standard 9–22", "09:00 to 22:00, every day."),
        SPLIT("Lunch + dinner", "11:00–15:00 and 19:00–23:00 — the kitchen rests in between."),
        WEEKEND("Weekend late", "Friday and Saturday to 23:30, weekdays to 22:00.")
    }

    data class State(
        val loading: Boolean = true,
        val store: StoreConfig = StoreConfig.unloaded(),
        val weekly: Map<String, List<StoreSlot>> = emptyMap(),
        val specialDates: List<StoreSpecialDate> = emptyList(),
        val issues: List<String> = emptyList(),
        val parityLine: String = "",
        val statusLine: String = "",
        val hasSnapshot: Boolean = false,
        val busy: Boolean = false,
        val message: String? = null,
        val dirty: Boolean = false
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    /**
     * Nothing this ViewModel does may end the process.
     *
     * An exception that escapes a `viewModelScope` job is uncaught, and Android's
     * answer to an uncaught exception is to kill the app — which the person
     * experiences as the app vanishing a few seconds after it opened, then the
     * crash guard reopening it, then the home screen. Every coroutine here carries
     * this handler, so a failed read becomes one sentence where the screen already
     * shows messages.
     */
    private val guard = com.undal.syl.app.core.util.UndalGuards.viewModel("reading the kitchen's schedule") { message ->
        _state.value = _state.value.copy(message = message, busy = false)
    }

    private var loadedStore: StoreConfig? = null

    init {
        viewModelScope.launch(guard) {
            storeRepo.observeStore().collect { store ->
                val editing = _state.value.dirty || _state.value.busy
                if (loadedStore?.updatedAt != store.updatedAt && !editing) {
                    loadedStore = store
                    _state.value = _state.value.copy(
                        loading = false,
                        store = store,
                        weekly = store.weekly.ifEmpty { defaultWeekly() },
                        specialDates = store.specialDates,
                        parityLine = vendorStoreRepo.parityLine(store),
                        statusLine = vendorStoreRepo.derivedStatusLine(store),
                        issues = emptyList()
                    )
                } else {
                    _state.value = _state.value.copy(
                        loading = false,
                        store = store,
                        parityLine = vendorStoreRepo.parityLine(store),
                        statusLine = vendorStoreRepo.derivedStatusLine(store)
                    )
                }
            }
        }
        viewModelScope.launch(guard) {
            vendorStoreRepo.lastSnapshot().collect { snapshot ->
                _state.value = _state.value.copy(hasSnapshot = snapshot != null)
            }
        }
    }

    val days: List<DayRow> = listOf(
        DayRow("mon", "Monday"),
        DayRow("tue", "Tuesday"),
        DayRow("wed", "Wednesday"),
        DayRow("thu", "Thursday"),
        DayRow("fri", "Friday"),
        DayRow("sat", "Saturday"),
        DayRow("sun", "Sunday")
    )

    // ------------------------------------------------------------------ editing

    fun onWeekdaySlot(dayKey: String, index: Int, open: String?, close: String?) {
        val slots = _state.value.weekly[dayKey].orEmpty().toMutableList()
        if (index !in slots.indices) return
        val current = slots[index]
        slots[index] = StoreSlot(
            open = open?.take(5) ?: current.open,
            close = close?.take(5) ?: current.close
        )
        update(dayKey, slots)
    }

    fun addWeekdaySlot(dayKey: String) {
        val slots = _state.value.weekly[dayKey].orEmpty() + StoreSlot("18:00", "22:00")
        update(dayKey, slots)
    }

    fun removeWeekdaySlot(dayKey: String, index: Int) {
        val slots = _state.value.weekly[dayKey].orEmpty().toMutableList()
        if (index !in slots.indices) return
        slots.removeAt(index)
        update(dayKey, slots)
    }

    fun applyTemplate(template: Template) {
        val weekly = when (template) {
            Template.ALL_DAY -> days.associate { it.key to listOf(StoreSlot("00:00", "23:59")) }
            Template.STANDARD -> days.associate { it.key to listOf(StoreSlot("09:00", "22:00")) }
            Template.SPLIT -> days.associate {
                it.key to listOf(StoreSlot("11:00", "15:00"), StoreSlot("19:00", "23:00"))
            }

            Template.WEEKEND -> days.associate {
                if (it.key == "fri" || it.key == "sat") {
                    it.key to listOf(StoreSlot("09:00", "23:30"))
                } else {
                    it.key to listOf(StoreSlot("09:00", "22:00"))
                }
            }
        }
        _state.value = _state.value.copy(
            weekly = weekly,
            dirty = true,
            message = "${template.label} applied. ${template.detail}",
            issues = vendorStoreRepo.scheduleIssues(weekly)
        )
    }

    private fun update(dayKey: String, slots: List<StoreSlot>) {
        val weekly = _state.value.weekly.toMutableMap()
        weekly[dayKey] = slots
        _state.value = _state.value.copy(
            weekly = weekly,
            dirty = true,
            issues = vendorStoreRepo.scheduleIssues(weekly) + vendorStoreRepo.specialDateIssues(_state.value.specialDates)
        )
    }

    // ------------------------------------------------------------ special dates

    fun addSpecialDate(millis: Long, closed: Boolean, note: String) {
        val date = TimeDhaka.fromInstant(java.time.Instant.ofEpochMilli(millis)).toLocalDate()
        val key = TimeDhaka.dateKey(date)
        val existing = _state.value.specialDates.filterNot { it.date == key }
        val special = StoreSpecialDate(
            date = key,
            closed = closed,
            slots = if (closed) emptyList() else listOf(StoreSlot("09:00", "22:00")),
            note = note.trim().take(80)
        )
        val updated = (existing + special).sortedBy { it.date }
        _state.value = _state.value.copy(
            specialDates = updated,
            dirty = true,
            issues = vendorStoreRepo.scheduleIssues(_state.value.weekly) + vendorStoreRepo.specialDateIssues(updated),
            message = if (closed) "$key will be closed." else "$key gets its own hours."
        )
    }

    fun removeSpecialDate(date: String) {
        val updated = _state.value.specialDates.filterNot { it.date == date }
        _state.value = _state.value.copy(
            specialDates = updated,
            dirty = true,
            issues = vendorStoreRepo.scheduleIssues(_state.value.weekly) + vendorStoreRepo.specialDateIssues(updated)
        )
    }

    fun toggleSpecialClosed(date: String) {
        val updated = _state.value.specialDates.map { special ->
            if (special.date != date) {
                special
            } else if (special.closed) {
                special.copy(closed = false, slots = listOf(StoreSlot("09:00", "22:00")))
            } else {
                special.copy(closed = true, slots = emptyList())
            }
        }
        _state.value = _state.value.copy(
            specialDates = updated,
            dirty = true,
            issues = vendorStoreRepo.scheduleIssues(_state.value.weekly) + vendorStoreRepo.specialDateIssues(updated)
        )
    }

    fun onSpecialNote(date: String, note: String) {
        val updated = _state.value.specialDates.map { special ->
            if (special.date == date) special.copy(note = note.take(80)) else special
        }
        _state.value = _state.value.copy(specialDates = updated, dirty = true)
    }

    fun addSpecialSlot(date: String) {
        val updated = _state.value.specialDates.map { special ->
            if (special.date == date) special.copy(slots = special.slots + StoreSlot("18:00", "22:00")) else special
        }
        _state.value = _state.value.copy(specialDates = updated, dirty = true)
    }

    fun onSpecialSlot(date: String, index: Int, open: String?, close: String?) {
        val updated = _state.value.specialDates.map { special ->
            if (special.date != date) return@map special
            val slots = special.slots.toMutableList()
            if (index !in slots.indices) return@map special
            val current = slots[index]
            slots[index] = StoreSlot(open?.take(5) ?: current.open, close?.take(5) ?: current.close)
            special.copy(slots = slots)
        }
        _state.value = _state.value.copy(specialDates = updated, dirty = true)
    }

    fun removeSpecialSlot(date: String, index: Int) {
        val updated = _state.value.specialDates.map { special ->
            if (special.date != date) return@map special
            val slots = special.slots.toMutableList()
            if (index !in slots.indices) return@map special
            slots.removeAt(index)
            special.copy(slots = slots)
        }
        _state.value = _state.value.copy(specialDates = updated, dirty = true)
    }

    // ------------------------------------------------------------------- writing

    fun save() {
        val state = _state.value
        val issues = vendorStoreRepo.scheduleIssues(state.weekly) + vendorStoreRepo.specialDateIssues(state.specialDates)
        if (issues.isNotEmpty()) {
            _state.value = state.copy(issues = issues, message = "Fix the ${issues.size} warning(s) first — they would confuse customers.")
            return
        }
        _state.value = state.copy(busy = true)
        viewModelScope.launch(guard) {
            val result = vendorStoreRepo.saveSchedule(state.weekly, state.specialDates)
            _state.value = _state.value.copy(
                busy = false,
                dirty = false,
                hasSnapshot = true,
                message = when (result) {
                    is UndalResult.Success -> "Saved. Customers see the new hours immediately."
                    is UndalResult.Failure -> result.message
                }
            )
        }
    }

    /** V-093: put the week back the way it was before the last save. */
    fun undoLastSave() {
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch(guard) {
            when (val result = vendorStoreRepo.undoLastSchedule()) {
                is UndalResult.Success -> {
                    val snapshot = result.value
                    _state.value = _state.value.copy(
                        busy = false,
                        dirty = false,
                        weekly = snapshot.weekly,
                        specialDates = snapshot.specialDates,
                        issues = emptyList(),
                        message = "Went back to the schedule from before your last save."
                    )
                }

                is UndalResult.Failure -> _state.value = _state.value.copy(busy = false, message = result.message)
            }
        }
    }

    fun dismissMessage() {
        _state.value = _state.value.copy(message = null)
    }

    /** The status the customer derives from this exact week, line by line. */
    fun previewLine(): String {
        val store = _state.value.store.copy(
            weekly = _state.value.weekly,
            specialDates = _state.value.specialDates
        )
        val state = StoreOpenState.of(store, TimeDhaka.now().withHour(13).withMinute(0))
        return "At 1:00 PM today customers would read: ${state.headline} · ${state.detail}"
    }

    fun todayKey(): String = TimeDhaka.weekdayKey(TimeDhaka.today())

    fun todayLines(): List<String> {
        val state = _state.value
        val todayKey = todayKey()
        val special = state.specialDates.firstOrNull { it.date == TimeDhaka.dateKey(LocalDate.now(TimeDhaka.zone)) }
        val slots = when {
            special != null -> special.slots
            else -> state.weekly[todayKey].orEmpty()
        }
        return slots.map { it.pretty() }
    }

    private fun defaultWeekly(): Map<String, List<StoreSlot>> =
        days.associate { it.key to listOf(StoreSlot("09:00", "22:00")) }
}
