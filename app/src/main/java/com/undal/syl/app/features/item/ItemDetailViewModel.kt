package com.undal.syl.app.features.item

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.undal.syl.app.core.cart.CartLine
import com.undal.syl.app.core.model.Combo
import com.undal.syl.app.core.model.MenuCard
import com.undal.syl.app.core.model.MenuItem
import com.undal.syl.app.core.telemetry.UndalTelemetry
import com.undal.syl.app.core.util.Money
import com.undal.syl.app.data.repo.CartRepo
import com.undal.syl.app.data.repo.MenuRepo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The dish page (C-039 … C-045).
 *
 * A dish is a decision, so this screen shows everything that changes the
 * decision — photo, description, discount, spice, what is inside a combo, the
 * cooking note — and nothing else. Related dishes come from the same category of
 * the live menu, ordered by what people actually order.
 */
@HiltViewModel
class ItemDetailViewModel @Inject constructor(
    private val menuRepo: MenuRepo,
    private val cartRepo: CartRepo,
    private val telemetry: UndalTelemetry
) : ViewModel() {

    data class State(
        val loading: Boolean = true,
        val card: MenuCard? = null,
        val related: List<MenuCard> = emptyList(),
        val comboContents: List<Pair<MenuItem, Int>> = emptyList(),
        val quantity: Int = 1,
        val note: String = "",
        val inCartQuantity: Int = 0,
        val message: String? = null
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var cardId: String = ""

    /** One event per opened page, not one per menu update (P-024). */
    private var offerLogged = false

    fun bind(id: String) {
        if (cardId == id) return
        cardId = id
        viewModelScope.launch {
            menuRepo.observeMenu().collect { snapshot ->
                val card = snapshot.card(id)
                val comboContents = (card as? MenuCard.Bundle)?.combo?.itemRefs.orEmpty().mapNotNull { ref ->
                    snapshot.dish(ref.menuItemId)?.let { dish -> dish to ref.qty }
                }
                val dish = (card as? MenuCard.Dish)?.item
                val related = snapshot.items
                    .filter { it.id != id && it.categoryId == dish?.categoryId }
                    .sortedByDescending { it.orderCount }
                    .take(6)
                    .map { MenuCard.Dish(it) }

                if (!offerLogged) {
                    offerLogged = true
                    telemetry.offerOpened(cardId, if (card is MenuCard.Bundle) "combo" else "dish")
                }

                _state.value = _state.value.copy(
                    loading = false,
                    card = card,
                    related = related,
                    comboContents = comboContents
                )
            }
        }
        viewModelScope.launch {
            cartRepo.cart.collect { cart ->
                val quantity = cart.lines.filter { it.id == id }.sumOf { it.quantity }
                _state.value = _state.value.copy(inCartQuantity = quantity)
            }
        }
    }

    fun setQuantity(value: Int) {
        _state.value = _state.value.copy(quantity = value.coerceIn(1, 50))
    }

    fun increment() = setQuantity(_state.value.quantity + 1)

    fun decrement() = setQuantity(_state.value.quantity - 1)

    fun onNoteChange(value: String) {
        _state.value = _state.value.copy(note = value.take(140))
    }

    /** Adds the chosen quantity with the customer's cooking note (C-043). */
    fun addToCart(onAdded: () -> Unit) {
        val card = _state.value.card ?: return
        if (!card.available) {
            _state.value = _state.value.copy(message = "This dish is finished for today.")
            return
        }
        viewModelScope.launch {
            val line = when (card) {
                is MenuCard.Dish -> CartLine(
                    id = card.item.id,
                    name = card.item.name,
                    unitAmountPaisa = card.item.payablePaisa,
                    quantity = _state.value.quantity,
                    note = _state.value.note.trim(),
                    imageUrl = card.item.imageUrl,
                    veg = card.item.veg,
                    spiceLevel = card.item.spiceLevel
                )

                is MenuCard.Bundle -> CartLine(
                    id = card.combo.id,
                    name = card.combo.name,
                    unitAmountPaisa = card.combo.amountPaisa,
                    quantity = _state.value.quantity,
                    note = _state.value.note.trim(),
                    imageUrl = card.combo.imageUrl,
                    isCombo = true
                )
            }
            cartRepo.add(line)
            _state.value = _state.value.copy(message = null)
            onAdded()
        }
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }

    /** The text of the share sheet for the dish deep link (C-045). */
    fun shareText(): String {
        val card = _state.value.card ?: return ""
        val name = card.name
        val price = Money.format(card.amountPaisa)
        return "$name · $price · cash on delivery from the Undal HomeKitchen in Sylhet."
    }

    fun comboSummary(combo: Combo, dishNames: Map<String, String>): String =
        combo.itemRefs.joinToString(", ") { ref ->
            val name = dishNames[ref.menuItemId] ?: "item"
            if (ref.qty > 1) "${ref.qty}× $name" else name
        }
}
