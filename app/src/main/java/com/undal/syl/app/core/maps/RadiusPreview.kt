package com.undal.syl.app.core.maps

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.undal.syl.app.core.theme.UndalDimens
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

/**
 * The delivery-radius preview (PART K-2 · V-080 · V-081).
 *
 * The vendor drags a slider and this map draws the exact circle those kilometres
 * buy, measured in real metres around the real kitchen pin. Seeing the boundary
 * is the difference between "5 km sounds fine" and "that cuts off my regular
 * customers in Amberkhana".
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
    val lifecycleOwner = LocalLifecycleOwner.current
    var mapView by remember { mutableStateOf<MapView?>(null) }
    var radiusOverlay by remember { mutableStateOf<Polygon?>(null) }
    var storeMarker by remember { mutableStateOf<Marker?>(null) }
    var customerMarker by remember { mutableStateOf<Marker?>(null) }

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(UndalDimens.radiusMd))
        ) {
            AndroidView(
                modifier = Modifier.fillMaxWidth().height(height),
                factory = { ctx ->
                    MapView(ctx).apply {
                        setTileSource(TileSourceFactory.MAPNIK)
                        setMultiTouchControls(true)
                        minZoomLevel = 4.0
                        maxZoomLevel = 20.0
                        controller.setZoom(zoomForRadius(radiusKm))
                        controller.setCenter(GeoPoint(storeLat, storeLng))
                        overlays.add(
                            org.osmdroid.views.overlay.CopyrightOverlay(ctx, UndalMaps.ATTRIBUTION)
                        )

                        val circle = Polygon(this).apply {
                            points = buildCircle(storeLat, storeLng, radiusKm)
                            outlinePaint.color = 0xFF0B5A2B.toInt()
                            outlinePaint.strokeWidth = 4f
                            fillPaint.color = 0x330B5A2B
                        }
                        overlays.add(circle)

                        val pin = Marker(this).apply {
                            position = GeoPoint(storeLat, storeLng)
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            title = "Our kitchen"
                            isDraggable = false
                        }
                        overlays.add(pin)

                        mapView = this
                        radiusOverlay = circle
                        storeMarker = pin
                    }
                }
            )
        }
        Text(
            text = "Circle shows ${com.undal.syl.app.core.util.Distance.formatKm(radiusKm)} around the kitchen · ${UndalMaps.ATTRIBUTION}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    LaunchedEffect(storeLat, storeLng, radiusKm) {
        val view = mapView ?: return@LaunchedEffect
        storeMarker?.position = GeoPoint(storeLat, storeLng)
        radiusOverlay?.points = buildCircle(storeLat, storeLng, radiusKm)
        view.controller.animateTo(GeoPoint(storeLat, storeLng), zoomForRadius(radiusKm), 350L)
        view.invalidate()
    }

    LaunchedEffect(customerLat, customerLng) {
        val view = mapView ?: return@LaunchedEffect
        if (customerLat != null && customerLng != null) {
            val existing = customerMarker
            if (existing != null) {
                existing.position = GeoPoint(customerLat, customerLng)
            } else {
                val pin = Marker(view).apply {
                    position = GeoPoint(customerLat, customerLng)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    title = "Customer"
                }
                view.overlays.add(pin)
                customerMarker = pin
            }
            view.invalidate()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView?.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView?.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView?.onDetach()
            mapView = null
            radiusOverlay = null
            storeMarker = null
            customerMarker = null
        }
    }
}

private fun zoomForRadius(radiusKm: Double): Double = when {
    radiusKm <= 1.5 -> 14.0
    radiusKm <= 3.5 -> 13.0
    radiusKm <= 7.0 -> 12.0
    radiusKm <= 11.0 -> 11.0
    else -> 10.0
}

/**
 * A real circle on the ground: the polygon is built from 72 points offset in
 * metres, so the boundary matches what the haversine gate will actually allow.
 */
private fun buildCircle(lat: Double, lng: Double, radiusKm: Double): List<GeoPoint> {
    val points = mutableListOf<GeoPoint>()
    val radiusMeters = radiusKm * 1000.0
    val latDegreesPerMeter = 1.0 / 111_320.0
    val lngDegreesPerMeter = 1.0 / (111_320.0 * kotlin.math.cos(Math.toRadians(lat)))
    val steps = 72
    for (index in 0 until steps) {
        val angle = 2.0 * Math.PI * index / steps
        val dLat = radiusMeters * kotlin.math.sin(angle) * latDegreesPerMeter
        val dLng = radiusMeters * kotlin.math.cos(angle) * lngDegreesPerMeter
        points.add(GeoPoint(lat + dLat, lng + dLng))
    }
    return points
}
