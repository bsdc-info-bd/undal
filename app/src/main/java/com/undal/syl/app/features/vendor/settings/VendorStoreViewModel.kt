package com.undal.syl.app.features.vendor.settings

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.undal.syl.app.R
import com.undal.syl.app.core.config.UndalBuildInfo
import com.undal.syl.app.core.maps.UndalLocationController
import com.undal.syl.app.core.model.StoreConfig
import com.undal.syl.app.core.model.StoreOpenState
import com.undal.syl.app.core.net.WorkerApi
import com.undal.syl.app.core.session.VendorSessionKeeper
import com.undal.syl.app.core.util.Money
import com.undal.syl.app.core.util.UndalResult
import com.undal.syl.app.data.repo.StoreRepo
import com.undal.syl.app.data.repo.VendorStoreRepo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Store settings (V-079 … V-086, V-091, V-092, V-095, V-096).
 *
 * Delivery money, the radius, the pin, the profile and the two sounds that decide
 * whether an order is noticed. Every field is validated against the same limits
 * the customer app uses, and the fee calculator shows the arithmetic before the
 * owner commits to it.
 */
@HiltViewModel
class VendorStoreViewModel @Inject constructor(
    private val storeRepo: StoreRepo,
    private val vendorStoreRepo: VendorStoreRepo,
    private val locationController: UndalLocationController,
    private val workerApi: WorkerApi,
    private val sessionKeeper: VendorSessionKeeper
) : ViewModel() {

    data class State(
        val loading: Boolean = true,
        val store: StoreConfig = StoreConfig.unloaded(),
        val openState: StoreOpenState = StoreOpenState.of(StoreConfig.unloaded()),
        val perKmText: String = "",
        val radiusKm: Double = 5.0,
        val minOrderText: String = "",
        val nameText: String = "",
        val pinLat: Double = 0.0,
        val pinLng: Double = 0.0,
        val pinLabel: String = "",
        val feeDistanceText: String = "",
        val uploadProgress: Float? = null,
        val locating: Boolean = false,
        val busy: Boolean = false,
        val cleaning: Boolean = false,
        val cleanupProgress: String? = null,
        /** What the last cleanup had to work around. Stays on screen; a toast does not. */
        val cleanupNotice: String? = null,
        val checkingCleanup: Boolean = false,
        val dirty: Boolean = false,
        val vendorClaim: Boolean = true,
        val message: String? = null
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
    private val guard = com.undal.syl.app.core.util.UndalGuards.viewModel("reading the store settings") { message ->
        _state.value = _state.value.copy(message = message, busy = false)
    }

    private var player: MediaPlayer? = null

    /** A freshly uploaded banner or logo waits here until the owner saves. */
    private var bannerUrl: String = ""
    private var logoUrl: String = ""

    init {
        viewModelScope.launch(guard) {
            storeRepo.observeStore().collect { store ->
                val editing = _state.value.dirty || _state.value.busy
                val state = _state.value
                _state.value = if (!editing) {
                    state.copy(
                        loading = false,
                        store = store,
                        openState = StoreOpenState.of(store),
                        perKmText = Money.formatPlain(store.deliveryPerKmAmountPaisa),
                        radiusKm = store.radiusKm,
                        minOrderText = if (store.minOrderAmountPaisa > 0L) {
                            Money.formatPlain(store.minOrderAmountPaisa)
                        } else {
                            ""
                        },
                        nameText = store.storeName,
                        pinLat = store.storeLat,
                        pinLng = store.storeLng,
                        pinLabel = store.storeAddressText
                    )
                } else {
                    state.copy(loading = false, store = store, openState = StoreOpenState.of(store))
                }
            }
        }
        viewModelScope.launch(guard) {
            val health = sessionKeeper.verifySession()
            _state.value = _state.value.copy(vendorClaim = health.vendorClaim)
        }
    }

    // ------------------------------------------------------------- editable fields

    fun onPerKm(value: String) {
        _state.value = _state.value.copy(perKmText = value.filter { it.isDigit() }.take(5), dirty = true)
    }

    fun onRadius(value: Double) {
        _state.value = _state.value.copy(radiusKm = value.coerceIn(1.0, 15.0), dirty = true)
    }

    fun onMinOrder(value: String) {
        _state.value = _state.value.copy(minOrderText = value.filter { it.isDigit() }.take(6), dirty = true)
    }

    fun onName(value: String) {
        _state.value = _state.value.copy(nameText = value.take(60), dirty = true)
    }

    fun onPinLabel(value: String) {
        _state.value = _state.value.copy(pinLabel = value.take(160), dirty = true)
    }

    fun onFeeDistance(value: String) {
        _state.value = _state.value.copy(feeDistanceText = value.filter { it.isDigit() || it == '.' }.take(5))
    }

    fun onPinPicked(lat: Double, lng: Double) {
        _state.value = _state.value.copy(pinLat = lat, pinLng = lng, dirty = true)
        viewModelScope.launch(guard) {
            val label = locationController.describe(lat, lng)
            _state.value = _state.value.copy(pinLabel = label)
        }
    }

    /** Grab-my-GPS: the owner's own phone is the most accurate pin available. */
    fun useCurrentLocation(context: Context) {
        _state.value = _state.value.copy(locating = true)
        viewModelScope.launch(guard) {
            val result = locationController.currentFix(context)
            _state.value = when (result) {
                is UndalResult.Success -> _state.value.copy(
                    locating = false,
                    pinLat = result.value.lat,
                    pinLng = result.value.lng,
                    dirty = true,
                    message = "Pin moved to this phone's position. Drag the map if the kitchen door is elsewhere."
                )

                is UndalResult.Failure -> _state.value.copy(locating = false, message = result.message)
            }
            if (_state.value.pinLat != 0.0 || _state.value.pinLng != 0.0) {
                val label = locationController.describe(_state.value.pinLat, _state.value.pinLng)
                _state.value = _state.value.copy(pinLabel = label)
            }
        }
    }

    /** V-092: the arithmetic behind a delivery fee, shown before it is saved. */
    fun feePreview(): String {
        val distance = _state.value.feeDistanceText.toDoubleOrNull() ?: 0.0
        val perKm = Money.parseTakaToPaisa(_state.value.perKmText) ?: 0L
        if (distance <= 0.0 || perKm <= 0L) return "Type a distance in kilometres to see the fee."
        return vendorStoreRepo.feePreviewText(distance, perKm)
    }

    fun radiusNotice(): String = vendorStoreRepo.radiusNotice(_state.value.radiusKm)

    fun parityLine(): String = vendorStoreRepo.parityLine(_state.value.store)

    fun versionLine(): String =
        "Undal ${UndalBuildInfo.versionName} (build ${UndalBuildInfo.versionCode}) · worker " +
            UndalBuildInfo.workerHost + " · timezone " + UndalBuildInfo.storeTimezoneId

    // ------------------------------------------------------------------- uploads

    fun uploadBanner(uri: Uri) {
        upload(uri, "Banner") { url -> bannerUrl = url }
    }

    fun uploadLogo(uri: Uri) {
        upload(uri, "Logo") { url -> logoUrl = url }
    }

    private fun upload(uri: Uri, label: String, assign: (String) -> Unit) {
        _state.value = _state.value.copy(uploadProgress = 0f)
        viewModelScope.launch(guard) {
            when (val result = vendorStoreRepo.uploadImage(uri) { fraction ->
                _state.value = _state.value.copy(uploadProgress = fraction)
            }) {
                is UndalResult.Success -> {
                    assign(result.value)
                    _state.value = _state.value.copy(
                        uploadProgress = null,
                        dirty = true,
                        message = "$label uploaded. Save the store to publish it."
                    )
                }

                is UndalResult.Failure -> _state.value = _state.value.copy(
                    uploadProgress = null,
                    message = result.message
                )
            }
        }
    }

    // --------------------------------------------------------------------- saves

    fun saveDelivery() {
        val perKm = Money.parseTakaToPaisa(_state.value.perKmText)
        if (perKm == null || perKm <= 0L) {
            _state.value = _state.value.copy(message = "Write the per-kilometre fee in taka, like 20.")
            return
        }
        val minOrder = Money.parseTakaToPaisa(_state.value.minOrderText) ?: 0L
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch(guard) {
            val result = vendorStoreRepo.setDelivery(perKm, _state.value.radiusKm, minOrder)
            _state.value = _state.value.copy(
                busy = false,
                dirty = false,
                message = when (result) {
                    is UndalResult.Success ->
                        "Delivery is ${Money.format(perKm)} per km inside ${_state.value.radiusKm} km. " +
                            "Customers see it on the cart immediately."

                    is UndalResult.Failure -> result.message
                }
            )
        }
    }

    fun savePin() {
        if (_state.value.pinLat == 0.0 && _state.value.pinLng == 0.0) {
            _state.value = _state.value.copy(message = "Pin the kitchen before saving — the fee is measured from it.")
            return
        }
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch(guard) {
            val result = vendorStoreRepo.setPin(
                lat = _state.value.pinLat,
                lng = _state.value.pinLng,
                addressText = _state.value.pinLabel
            )
            _state.value = _state.value.copy(
                busy = false,
                dirty = false,
                message = when (result) {
                    is UndalResult.Success -> "Kitchen pin saved. Distances are measured from here."
                    is UndalResult.Failure -> result.message
                }
            )
        }
    }

    fun saveProfile() {
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch(guard) {
            val result = vendorStoreRepo.setProfile(
                storeName = _state.value.nameText,
                bannerUrl = bannerUrl.ifBlank { _state.value.store.bannerUrl },
                logoUrl = logoUrl.ifBlank { _state.value.store.logoUrl }
            )
            _state.value = _state.value.copy(
                busy = false,
                dirty = false,
                message = when (result) {
                    is UndalResult.Success -> "Store profile saved."
                    is UndalResult.Failure -> result.message
                }
            )
        }
    }

    // ---------------------------------------------------------------- sound tests

    /** V-044 / V-086: the real order sound, exactly the file the alert service loops. */
    fun testOrderSound(context: Context) {
        play(context, R.raw.undal_order_alert, "The order alert plays for six seconds, then stops.")
    }

    /** The preparation tick is the one synthetic tone Undal ships, and only for timers. */
    fun testTickSound(context: Context) {
        play(context, R.raw.undal_prep_tick, "That is the ten-second preparation tick.")
    }

    private fun play(context: Context, resId: Int, message: String) {
        runCatching {
            player?.release()
            player = MediaPlayer.create(context, resId)
            player?.setOnCompletionListener { done ->
                done.release()
                player = null
            }
            player?.start()
        }.onSuccess { _state.value = _state.value.copy(message = message) }
            .onFailure { _state.value = _state.value.copy(message = "This phone refused to play the sound.") }
    }

    /**
     * One confirmed action drains bounded batches.
     *
     * Three things this loop refuses to do, all of them learned from the failure
     * it replaced — a merchant tapping "Clear finished history" and being told
     * "Cleanup paused (HTTP 502). You can safely try again." forever:
     *
     *   · **hide the reason.** The Worker's own sentence and hint are shown, so a
     *     missing index reads as a missing index and names the workflow that
     *     deploys it;
     *   · **lose the work already done.** The batch cursor survives a failure, and
     *     the summary says how much was deleted before it stopped;
     *   · **hammer a refusal that cannot change.** Only what the Worker marks
     *     retryable is retried, a bounded number of times, with a growing pause.
     */
    fun cleanupHistory() {
        if (_state.value.busy || _state.value.cleaning) return
        _state.value = _state.value.copy(
            cleaning = true,
            busy = true,
            cleanupProgress = "Starting cleanup…",
            cleanupNotice = null
        )
        viewModelScope.launch(guard) {
            var orders = 0
            var notifications = 0
            var before: String? = null
            var batches = 0
            var strategy = "indexed"
            var hint: String? = null
            var stoppedBy: String? = null
            val warnings = mutableListOf<String>()
            try {
                while (batches < CLEANUP_BATCH_LIMIT && stoppedBy == null) {
                    batches += 1
                    var batch: WorkerApi.CleanupBatch? = null
                    var attempt = 0
                    while (attempt < CLEANUP_ATTEMPTS_PER_BATCH && batch == null && stoppedBy == null) {
                        attempt += 1
                        if (attempt > 1) {
                            _state.value = _state.value.copy(
                                cleanupProgress = "The Worker asked for a moment. Retrying this batch ($attempt of $CLEANUP_ATTEMPTS_PER_BATCH)…"
                            )
                            kotlinx.coroutines.delay(CLEANUP_RETRY_PAUSE_MILLIS * attempt)
                        }
                        try {
                            batch = workerApi.cleanupHistory(before)
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (refusal: WorkerApi.WorkerRefusal) {
                            if (refusal.retryable && attempt < CLEANUP_ATTEMPTS_PER_BATCH) {
                                // A rate limit or a dropped connection: worth one more try.
                                continue
                            }
                            stoppedBy = refusal.message ?: "Cleanup paused."
                        } catch (error: Exception) {
                            stoppedBy = error.message ?: "Cleanup paused. You can safely try again."
                        }
                    }
                    val result = batch ?: break
                    before = result.before
                    orders += result.orders
                    notifications += result.notifications
                    strategy = result.strategy
                    if (result.hint != null) hint = result.hint
                    for (warning in result.warnings) {
                        if (!warnings.contains(warning)) warnings.add(warning)
                    }
                    _state.value = _state.value.copy(cleanupProgress = cleanupSummary(orders, notifications))
                    if (!result.more) break
                    kotlinx.coroutines.delay(CLEANUP_BATCH_PAUSE_MILLIS)
                }

                val done = cleanupSummary(orders, notifications)
                val message = when {
                    stoppedBy != null && orders > 0 -> "$stoppedBy\n\nDeleted before it stopped: $done."
                    stoppedBy != null -> stoppedBy
                    batches >= CLEANUP_BATCH_LIMIT ->
                        "Cleanup paused after $CLEANUP_BATCH_LIMIT batches. $done. Run it again to continue — nothing already deleted comes back."
                    else -> "Cleanup complete. $done. Active orders were kept."
                }
                val notice = (warnings + listOfNotNull(hint) + listOfNotNull(
                    if (strategy == "scan") {
                        "This project is using the slower index-free scan. Cleanup works, and re-running the seed-config workflow deploys the composite index that makes it fast."
                    } else {
                        null
                    }
                )).distinct().joinToString("\n\n").ifBlank { null }

                _state.value = _state.value.copy(message = message, cleanupNotice = notice)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    message = error.message ?: "Cleanup paused. You can safely try again."
                )
            } finally {
                _state.value = _state.value.copy(cleaning = false, busy = false, cleanupProgress = null)
            }
        }
    }

    /**
     * Asks the Worker to check the whole cleanup path **without deleting
     * anything**, and puts the answer on screen.
     *
     * This is the difference between "try again" and knowing: a merchant can see
     * whether the composite index exists, whether the Realtime Database mirrors
     * are reachable and how many finished orders are waiting, before they commit
     * to a deletion that cannot be undone.
     */
    fun checkCleanupPath() {
        if (_state.value.busy || _state.value.checkingCleanup) return
        _state.value = _state.value.copy(checkingCleanup = true, busy = true, cleanupNotice = null)
        viewModelScope.launch(guard) {
            try {
                val diagnosis = workerApi.cleanupDiagnosis()
                val failed = diagnosis.checks.filterNot { it.ok }
                val lines = mutableListOf(diagnosis.summary)
                if (diagnosis.finishedSample > 0) {
                    lines.add("The next batch would delete ${diagnosis.finishedSample} finished order(s).")
                }
                if (diagnosis.mirrorsPending > 0) {
                    lines.add("${diagnosis.mirrorsPending} live mirror(s) are queued from an earlier run and will be retried.")
                }
                for (check in diagnosis.checks) {
                    lines.add("${if (check.ok) "✓" else "✗"} ${check.name} — ${check.detail}")
                    if (!check.ok && check.hint != null) lines.add("   ${check.hint}")
                }
                _state.value = _state.value.copy(
                    cleanupNotice = lines.joinToString("\n"),
                    message = if (failed.isEmpty()) {
                        "History cleanup is fully wired. Nothing is blocking it."
                    } else {
                        "History cleanup can run, but ${failed.size} check(s) need attention — read them below."
                    }
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    message = error.message ?: "The history self-test could not reach the Worker."
                )
            } finally {
                _state.value = _state.value.copy(checkingCleanup = false, busy = false)
            }
        }
    }

    private fun cleanupSummary(orders: Int, notifications: Int): String =
        "$orders finished orders · $notifications kitchen notifications deleted"

    /**
     * The share button in this build with no store address.
     *
     * A build compiled without the owner's `WORKER_HOST` secret has nothing to
     * share, and opening the phone's share sheet with `https://undal.YOUR-
     * SUBDOMAIN…` in it puts a dead link in front of a customer. The screen says
     * what is missing instead.
     */
    fun reportMissingStoreAddress() {
        _state.value = _state.value.copy(
            message = "This build does not know the store's web address yet, so there is no link " +
                "to share. Set the WORKER_HOST secret and build again."
        )
    }

    fun dismissMessage() {
        _state.value = _state.value.copy(message = null)
    }

    private companion object {
        /** How many batches one confirmed tap may drain before it pauses. */
        const val CLEANUP_BATCH_LIMIT = 100

        /** Attempts per batch: one try plus two retries of a retryable refusal. */
        const val CLEANUP_ATTEMPTS_PER_BATCH = 3

        const val CLEANUP_RETRY_PAUSE_MILLIS = 1_500L
        const val CLEANUP_BATCH_PAUSE_MILLIS = 1_000L
    }

    override fun onCleared() {
        player?.release()
        player = null
        super.onCleared()
    }
}
