package com.undal.syl.app.features.vendor.dashboard

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.undal.syl.app.core.background.PresenceHeartbeat
import com.undal.syl.app.core.background.PresenceHeartbeatWorker
import com.undal.syl.app.core.background.TokenRefreshWorker
import com.undal.syl.app.core.fcm.TokenRegistrar
import com.undal.syl.app.core.model.StoreConfig
import com.undal.syl.app.core.model.StoreOpenState
import com.undal.syl.app.core.model.VendorDayPoint
import com.undal.syl.app.core.model.VendorDayStats
import com.undal.syl.app.core.model.VendorOrder
import com.undal.syl.app.core.model.VendorStatusEvent
import com.undal.syl.app.core.session.AuthSession
import com.undal.syl.app.core.session.VendorSessionKeeper
import com.undal.syl.app.core.telemetry.UndalTelemetry
import com.undal.syl.app.core.util.OrderStatus
import com.undal.syl.app.core.util.UndalResult
import com.undal.syl.app.data.repo.StoreRepo
import com.undal.syl.app.data.repo.VendorOrderRepo
import com.undal.syl.app.data.repo.VendorStoreRepo
import com.undal.syl.app.features.vendor.storeShareText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The kitchen's home screen (V-011 … V-023, V-044, V-076 … V-078, V-094, V-095,
 * V-108, V-110).
 *
 * Everything here answers one question — what needs my hands right now — and it
 * answers it from live data: today's orders, the store switch, the prep timers
 * and the heartbeat that keeps the store open.
 */
@HiltViewModel
class VendorDashboardViewModel @Inject constructor(
    private val vendorOrderRepo: VendorOrderRepo,
    private val storeRepo: StoreRepo,
    private val vendorStoreRepo: VendorStoreRepo,
    private val registrar: TokenRegistrar,
    private val sessionKeeper: VendorSessionKeeper,
    private val heartbeat: PresenceHeartbeat,
    private val session: AuthSession,
    private val auth: FirebaseAuth,
    private val telemetry: UndalTelemetry
) : ViewModel() {

    data class State(
        val loading: Boolean = true,
        val store: StoreConfig = StoreConfig.unloaded(),
        val openState: StoreOpenState = StoreOpenState.of(StoreConfig.unloaded()),
        val today: List<VendorOrder> = emptyList(),
        val week: List<VendorDayPoint> = emptyList(),
        val stats: VendorDayStats = VendorDayStats(),
        val events: List<VendorStatusEvent> = emptyList(),
        val runningTimers: List<VendorOrder> = emptyList(),
        val waiting: List<VendorOrder> = emptyList(),
        val fromCache: Boolean = false,
        val notificationsEnabled: Boolean = true,
        val tokenTail: String = "",
        val heartbeatLabel: String = "checking…",
        val sessionSummary: String = "",
        val vendorClaim: Boolean = false,
        val note: String = "",
        val busy: Boolean = false,
        val infoMessage: String? = null,
        val exportCsv: String? = null,
        val shareText: String = "",
        val presence: PresenceHeartbeat.Presence? = null
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Bumped by pull-to-refresh: restarting the listener forces a server read (V-022). */
    private val refreshKey = MutableStateFlow(0)

    private val _now = MutableStateFlow(System.currentTimeMillis())

    /** One-second clock so the queue's age badges tick (V-015). */
    val now: StateFlow<Long> = _now.asStateFlow()

    init {
        viewModelScope.launch {
            while (true) {
                _now.value = System.currentTimeMillis()
                delay(1_000L)
            }
        }
        observeStore()
        observeToday()
        observeWeek()
        observeHeartbeat()
    }

    fun refresh(context: Context) {
        viewModelScope.launch {
            val health = sessionKeeper.verifySession()
            val token = registrar.currentToken()
            _state.value = _state.value.copy(
                loading = false,
                notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
                tokenTail = token?.takeLast(8).orEmpty(),
                sessionSummary = when {
                    !health.signedIn -> "Not signed in"
                    health.vendorClaim -> "Kitchen account verified"
                    else -> "Signed in without the kitchen claim"
                },
                vendorClaim = health.vendorClaim,
                infoMessage = health.lastError
            )
            if (health.vendorClaim) {
                PresenceHeartbeat.startService(context)
            }
        }
    }

    private fun observeStore() {
        viewModelScope.launch {
            storeRepo.observeStore().collect { store ->
                _state.value = _state.value.copy(
                    store = store,
                    openState = StoreOpenState.of(store)
                )
            }
        }
    }

    private fun observeToday() {
        viewModelScope.launch {
            refreshKey.flatMapLatest { vendorOrderRepo.observeToday() }.collect { feed ->
                val open = feed.orders.filter { it.isOpen }
                _state.value = _state.value.copy(
                    loading = false,
                    today = feed.orders,
                    fromCache = feed.fromCache,
                    stats = VendorDayStats.of(feed.orders),
                    events = VendorOrder.events(feed.orders),
                    waiting = feed.orders
                        .filter { it.status == OrderStatus.PLACED }
                        .sortedBy { it.createdAtMillis },
                    runningTimers = open
                        .filter { it.status == OrderStatus.ACCEPTED }
                        .sortedBy { it.prepEndsAtMillis ?: Long.MAX_VALUE }
                )
                // If this phone crashes tonight, the report should say whether the
                // store was open and how many orders were waiting (P-023).
                telemetry.vendorState(
                    storeOpen = _state.value.openState.serviceable,
                    pendingOrders = _state.value.waiting.size
                )
            }
        }
        // The kitchen's own alert history mirrors the phone's ringing (V-109).
        viewModelScope.launch {
            refreshKey.flatMapLatest { vendorOrderRepo.observeToday() }.collect { feed ->
                feed.orders.filter { it.status == OrderStatus.PLACED }
                    .forEach { order -> vendorOrderRepo.recordAlertIfNew(order) }
            }
        }
    }

    private fun observeWeek() {
        viewModelScope.launch {
            refreshKey.flatMapLatest { vendorOrderRepo.observeWeek() }.collect { feed ->
                _state.value = _state.value.copy(week = VendorOrder.weekSeries(feed.orders))
            }
        }
    }

    private fun observeHeartbeat() {
        viewModelScope.launch {
            heartbeat.observe().collect { presence ->
                val lastSeen = presence?.lastSeen ?: 0L
                _state.value = _state.value.copy(
                    presence = presence,
                    heartbeatLabel = if (lastSeen == 0L) {
                        "never"
                    } else {
                        PresenceHeartbeat.describeAge(System.currentTimeMillis() - lastSeen)
                    }
                )
            }
        }
    }

    /** Pull-to-refresh: health first, then the live listeners restart (V-022). */
    fun refreshAll(context: Context) {
        refresh(context)
        refreshKey.value += 1
    }

    fun onNoteChange(value: String) {
        _state.value = _state.value.copy(note = value.take(140))
    }

    /** One-tap open or close, carrying the note customers read (V-017, V-075). */
    fun setOpen(context: Context, open: Boolean) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            val note = _state.value.note.trim().ifBlank {
                if (open) "The kitchen is taking orders now." else "The kitchen is closed for now."
            }
            val result = vendorStoreRepo.setOpen(open = open, note = note)
            if (open && result is UndalResult.Success) {
                PresenceHeartbeat.startService(context)
                PresenceHeartbeatWorker.ensureScheduled(context)
            }
            _state.value = _state.value.copy(
                busy = false,
                note = "",
                infoMessage = when (result) {
                    is UndalResult.Success -> if (open) "The store is open. Customers see it now." else "The store is closed."
                    is UndalResult.Failure -> result.message
                }
            )
        }
    }

    /** The reopen flow after the auto-offline cron closed the store (V-076, V-077). */
    fun reopenNow(context: Context) {
        _state.value = _state.value.copy(note = "Back on the phone — accepting orders again.")
        setOpen(context, open = true)
    }

    fun heartbeatNow(context: Context) {
        TokenRefreshWorker.enqueue(context)
        PresenceHeartbeat.startService(context)
        PresenceHeartbeatWorker.ensureScheduled(context)
        refresh(context)
        _state.value = _state.value.copy(infoMessage = "Heartbeat sent. The store stays open while this phone checks in.")
    }

    fun exportToday() {
        val csv = VendorOrder.csv(_state.value.today)
        _state.value = _state.value.copy(exportCsv = csv)
    }

    fun clearExport() {
        _state.value = _state.value.copy(exportCsv = null)
    }

    fun shareStoreLink() {
        val storeName = _state.value.store.storeName.ifBlank { "Undal HomeKitchen" }
        _state.value = _state.value.copy(shareText = storeShareText(storeName, host()))
    }

    private fun host(): String = com.undal.syl.app.core.config.UndalBuildInfo.workerHost

    fun dismissMessage() {
        _state.value = _state.value.copy(infoMessage = null, shareText = "")
    }

    /** Signs out and stops every device-side process that belongs to the kitchen. */
    fun signOut(context: Context) {
        viewModelScope.launch {
            auth.currentUser?.uid?.let { uid -> registrar.unregister(uid) }
            PresenceHeartbeat.stopService(context)
            PresenceHeartbeatWorker.cancel(context)
            heartbeat.markOffline()
            session.signOut()
        }
    }
}
