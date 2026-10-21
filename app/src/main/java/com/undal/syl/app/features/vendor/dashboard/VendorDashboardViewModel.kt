package com.undal.syl.app.features.vendor.dashboard

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.undal.syl.app.core.background.KitchenWatchService
import com.undal.syl.app.core.background.OrderAlertController
import com.undal.syl.app.core.background.PresenceHeartbeat
import com.undal.syl.app.core.background.PresenceHeartbeatWorker
import com.undal.syl.app.core.background.TokenRefreshWorker
import com.undal.syl.app.core.fcm.TokenRegistrar
import com.undal.syl.app.core.model.StoreConfig
import com.undal.syl.app.core.model.StoreHours
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
import com.undal.syl.app.data.repo.PresenceRepo
import com.undal.syl.app.data.repo.VendorOrderRepo
import com.undal.syl.app.data.repo.VendorStoreRepo
import com.undal.syl.app.features.vendor.storeShareText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
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
    private val presenceRepo: PresenceRepo,
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
        val presence: PresenceHeartbeat.Presence? = null,
        /**
         * The last seven days the Worker's presence sweep wrote, newest first.
         * Empty until the first snapshot arrives, so the analytics card can say
         * "waiting for the first check-in" instead of showing a confident zero.
         */
        val presenceDays: List<com.undal.syl.app.core.model.PresenceDay> = emptyList(),
        val presenceLoaded: Boolean = false
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
    private val guard = com.undal.syl.app.core.util.UndalGuards.viewModel("reading the kitchen's dashboard") { message ->
        _state.value = _state.value.copy(infoMessage = message, busy = false)
    }

    /** Bumped by pull-to-refresh: restarting the listener forces a server read (V-022). */
    private val refreshKey = MutableStateFlow(0)

    /**
     * The age badges, ticking once a second — but only while a screen is looking.
     *
     * This was a `while (true)` loop inside `viewModelScope`, and a view model
     * lives for as long as its screen is on the back stack: on a kitchen phone
     * left in a pocket with the queue open, that was one wake-up per second, for
     * hours, for a number nobody was reading. `WhileSubscribed` ties the clock to
     * the screen instead, with five seconds of grace so a rotation does not
     * restart it.
     */
    val now: StateFlow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(1_000L)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), System.currentTimeMillis())

    init {
        observeStore()
        observeToday()
        observeWeek()
        observeHeartbeat()
        observePresenceHistory()
    }

    fun refresh(context: Context) {
        viewModelScope.launch(guard) {
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
        viewModelScope.launch(guard) {
            // On the clock: the owner watches the store open and close on its hours.
            storeRepo.observeStoreOnTheClock().collect { store ->
                _state.value = _state.value.copy(
                    store = store,
                    openState = StoreOpenState.of(store)
                )
            }
        }
    }

    private fun observeToday() {
        viewModelScope.launch(guard) {
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
        viewModelScope.launch(guard) {
            refreshKey.flatMapLatest { vendorOrderRepo.observeToday() }.collect { feed ->
                feed.orders.filter { it.status == OrderStatus.PLACED }
                    .forEach { order -> vendorOrderRepo.recordAlertIfNew(order) }
            }
        }
    }

    private fun observeWeek() {
        viewModelScope.launch(guard) {
            refreshKey.flatMapLatest { vendorOrderRepo.observeWeek() }.collect { feed ->
                _state.value = _state.value.copy(week = VendorOrder.weekSeries(feed.orders))
            }
        }
    }

    private fun observeHeartbeat() {
        viewModelScope.launch(guard) {
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

    /**
     * Seven days of connection samples: the uptime figure, the pauses the
     * watchdog applied by itself, and every outage window with its clock times.
     * Read once per dashboard open and kept live while it is on screen.
     */
    private fun observePresenceHistory() {
        viewModelScope.launch(guard) {
            presenceRepo.observeDays(PRESENCE_DAYS).collect { days ->
                _state.value = _state.value.copy(presenceDays = days, presenceLoaded = true)
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

    /**
     * The owner's switch, carrying the note customers read (V-017, V-075).
     *
     * On puts the store on its schedule — open now if the hours say so, and by
     * itself at the next opening if they do not. Off pauses it until it is turned
     * back on. The message says which of those just happened, with the time.
     */
    fun setOpen(context: Context, open: Boolean) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch(guard) {
            val store = _state.value.store
            val note = _state.value.note.trim().ifBlank {
                if (open) "The kitchen is taking orders now." else "The kitchen is closed for now."
            }
            val result = vendorStoreRepo.setOpen(open = open, note = note, store = store)
            if (open && result is UndalResult.Success) {
                PresenceHeartbeat.startService(context)
                PresenceHeartbeatWorker.ensureScheduled(context)
            }
            _state.value = _state.value.copy(
                busy = false,
                note = "",
                infoMessage = when (result) {
                    is UndalResult.Success -> when {
                        !open -> "The store is paused. It stays closed until you open it again."
                        StoreHours.isOpenAt(store) -> "The store is open. Customers can order now."
                        else -> "The store is on your schedule. It is closed now and opens by itself " +
                            (StoreHours.nextOpening(store)?.label ?: "when your hours begin") + "."
                    }
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

    /**
     * The owner has confirmed they are cooking: open the store and say so.
     *
     * This is what the opening reminder's notification lands on — one tap from a
     * push that asked "are you taking orders?" to a store that is open and
     * ringing, with the heartbeat service started in the same breath so the
     * watchdog's ten-minute rule has something to read.
     */
    fun confirmOpenNow(context: Context) {
        _state.value = _state.value.copy(note = "Confirmed on the phone — orders are welcome.")
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
        val text = storeShareText(storeName, host())
        // A build without the store's address says so, instead of sharing a link
        // that opens "site cannot be reached" in front of a customer.
        _state.value = if (text != null) {
            _state.value.copy(shareText = text)
        } else {
            _state.value.copy(
                infoMessage = "This build does not know the store's web address yet, so there is " +
                    "no link to share. Set the WORKER_HOST secret and build again."
            )
        }
    }

    private fun host(): String? = com.undal.syl.app.core.config.UndalBuildInfo.webStoreHost

    fun dismissMessage() {
        _state.value = _state.value.copy(infoMessage = null, shareText = "")
    }

    /** Signs out and stops every device-side process that belongs to the kitchen. */
    fun signOut(context: Context) {
        viewModelScope.launch(guard) {
            auth.currentUser?.uid?.let { uid -> registrar.unregister(uid) }
            PresenceHeartbeat.stopService(context)
            PresenceHeartbeatWorker.cancel(context)
            heartbeat.markOffline()
            // Everything that keeps this phone ringing belongs to the kitchen
            // session, so it goes with it: the live watcher, anything currently
            // ringing, and the watchdog that would revive both.
            KitchenWatchService.stop(context)
            OrderAlertController.stop()
            session.signOut()
        }
    }

    companion object {
        /** A working week is enough to see a pattern without scraping a year. */
        const val PRESENCE_DAYS = 7
    }
}
