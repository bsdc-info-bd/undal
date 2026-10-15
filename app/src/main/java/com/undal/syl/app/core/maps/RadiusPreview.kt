package com.undal.syl.app.core.maps

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.undal.syl.app.core.theme.UndalDimens

/**
 * The delivery-radius preview (PART K-2 · V-080 · V-081).
 *
 * The vendor drags a slider and this map draws the exact circle those kilometres
 * buy, measured in real metres around the real kitchen pin. Seeing the boundary
 * is the difference between "5 km sounds fine" and "that cuts off my regular
 * customers in Amberkhana".
 *
 * It is the same engine as every other map in the app — [UndalOsmMap] — so the
 * circle is the very polygon the haversine gate measures against, the pin is the
 * very point the fee is computed from, and a fix to the map is a fix here.
 *
 * A customer's pin can be shown at the same time: the vendor's honest question is
 * almost always "is *this* address inside the line?", and answering it needs both
 * points on one screen.
 */
@Composable
fun UndalRadiusPreview(
    storeLat: Double,
    storeLng: Double,
    radiusKm: Double,
    modifier: Modifier = Modifier,
    height: Dp = UndalDimens.mapHeight,
    customerLat: Double? = null,
    customerLng: Double? = null
) {
    val pins = buildList {
        add(
            UndalMapPin(
                id = KITCHEN_PIN_ID,
                lat = storeLat,
                lng = storeLng,
                title = "Our kitchen",
                role = UndalPinRole.Kitchen
            )
        )
        if (customerLat != null && customerLng != null) {
            add(
                UndalMapPin(
                    id = CUSTOMER_PIN_ID,
                    lat = customerLat,
                    lng = customerLng,
                    title = "Customer",
                    role = UndalPinRole.Customer
                )
            )
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        UndalOsmMap(
            latitude = storeLat,
            longitude = storeLng,
            height = height,
            zoom = UndalMapGeometry.zoomForRadius(radiusKm),
            pins = pins,
            circle = UndalMapCircle(storeLat, storeLng, radiusKm),
            interactive = true,
            showZoomButtons = true,
            showScaleBar = true,
            mapLabel = "Delivery radius around the kitchen"
        )
        Text(
            text = "Circle shows ${com.undal.syl.app.core.util.Distance.formatKm(radiusKm)} around the " +
                "kitchen · ${UndalMaps.ATTRIBUTION}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = UndalDimens.xs)
        )
        Spacer(Modifier.height(UndalDimens.xs))
    }
}

private const val KITCHEN_PIN_ID = "undal-kitchen-pin"
private const val CUSTOMER_PIN_ID = "undal-customer-pin"
