package com.undal.syl.app.features.store

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.undal.syl.app.core.cart.CartLine
import com.undal.syl.app.core.maps.UndalLocation
import com.undal.syl.app.core.model.Campaign
import com.undal.syl.app.core.model.MenuCard
import com.undal.syl.app.core.model.MenuSnapshot
import com.undal.syl.app.core.model.MenuSort
import com.undal.syl.app.core.model.StoreConfig
import com.undal.syl.app.core.model.StoreOpenState
import com.undal.syl.app.core.util.Distance
import com.undal.syl.app.core.util.Money
import com.undal.syl.app.core.util.TimeDhaka
import com.undal.syl.app.data.repo.AuthRepo
import com.undal.syl.app.data.repo.CartRepo
import com.undal.syl.app.data.repo.CouponRepo
import com.undal.syl.app.data.repo.MenuRepo
import com.undal.syl.app.data.repo.StoreRepo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The store front (C-021 … C-037).
 *
 * One screen, one question answered honestly: can this kitchen reach you, and
 * what can you order right now. Every number shown here — the fee, the radius
 * verdict, the minimum order — is computed from the live store document and the
 * customer's own pinned address, never hard-coded.
 */
@HiltViewModel
class StoreViewModel @Inject constructor(
    private val storeRepo: StoreRepo,
    private val menuRepo: MenuRepo,
    private val cartRepo: CartRepo,
    private val authRepo: AuthRepo,
    private val couponRepo: CouponRepo
) : ViewModel() {

    /** How the customer's pinned address stands against the kitchen's radius. */
    sealed interface Reachability {
        data object Unknown : Reachability

        data class Deliverable(val distanceKm: Double, val feePaisa: Long) : Reachability

        data class TooFar(val distanceKm: Double, val radiusKm: Double) : Reachability

        data object NoPin : Reachability
    }

    data class State(
        val loading: Boolean = true,
        val store: StoreConfig = StoreConfig.unloaded(),
        val openState: StoreOpenState = StoreOpenState.of(StoreConfig.unloaded()),
        val menu: MenuSnapshot = MenuSnapshot(),
        val cards: List<MenuCard> = emptyList(),
        val campaigns: List<Campaign> = emptyList(),
        /**
         * The offers the kitchen is running, from the same public board the web
         * store prints its banners from.
         *
         * A code a customer cannot see is a code they cannot type, and an
         * automatic offer that this basket does not reach yet is invisible — so
         * without this list the app's only coupon surface is the field in the
         * basket, which is a place to *enter* a code and not a place to find one.
         */
        val offers: List<CouponRepo.Coupon> = emptyList(),
        val query: String = "",
        val categoryId: String? = null,
        val subcategoryId: String? = null,
        val sort: MenuSort = MenuSort.RECOMMENDED,
        val address: UndalLocation? = null,
        val reachability: Reachability = Reachability.Unknown,
        val feeLabel: String = "",
        val minimumOrderLabel: String = "",
        val cartCount: Int = 0,
        val cartSubtotalLabel: String = "",
        val quantities: Map<String, Int> = emptyMap(),
        val errorMessage: String? = null,
        val greeting: String = "",
        val addressLabel: String = ""
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
    private val guard = com.undal.syl.app.core.util.UndalGuards.viewModel("reading the menu") { message ->
        _state.value = _state.value.copy(errorMessage = message)
    }

    private val addressFlow = MutableStateFlow<UndalLocation?>(null)
    private val filterFlow = MutableStateFlow(Filter())
    private val quantitiesFlow = MutableStateFlow<Map<String, Int>>(emptyMap())

    private data class Filter(
        val query: String = "",
        val categoryId: String? = null,
        val subcategoryId: String? = null,
        val sort: MenuSort = MenuSort.RECOMMENDED
    )

    init {
        viewModelScope.launch(guard) {
            refreshAddress()
        }
        // Quantities per dish drive the stepper on every card, so the grid shows
        // exactly how many of this dish are already in the cart.
        viewModelScope.launch(guard) {
            cartRepo.cart.collect { cart ->
                quantitiesFlow.value = buildMap {
                    cart.lines.forEach { line ->
                        put(line.id, (get(line.id) ?: 0) + line.quantity)
                    }
                }
            }
        }
        viewModelScope.launch(guard) {
            val filterAndQuantities = combine(filterFlow, quantitiesFlow) { filter, quantities ->
                filter to quantities
            }
            combine(
                storeRepo.observeStore(),
                menuRepo.observeMenu(),
                cartRepo.cart,
                addressFlow,
                filterAndQuantities
            ) { store, menu, cart, address, (filter, quantities) ->
                val openState = StoreOpenState.of(store)
                val cards = menuRepo.filter(
                    snapshot = menu,
                    categoryId = filter.categoryId,
                    subcategoryId = filter.subcategoryId,
                    query = filter.query,
                    sort = filter.sort
                )
                State(
                    loading = false,
                    store = store,
                    openState = openState,
                    menu = menu,
                    cards = cards,
                    campaigns = menu.campaigns,
                    query = filter.query,
                    categoryId = filter.categoryId,
                    subcategoryId = filter.subcategoryId,
                    sort = filter.sort,
                    address = address,
                    reachability = reachabilityOf(store, address),
                    feeLabel = "${Money.format(store.deliveryPerKmAmountPaisa)} per km",
                    minimumOrderLabel = Money.format(store.minOrderAmountPaisa),
                    cartCount = cart.itemCount,
                    cartSubtotalLabel = Money.format(cart.subtotalPaisa),
                    quantities = quantities,
                    errorMessage = null,
                    greeting = greetingForHour(TimeDhaka.now().hour),
                    addressLabel = addressLabelOf(address)
                )
            }.collect { next ->
                // The offers are not an input to this combine: they come from the
                // Worker's public board on their own clock, and the menu snapshot
                // rebuilds the whole State on every emission. Carrying them across
                // is what stops a menu update from emptying the offer strip.
                _state.value = next.copy(offers = _state.value.offers)
            }
        }
        viewModelScope.launch(guard) { refreshOffers() }
    }

    /** The header greeting, told by the Dhaka clock (C-021). */
    private fun greetingForHour(hour: Int): String = when (hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        in 17..21 -> "Good evening"
        else -> "Late-night craving?"
    }

    /** The chip under the greeting: the address title, its description as fallback. */
    private fun addressLabelOf(address: UndalLocation?): String {
        if (address == null || !address.isValid) return ""
        return address.title.trim().ifBlank { address.desc.trim() }
    }

    /** Re-reads the pinned address; call this whenever the customer returns. */
    fun refreshAddress() {
        viewModelScope.launch(guard) {
            addressFlow.value = authRepo.loadProfileLocation()
        }
    }

    /**
     * The offers, as the kitchen published them.
     *
     * The board is cached in [CouponRepo] for a minute, so this is cheap to call
     * every time the customer comes back to the store. An empty answer is a real
     * answer — a kitchen running no promotion shows no strip — and a failed read
     * leaves the last good list rather than blanking the screen, because an offer
     * that flickers out is worse than one that is a minute stale.
     */
    fun refreshOffers() {
        viewModelScope.launch(guard) {
            val board = couponRepo.board()
            if (board.isNotEmpty() || _state.value.offers.isEmpty()) {
                _state.value = _state.value.copy(offers = board)
            }
        }
    }

    fun onQueryChange(value: String) {
        filterFlow.value = filterFlow.value.copy(query = value)
    }

    fun onCategorySelected(categoryId: String?) {
        val current = filterFlow.value
        val sameCategory = current.categoryId == categoryId
        filterFlow.value = current.copy(
            categoryId = if (sameCategory && categoryId != null) null else categoryId,
            subcategoryId = null
        )
    }

    fun onSubcategorySelected(subcategoryId: String?) {
        val current = filterFlow.value
        filterFlow.value = current.copy(
            subcategoryId = if (current.subcategoryId == subcategoryId) null else subcategoryId
        )
    }

    fun onSortSelected(sort: MenuSort) {
        filterFlow.value = filterFlow.value.copy(sort = sort)
    }

    fun clearFilters() {
        filterFlow.value = Filter()
    }

    /** Quick add straight from the grid, with the dish's own note left empty. */
    fun quickAdd(itemId: String) {
        viewModelScope.launch(guard) {
            val item = _state.value.menu.dish(itemId)
            if (item != null) {
                if (!item.available) {
                    _state.value = _state.value.copy(errorMessage = "${item.name} is finished for today.")
                    return@launch
                }
                cartRepo.add(
                    CartLine(
                        id = item.id,
                        name = item.name,
                        unitAmountPaisa = item.payablePaisa,
                        quantity = 1,
                        imageUrl = item.imageUrl,
                        veg = item.veg,
                        spiceLevel = item.spiceLevel
                    )
                )
            } else {
                val combo = _state.value.menu.combo(itemId) ?: return@launch
                if (!combo.available) {
                    _state.value = _state.value.copy(errorMessage = "${combo.name} is finished for today.")
                    return@launch
                }
                cartRepo.add(
                    CartLine(
                        id = combo.id,
                        name = combo.name,
                        unitAmountPaisa = combo.amountPaisa,
                        quantity = 1,
                        imageUrl = combo.imageUrl,
                        isCombo = true
                    )
                )
            }
        }
    }

    fun quickRemove(itemId: String) {
        viewModelScope.launch(guard) {
            val cart = cartRepo.current()
            val line = cart.lines.firstOrNull { it.id == itemId } ?: return@launch
            cartRepo.setQuantity(line.id, line.note, line.quantity - 1)
        }
    }

    fun clearMessage() {
        _state.value = _state.value.copy(errorMessage = null)
    }

    private fun reachabilityOf(store: StoreConfig, address: UndalLocation?): Reachability {
        if (address == null) return Reachability.Unknown
        if (!store.hasPublishedPin) return Reachability.NoPin
        val distance = store.distanceFrom(address.lat, address.lng) ?: return Reachability.Unknown
        return if (store.isServiceable(distance)) {
            Reachability.Deliverable(distanceKm = distance, feePaisa = store.deliveryFeeFor(distance))
        } else {
            Reachability.TooFar(distanceKm = distance, radiusKm = store.radiusKm)
        }
    }

    /** Plain-language verdict used by the banner under the store status. */
    fun reachabilityLine(reachability: Reachability): String = when (reachability) {
        is Reachability.Unknown -> "Add your delivery address to see the exact fee."
        is Reachability.NoPin -> "The kitchen is publishing its delivery area."
        is Reachability.Deliverable ->
            "We deliver to ${reachability.distanceKm.let { Distance.formatKm(it) }} — fee " +
                Money.format(reachability.feePaisa)
        is Reachability.TooFar ->
            "That address is ${Distance.formatKm(reachability.distanceKm)} away, outside our " +
                "${Distance.formatKm(reachability.radiusKm)} delivery area."
    }
}
