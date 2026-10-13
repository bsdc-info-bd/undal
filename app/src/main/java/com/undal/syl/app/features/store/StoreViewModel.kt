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
import com.undal.syl.app.data.repo.AuthRepo
import com.undal.syl.app.data.repo.CartRepo
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
    private val authRepo: AuthRepo
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
        val errorMessage: String? = null
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

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
        viewModelScope.launch {
            refreshAddress()
        }
        // Quantities per dish drive the stepper on every card, so the grid shows
        // exactly how many of this dish are already in the cart.
        viewModelScope.launch {
            cartRepo.cart.collect { cart ->
                quantitiesFlow.value = buildMap {
                    cart.lines.forEach { line ->
                        put(line.id, (get(line.id) ?: 0) + line.quantity)
                    }
                }
            }
        }
        viewModelScope.launch {
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
                    errorMessage = null
                )
            }.collect { next -> _state.value = next }
        }
    }

    /** Re-reads the pinned address; call this whenever the customer returns. */
    fun refreshAddress() {
        viewModelScope.launch {
            addressFlow.value = authRepo.loadProfileLocation()
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
        viewModelScope.launch {
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
        viewModelScope.launch {
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
