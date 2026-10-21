package com.undal.syl.app.features.cart

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.undal.syl.app.core.cart.Cart
import com.undal.syl.app.core.cart.CartLine
import com.undal.syl.app.core.maps.UndalLocation
import com.undal.syl.app.core.model.MenuSnapshot
import com.undal.syl.app.core.model.StoreConfig
import com.undal.syl.app.core.model.StoreOpenState
import com.undal.syl.app.core.util.Distance
import com.undal.syl.app.core.util.Money
import com.undal.syl.app.core.util.UndalResult
import com.undal.syl.app.data.repo.AuthRepo
import com.undal.syl.app.data.repo.CartRepo
import com.undal.syl.app.data.repo.CouponRepo
import com.undal.syl.app.data.repo.MenuRepo
import com.undal.syl.app.data.repo.OrderRepo
import com.undal.syl.app.data.repo.StoreRepo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Cart and checkout (C-046 … C-062).
 *
 * The cart is the customer's, the prices are the kitchen's, and this screen is
 * where the two are reconciled: the moment it opens, every line is compared with
 * the live menu, prices are brought up to date with an explanation, and a dish
 * that has been switched off is flagged instead of silently ordered.
 *
 * Nothing is hidden behind the button: each reason the order cannot be placed is
 * listed with its own sentence, and the button says which one is blocking.
 */
@HiltViewModel
class CartViewModel @Inject constructor(
    private val cartRepo: CartRepo,
    private val storeRepo: StoreRepo,
    private val menuRepo: MenuRepo,
    private val couponRepo: CouponRepo,
    private val orderRepo: OrderRepo,
    private val authRepo: AuthRepo
) : ViewModel() {

    data class State(
        val loading: Boolean = true,
        val lines: List<CartLine> = emptyList(),
        val unavailableLineIds: Set<String> = emptySet(),
        val priceNotes: List<String> = emptyList(),
        val store: StoreConfig = StoreConfig.unloaded(),
        val openState: StoreOpenState = StoreOpenState.of(StoreConfig.unloaded()),
        val address: UndalLocation? = null,
        val distanceKm: Double? = null,
        val deliveryPaisa: Long = 0L,
        val subtotalPaisa: Long = 0L,
        val discountPaisa: Long = 0L,
        val totalPaisa: Long = 0L,
        val couponCode: String = "",
        val couponMessage: String? = null,
        val couponIsError: Boolean = false,
        val couponChecking: Boolean = false,
        /** True when the discount on the bill was applied by the kitchen, not typed. */
        val autoApplied: Boolean = false,
        /** "SAVE10 applied automatically — ৳ 50 off." */
        val autoMessage: String? = null,
        /** "20% off, up to ৳ 100" — what the automatic coupon is worth. */
        val autoHeadline: String = "",
        val note: String = "",
        val blockers: List<String> = emptyList(),
        val placing: Boolean = false,
        val placedOrderId: String? = null,
        val placedShortId: String? = null,
        val placedTotalLabel: String = "",
        val message: String? = null,
        val lastRemoved: CartLine? = null
    ) {

        val itemCount: Int get() = lines.sumOf { it.quantity }

        val subtotalLabel: String get() = Money.format(subtotalPaisa)

        val deliveryLabel: String get() = Money.format(deliveryPaisa)

        val discountLabel: String get() = "- ${Money.format(discountPaisa)}"

        val totalLabel: String get() = Money.format(totalPaisa)

        val canPlace: Boolean get() = blockers.isEmpty() && !placing && lines.isNotEmpty()

        val distanceCaption: String
            get() = distanceKm?.let { "Billed whole kilometres · ${Distance.formatKm(it)} from the kitchen" }
                ?: "Add your address to see the exact delivery fee."
    }

    /** Half a second of quiet: a customer tapping "+" four times is one basket. */
    private companion object {
        const val AUTO_OFFER_DEBOUNCE_MILLIS = 450L
    }

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
    private val guard = com.undal.syl.app.core.util.UndalGuards.viewModel("reading your basket") { message ->
        _state.value = _state.value.copy(message = message)
    }

    private val addressFlow = MutableStateFlow<UndalLocation?>(null)
    private val noteFlow = MutableStateFlow("")
    private val priceNotes = mutableListOf<String>()

    /** The coupon as the server described it, kept so the discount tracks the subtotal. */
    private var appliedCoupon: CouponRepo.Coupon? = null

    /**
     * The coupon the kitchen's own board would apply to this basket, priced
     * whenever the basket changes. Null until the first answer arrives, and null
     * forever on a phone with no network — the basket is then priced without it,
     * which is the honest outcome.
     */
    private val autoOfferFlow = MutableStateFlow<CouponRepo.Offer?>(null)

    /** Everything the screen is built from, so the offer can join the combine. */
    private data class Inputs(
        val store: StoreConfig,
        val menu: MenuSnapshot,
        val cart: Cart,
        val address: UndalLocation?,
        val note: String
    )

    init {
        viewModelScope.launch(guard) { refreshAddress() }
        viewModelScope.launch(guard) {
            val inputs = combine(
                // On the clock: the store closes at closing time with nothing in
                // Firestore changing, and the bill must stop offering to order.
                storeRepo.observeStoreOnTheClock(),
                menuRepo.observeMenu(),
                cartRepo.cart,
                addressFlow,
                noteFlow
            ) { store, menu, cart, address, note -> Inputs(store, menu, cart, address, note) }
            // The automatic offer is a sixth input, so the bill, the badge and the
            // "applied automatically" line always describe one basket.
            combine(inputs, autoOfferFlow) { current, offer -> buildState(current, offer) }
                .collect { next -> _state.value = next }
        }
        viewModelScope.launch(guard) {
            // Re-price the automatic coupon when the basket or the ride changes —
            // not on every keystroke, and never because the discount itself moved
            // (that would be a loop: the offer changes the very number watched).
            _state
                .map { it.subtotalPaisa to it.deliveryPaisa }
                .distinctUntilChanged()
                .debounce(AUTO_OFFER_DEBOUNCE_MILLIS)
                .collect { (subtotal, delivery) -> refreshAutomaticCoupon(subtotal, delivery) }
        }
    }

    /**
     * Asks the kitchen's board what it would give this basket, unless the customer
     * has already chosen a code of their own — a typed code always wins and is
     * never silently replaced by an automatic one.
     */
    private suspend fun refreshAutomaticCoupon(subtotalPaisa: Long, deliveryPaisa: Long) {
        if (appliedCoupon != null || subtotalPaisa <= 0L) {
            if (autoOfferFlow.value != null) autoOfferFlow.value = null
            return
        }
        val offer = couponRepo.automatic(subtotalPaisa, deliveryPaisa)
        if (offer != autoOfferFlow.value) autoOfferFlow.value = offer
    }

    fun refreshAddress() {
        viewModelScope.launch(guard) { addressFlow.value = authRepo.loadProfileLocation() }
    }

    private suspend fun buildState(inputs: Inputs, autoOffer: CouponRepo.Offer?): State {
        val store = inputs.store
        val menu = inputs.menu
        val cart = inputs.cart
        val address = inputs.address
        val note = inputs.note
        // Reconcile the cart with the live menu before showing anything.
        val unavailable = mutableSetOf<String>()
        var reconciled = cart
        cart.lines.forEach { line ->
            val livePrice = when {
                line.isCombo -> menu.combo(line.id)?.takeIf { it.available }?.amountPaisa
                else -> menu.dish(line.id)?.takeIf { it.available }?.payablePaisa
            }
            if (livePrice == null) {
                unavailable += line.id
            } else if (livePrice != line.unitAmountPaisa) {
                reconciled = reconciled.withQuantity(line.id, line.note, line.quantity)
                    .let { current ->
                        current.copy(
                            lines = current.lines.map { candidate ->
                                if (candidate.id == line.id && candidate.note == line.note) {
                                    candidate.copy(unitAmountPaisa = livePrice)
                                } else {
                                    candidate
                                }
                            }
                        )
                    }
                priceNotes += "${line.name} is now ${Money.format(livePrice)} — your cart has been updated."
            }
        }
        reconciled.lines.forEach { line ->
            val stillThere = when {
                line.isCombo -> menu.combo(line.id) != null
                else -> menu.dish(line.id) != null
            }
            if (!stillThere) unavailable += line.id
        }
        if (reconciled.lines != cart.lines) {
            cartRepo.replaceLines(reconciled)
            reconciled = cartRepo.current()
        }

        val distance = if (address != null) store.distanceFrom(address.lat, address.lng) else null
        val delivery = distance?.let { store.deliveryFeeFor(it) } ?: 0L
        val subtotal = reconciled.subtotalPaisa

        // A typed code wins; otherwise the kitchen's own automatic offer, priced by
        // the same rule the server runs before it writes the order.
        val typedDiscount = appliedCoupon?.discountFor(subtotal, delivery) ?: 0L
        val automatic = autoOffer.takeIf { appliedCoupon == null && typedDiscount <= 0L }
        val discount = typedDiscount + (automatic?.discountPaisa ?: 0L)
        val effectiveDiscount = if (unavailable.isEmpty()) discount else 0L
        val total = Money.grandTotal(subtotal, delivery, effectiveDiscount)
        val openState = StoreOpenState.of(store)

        return State(
            loading = false,
            lines = reconciled.lines,
            unavailableLineIds = unavailable,
            priceNotes = priceNotes.toList(),
            store = store,
            openState = openState,
            address = address,
            distanceKm = distance,
            deliveryPaisa = delivery,
            subtotalPaisa = subtotal,
            discountPaisa = effectiveDiscount,
            totalPaisa = total,
            couponCode = reconciled.couponCode,
            couponMessage = _state.value.couponMessage,
            couponIsError = _state.value.couponIsError,
            couponChecking = false,
            autoApplied = automatic != null && unavailable.isEmpty(),
            autoMessage = automatic?.message?.takeIf { unavailable.isEmpty() },
            autoHeadline = automatic?.headline.orEmpty(),
            note = note,
            blockers = blockersFor(openState, store, address, distance, subtotal, unavailable, reconciled),
            placing = _state.value.placing,
            placedOrderId = _state.value.placedOrderId,
            placedShortId = _state.value.placedShortId,
            placedTotalLabel = _state.value.placedTotalLabel,
            message = _state.value.message,
            lastRemoved = _state.value.lastRemoved
        )
    }

    /**
     * Every reason the order cannot be placed, in the order the customer should
     * fix them (C-060).
     */
    private fun blockersFor(
        openState: StoreOpenState,
        store: StoreConfig,
        address: UndalLocation?,
        distance: Double?,
        subtotal: Long,
        unavailable: Set<String>,
        cart: Cart
    ): List<String> = buildList {
        if (!openState.serviceable) {
            add(
                when (openState.reason) {
                    StoreOpenState.Reason.AUTO_CLOSED ->
                        "The kitchen is offline, so it is not taking orders. Your cart is saved."
                    StoreOpenState.Reason.OUTSIDE_HOURS ->
                        "The kitchen is closed right now · ${openState.nextOpeningLine}. Your cart is saved until then."
                    StoreOpenState.Reason.PAUSED ->
                        "The kitchen is closed: ${store.manualOverrideNote.ifBlank { "check back soon" }}"
                    else -> "${openState.headline}: ${openState.detail}"
                }
            )
        }
        if (address == null) {
            add("Add your delivery address so we can calculate the ride.")
        }
        if (distance != null && !store.isServiceable(distance)) {
            add(
                "That address is ${Distance.formatKm(distance)} away — outside the " +
                    "${Distance.formatKm(store.radiusKm)} we can deliver."
            )
        }
        if (unavailable.isNotEmpty()) {
            val names = cart.lines.filter { it.id in unavailable }.joinToString(", ") { it.name }
            add("Finished for today: $names. Remove those lines to continue.")
        }
        if (subtotal < store.minOrderAmountPaisa && subtotal > 0) {
            add(
                "The kitchen's minimum order is ${Money.format(store.minOrderAmountPaisa)} — " +
                    "add ${Money.format(store.minOrderAmountPaisa - subtotal)} more."
            )
        }
        if (cart.lines.isEmpty()) {
            add("Your cart is empty.")
        }
    }

    fun increment(line: CartLine) {
        viewModelScope.launch(guard) { cartRepo.setQuantity(line.id, line.note, line.quantity + 1) }
    }

    fun decrement(line: CartLine) {
        viewModelScope.launch(guard) {
            if (line.quantity <= 1) {
                _state.value = _state.value.copy(lastRemoved = line)
            }
            cartRepo.setQuantity(line.id, line.note, line.quantity - 1)
        }
    }

    fun remove(line: CartLine) {
        viewModelScope.launch(guard) {
            _state.value = _state.value.copy(lastRemoved = line)
            cartRepo.remove(line.id, line.note)
        }
    }

    fun undoRemove() {
        val line = _state.value.lastRemoved ?: return
        viewModelScope.launch(guard) {
            cartRepo.add(line)
            _state.value = _state.value.copy(lastRemoved = null)
        }
    }

    fun dismissUndo() {
        _state.value = _state.value.copy(lastRemoved = null)
    }

    fun clearCart() {
        viewModelScope.launch(guard) {
            appliedCoupon = null
            autoOfferFlow.value = null
            cartRepo.clear()
            _state.value = _state.value.copy(
                couponCode = "",
                couponMessage = "Cart cleared.",
                couponIsError = false
            )
        }
    }

    fun onNoteChange(value: String) {
        noteFlow.value = value.take(200)
    }

    /** Validates the code against the live coupon document (C-052). */
    fun applyCoupon(code: String) {
        val current = _state.value
        if (current.subtotalPaisa <= 0L) {
            _state.value = current.copy(couponMessage = "Add something to the cart first.", couponIsError = true)
            return
        }
        _state.value = current.copy(couponChecking = true, couponMessage = null)
        viewModelScope.launch(guard) {
            when (val outcome = couponRepo.validate(code, current.subtotalPaisa, current.deliveryPaisa)) {
                is CouponRepo.Outcome.Valid -> {
                    appliedCoupon = outcome.coupon
                    // A typed code replaces the automatic one rather than stacking
                    // with it: two discounts on one order is not a promotion, it is
                    // a mistake.
                    autoOfferFlow.value = null
                    cartRepo.applyCoupon(outcome.coupon.code)
                    _state.value = _state.value.copy(
                        couponCode = outcome.coupon.code,
                        couponChecking = false,
                        couponMessage = "${outcome.coupon.code} applied — ${Money.format(outcome.discountPaisa)} off. " +
                            outcome.coupon.description,
                        couponIsError = false,
                        discountPaisa = outcome.discountPaisa
                    )
                }

                is CouponRepo.Outcome.Rejected -> {
                    _state.value = _state.value.copy(
                        couponChecking = false,
                        couponMessage = outcome.message,
                        couponIsError = true
                    )
                }
            }
        }
    }

    fun removeCoupon() {
        viewModelScope.launch(guard) {
            appliedCoupon = null
            cartRepo.clearCoupon()
            // Removing a typed code lets the kitchen's own offer come back.
            refreshAutomaticCoupon(_state.value.subtotalPaisa, _state.value.deliveryPaisa)
            _state.value = _state.value.copy(
                couponCode = "",
                couponMessage = "Coupon removed.",
                couponIsError = false,
                discountPaisa = 0L
            )
        }
    }

    /** Places the order (C-061) and keeps the result for the success screen. */
    fun placeOrder() {
        val current = _state.value
        val address = current.address
        if (address == null) {
            _state.value = current.copy(message = "Add your delivery address before ordering.")
            return
        }
        if (!address.isValid) {
            _state.value = current.copy(
                message = "Your address needs a title, a description and a delivery note before ordering."
            )
            return
        }
        if (!current.canPlace) {
            _state.value = current.copy(
                message = current.blockers.firstOrNull() ?: "The order cannot be placed yet."
            )
            return
        }

        _state.value = current.copy(placing = true, message = null)
        viewModelScope.launch(guard) {
            val draft = OrderRepo.OrderDraft(
                store = current.store,
                address = address,
                distanceKm = current.distanceKm ?: 0.0,
                customerName = authRepo.loadProfileName().orEmpty().ifBlank { "Undal customer" },
                phone = authRepo.loadProfilePhone().orEmpty(),
                lines = current.lines,
                // Whatever the bill shows is what the order records: a typed code,
                // or the kitchen's own automatic one. The order path re-checks it
                // with the same rule before it writes anything.
                couponCode = current.couponCode.ifBlank { autoOfferFlow.value?.code.orEmpty() },
                note = current.note
            )
            when (val result = orderRepo.placeOrder(draft)) {
                is UndalResult.Success -> {
                    cartRepo.clear()
                    appliedCoupon = null
                    priceNotes.clear()
                    _state.value = _state.value.copy(
                        placing = false,
                        placedOrderId = result.value.orderId,
                        placedShortId = result.value.shortId,
                        placedTotalLabel = Money.format(result.value.totalAmountPaisa),
                        lines = emptyList(),
                        couponCode = "",
                        message = null
                    )
                }

                is UndalResult.Failure -> {
                    _state.value = _state.value.copy(placing = false, message = result.message)
                }
            }
        }
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }

    fun dismissSuccess() {
        _state.value = _state.value.copy(placedOrderId = null, placedShortId = null, placedTotalLabel = "")
    }
}
