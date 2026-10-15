package com.undal.syl.app.core.maps

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import com.undal.syl.app.core.design.UndalTextButton
import com.undal.syl.app.core.theme.UndalDimens
import java.util.Locale

/**
 * What a pin is good for once it is on the screen (C-045 · V-027).
 *
 * A pin in Undal is not decoration: it is the address the rider is sent to. The
 * kitchen also has to hand that point to whatever navigation app the rider
 * actually uses, and a customer may want to send it to a friend. All three are
 * the same two facts — a latitude and a longitude — leaving the app in the shape
 * the receiving app expects.
 *
 * Nothing here needs an API key, an SDK or a network call: a `geo:` intent is the
 * Android-wide contract every map and navigation app implements, and the
 * OpenStreetMap web link is the fallback for a phone with no navigation app at
 * all. The customer's own map app is the one that gets asked first — Undal never
 * decides for them which company sees their address.
 */
object UndalMapTools {

    /** The coordinates a person can read out over the phone. */
    fun coordinates(lat: Double, lng: Double): String =
        String.format(Locale.ENGLISH, "%.5f, %.5f", lat, lng)

    /**
     * The `geo:` intent: no scheme handler, no problem — the caller falls back to
     * the web link when this returns false.
     */
    fun externalIntent(lat: Double, lng: Double, label: String?): Intent {
        val query = if (label.isNullOrBlank()) {
            "$lat,$lng"
        } else {
            "$lat,$lng(${label.replace("(", " ").replace(")", " ")})"
        }
        return Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lng?q=$query"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * The same point in a browser, with the pin already dropped and the marker
     * labelled, so a link pasted into a chat opens on the right door.
     */
    fun webUrl(lat: Double, lng: Double, zoom: Int = 18): String =
        String.format(
            Locale.ENGLISH,
            "https://www.openstreetmap.org/?mlat=%.5f&mlon=%.5f#map=%d/%.5f/%.5f",
            lat, lng, zoom, lat, lng
        )

    /**
     * Opens the point in whatever maps app the phone has, and answers honestly.
     *
     * Returns false when nothing could be opened, so a caller can show the
     * coordinates instead of a button that silently did nothing.
     */
    fun openInMapsApp(context: Context, lat: Double, lng: Double, label: String? = null): Boolean {
        if (!isPlausible(lat, lng)) return false
        val opened = runCatching {
            context.startActivity(externalIntent(lat, lng, label))
        }.isSuccess
        if (opened) return true
        return runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(webUrl(lat, lng)))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess
    }

    /** Copies "24.89490, 91.86870" to the clipboard. */
    fun copyCoordinates(context: Context, lat: Double, lng: Double, label: String = "Undal pin"): Boolean {
        if (!isPlausible(lat, lng)) return false
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return false
        val copied = runCatching {
            clipboard.setPrimaryClip(ClipData.newPlainText(label, coordinates(lat, lng)))
        }.isSuccess
        if (!copied) return false
        // Android 13 shows its own "copied" confirmation; older versions show
        // nothing at all, and a copy with no feedback reads as a dead button.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                Toast.makeText(context, "Pinned point copied", Toast.LENGTH_SHORT).show()
            }
        }
        return true
    }

    /**
     * The message a share sheet gets: where the food is going, and a link that
     * opens on that exact point. Coordinates are in the text as well, because the
     * rider's app may not accept a link at all.
     */
    fun shareText(title: String, lat: Double, lng: Double, note: String = ""): String = buildString {
        append(if (title.isBlank()) "Undal delivery point" else title)
        append(" — ")
        append(coordinates(lat, lng))
        if (note.isNotBlank()) {
            append("\n")
            append(note)
        }
        append("\n")
        append(webUrl(lat, lng))
    }

    /** A share intent for the point, ready for `Intent.createChooser`. */
    fun shareIntent(title: String, lat: Double, lng: Double, note: String = ""): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, if (title.isBlank()) "Undal delivery point" else title)
            putExtra(Intent.EXTRA_TEXT, shareText(title, lat, lng, note))
        }
        return Intent.createChooser(send, "Share this delivery point").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** True for a real point on earth; a half-published pin reads as 0,0. */
    fun isPlausible(lat: Double, lng: Double): Boolean =
        lat in -90.0..90.0 && lng in -180.0..180.0 && !(lat == 0.0 && lng == 0.0)
}

/**
 * A delivery point, drawn and then made useful (C-058 · C-045 · V-027).
 *
 * Every read-only map in the app is this composable, which is why the address
 * card, the order card, the profile and the kitchen's order detail all look and
 * behave the same: the map, the coordinates in writing, and the two things a
 * person actually has to do with an address — hand it to their own navigation app
 * and copy it into a message.
 *
 * The tool row scrolls sideways rather than wrapping, because a phone in Bangla
 * or a large system font makes a two-button row wider than the screen, and a
 * clipped button is a button that does not exist.
 */
@Composable
fun UndalLocationMap(
    latitude: Double,
    longitude: Double,
    modifier: Modifier = Modifier,
    height: Dp = UndalDimens.mapHeight,
    title: String = "Delivery point",
    pins: List<UndalMapPin>? = null,
    circle: UndalMapCircle? = null,
    fitPins: Boolean = false,
    zoom: Double = UndalMaps.ZOOM_STREET,
    showTools: Boolean = true,
    caption: String? = null
) {
    val drawn = pins ?: listOf(
        UndalMapPin(
            id = DEFAULT_PIN_ID,
            lat = latitude,
            lng = longitude,
            title = title,
            role = UndalPinRole.Destination
        )
    )
    val usable = UndalMapTools.isPlausible(latitude, longitude)

    Column(modifier = modifier.fillMaxWidth()) {
        UndalOsmMap(
            latitude = latitude,
            longitude = longitude,
            height = height,
            zoom = zoom,
            pins = drawn,
            circle = circle,
            fitPins = fitPins,
            interactive = true,
            showZoomButtons = true,
            showScaleBar = true,
            mapLabel = if (title.isBlank()) "Delivery point map" else "$title map"
        )
        Spacer(Modifier.height(UndalDimens.xs))
        UndalMapCaption(
            latitude = latitude,
            longitude = longitude,
            prefix = caption,
            suffix = UndalMaps.ATTRIBUTION
        )
        if (showTools && usable) {
            UndalMapToolRow(latitude = latitude, longitude = longitude, title = title)
        }
    }
}

private const val DEFAULT_PIN_ID = "undal-location-pin"

/**
 * The two things a person does with an address they can see: hand it to their own
 * navigation app, or copy it into a message.
 *
 * It scrolls sideways rather than wrapping, because a large system font makes two
 * buttons wider than the screen, and a clipped button is a button that does not
 * exist. Nothing is offered for a half-published pin at 0,0.
 */
@Composable
fun UndalMapToolRow(
    latitude: Double,
    longitude: Double,
    title: String = "Delivery point",
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    if (!UndalMapTools.isPlausible(latitude, longitude)) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(UndalDimens.sm)
    ) {
        UndalTextButton(
            text = "Open in a map app",
            onClick = { UndalMapTools.openInMapsApp(context, latitude, longitude, title) },
            icon = Icons.Filled.Place
        )
        UndalTextButton(
            text = "Copy the pin",
            onClick = { UndalMapTools.copyCoordinates(context, latitude, longitude, title) }
        )
    }
}
