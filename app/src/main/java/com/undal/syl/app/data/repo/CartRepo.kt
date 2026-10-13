package com.undal.syl.app.data.repo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.undal.syl.app.core.cart.Cart
import com.undal.syl.app.core.cart.CartCodec
import com.undal.syl.app.core.cart.CartLine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The cart, stored on the device (C-035 · C-042 · C-043).
 *
 * A cart is not a document on the server: it is a private intention that must
 * survive closing the app, a train tunnel, and the phone being handed to a
 * friend. DataStore gives exactly that, and nothing about a cart someone
 * abandoned needs to reach the kitchen's database.
 */
@Singleton
class CartRepo @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {

    val cart: Flow<Cart> = dataStore.data.map { preferences ->
        CartCodec.decode(preferences[KEY_CART])
    }

    suspend fun current(): Cart = CartCodec.decode(dataStore.data.first()[KEY_CART])

    suspend fun add(line: CartLine): Cart = mutate { it.withLine(line) }

    suspend fun setQuantity(lineId: String, note: String, quantity: Int): Cart =
        mutate { it.withQuantity(lineId, note, quantity) }

    suspend fun remove(lineId: String, note: String): Cart = mutate { it.without(lineId, note) }

    suspend fun applyCoupon(code: String): Cart = mutate { it.withCoupon(code) }

    suspend fun clearCoupon(): Cart = mutate { it.withCoupon("") }

    suspend fun clear(): Cart = mutate { it.cleared() }

    /**
     * Writes a cart that was reconciled against the live menu (price updates,
     * removed dishes) without touching its coupon.
     */
    suspend fun replaceLines(cart: Cart): Cart = mutate { current ->
        current.copy(
            lines = cart.lines.take(Cart.MAX_LINES),
            updatedAtMillis = System.currentTimeMillis()
        )
    }

    /**
     * Reads, changes and writes the cart in one place so two fast taps on the
     * quantity stepper can never lose each other's update.
     */
    private suspend fun mutate(transform: (Cart) -> Cart): Cart {
        var result = Cart()
        dataStore.edit { preferences ->
            val updated = transform(CartCodec.decode(preferences[KEY_CART]))
            if (updated.lines.size > Cart.MAX_LINES) {
                return@edit
            }
            preferences[KEY_CART] = CartCodec.encode(updated)
        }
        result = CartCodec.decode(dataStore.data.first()[KEY_CART])
        return result
    }

    private companion object {
        private val KEY_CART = stringPreferencesKey("cart_json")
    }
}
