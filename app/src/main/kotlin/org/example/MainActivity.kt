package org.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.util.*

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState

import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import androidx.compose.ui.viewinterop.AndroidView

class MainActivity : ComponentActivity() {

    private val analyzer = GaitAnalyzer()
    private lateinit var sensorManager: GaitSensorManager
    private lateinit var bleManager: BleHeartRateManager
    private lateinit var voiceManager: VoiceSensorManager
    private lateinit var locationSensorManager: LocationSensorManager
    private lateinit var ttsManager: TTSManager
    private lateinit var tourManager: TourManager
    private lateinit var blinkSensorManager: BlinkSensorManager

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.all { it.value }
        if (granted) {
            bleManager.startScan()
            locationSensorManager.start()
            blinkSensorManager.start(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Configuration.getInstance().userAgentValue = packageName
        
        sensorManager = GaitSensorManager(this, analyzer)
        bleManager = BleHeartRateManager(this)
        voiceManager = VoiceSensorManager()
        locationSensorManager = LocationSensorManager(this)
        ttsManager = TTSManager(this)
        tourManager = TourManager(this, ttsManager)
        blinkSensorManager = BlinkSensorManager(this)
        
        checkAndRequestPermissions()

        setContent {
            MaterialTheme {
                MainAppScreen(analyzer, bleManager, voiceManager, locationSensorManager, tourManager, blinkSensorManager)
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsList = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionsList.add(Manifest.permission.BLUETOOTH_SCAN)
            permissionsList.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissionsList.add(Manifest.permission.BLUETOOTH)
            permissionsList.add(Manifest.permission.BLUETOOTH_ADMIN)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissionsList.add(Manifest.permission.ACTIVITY_RECOGNITION)
        }

        val missingPermissions = permissionsList.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        } else {
            bleManager.startScan()
            locationSensorManager.start()
            blinkSensorManager.start(this)
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            sensorManager.start()
            voiceManager.start()
            locationSensorManager.start()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.stop()
        voiceManager.stop()
        locationSensorManager.stop()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        bleManager.disconnect()
        ttsManager.shutdown()
        blinkSensorManager.stop()
    }
}

@Composable
fun MainAppScreen(
    analyzer: GaitAnalyzer, 
    bleManager: BleHeartRateManager, 
    voiceManager: VoiceSensorManager, 
    locationSensorManager: LocationSensorManager,
    tourManager: TourManager,
    blinkSensorManager: BlinkSensorManager
) {
    var selectedTabIndex by remember { mutableStateOf(0) }
    val tabs = listOf("Tour", "Debug")
    val location by locationSensorManager.location.collectAsState()
    
    var isManualGpsMode by remember { mutableStateOf(false) }
    var manualLocation by remember { mutableStateOf<GeoPoint?>(null) }

    val effectiveLat = if (isManualGpsMode) manualLocation?.latitude else location?.latitude
    val effectiveLon = if (isManualGpsMode) manualLocation?.longitude else location?.longitude

    LaunchedEffect(effectiveLat, effectiveLon) {
        if (effectiveLat != null && effectiveLon != null) {
            tourManager.checkLocation(effectiveLat, effectiveLon)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTabIndex) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTabIndex == index,
                    onClick = { selectedTabIndex = index },
                    text = { Text(title) }
                )
            }
        }

        when (selectedTabIndex) {
            0 -> TourScreen(
                tourManager = tourManager, 
                location = location,
                isManualGpsMode = isManualGpsMode,
                manualLocation = manualLocation
            )
            1 -> GaitApp(
                analyzer = analyzer, 
                bleManager = bleManager, 
                voiceManager = voiceManager, 
                locationSensorManager = locationSensorManager, 
                tourManager = tourManager, 
                blinkSensorManager = blinkSensorManager,
                isManualGpsMode = isManualGpsMode,
                onManualGpsModeChange = { 
                    isManualGpsMode = it 
                    if (it && manualLocation == null) {
                        manualLocation = location?.let { loc -> GeoPoint(loc.latitude, loc.longitude) } ?: GeoPoint(40.892, 29.377)
                    }
                },
                manualLocation = manualLocation,
                onManualLocationChange = { manualLocation = it }
            )
        }
    }
}

@Composable
fun TourScreen(
    tourManager: TourManager, 
    location: android.location.Location?,
    isManualGpsMode: Boolean = false,
    manualLocation: GeoPoint? = null
) {
    val history by tourManager.history.collectAsState()
    val currentArea by tourManager.currentArea.collectAsState()
    val listState = rememberLazyListState()

    val showMap = currentArea == "None"

    LaunchedEffect(history.size) {
        if (history.isNotEmpty()) {
            listState.animateScrollToItem(history.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Top half: Image Box or Map
        if (showMap) {
            OsmMapPreview(
                location = location, 
                isManualGpsMode = isManualGpsMode,
                manualLocation = manualLocation,
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
        } else {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE0E0E0))
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Image Placeholder",
                        color = Color.Gray,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                }
            }
        }

        // Bottom half: Text Box
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF0F0F0))
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                itemsIndexed(history) { index, text ->
                    val isLast = index == history.size - 1
                    val textColor = if (isLast) Color.Black else Color.DarkGray

                    Text(
                        text = text,
                        fontSize = 18.sp,
                        fontWeight = if (isLast) FontWeight.Medium else FontWeight.Normal,
                        color = textColor
                    )
                }
            }
        }
    }
}

@Composable
fun GaitApp(
    analyzer: GaitAnalyzer, 
    bleManager: BleHeartRateManager, 
    voiceManager: VoiceSensorManager, 
    locationSensorManager: LocationSensorManager, 
    tourManager: TourManager, 
    blinkSensorManager: BlinkSensorManager,
    isManualGpsMode: Boolean = false,
    onManualGpsModeChange: (Boolean) -> Unit = {},
    manualLocation: GeoPoint? = null,
    onManualLocationChange: ((GeoPoint) -> Unit)? = null
) {
    var features by remember { mutableStateOf<GaitFeatures?>(null) }
    var stepIntervals by remember { mutableStateOf<List<Float>>(emptyList()) }
    val hrValue by bleManager.heartRate.collectAsState()
    val rrIntervals by bleManager.rrIntervals.collectAsState()
    val bleState by bleManager.connectionState.collectAsState()
    val isSpeaking by voiceManager.isSpeaking.collectAsState()
    val isCooldown by voiceManager.isCooldown.collectAsState()
    val location by locationSensorManager.location.collectAsState()
    val blinksLast30s by blinkSensorManager.blinksLast30s.collectAsState()
    val leftEyeProb by blinkSensorManager.leftEyeProb.collectAsState()
    val rightEyeProb by blinkSensorManager.rightEyeProb.collectAsState()

    val isPlaying by tourManager.isPlaying.collectAsState()
    val isDetailed by tourManager.isDetailed.collectAsState()
    val isAutoMode by tourManager.isAutoMode.collectAsState()
    val currentLocation by tourManager.currentLocation.collectAsState()
    val currentArea by tourManager.currentArea.collectAsState()
    
    LaunchedEffect(rrIntervals) {
        analyzer.addRRIntervals(rrIntervals)
    }

    LaunchedEffect(isSpeaking) {
        while (true) {
            try {
                features = analyzer.analyze(hrValue, isSpeaking)
                stepIntervals = analyzer.getStepIntervals()
                tourManager.updateContext(features?.loadLevel ?: "Waiting...", isSpeaking)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            delay(1000) // Update every second
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Tour Controls
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE0F7FA))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Tour Controls", fontWeight = FontWeight.Bold, color = Color.Black)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Auto Mode", fontSize = 14.sp, color = Color.Black)
                            Spacer(Modifier.width(8.dp))
                            Switch(
                                checked = isAutoMode,
                                onCheckedChange = { tourManager.toggleAutoMode() }
                            )
                        }
                    }
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Button(
                            onClick = { tourManager.togglePlayPause() },
                            enabled = !isAutoMode
                        ) {
                            Text(if (isPlaying) "Pause Tour" else "Play Tour")
                        }
                        
                        Button(
                            onClick = { tourManager.toggleScript() },
                            enabled = !isAutoMode,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isDetailed) Color(0xFF1976D2) else Color(0xFF388E3C),
                                disabledContainerColor = Color.Gray
                            )
                        ) {
                            Text(if (isDetailed) "Detailed Script" else "Simple Script")
                        }
                    }

                    // Location selector
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        tourManager.availableLocations.forEach { loc ->
                            val isSelected = currentLocation == loc
                            Button(
                                onClick = { tourManager.selectLocation(loc) },
                                enabled = !isAutoMode,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isSelected) Color(0xFF00796B) else Color.Gray,
                                    disabledContainerColor = if (isSelected) Color(0x8800796B) else Color.LightGray
                                ),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = loc.replace("_", " ").split(" ").joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } },
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Manual GPS Mode", fontSize = 14.sp, color = Color.Black)
                        Switch(
                            checked = isManualGpsMode,
                            onCheckedChange = onManualGpsModeChange
                        )
                    }

                    Text(
                        text = "Current POI Area: ${if (currentArea == "None") "None" else currentArea.replace("_", " ").split(" ").joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.DarkGray,
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp)
                    )
                }
            }

            Text(
                text = "Gait & Heart Rate Monitor",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Cognitive Load Estimations",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.Start)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SmallLoadCard("Gait", features?.gaitLoadIndex ?: 0.0, Modifier.weight(1f))
                SmallLoadCard("Heart", features?.hrLoadIndex ?: 0.0, Modifier.weight(1f))
            }

            CognitiveLoadCard("Combined Load", features?.combinedLoadIndex, features?.loadLevel ?: "Waiting...")

            // Voice Activity Indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Mic:", fontSize = 14.sp, color = Color.Gray)
                Spacer(modifier = Modifier.width(8.dp))
                Canvas(modifier = Modifier.size(12.dp)) {
                    drawCircle(color = if (isSpeaking) Color.Green else Color.Gray)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = when {
                        isCooldown -> "Waiting 3s... (+1 Level)"
                        isSpeaking -> "Speech Detected (+1 Level)"
                        else -> "Quiet"
                    }, 
                    fontSize = 14.sp, 
                    color = if (isSpeaking) Color.DarkGray else Color.Gray
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            
            HeartRateCard(hrValue, bleState, features?.sdnn ?: 0.0)

            Spacer(modifier = Modifier.height(8.dp))
            
            StepIntervalGraph(stepIntervals)

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Inner Workings (Gait Parameters)",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.Start)
            )

            ParameterRow("Step Time", String.format(Locale.US, "%.0f ms", features?.stepTime ?: 0.0))
            ParameterRow("Step Variability", String.format(Locale.US, "%.1f ms", features?.stepTimeVariability ?: 0.0))
            ParameterRow("Location", location?.let { String.format(Locale.US, "%.5f, %.5f", it.latitude, it.longitude) } ?: "Waiting for GPS...")
            ParameterRow("Left Eye Open Prob", leftEyeProb?.let { String.format(Locale.US, "%.2f", it) } ?: "--")
            ParameterRow("Right Eye Open Prob", rightEyeProb?.let { String.format(Locale.US, "%.2f", it) } ?: "--")
            ParameterRow("Blinks (Last 30s)", "$blinksLast30s")
            ParameterRow("Blink Rate (/sec)", String.format(Locale.US, "%.2f", blinksLast30s / 30.0f))

            Spacer(modifier = Modifier.height(16.dp))

            OsmMapPreview(
                location = location,
                isManualGpsMode = isManualGpsMode,
                manualLocation = manualLocation,
                onManualLocationChange = onManualLocationChange
            )

            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = "Demo Build",
                fontSize = 12.sp,
                color = Color.Gray
            )
        }
    }
}

@Composable
fun OsmMapPreview(
    location: android.location.Location?,
    isManualGpsMode: Boolean = false,
    manualLocation: GeoPoint? = null,
    onManualLocationChange: ((GeoPoint) -> Unit)? = null,
    modifier: Modifier = Modifier.fillMaxWidth().height(250.dp)
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                MapView(context).apply {
                    setOnTouchListener { v, event ->
                        when (event.action) {
                            android.view.MotionEvent.ACTION_DOWN -> v.parent.requestDisallowInterceptTouchEvent(true)
                            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> v.parent.requestDisallowInterceptTouchEvent(false)
                        }
                        false
                    }
                    setMultiTouchControls(true)
                    controller.setZoom(16.0)
                    
                    val pois = listOf(
                        TourManager.POI(1, "amphitheatre", 40.89320979780269, 29.379169091952324, 80.0),
                        TourManager.POI(2, "lake_memorial", 40.892876775980646, 29.375505980922984, 60.0),
                        TourManager.POI(3, "tosun_terzioglu_garden", 40.890581851850961, 29.377186219901855, 80.0)
                    )
                    
                    for (poi in pois) {
                        val center = GeoPoint(poi.lat, poi.lon)
                        val marker = Marker(this)
                        marker.position = center
                        marker.title = poi.name
                        overlays.add(marker)
                        
                        val circle = Polygon(this)
                        val pts = Polygon.pointsAsCircle(center, poi.radiusMeters)
                        circle.points = pts
                        circle.fillColor = 0x220000FF
                        circle.strokeColor = 0x880000FF.toInt()
                        circle.strokeWidth = 2.0f
                        overlays.add(circle)
                    }
                    
                    val centerPt = GeoPoint(40.892, 29.377)
                    controller.setCenter(centerPt)
                }
            },
            update = { mapView ->
                mapView.overlays.removeAll { it is Marker && (it.title == "User" || it.title == "Manual") }
                
                if (isManualGpsMode && manualLocation != null) {
                    val manualMarker = Marker(mapView)
                    manualMarker.position = manualLocation
                    manualMarker.title = "Manual"
                    manualMarker.isDraggable = true
                    manualMarker.icon = ContextCompat.getDrawable(mapView.context, android.R.drawable.ic_menu_edit)
                    manualMarker.setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                        override fun onMarkerDragStart(marker: Marker) {}
                        override fun onMarkerDrag(marker: Marker) {}
                        override fun onMarkerDragEnd(marker: Marker) {
                            onManualLocationChange?.invoke(marker.position)
                        }
                    })
                    mapView.overlays.add(manualMarker)
                    mapView.invalidate()
                } else if (!isManualGpsMode && location != null) {
                    val userPt = GeoPoint(location.latitude, location.longitude)
                    
                    val userMarker = Marker(mapView)
                    userMarker.position = userPt
                    userMarker.title = "User"
                    userMarker.icon = ContextCompat.getDrawable(mapView.context, android.R.drawable.ic_menu_mylocation)
                    mapView.overlays.add(userMarker)
                    
                    mapView.invalidate()
                }
            }
        )
    }
}

@Composable
fun StepIntervalGraph(intervals: List<Float>) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text("Step Intervals (ms)", fontSize = 12.sp, color = Color.Gray)
            Spacer(modifier = Modifier.height(4.dp))
            Canvas(modifier = Modifier.fillMaxSize()) {
                if (intervals.isEmpty()) return@Canvas

                val width = size.width
                val height = size.height

                val maxVal = maxOf(intervals.maxOrNull() ?: 1000f, 800f)
                val minVal = minOf(intervals.minOrNull() ?: 300f, 400f)
                val range = maxVal - minVal + 100f // Padding
                
                val points = intervals.mapIndexed { index, value ->
                    val x = if (intervals.size > 1) index.toFloat() / (intervals.size - 1) * width else width / 2f
                    val y = height - ((value - minVal + 50f) / range * height)
                    Offset(x, y)
                }

                val path = Path()
                points.forEachIndexed { index, offset ->
                    if (index == 0) {
                        path.moveTo(offset.x, offset.y)
                    } else {
                        path.lineTo(offset.x, offset.y)
                    }
                    drawCircle(
                        color = Color.Red,
                        radius = 4.dp.toPx(),
                        center = offset
                    )
                }

                drawPath(
                    path = path,
                    color = Color.Blue,
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }
    }
}

@Composable
fun HeartRateCard(hrValue: Int?, bleState: String, sdnn: Double) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(110.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFE91E63)
        )
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(text = "Heart Rate", fontSize = 14.sp, color = Color.White)
                Text(
                    text = hrValue?.let { "$it BPM" } ?: "-- BPM",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(text = "SDNN (HRV)", fontSize = 14.sp, color = Color.White)
                Text(
                    text = String.format(Locale.US, "%.1f ms", sdnn),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(text = bleState, fontSize = 10.sp, color = Color.White.copy(alpha = 0.7f))
            }
        }
    }
}

@Composable
fun SmallLoadCard(label: String, load: Double?, modifier: Modifier = Modifier) {
    val color = if (load == null) Color.Gray else getNumericLoadColor(load)
    Card(
        modifier = modifier.height(80.dp),
        colors = CardDefaults.cardColors(
            containerColor = color
        )
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(text = label, fontSize = 12.sp, color = Color.White)
            Text(
                text = if (load == null) "--" else String.format(Locale.US, "%.1f", load),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}

@Composable
fun CognitiveLoadCard(title: String, load: Double?, level: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
        colors = CardDefaults.cardColors(
            containerColor = getLevelColor(level)
        )
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title,
                fontSize = 18.sp,
                color = Color.White
            )
            Text(
                text = if (load == null) "Waiting..." else String.format(Locale.US, "%.1f", load),
                fontSize = 38.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            if (load != null) {
                Text(
                    text = level,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White.copy(alpha = 0.9f)
                )
            }
        }
    }
}

fun getNumericLoadColor(load: Double): Color {
    return when {
        load < 50.0 -> Color(0xFF4CAF50) // Low - Green
        load < 100.0 -> Color(0xFFFFC107) // Medium - Amber
        else -> Color(0xFFF44336)      // High - Red
    }
}

fun getLevelColor(level: String): Color {
    return when (level) {
        "Low" -> Color(0xFF4CAF50)
        "Medium" -> Color(0xFFFFC107)
        "High" -> Color(0xFFF44336)
        "Overload" -> Color(0xFF9C27B0) // Purple for Overload
        else -> Color.Gray
    }
}

@Composable
fun ParameterRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, fontWeight = FontWeight.Medium)
        Text(text = value, color = MaterialTheme.colorScheme.primary)
    }
}
