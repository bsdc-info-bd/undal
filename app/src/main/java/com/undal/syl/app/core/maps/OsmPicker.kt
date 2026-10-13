package com.undal.syl.app.core.maps

import android.graphics.drawable.Drawable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
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
import com.undal.syl.app.core.design.UndalSecondaryButton
import com.undal.syl.app.core.theme.UndalDimens
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

/**
 * The interactive pin (PART K-2 · C-013).
 *
 * Dragging is the point: a GPS fix in Sylhet routinely lands on the wrong side
 * of a lane, and a rider who cannot find the gate costs the kitchen a
 * cancellation. A "use my location" button re-centres on demand, and the marker
 * reports every drag so the caller can re-geocode the label.
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
    onRecentre: (() -> Unit)? = null
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var mapView by remember { mutableStateOf<MapView?>(null) }
    var marker by remember { mutableStateOf<Marker?>(null) }

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
                        controller.setZoom(zoom)
                        controller.setCenter(GeoPoint(latitude, longitude))
                        // Attribution is mandatory under the OSM tile policy.
                        overlays.add(
                            org.osmdroid.views.overlay.CopyrightOverlay(ctx)
                        )
                        val pin = Marker(this).apply {
                            position = GeoPoint(latitude, longitude)
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            isDraggable = true
                            title = "Drop your pin here"
                            icon = defaultMarker(ctx)
                            setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                                override fun onMarkerDrag(marker: Marker) = Unit
                                override fun onMarkerDragEnd(marker: Marker) {
                                    val point = marker.position
                                    onPicked(point.latitude, point.longitude)
                                }

                                override fun onMarkerDragStart(marker: Marker) = Unit
                            })
                        }
                        overlays.add(pin)
                        mapView = this
                        marker = pin
                    }
                }
            )
        }

        if (onRecentre != null && recentreLabel != null) {
            UndalSecondaryButton(
                text = recentreLabel,
                onClick = onRecentre,
                modifier = Modifier.padding(top = UndalDimens.sm),
                icon = Icons.Filled.Place
            )
        }
    }

    // Move the camera and the pin when the caller supplies a new point
    // (a GPS fix, a store-pin change, or a restored draft address).
    LaunchedEffect(latitude, longitude) {
        val view = mapView ?: return@LaunchedEffect
        val point = GeoPoint(latitude, longitude)
        marker?.position = point
        view.controller.animateTo(point, zoom, 400L)
        view.invalidate()
    }

    // osmdroid keeps tile downloads and a CPU wake lock alive; releasing them on
    // pause is the difference between a good citizen and a battery complaint.
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
            marker = null
        }
    }
}

/**
 * The brand mark doubles as the drag pin. Using our own drawable (rather than
 * osmdroid's own resource) keeps the app independent of the library's resource
 * namespace, which AGP 8's non-transitive R classes no longer expose.
 */
private fun defaultMarker(context: android.content.Context): Drawable =
    androidx.core.content.ContextCompat.getDrawable(context, com.undal.syl.app.R.drawable.ic_undal_mark)
        ?: android.graphics.drawable.ColorDrawable(0xFF0B5A2B.toInt())

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
