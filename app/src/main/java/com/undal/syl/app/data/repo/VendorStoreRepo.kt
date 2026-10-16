package com.undal.syl.app.data.repo

import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.undal.syl.app.core.model.StoreConfig
import com.undal.syl.app.core.model.StoreHours
import com.undal.syl.app.core.model.StoreOpenState
import com.undal.syl.app.core.model.StoreSlot
import com.undal.syl.app.core.model.StoreSpecialDate
import com.undal.syl.app.core.net.CloudinaryUploader
import com.undal.syl.app.core.util.Money
import com.undal.syl.app.core.util.TimeDhaka
import com.undal.syl.app.core.util.UndalDigits
import com.undal.syl.app.core.util.UndalErrors
import com.undal.syl.app.core.util.UndalResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The store document as the owner edits it (V-071 … V-093).
 *
 * `config/store` is world-readable and vendor-writable, which is exactly the
 * behaviour the product wants: the owner changes the hours on their phone and
 * every customer app shows the new status within a second. The undo snapshot is
 * local — an escape hatch for a slip of the thumb, not a versioned audit trail.
 */
@Singleton
class VendorStoreRepo @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val dataStore: DataStore<Preferences>,
    private val uploader: CloudinaryUploader
) {

    data class ScheduleSnapshot(
        val weekly: Map<String, List<StoreSlot>>,
        val specialDates: List<StoreSpecialDate>,
        val takenAtMillis: Long
    )

    // ------------------------------------------------------------------ status

    /**
     * The owner's switch, with the note customers read (V-017, V-075, V-077).
     *
     * On means "take orders whenever my hours say so": the store then opens and
     * closes itself on the schedule, every day, with nobody tapping anything. Off
     * pauses it until the owner turns it back on. The switch no longer opens a
     * store outside its hours — that is what let a kitchen with published hours
     * take orders at three in the morning.
     *
     * Three fields carry it: `acceptingOrders` is the switch itself; `open`, the
     * only field older builds read, is written as what the hours say *right now*
     * (the Worker's presence cron keeps it there afterwards); `openSynced` records
     * that this write was schedule-aware. See [StoreHours] and `StoreConfig`.
     */
    suspend fun setOpen(open: Boolean, note: String, store: StoreConfig): UndalResult<Unit> = runCatching {
        val trimmed = note.trim().take(140)
        val takingOrders = open && StoreHours.isOpenAt(store)
        val payload = if (open) {
            mapOf(
                "acceptingOrders" to true,
                "open" to takingOrders,
                "openSynced" to takingOrders,
                // Opening by hand clears the auto-offline flag the cron set, so the
                // store is not closed again by the next heartbeat sweep.
                "autoClosed" to false,
                "manualOverrideNote" to trimmed,
                "reopenedAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        } else {
            mapOf(
                "acceptingOrders" to false,
                "open" to false,
                "openSynced" to false,
                "manualOverrideNote" to trimmed,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        }
        firestore.collection(CONFIG).document(STORE).update(payload).await()
    }.fold(
        onSuccess = { UndalResult.Success(Unit) },
        onFailure = { UndalResult.Failure(UndalErrors.firestore(it), it) }
    )

    // ---------------------------------------------------------------- schedule

    fun lastSnapshot(): Flow<ScheduleSnapshot?> = dataStore.data.map { preferences ->
        decode(preferences[SCHEDULE_SNAPSHOT])
    }

    /**
     * Publishes the week and its special dates. Given the [store] as it stands,
     * the new hours also move `open` at once — an owner who adds today's evening
     * hours at 18:59 opens at 19:00 on every phone, not at the next cron sweep.
     */
    suspend fun saveSchedule(
        weekly: Map<String, List<StoreSlot>>,
        specialDates: List<StoreSpecialDate>,
        store: StoreConfig? = null
    ): UndalResult<Unit> = runCatching {
        val current = dataStore.data.first()[SCHEDULE_SNAPSHOT]
        val payload = mutableMapOf<String, Any>(
            "weekly" to weekly.mapValues { (_, slots) ->
                slots.map { slot -> mapOf("open" to slot.open, "close" to slot.close) }
            },
            "specialDates" to specialDates.map { special ->
                mapOf(
                    "date" to special.date,
                    "closed" to special.closed,
                    "note" to special.note,
                    "slots" to special.slots.map { slot -> mapOf("open" to slot.open, "close" to slot.close) }
                )
            },
            "updatedAt" to FieldValue.serverTimestamp()
        )
        // Only from a store that has really loaded: the placeholder shown before the
        // first snapshot has its switch off, and recording that would pause the store.
        if (store != null && store.updatedAt != null) {
            val withNewHours = store.copy(weekly = weekly, specialDates = specialDates)
            val takingOrders = StoreHours.isTakingOrders(withNewHours)
            payload["acceptingOrders"] = withNewHours.switchOn
            payload["open"] = takingOrders
            payload["openSynced"] = takingOrders
        }
        firestore.collection(CONFIG).document(STORE).update(payload).await()
        if (current != null) {
            dataStore.edit { preferences -> preferences[PREVIOUS_SNAPSHOT] = current }
        }
    }.fold(
        onSuccess = { UndalResult.Success(Unit) },
        onFailure = { UndalResult.Failure(UndalErrors.firestore(it), it) }
    )

    fun previousSnapshot(): Flow<ScheduleSnapshot?> = dataStore.data.map { preferences ->
        decode(preferences[PREVIOUS_SNAPSHOT])
    }

    /** Undo the last schedule change from the local snapshot (V-093). */
    suspend fun undoLastSchedule(store: StoreConfig? = null): UndalResult<ScheduleSnapshot> {
        val previous = decode(dataStore.data.first()[PREVIOUS_SNAPSHOT])
            ?: return UndalResult.Failure("There is no earlier schedule on this phone to go back to.")
        return when (val written = saveSchedule(previous.weekly, previous.specialDates, store)) {
            is UndalResult.Success -> UndalResult.Success(previous)
            is UndalResult.Failure -> written
        }
    }

    suspend fun persistSnapshot(weekly: Map<String, List<StoreSlot>>, specialDates: List<StoreSpecialDate>) {
        dataStore.edit { preferences ->
            preferences[SCHEDULE_SNAPSHOT] = encode(weekly, specialDates)
        }
    }

    // ---------------------------------------------------------------- delivery

    suspend fun setDelivery(
        perKmPaisa: Long,
        radiusKm: Double,
        minOrderPaisa: Long
    ): UndalResult<Unit> = runCatching {
        if (perKmPaisa <= 0L) error("The per-kilometre fee must be more than zero.")
        if (radiusKm !in 1.0..15.0) error("The radius must stay between 1 and 15 km.")
        if (minOrderPaisa < 0L) error("The minimum order cannot be negative.")
        firestore.collection(CONFIG).document(STORE)
            .update(
                mapOf(
                    "deliveryPerKmAmountPaisa" to perKmPaisa,
                    "radiusKm" to radiusKm,
                    "minOrderAmountPaisa" to minOrderPaisa,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            .await()
    }.fold(
        onSuccess = { UndalResult.Success(Unit) },
        onFailure = { UndalResult.Failure(it.message ?: UndalErrors.firestore(it), it) }
    )

    suspend fun setPin(lat: Double, lng: Double, addressText: String): UndalResult<Unit> = runCatching {
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) error("That pin is not on the map.")
        if (lat == 0.0 && lng == 0.0) error("Pin the kitchen before saving.")
        firestore.collection(CONFIG).document(STORE)
            .update(
                mapOf(
                    "storeLat" to lat,
                    "storeLng" to lng,
                    "storeAddressText" to addressText.trim().take(160),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            .await()
    }.fold(
        onSuccess = { UndalResult.Success(Unit) },
        onFailure = { UndalResult.Failure(it.message ?: UndalErrors.firestore(it), it) }
    )

    suspend fun setProfile(
        storeName: String,
        bannerUrl: String,
        logoUrl: String
    ): UndalResult<Unit> = runCatching {
        if (storeName.trim().length < 2) error("The store needs a name customers recognise.")
        firestore.collection(CONFIG).document(STORE)
            .update(
                mapOf(
                    "storeName" to storeName.trim().take(60),
                    "bannerUrl" to bannerUrl,
                    "logoUrl" to logoUrl,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            .await()
    }.fold(
        onSuccess = { UndalResult.Success(Unit) },
        onFailure = { UndalResult.Failure(it.message ?: UndalErrors.firestore(it), it) }
    )

    // ------------------------------------------------------------------ uploads

    /**
     * The storefront banner and logo go through the same unsigned Cloudinary
     * preset as the menu (V-072). The repo hands back the `secure_url` itself, so
     * the screen only ever deals in one string.
     */
    suspend fun uploadImage(uri: Uri, onProgress: (Float) -> Unit): UndalResult<String> =
        when (val result = uploader.uploadImage(uri, onProgress)) {
            is UndalResult.Success -> UndalResult.Success(result.value.secureUrl)
            is UndalResult.Failure -> result
        }

    // --------------------------------------------------------------- explainers

    /** What the customer reads right now, before the owner saves anything (V-089). */
    fun parityLine(store: StoreConfig): String {
        val state = StoreOpenState.of(store)
        return if (state.serviceable) {
            "Customers now read: Open · ${state.todayLine}"
        } else {
            "Customers now read: Closed · ${state.headline}"
        }
    }

    fun derivedStatusLine(store: StoreConfig): String {
        val state = StoreOpenState.of(store)
        return "${state.headline} — ${state.detail}"
    }

    /** Warnings the schedule builder shows before saving (V-090). */
    fun scheduleIssues(weekly: Map<String, List<StoreSlot>>): List<String> {
        val issues = mutableListOf<String>()
        weekly.forEach { (day, slots) ->
            if (slots.isEmpty()) return@forEach
            slots.forEachIndexed { index, slot ->
                val open = slot.openMinutes
                val close = slot.closeMinutes
                if (open == null || close == null) {
                    issues.add("$day · slot ${index + 1}: write times as 24-hour, like 09:00 and 22:00.")
                } else if (open == close) {
                    issues.add("$day · slot ${index + 1}: opening and closing are the same minute.")
                }
            }
            slots.sortedBy { it.openMinutes ?: 0 }.zipWithNext().forEach { (first, second) ->
                val firstClose = first.closeMinutes ?: return@forEach
                val secondOpen = second.openMinutes ?: return@forEach
                if (TimeDhaka.isWithinSlot(first.openMinutes ?: 0, firstClose, secondOpen)) {
                    issues.add("$day: two slots overlap — the later one starts before the earlier one ends.")
                }
            }
        }
        return issues
    }

    fun specialDateIssues(specialDates: List<StoreSpecialDate>): List<String> {
        val issues = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        specialDates.forEach { special ->
            if (!seen.add(special.date)) {
                issues.add("${special.date} is listed twice — keep one entry per date.")
            }
            val parsed = runCatching { LocalDate.parse(special.date) }.getOrNull()
            if (parsed == null) {
                issues.add("\"${special.date}\" is not a date. Use the picker.")
                return@forEach
            }
            if (parsed.isBefore(TimeDhaka.today())) {
                issues.add("${special.date} is already in the past, so it will never apply.")
            }
            if (special.closed && special.slots.isNotEmpty()) {
                issues.add("${special.date} is marked closed but still has opening slots.")
            }
            if (!special.closed && special.slots.isEmpty()) {
                issues.add("${special.date} is marked open but has no hours — it will behave like a normal day.")
            }
        }
        return issues
    }

    fun radiusNotice(radiusKm: Double): String =
        "Pins further than ${UndalDigits.format(radiusKm, 1)} km from the kitchen are refused at checkout, and the " +
            "fee is charged per whole kilometre. Changing this changes who can order from you."

    fun feePreviewText(distanceKm: Double, perKmPaisa: Long): String =
        "${Money.format(Money.deliveryFeePaisa(distanceKm, perKmPaisa))} for " +
            "${UndalDigits.format(distanceKm, 1)} km"

    // ------------------------------------------------------------- persistence

    private fun encode(weekly: Map<String, List<StoreSlot>>, specialDates: List<StoreSpecialDate>): String {
        val weeklyJson = JSONObject()
        weekly.forEach { (day, slots) ->
            val array = JSONArray()
            slots.forEach { slot -> array.put(JSONObject().put("open", slot.open).put("close", slot.close)) }
            weeklyJson.put(day, array)
        }
        val specialJson = JSONArray()
        specialDates.forEach { special ->
            val slots = JSONArray()
            special.slots.forEach { slot -> slots.put(JSONObject().put("open", slot.open).put("close", slot.close)) }
            specialJson.put(
                JSONObject()
                    .put("date", special.date)
                    .put("closed", special.closed)
                    .put("note", special.note)
                    .put("slots", slots)
            )
        }
        return JSONObject()
            .put("weekly", weeklyJson)
            .put("specialDates", specialJson)
            .put("takenAt", System.currentTimeMillis())
            .toString()
    }

    private fun decode(raw: String?): ScheduleSnapshot? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val root = JSONObject(raw)
            val weeklyJson = root.optJSONObject("weekly") ?: JSONObject()
            val weekly = mutableMapOf<String, List<StoreSlot>>()
            weeklyJson.keys().forEach { day ->
                val array = weeklyJson.optJSONArray(day) ?: JSONArray()
                weekly[day] = (0 until array.length()).mapNotNull { index ->
                    val slot = array.optJSONObject(index) ?: return@mapNotNull null
                    StoreSlot(open = slot.optString("open"), close = slot.optString("close"))
                }
            }
            val specialJson = root.optJSONArray("specialDates") ?: JSONArray()
            val specialDates = (0 until specialJson.length()).mapNotNull { index ->
                val entry = specialJson.optJSONObject(index) ?: return@mapNotNull null
                val slots = entry.optJSONArray("slots") ?: JSONArray()
                StoreSpecialDate(
                    date = entry.optString("date"),
                    closed = entry.optBoolean("closed"),
                    slots = (0 until slots.length()).mapNotNull { slotIndex ->
                        val slot = slots.optJSONObject(slotIndex) ?: return@mapNotNull null
                        StoreSlot(open = slot.optString("open"), close = slot.optString("close"))
                    },
                    note = entry.optString("note")
                )
            }
            ScheduleSnapshot(
                weekly = weekly,
                specialDates = specialDates,
                takenAtMillis = root.optLong("takenAt")
            )
        }.getOrNull()
    }

    private companion object {
        const val CONFIG = "config"
        const val STORE = "store"
        val SCHEDULE_SNAPSHOT = stringPreferencesKey("vendor_schedule_snapshot")
        val PREVIOUS_SNAPSHOT = stringPreferencesKey("vendor_schedule_snapshot_previous")
    }
}
