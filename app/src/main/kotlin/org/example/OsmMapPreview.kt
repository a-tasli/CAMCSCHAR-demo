package org.example

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

@Composable
fun OsmMapPreview(
    location: android.location.Location?,
    isManualGpsMode: Boolean = false,
    manualLat: Double? = null,
    manualLon: Double? = null,
    resetTrigger: Int = 0,
    onManualLocationChange: ((Double, Double) -> Unit)? = null,
    modifier: Modifier = Modifier.fillMaxWidth().height(250.dp),
    isMinimap: Boolean = false
) {
    var isDragging by remember { mutableStateOf(false) }
    var mapViewInstance by remember { mutableStateOf<MapView?>(null) }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    val lastResetTrigger = resetTrigger
                    MapView(context).apply {
                        tag = lastResetTrigger

                        setOnTouchListener { v, event ->
                            when (event.action) {
                                android.view.MotionEvent.ACTION_DOWN -> v.parent.requestDisallowInterceptTouchEvent(true)
                                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> v.parent.requestDisallowInterceptTouchEvent(false)
                            }
                            false
                        }
                        setMultiTouchControls(true)
                        zoomController.setVisibility(
                            if (isMinimap) CustomZoomButtonsController.Visibility.NEVER
                            else CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT
                        )
                        controller.setZoom(16.0)

                        // Read POIs directly from GeoJSON via repository (Single Source of Truth)
                        val pois = PoiRepository.loadPois(context)

                        for (poi in pois) {
                            val center = GeoPoint(poi.lat, poi.lon)
                            val marker = Marker(this)
                            marker.position = center
                            marker.title = poi.name
                            overlays.add(marker)

                            val circle = Polygon(this)
                            val pts = Polygon.pointsAsCircle(center, poi.radiusMeters)
                            circle.points = pts
                            circle.fillPaint.color = 0x220000FF
                            circle.outlinePaint.color = 0x880000FF.toInt()
                            circle.outlinePaint.strokeWidth = 2.0f
                            overlays.add(circle)
                        }

                        val centerPt = if (isManualGpsMode && manualLat != null && manualLon != null) {
                            GeoPoint(manualLat, manualLon)
                        } else if (location != null) {
                            GeoPoint(location.latitude, location.longitude)
                        } else {
                            GeoPoint(40.892, 29.377)
                        }
                        controller.setCenter(centerPt)
                        mapViewInstance = this
                    }
                },
                update = { mapView ->
                    var manualMarker = mapView.overlays.find { it is Marker && it.title == "Manual" } as? Marker
                    var userMarker = mapView.overlays.find { it is Marker && it.title == "User" } as? Marker

                    val lastResetTrigger = mapView.tag as? Int ?: 0
                    val forceUpdate = resetTrigger != lastResetTrigger
                    if (forceUpdate) {
                        mapView.tag = resetTrigger
                    }

                    if (isManualGpsMode && manualLat != null && manualLon != null) {
                        if (userMarker != null) {
                            mapView.overlays.remove(userMarker)
                        }
                        if (manualMarker == null) {
                            manualMarker = Marker(mapView)
                            manualMarker.title = "Manual"
                            manualMarker.isDraggable = true
                            manualMarker.icon = ContextCompat.getDrawable(mapView.context, android.R.drawable.ic_menu_edit)
                            manualMarker.setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                                override fun onMarkerDragStart(marker: Marker) {
                                    isDragging = true
                                }
                                override fun onMarkerDrag(marker: Marker) {}
                                override fun onMarkerDragEnd(marker: Marker) {
                                    isDragging = false
                                    onManualLocationChange?.invoke(marker.position.latitude, marker.position.longitude)
                                }
                            })
                            mapView.overlays.add(manualMarker)
                            manualMarker.position = GeoPoint(manualLat, manualLon)
                            mapView.invalidate()
                        }

                        if (forceUpdate && !isDragging) {
                            manualMarker.position = GeoPoint(manualLat, manualLon)
                            mapView.invalidate()
                        }

                    } else if (!isManualGpsMode && location != null) {
                        if (manualMarker != null) {
                            mapView.overlays.remove(manualMarker)
                        }

                        val userPt = GeoPoint(location.latitude, location.longitude)
                        if (userMarker == null) {
                            userMarker = Marker(mapView)
                            userMarker.title = "User"
                            userMarker.icon = ContextCompat.getDrawable(mapView.context, android.R.drawable.ic_menu_mylocation)
                            mapView.overlays.add(userMarker)
                        }
                        userMarker.position = userPt
                        mapView.invalidate()
                    } else {
                        if (manualMarker != null) mapView.overlays.remove(manualMarker)
                        if (userMarker != null) mapView.overlays.remove(userMarker)
                        mapView.invalidate()
                    }
                }
            )

            if (isMinimap) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Surface(
                        onClick = { mapViewInstance?.controller?.zoomIn() },
                        modifier = Modifier.size(28.dp),
                        shape = MaterialTheme.shapes.extraSmall,
                        color = Color.White.copy(alpha = 0.8f),
                        tonalElevation = 2.dp
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Add, contentDescription = "Zoom In", modifier = Modifier.size(16.dp))
                        }
                    }
                    Surface(
                        onClick = { mapViewInstance?.controller?.zoomOut() },
                        modifier = Modifier.size(28.dp),
                        shape = MaterialTheme.shapes.extraSmall,
                        color = Color.White.copy(alpha = 0.8f),
                        tonalElevation = 2.dp
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Remove, contentDescription = "Zoom Out", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}
