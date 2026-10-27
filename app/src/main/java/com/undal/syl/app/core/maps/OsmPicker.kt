package com.undal.syl.app.core.maps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.undal.syl.app.core.design.UndalSecondaryButton
import com.undal.syl.app.core.theme.UndalDimens
import kotlin.math.cos

/**
 * The interactive pin (PART K-2 · C-013 · V-081).
 *
 * Dragging is the point: a GPS fix in Sylhet routinely lands on the wrong side
 * of a lane, and a rider who cannot find the gate costs the kitchen a
 * cancellation. So the pin can be placed four ways, because four different people
 * use this screen:
 *
 *  · **drag** the map under a pin that never leaves the middle of the card — the
 *    gesture the web picker uses, and the one that cannot go wrong,
 *  · **tap** or **long-press** anywhere on the map — quick or precise placement,
 *  · **nudge arrows** — for somebody holding the phone in one hand, or using
 *    TalkBack, for whom a drag is not a gesture at all,
 *  · **use my current location** — the GPS fix, which is the starting point
 *    rather than the answer.
 *
 * The accuracy of that fix is drawn as a translucent circle, so "accurate to
 * 40 m" is something the customer can see rather than read. Everything else —
 * tiles, cache, attribution, and the camera rule that stops the map fighting the
 * finger that just moved the pin — belongs to [UndalOsmMap], and is therefore
 * identical on every map in the app.
 */
@Composable
fun UndalOsmPicker(
    latitude: Double,
    longitude: Double,
    onPicked: (Double, Double) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = UndalDimens.mapHeight,
    zoom: Double = UndalMaps.ZOOM_STREET,
    recentreLabel: String? = null,
    onRecentre: (() -> Unit)? = null,
    accuracyMeters: Float? = null,
    pinTitle: String = "Delivery point",
    mapLabel: String = "Pin your delivery address",
    showNudgeControls: Boolean = true
) {
    val accuracy = accuracyMeters
        ?.takeIf { it > 0f }
        ?.let { UndalMapAccuracy(latitude, longitude, it) }

    Column(modifier = modifier.fillMaxWidth()) {
        UndalOsmMap(
            latitude = latitude,
            longitude = longitude,
            height = height,
            zoom = zoom,
            // No marker: the pin itself is drawn by Compose at the exact middle of
            // the card (see `centredPin`), and the map is what moves under it. This
            // is how the web picker has always worked, and it is immune to the one
            // failure a marker has — being drawn at a coordinate the map's scroll
            // offset got wrong, which is a pin sitting in the corner of the card.
            pins = emptyList(),
            centredPin = true,
            onCentreMoved = { lat, lng -> onPicked(lat, lng) },
            accuracy = accuracy,
            interactive = true,
            showZoomButtons = true,
            showScaleBar = true,
            recentreLabel = recentreLabel,
            onRecentre = onRecentre,
            onMapTap = { lat, lng -> onPicked(lat, lng) },
            onLongPress = { lat, lng -> onPicked(lat, lng) },
            mapLabel = mapLabel
        )

        Spacer(Modifier.height(UndalDimens.xs))
        Text(
            text = "Drag the map to put the pin on your door, tap a spot, or hold for a precise pick. " +
                UndalMaps.ATTRIBUTION,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (showNudgeControls) {
            Spacer(Modifier.height(UndalDimens.sm))
            UndalPinNudgeRow(
                onNudge = { dLat, dLng -> onPicked(latitude + dLat, longitude + dLng) },
                modifier = Modifier.fillMaxWidth()
            )
        }

    }
}

/**
 * Four arrows, each about five metres of ground, so a pin can be walked onto a
 * doorstep without a drag and without a two-finger gesture.
 *
 * The east/west steps are corrected for the latitude they are used at: a degree
 * of longitude is shorter than a degree of latitude everywhere except the
 * equator, and a nudge that ignores that drifts as the pin is walked north.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UndalPinNudgeRow(
    onNudge: (Double, Double) -> Unit,
    modifier: Modifier = Modifier
) {
    // The whole app is inside one delivery radius in Sylhet, so the correction is
    // computed once for that latitude instead of per pin.
    val lngStep = NUDGE_DEGREES / cos(Math.toRadians(SYLHET_LAT)).coerceAtLeast(0.2)
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(UndalDimens.sm),
        verticalArrangement = Arrangement.spacedBy(UndalDimens.sm)
    ) {
        NudgeArrow(label = "Move the pin west", glyph = "←") { onNudge(0.0, -lngStep) }
        NudgeArrow(label = "Move the pin north", glyph = "↑") { onNudge(NUDGE_DEGREES, 0.0) }
        NudgeArrow(label = "Move the pin south", glyph = "↓") { onNudge(-NUDGE_DEGREES, 0.0) }
        NudgeArrow(label = "Move the pin east", glyph = "→") { onNudge(0.0, lngStep) }
        Text(
            text = "about ${NUDGE_METRES} m a tap",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun NudgeArrow(label: String, glyph: String, onClick: () -> Unit) {
    UndalSecondaryButton(
        text = glyph,
        onClick = onClick,
        fillWidth = false,
        modifier = Modifier.semantics { contentDescription = label }
    )
}

/** Small text row showing the current pick, used under the picker. */
@Composable
fun UndalPickedPointLabel(label: String, modifier: Modifier = Modifier) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = UndalDimens.xs)
    )
}

/** About five metres of latitude — one careful tap, never half a street. */
private const val NUDGE_DEGREES = 0.00005

/** What that works out to on the ground, printed under the arrows. */
private const val NUDGE_METRES = 6

/** Sylhet, where the whole delivery area is. */
private const val SYLHET_LAT = 24.8949
