package com.undal.syl.app.core.maps

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.undal.syl.app.core.theme.UndalDimens
import com.undal.syl.app.core.theme.UndalPalette
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.ScaleBarOverlay

/**
 * The one map in Undal (PART K · C-013 · C-014 · C-058 · V-027 · V-080 · V-081).
 *
 * Every map surface in the app is this component drawn from OpenStreetMap tiles
 * by osmdroid, in the app's own process: the onboarding picker, the address card,
 * the order tracking card, the kitchen's order detail and the delivery-radius
 * preview. There is no Google Maps dependency anywhere, no API key, no WebView
 * and no second map engine to disagree with the first.
 *
 * **Why the WebView is gone.** The old screen offered Google's public embed page
 * as a "keyless" second view. It was the wrong trade in every direction: it is a
 * web page Google serves to an embedded browser and can refuse (a grey rectangle
 * where a customer's address should be), it needed its own network stack, its own
 * consent wall and its own failure clock, it could not be dragged — the one
 * gesture pinning an address needs — and it was the only place in the app where
 * JavaScript ran at all. One engine that always draws, in-process, offline from
 * its own tile cache, is worth more than a familiar logo that sometimes does not.
 *
 * **What "advanced" means here.** The engine is one component with one renderer,
 * so every surface behaves the same and every fix lands once:
 *
 *   · drag a pin, or long-press anywhere on the map to move it there,
 *   · zoom buttons with real 48 dp targets, because a pinch is not a gesture
 *     everyone can make (and TalkBack cannot make it at all),
 *   · a scale bar, so a distance on the screen means metres on the ground,
 *   · the GPS accuracy drawn as a translucent circle, so "accurate to 12 m" is
 *     something the customer can see rather than read,
 *   · the delivery radius drawn from the same 72-point polygon the haversine gate
 *     measures against,
 *   · several pins at once (kitchen and customer) with the camera fitted to all
 *     of them, which is what a rider actually needs to see,
 *   · a tile cache with an explicit ceiling and a real expiry, tuned once at
 *     application start,
 *   · attribution that cannot be lost — the mandatory OpenStreetMap credit is an
 *     overlay on every map and repeated in the caption under it,
 *   · one camera rule: the map moves when the pin moves *for a reason other than
 *     the finger that moved it*, so dragging a pin never fights the animation.
 *
 * The renderer is deliberately imperative and diff-based: pins are matched by id,
 * so a recomposition that changes one pin does not remove and recreate every
 * marker (which is what makes a map flicker and lose a drag in progress).
 */

/** What a pin means, which decides its colour and its shape. */
enum class UndalPinRole {
    /** The customer's door — the drop pin, drawn as a classic teardrop. */
    Destination,

    /** The kitchen. */
    Kitchen,

    /** The customer's saved address, shown to the kitchen without being edited. */
    Customer,

    /** The phone's own position, drawn as a small dot with its accuracy circle. */
    User
}

/** One pin on an Undal map. `id` is what makes a re-render a diff, not a rebuild. */
data class UndalMapPin(
    val id: String,
    val lat: Double,
    val lng: Double,
    val title: String = "",
    val role: UndalPinRole = UndalPinRole.Destination,
    val draggable: Boolean = false
)

/** A delivery-radius circle, in kilometres, measured around a real point. */
data class UndalMapCircle(
    val lat: Double,
    val lng: Double,
    val radiusKm: Double
)

/** The phone's own fix, drawn as a dot with the accuracy it was measured to. */
data class UndalMapAccuracy(
    val lat: Double,
    val lng: Double,
    val metres: Float
)

/** Circle geometry, shared by the renderer and by the radius preview's caption. */
object UndalMapGeometry {

    /**
     * A real circle on the ground.
     *
     * The polygon is built from points offset in metres from the centre, so the
     * boundary a vendor sees is the boundary the distance gate actually allows —
     * not a screen-space ellipse that changes size with the zoom.
     */
    fun circlePoints(lat: Double, lng: Double, radiusKm: Double, steps: Int = 72): List<GeoPoint> {
        val points = ArrayList<GeoPoint>(steps)
        val radiusMeters = radiusKm.coerceIn(0.05, 50.0) * 1000.0
        val latDegreesPerMeter = 1.0 / 111_320.0
        // A cosine that cannot reach zero, so a circle drawn near a pole (or from a
        // half-published pin at 0,0) stays a circle instead of becoming a line.
        val cosLat = kotlin.math.cos(Math.toRadians(lat)).coerceAtLeast(0.01)
        val lngDegreesPerMeter = 1.0 / (111_320.0 * cosLat)
        for (index in 0 until steps) {
            val angle = 2.0 * Math.PI * index / steps
            val dLat = radiusMeters * kotlin.math.sin(angle) * latDegreesPerMeter
            val dLng = radiusMeters * kotlin.math.cos(angle) * lngDegreesPerMeter
            points.add(GeoPoint(lat + dLat, lng + dLng))
        }
        return points
    }

    /** The zoom that shows a radius of [radiusKm] with the whole circle on screen. */
    fun zoomForRadius(radiusKm: Double): Double = when {
        radiusKm <= 1.5 -> 14.0
        radiusKm <= 3.5 -> 13.0
        radiusKm <= 7.0 -> 12.0
        radiusKm <= 11.0 -> 11.0
        else -> 10.0
    }
}

/**
 * The pin shapes, drawn in code.
 *
 * A `Drawable` rather than a resource: the colour is chosen per role at runtime,
 * the geometry is exact at every density, and the app does not carry five almost
 * identical PNGs. The teardrop is a circle unioned with a triangle, which is the
 * same shape every map app uses for "here" and is unmistakable at 12 dp.
 */
private class UndalPinDrawable(
    private val fill: Int,
    private val ring: Int,
    private val sizePx: Int,
    private val teardrop: Boolean,
    private val shadow: Boolean
) : Drawable() {

    private val path = Path()
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.argb(46, 0, 0, 0)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = fill
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = ring
        strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ring
    }

    override fun getIntrinsicWidth(): Int = sizePx

    override fun getIntrinsicHeight(): Int = sizePx

    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        ringPaint.alpha = alpha
        dotPaint.alpha = alpha
        shadowPaint.alpha = alpha / 4
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        ringPaint.colorFilter = colorFilter
        dotPaint.colorFilter = colorFilter
    }

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return
        val cx = w / 2f
        path.reset()
        val stroke = (w * 0.075f).coerceAtLeast(1.5f)
        ringPaint.strokeWidth = stroke

        if (teardrop) {
            val radius = w * 0.36f
            val cy = radius + stroke * 1.6f
            path.addCircle(cx, cy, radius, Path.Direction.CW)
            val tail = Path().apply {
                moveTo(cx - radius * 0.62f, cy + radius * 0.66f)
                lineTo(cx, h - stroke * 0.4f)
                lineTo(cx + radius * 0.62f, cy + radius * 0.66f)
                close()
            }
            path.op(tail, Path.Op.UNION)
            if (shadow) {
                val shadowRadius = w * 0.20f
                canvas.drawOval(
                    cx - shadowRadius,
                    h - shadowRadius * 0.8f,
                    cx + shadowRadius,
                    h + shadowRadius * 0.5f,
                    shadowPaint
                )
            }
            canvas.drawPath(path, fillPaint)
            canvas.drawPath(path, ringPaint)
            canvas.drawCircle(cx, cy, radius * 0.34f, dotPaint)
        } else {
            val radius = w * 0.40f
            val cy = h / 2f
            path.addCircle(cx, cy, radius, Path.Direction.CW)
            if (shadow) {
                canvas.drawCircle(cx, cy + radius * 0.14f, radius, shadowPaint)
            }
            canvas.drawPath(path, fillPaint)
            canvas.drawPath(path, ringPaint)
            canvas.drawCircle(cx, cy, radius * 0.36f, dotPaint)
        }
    }
}

/**
 * Owns one [MapView] and everything drawn on it.
 *
 * One place decides what is on the map, so a pin that moves is a position write
 * rather than a teardown. Everything here runs on the main thread, because every
 * caller is a Compose effect.
 */
private class UndalMapRenderer(private val mapView: UndalMapView) {

    private val markers = LinkedHashMap<String, Marker>()
    private var radiusCircle: Polygon? = null
    private var accuracyCircle: Polygon? = null
    private var eventsOverlay: MapEventsOverlay? = null
    private data class CameraTarget(val lat: Double, val lng: Double, val zoom: Double)
    private var cameraPendingLayout: CameraTarget? = null
    private var fitPendingLayout: List<UndalMapPin>? = null
    private var cameraHasBeenPositioned = false
    private val cameraLayoutListener: View.OnLayoutChangeListener = object : View.OnLayoutChangeListener {
        override fun onLayoutChange(
            view: View,
            left: Int,
            top: Int,
            right: Int,
            bottom: Int,
            oldLeft: Int,
            oldTop: Int,
            oldRight: Int,
            oldBottom: Int
        ) {
            if (view.width <= 0 || view.height <= 0) return
            view.removeOnLayoutChangeListener(this)
            val fit = fitPendingLayout
            fitPendingLayout = null
            if (fit != null) {
                fitPins(fit)
            } else {
                val target = cameraPendingLayout ?: return
                cameraPendingLayout = null
                moveCamera(target.lat, target.lng, target.zoom, animate = false, force = true)
            }
        }
    }

    fun installEvents(receiver: MapEventsReceiver) {
        if (eventsOverlay != null) return
        val overlay = MapEventsOverlay(receiver)
        // Index 0: the events overlay is drawn first and receives touches last, so
        // a drag that starts on a marker is the marker's drag, never a long-press
        // "move the pin here" on the map underneath it.
        mapView.overlays.add(0, overlay)
        eventsOverlay = overlay
    }

    fun renderPins(
        pins: List<UndalMapPin>,
        accentColour: Int,
        brandColour: Int,
        onDragged: (String, Double, Double) -> Unit,
        onTapped: (UndalMapPin) -> Unit
    ) {
        val wanted = LinkedHashMap<String, UndalMapPin>()
        pins.forEach { pin -> wanted[pin.id] = pin }

        val iterator = markers.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!wanted.containsKey(entry.key)) {
                mapView.overlays.remove(entry.value)
                iterator.remove()
            }
        }

        pins.forEach { pin ->
            val existing = markers[pin.id]
            val marker = existing ?: createMarker(pin, accentColour, brandColour).also { created ->
                mapView.overlays.add(created)
                markers[pin.id] = created
            }
            marker.setPosition(GeoPoint(pin.lat, pin.lng))
            marker.setTitle(pin.title)
            // osmdroid only starts a Marker drag after its long-press detector
            // fires. That is easy to mistake for a dead pin, and a fast finger
            // pans the map instead. UndalMapView owns an immediate, slop-aware
            // drag for destination pins below; keep the built-in handler off so
            // there can never be two gesture owners fighting for one touch.
            marker.setDraggable(false)
            marker.setIcon(pinIcon(pin.role, accentColour, brandColour))
            // Returning true consumes the tap: osmdroid's own bubble is never used,
            // because the title of a pin is already printed in the caption below
            // the map, in the app's own type and language.
            marker.setOnMarkerClickListener { _, _ ->
                onTapped(pin)
                true
            }
        }
        val dragTargets = pins
            .asSequence()
            .filter { it.draggable }
            .mapNotNull { pin -> markers[pin.id]?.let { UndalDraggablePin(pin.id, it) } }
            .toList()
        mapView.setDraggablePins(dragTargets, onDragged)
        mapView.invalidate()
    }

    private fun createMarker(pin: UndalMapPin, accentColour: Int, brandColour: Int): Marker =
        Marker(mapView).apply {
            if (pin.role == UndalPinRole.Destination) {
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            } else {
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            }
            setPanToView(false)
            icon = pinIcon(pin.role, accentColour, brandColour)
            setPosition(GeoPoint(pin.lat, pin.lng))
        }

    private fun pinIcon(role: UndalPinRole, accentColour: Int, brandColour: Int): Drawable {
        val density = mapView.context.resources.displayMetrics.density
        val white = AndroidColor.WHITE
        return when (role) {
            UndalPinRole.Destination -> UndalPinDrawable(
                fill = brandColour,
                ring = white,
                sizePx = (46 * density).toInt(),
                teardrop = true,
                shadow = true
            )

            UndalPinRole.Kitchen -> UndalPinDrawable(
                fill = brandColour,
                ring = white,
                sizePx = (30 * density).toInt(),
                teardrop = false,
                shadow = true
            )

            UndalPinRole.Customer -> UndalPinDrawable(
                fill = accentColour,
                ring = white,
                sizePx = (30 * density).toInt(),
                teardrop = false,
                shadow = true
            )

            UndalPinRole.User -> UndalPinDrawable(
                fill = AndroidColor.parseColor("#1A73E8"),
                ring = white,
                sizePx = (24 * density).toInt(),
                teardrop = false,
                shadow = false
            )
        }
    }

    fun renderCircle(circle: UndalMapCircle?, brandColour: Int) {
        if (circle == null) {
            radiusCircle?.let { existing ->
                mapView.overlays.remove(existing)
                radiusCircle = null
                mapView.invalidate()
            }
            return
        }
        val polygon = radiusCircle ?: Polygon(mapView).also { created ->
            created.outlinePaint.color = brandColour
            created.outlinePaint.strokeWidth = 5f
            created.fillPaint.color = withAlpha(brandColour, 0x33)
            mapView.overlays.add(created)
            radiusCircle = created
        }
        polygon.setPoints(UndalMapGeometry.circlePoints(circle.lat, circle.lng, circle.radiusKm))
        polygon.outlinePaint.color = brandColour
        polygon.fillPaint.color = withAlpha(brandColour, 0x33)
        mapView.invalidate()
    }

    fun renderAccuracy(accuracy: UndalMapAccuracy?, brandColour: Int) {
        if (accuracy == null || accuracy.metres <= 0f) {
            accuracyCircle?.let { existing ->
                mapView.overlays.remove(existing)
                accuracyCircle = null
                mapView.invalidate()
            }
            return
        }
        val polygon = accuracyCircle ?: Polygon(mapView).also { created ->
            created.outlinePaint.color = withAlpha(brandColour, 0x66)
            created.outlinePaint.strokeWidth = 3f
            created.fillPaint.color = withAlpha(brandColour, 0x1F)
            mapView.overlays.add(created)
            accuracyCircle = created
        }
        // The fix is a circle of uncertainty around a point, and the polygon is
        // built in the same metres the accuracy figure is reported in.
        val radiusKm = (accuracy.metres / 1000.0).coerceAtLeast(0.01)
        polygon.setPoints(UndalMapGeometry.circlePoints(accuracy.lat, accuracy.lng, radiusKm, steps = 48))
        mapView.invalidate()
    }

    fun moveCamera(
        lat: Double,
        lng: Double,
        zoom: Double,
        animate: Boolean = true,
        force: Boolean = false
    ) {
        // A remembered MapView is created during Compose before AndroidView has
        // measured it. osmdroid can report the requested centre at that point,
        // even though no screen projection exists yet; the old equality fast-path
        // then skipped the only camera update and left the real pin at a corner.
        // Defer one latest target until layout and apply it without animation.
        if (mapView.width <= 0 || mapView.height <= 0) {
            cameraPendingLayout = CameraTarget(lat, lng, zoom)
            fitPendingLayout = null
            mapView.removeOnLayoutChangeListener(cameraLayoutListener)
            mapView.addOnLayoutChangeListener(cameraLayoutListener)
            return
        }
        val firstCameraPosition = !cameraHasBeenPositioned
        cameraHasBeenPositioned = true
        val target = GeoPoint(lat, lng)
        val current = mapView.mapCenter
        val alreadyThere = current != null &&
            kotlin.math.abs(current.latitude - lat) < 0.0000001 &&
            kotlin.math.abs(current.longitude - lng) < 0.0000001 &&
            kotlin.math.abs(mapView.zoomLevelDouble - zoom) < 0.001
        if (alreadyThere && !force && !firstCameraPosition) return
        if (animate && !firstCameraPosition) {
            mapView.controller.animateTo(target, zoom, CAMERA_ANIMATION_MILLIS)
        } else {
            mapView.controller.setZoom(zoom)
            mapView.controller.setCenter(target)
        }
    }

    /**
     * Fit every pin on screen, with padding, rather than guessing a zoom.
     *
     * The kitchen→customer pair on an order is the reason this exists: a fixed
     * zoom level puts one of the two pins off the screen on a small phone.
     */
    fun fitPins(pins: List<UndalMapPin>) {
        if (pins.isEmpty()) return
        if (mapView.width <= 0 || mapView.height <= 0) {
            fitPendingLayout = pins.toList()
            cameraPendingLayout = null
            mapView.removeOnLayoutChangeListener(cameraLayoutListener)
            mapView.addOnLayoutChangeListener(cameraLayoutListener)
            return
        }
        cameraHasBeenPositioned = true
        if (pins.size == 1) {
            moveCamera(pins[0].lat, pins[0].lng, mapView.zoomLevelDouble, animate = false, force = true)
            return
        }
        var north = pins[0].lat
        var south = pins[0].lat
        var east = pins[0].lng
        var west = pins[0].lng
        pins.forEach { pin ->
            north = kotlin.math.max(north, pin.lat)
            south = kotlin.math.min(south, pin.lat)
            east = kotlin.math.max(east, pin.lng)
            west = kotlin.math.min(west, pin.lng)
        }
        if (north == south && east == west) {
            moveCamera(north, east, ZOOM_FIT_SINGLE_POINT, animate = true)
            return
        }
        val box = BoundingBox(north, east, south, west)
        // A single tap-mode fit: osmdroid animates the zoom and the centre, and the
        // border keeps a pin's 46 dp teardrop from touching the card's edge.
        runCatching { mapView.zoomToBoundingBox(box, true, FIT_BORDER_PIXELS) }
    }

    private fun withAlpha(colour: Int, alpha: Int): Int =
        (colour and 0x00FFFFFF) or (alpha shl 24)

    private companion object {
        const val CAMERA_ANIMATION_MILLIS = 350L
        const val FIT_BORDER_PIXELS = 96
        const val ZOOM_FIT_SINGLE_POINT = 17.0
    }
}

/**
 * The map itself.
 *
 * @param latitude camera centre — follows the caller's state, and is left alone
 *   while the finger that moved the pin is still on the glass.
 * @param pins every pin to draw; matching is by [UndalMapPin.id].
 * @param circle the delivery-radius ring, when a screen needs one.
 * @param accuracy the phone's own fix, drawn as a circle of uncertainty.
 * @param fitPins frame every pin instead of centring on one point.
 * @param onLongPress a long press on the map (used by the picker to move the pin).
 */
@Composable
fun UndalOsmMap(
    latitude: Double,
    longitude: Double,
    modifier: Modifier = Modifier,
    height: Dp = UndalDimens.mapHeight,
    zoom: Double = UndalMaps.ZOOM_STREET,
    pins: List<UndalMapPin> = emptyList(),
    circle: UndalMapCircle? = null,
    accuracy: UndalMapAccuracy? = null,
    fitPins: Boolean = false,
    interactive: Boolean = true,
    showZoomButtons: Boolean = true,
    showScaleBar: Boolean = true,
    recentreLabel: String? = null,
    onRecentre: (() -> Unit)? = null,
    onPinDragged: ((String, Double, Double) -> Unit)? = null,
    onMapTap: ((Double, Double) -> Unit)? = null,
    onLongPress: ((Double, Double) -> Unit)? = null,
    onPinTapped: ((UndalMapPin) -> Unit)? = null,
    mapLabel: String = "Map",
    footer: (@Composable () -> Unit)? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // The newest callbacks without re-creating the map: a caller that passes a new
    // lambda on every recomposition must not rebuild the marker diff.
    val dragCallback by rememberUpdatedState(onPinDragged)
    val tapCallback by rememberUpdatedState(onPinTapped)
    val mapTapCallback by rememberUpdatedState(onMapTap)
    val longPressCallback by rememberUpdatedState(onLongPress)
    val recentreCallback by rememberUpdatedState(onRecentre)

    // ONE MapView for the whole life of this composable, created here — not inside
    // the AndroidView factory — and never replaced.
    //
    // This is the blank map. The view used to be created in the factory, which
    // then wrote it into `mutableStateOf` state; the lifecycle effect below was
    // keyed on that state. So the moment the map existed the key changed, the
    // effect's previous instance was disposed, and its onDispose ran
    // `onDetach()` on the brand-new map — osmdroid's teardown, which shuts the
    // tile downloader's threads down for good and drops the redraw handler — and
    // then nulled the renderer. Every map in the app was destroyed the instant it
    // was built: a grey grid, no pin, zoom buttons that did nothing.
    val mapView = remember {
        // The tile cache directory and the identifying user agent have to exist
        // before the first tile request, which this is.
        UndalMaps.initialize(context)
        UndalMapView(context, holdsGestures = interactive).apply {
            setTileSource(org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK)
            setMultiTouchControls(interactive)
            setBuiltInZoomControls(false)
            setTilesScaledToDpi(true)
            minZoomLevel = UndalMaps.MIN_ZOOM
            maxZoomLevel = UndalMaps.MAX_ZOOM
            isNestedScrollingEnabled = false
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            controller.setZoom(zoom)
            controller.setCenter(GeoPoint(latitude, longitude))
            // Attribution is mandatory under the OpenStreetMap tile policy and is
            // drawn on the tiles themselves, so it survives every screenshot and
            // every zoom change.
            overlays.add(CopyrightOverlay(context))
            if (showScaleBar) {
                runCatching {
                    val scale = ScaleBarOverlay(this)
                    scale.setAlignBottom(true)
                    scale.setAlignRight(true)
                    scale.setUnitsOfMeasure(ScaleBarOverlay.UnitsOfMeasure.metric)
                    overlays.add(scale)
                }
            }
        }
    }
    val renderer = remember(mapView) {
        UndalMapRenderer(mapView).also { created ->
            if (interactive && (longPressCallback != null || mapTapCallback != null)) {
                created.installEvents(object : MapEventsReceiver {
                    override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                        val callback = mapTapCallback ?: return false
                        callback(p.latitude, p.longitude)
                        return true
                    }

                    override fun longPressHelper(p: GeoPoint): Boolean {
                        val callback = longPressCallback ?: return false
                        callback(p.latitude, p.longitude)
                        return true
                    }
                })
            }
        }
    }

    val brandColour = UndalPalette.Brand.toArgb()
    val accentColour = UndalPalette.Accent.toArgb()

    // How the map describes itself to a screen reader: the coordinates are the
    // truth a picture cannot carry, and they are spoken as digits.
    val spokenLabel = remember(latitude, longitude, pins, mapLabel) {
        buildString {
            append(mapLabel)
            if (pins.size > 1) append(", ${pins.size} points")
            append(", centred on ${UndalLocation.latLngLabel(latitude, longitude)}")
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(UndalDimens.radiusMd))
        ) {
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(height)
                    .semantics { contentDescription = spokenLabel },
                // The view already exists; the factory only hands it over.
                factory = { mapView }
            )

            if (showZoomButtons && interactive) {
                UndalMapZoomControls(
                    onZoomIn = { mapView.controller.zoomIn() },
                    onZoomOut = { mapView.controller.zoomOut() },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(UndalDimens.sm)
                )
            }

            if (recentreLabel != null && recentreCallback != null) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = UndalDimens.elevationRaised,
                    shadowElevation = UndalDimens.elevationRaised,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(UndalDimens.sm)
                ) {
                    IconButton(
                        onClick = { recentreCallback?.invoke() },
                        modifier = Modifier.size(UndalDimens.touchTarget)
                    ) {
                        Icon(
                            Icons.Filled.LocationOn,
                            contentDescription = recentreLabel,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(UndalDimens.iconMd)
                        )
                    }
                }
            }
        }

        if (footer != null) {
            footer()
        }
    }

    // Pins, circles and accuracy: rebuilt when the data changes, never because a
    // lambda changed identity.
    LaunchedEffect(renderer, pins, circle, accuracy) {
        renderer.renderCircle(circle, brandColour)
        renderer.renderAccuracy(accuracy, brandColour)
        renderer.renderPins(
            pins = pins,
            accentColour = accentColour,
            brandColour = brandColour,
            onDragged = { id, lat, lng -> dragCallback?.invoke(id, lat, lng) },
            onTapped = { pin -> tapCallback?.invoke(pin) }
        )
    }

    // The camera follows the caller's coordinates — but a position the finger just
    // produced is already on screen, so the renderer skips the animation for it.
    LaunchedEffect(renderer, latitude, longitude, zoom, fitPins, pins) {
        if (fitPins) {
            renderer.fitPins(pins)
        } else {
            renderer.moveCamera(latitude, longitude, zoom)
        }
    }

    // The page owns the map's resume and pause. Keyed on the lifecycle owner
    // alone: nothing about the map itself may restart this effect, because
    // restarting is disposing.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // The one teardown, when this map leaves the screen for good. `mapView` is
    // remembered and never changes, so this runs exactly once — never on a map
    // that is still being shown.
    DisposableEffect(mapView) {
        onDispose {
            mapView.onPause()
            mapView.onDetach()
        }
    }
}

/**
 * A MapView that keeps the gesture it was given.
 *
 * Every picker in Undal sits inside a screen that scrolls, and osmdroid never
 * asks its parent not to intercept: after a few pixels of vertical movement the
 * page's scroll took the gesture, so dragging the pin — or the map — up or down
 * scrolled the page instead. While a finger is on an interactive map, the parent
 * is asked to keep its hands off; Compose's interop honours the request.
 */
private data class UndalDraggablePin(val id: String, val marker: Marker)

/**
 * A MapView that keeps map gestures and owns direct, forgiving pin drags.
 *
 * osmdroid's built-in Marker requires a long press before it enters drag mode.
 * A quick drag therefore pans the map and makes a customer think the pin is
 * broken. We capture a touch that starts on a draggable pin, preserve the
 * finger-to-anchor offset, and translate subsequent local pixels through the
 * current map projection. All other gestures still go to osmdroid unchanged.
 */
private class UndalMapView(
    context: Context,
    private val holdsGestures: Boolean
) : MapView(context) {

    private data class ActiveDrag(
        val target: UndalDraggablePin,
        val pointerId: Int,
        val downX: Float,
        val downY: Float,
        val anchorOffsetX: Float,
        val anchorOffsetY: Float,
        val origin: GeoPoint,
        var moved: Boolean = false
    )

    private var draggablePins: List<UndalDraggablePin> = emptyList()
    private var onPinDragEnd: ((String, Double, Double) -> Unit)? = null
    private var activeDrag: ActiveDrag? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    fun setDraggablePins(
        pins: List<UndalDraggablePin>,
        onDragEnd: (String, Double, Double) -> Unit
    ) {
        draggablePins = pins
        onPinDragEnd = onDragEnd
        if (activeDrag?.target?.let { active -> pins.none { it.marker === active.marker } } == true) {
            cancelPinDrag()
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (holdsGestures && event.actionMasked != MotionEvent.ACTION_UP &&
            event.actionMasked != MotionEvent.ACTION_CANCEL
        ) {
            // This map lives inside a scrolling Compose form. A vertical pin drag
            // must belong to the map, not be stolen by the page underneath it.
            parent?.requestDisallowInterceptTouchEvent(true)
        }

        val drag = activeDrag
        if (drag != null) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount > 1) {
                        cancelPinDrag()
                    } else {
                        movePinWithFinger(drag, event, drag.pointerId)
                    }
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    finishPinDrag(drag, event, drag.pointerId)
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    // A second finger means the user changed their mind and wants
                    // a map gesture; do not leave a half-moved pin behind.
                    cancelPinDrag()
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    cancelPinDrag()
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }

                else -> return true
            }
        }

        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            findDraggablePin(event)?.let { target ->
                val anchor = Point()
                projection.toPixels(target.marker.position, anchor)
                activeDrag = ActiveDrag(
                    target = target,
                    pointerId = event.getPointerId(0),
                    downX = event.x,
                    downY = event.y,
                    anchorOffsetX = event.x - anchor.x,
                    anchorOffsetY = event.y - anchor.y,
                    origin = GeoPoint(target.marker.position.latitude, target.marker.position.longitude)
                )
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
        }

        val handled = super.dispatchTouchEvent(event)
        if (holdsGestures && (event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL)
        ) {
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return handled
    }

    private fun findDraggablePin(event: MotionEvent): UndalDraggablePin? {
        val density = resources.displayMetrics.density
        return draggablePins.asReversed().firstOrNull { target ->
            val marker = target.marker
            if (!marker.isEnabled) return@firstOrNull false
            val screen = Point()
            projection.toPixels(marker.position, screen)
            val icon = marker.icon
            val iconWidth = icon?.intrinsicWidth?.takeIf { it > 0 } ?: (46f * density).toInt()
            val iconHeight = icon?.intrinsicHeight?.takeIf { it > 0 } ?: (46f * density).toInt()
            val extra = (10f * density).toInt()
            // The destination pin uses a bottom-centre anchor: its geographic
            // point is the tip, not the visual centre of the teardrop.
            val left = screen.x - iconWidth / 2 - extra
            val right = screen.x + iconWidth / 2 + extra
            val top = screen.y - iconHeight - extra
            val bottom = screen.y + extra
            event.x >= left && event.x <= right && event.y >= top && event.y <= bottom
        }
    }

    private fun movePinWithFinger(drag: ActiveDrag, event: MotionEvent, pointerId: Int) {
        val index = event.findPointerIndex(pointerId)
        if (index < 0) return
        val x = event.getX(index)
        val y = event.getY(index)
        if (!drag.moved && kotlin.math.hypot((x - drag.downX).toDouble(), (y - drag.downY).toDouble()) < touchSlop) {
            return
        }
        drag.moved = true
        val point = projection.fromPixels(
            (x - drag.anchorOffsetX).toInt(),
            (y - drag.anchorOffsetY).toInt()
        ) as? GeoPoint ?: return
        drag.target.marker.position = point
        invalidate()
    }

    private fun finishPinDrag(drag: ActiveDrag, event: MotionEvent, pointerId: Int) {
        movePinWithFinger(drag, event, pointerId)
        activeDrag = null
        if (drag.moved) {
            val point = drag.target.marker.position
            onPinDragEnd?.invoke(drag.target.id, point.latitude, point.longitude)
        }
        invalidate()
    }

    private fun cancelPinDrag() {
        val drag = activeDrag ?: return
        drag.target.marker.position = drag.origin
        activeDrag = null
        invalidate()
    }
}

/**
 * Two buttons, stacked, 48 dp each, with the state a screen reader can announce.
 *
 * A pinch is a two-finger gesture and a double tap is not a zoom control: for
 * somebody using TalkBack, or holding the phone with one hand over a pot, these
 * are the only zoom that exists.
 */
@Composable
private fun UndalMapZoomControls(
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(UndalDimens.xs)) {
        UndalMapControlButton(label = "Zoom in", onClick = onZoomIn) {
            Icon(
                Icons.Filled.Add,
                contentDescription = null,
                modifier = Modifier.size(UndalDimens.iconMd)
            )
        }
        UndalMapControlButton(label = "Zoom out", onClick = onZoomOut) {
            // A minus glyph: "Remove" is not in the icon core set this app ships,
            // and a wrong icon is worse than a plain one.
            Text("−", style = MaterialTheme.typography.titleLarge)
        }
    }
}

/** One square glass control with a real 48 dp target. */
@Composable
private fun UndalMapControlButton(
    label: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = UndalDimens.elevationRaised,
        shadowElevation = UndalDimens.elevationRaised
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.size(UndalDimens.touchTarget)
        ) {
            Box(
                modifier = Modifier
                    .size(UndalDimens.iconMd)
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center
            ) {
                content()
            }
        }
    }
}

/**
 * A row of small facts under the map: where the pin is, and the attribution the
 * OpenStreetMap tile policy requires to be visible.
 */
@Composable
fun UndalMapCaption(
    latitude: Double,
    longitude: Double,
    modifier: Modifier = Modifier,
    prefix: String? = null,
    suffix: String? = null
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Filled.LocationOn,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(UndalDimens.iconSm)
        )
        Spacer(Modifier.width(UndalDimens.xs))
        Text(
            text = buildString {
                if (prefix != null) append(prefix).append(" · ")
                append(UndalLocation.latLngLabel(latitude, longitude))
                if (suffix != null) append(" · ").append(suffix)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
    }
}
