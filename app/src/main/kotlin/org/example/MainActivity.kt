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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import android.graphics.BitmapFactory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.ui.draw.scale
import androidx.compose.material.icons.filled.Pause

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
        
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
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
                Surface(color = Color.White) {
                    MainAppScreen(analyzer, bleManager, voiceManager, locationSensorManager, tourManager, blinkSensorManager)
                }
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsList.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionsList.add(Manifest.permission.BLUETOOTH_SCAN)
            permissionsList.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissionsList.add(Manifest.permission.BLUETOOTH)
            permissionsList.add(Manifest.permission.BLUETOOTH_ADMIN)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissionsList.add(Manifest.permission.ACTIVITY_RECOGNITION)
            permissionsList.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
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
        if (!tourManager.isPlaying.value) {
            sensorManager.stop()
            voiceManager.stop()
            locationSensorManager.stop()
        }
    }
    
    override fun onStop() {
        super.onStop()
        if (!tourManager.isPlaying.value) {
            sensorManager.stop()
            voiceManager.stop()
            locationSensorManager.stop()
        }
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
    
    val isManualGpsMode by tourManager.isManualGpsMode.collectAsState()
    val manualLat by tourManager.manualLocationLat.collectAsState()
    val manualLon by tourManager.manualLocationLon.collectAsState()

    val effectiveLat = if (isManualGpsMode) manualLat else location?.latitude
    val effectiveLon = if (isManualGpsMode) manualLon else location?.longitude

    LaunchedEffect(effectiveLat, effectiveLon) {
        if (effectiveLat != null && effectiveLon != null) {
            tourManager.checkLocation(effectiveLat, effectiveLon)
        }
    }
    
    val isPlaying by tourManager.isPlaying.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            TourService.startService(context)
        } else {
            TourService.stopService(context)
        }
    }

    val hrValue by bleManager.heartRate.collectAsState()
    val rrIntervals by bleManager.rrIntervals.collectAsState()
    val isSpeaking by voiceManager.isSpeaking.collectAsState()
    
    var features by remember { mutableStateOf<GaitFeatures?>(null) }
    var stepIntervals by remember { mutableStateOf<List<Float>>(emptyList()) }

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

    Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
        TabRow(
            selectedTabIndex = selectedTabIndex,
            modifier = Modifier.height(72.dp),
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.primary
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTabIndex == index,
                    onClick = { selectedTabIndex = index },
                    text = { 
                        Text(
                            text = title,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        ) 
                    }
                )
            }
        }

        when (selectedTabIndex) {
            0 -> TourScreen(
                tourManager = tourManager, 
                location = location,
                isManualGpsMode = isManualGpsMode,
                manualLat = manualLat,
                manualLon = manualLon
            )
            1 -> GaitApp(
                features = features,
                stepIntervals = stepIntervals,
                analyzer = analyzer, 
                bleManager = bleManager, 
                voiceManager = voiceManager, 
                locationSensorManager = locationSensorManager, 
                tourManager = tourManager, 
                blinkSensorManager = blinkSensorManager,
                isManualGpsMode = isManualGpsMode,
                manualLat = manualLat,
                manualLon = manualLon,
                location = location
            )
        }
    }
}

@Composable
fun AssetUtteranceImage(
    location: String?,
    index: Int?,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onImageLoaded: ((ImageBitmap) -> Unit)? = null
) {
    if (location == null || index == null) return

    val context = LocalContext.current
    var imageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var attemptedLoad by remember { mutableStateOf(false) }

    LaunchedEffect(location, index) {
        withContext(Dispatchers.IO) {
            try {
                val assetManager = context.assets
                val folderPath = "images/$location"
                val files = assetManager.list(folderPath) ?: emptyArray()
                
                // Find a file where the trailing number matches the index exactly
                val file = files.find { 
                    val nameWithoutExt = it.substringBeforeLast(".")
                    val trailingNumber = nameWithoutExt.takeLastWhile { char -> char.isDigit() }.toIntOrNull()
                    trailingNumber == index
                }
                
                if (file != null) {
                    val inputStream = assetManager.open("$folderPath/$file")
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    val img = bitmap.asImageBitmap()
                    inputStream.close()
                    withContext(Dispatchers.Main) {
                        imageBitmap = img
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            attemptedLoad = true
        }
    }

    LaunchedEffect(imageBitmap) {
        if (imageBitmap != null) {
            onImageLoaded?.invoke(imageBitmap!!)
        }
    }

    if (imageBitmap != null) {
        if (isExpanded) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFD6D6D6)),
                onClick = onToggle
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(), 
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = imageBitmap!!,
                        contentDescription = "Utterance Image",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            }
        } else {
            TextButton(onClick = onToggle) {
                Text("Show Image", fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun TourScreen(
    tourManager: TourManager, 
    location: android.location.Location?,
    isManualGpsMode: Boolean = false,
    manualLat: Double? = null,
    manualLon: Double? = null
) {
    val history by tourManager.history.collectAsState()
    val currentArea by tourManager.currentArea.collectAsState()
    val resetTrigger by tourManager.manualGpsResetTrigger.collectAsState()
    val isPlaying by tourManager.isPlaying.collectAsState()
    val isAutoMode by tourManager.isAutoMode.collectAsState()
    val isLargeImageMode by tourManager.isLargeImageMode.collectAsState()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var latestImage by remember { mutableStateOf<ImageBitmap?>(null) }
    var latestImageIndex by remember { mutableStateOf(-1) }
    var scrollTrigger by remember { mutableStateOf(0) }

    val showMap = currentArea == "None"

    LaunchedEffect(history) {
        if (history.size <= 1) {
            latestImage = null
            latestImageIndex = -1
        }
    }

    LaunchedEffect(history, isLargeImageMode, showMap, scrollTrigger) {
        if (history.isNotEmpty()) {
            // Give layout a bit more time to settle, especially for mode switches
            delay(150)
            val totalItems = history.size + history.count { it.isUtterance }
            listState.animateScrollToItem(totalItems - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Phase 2: Out of Bounds layout (showMap) mimics Large Image Mode
                // Phase 1: In-bounds layout follows isLargeImageMode
                val showTopBox = showMap || isLargeImageMode

                if (showTopBox) {
                    // Top Box: Map if Out of Bounds, Image if In Bounds
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        colors = CardDefaults.cardColors(
                            containerColor = if (showMap) Color.White else Color(0xFFE0E0E0)
                        )
                    ) {
                        if (showMap) {
                            OsmMapPreview(
                                location = location, 
                                isManualGpsMode = isManualGpsMode,
                                manualLat = manualLat,
                                manualLon = manualLon,
                                resetTrigger = resetTrigger,
                                onManualLocationChange = { lat, lon -> tourManager.updateManualLocation(lat, lon) },
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                if (latestImage != null) {
                                    Image(
                                        bitmap = latestImage!!,
                                        contentDescription = "Latest Utterance Image",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Text(
                                        text = "Large Image Placeholder\nWaiting for image...",
                                        color = Color.Gray,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 20.sp,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                }

                // Text Box
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxSize()
                    ) {
                        history.forEachIndexed { index, item ->
                            val isLast = index == history.size - 1
                            val textColor = if (isLast) Color.Black else Color.DarkGray

                            if (item.isUtterance) {
                                item {
                                    val isExpanded = item.showImage ?: (showMap || !isLargeImageMode)
                                    AssetUtteranceImage(
                                        location = item.location,
                                        index = item.utteranceIndex,
                                        isExpanded = isExpanded,
                                        onToggle = { 
                                            tourManager.toggleImageForUtterance(index, isExpanded)
                                            scrollTrigger++
                                        },
                                        onImageLoaded = { bitmap ->
                                            if (index >= latestImageIndex) {
                                                latestImage = bitmap
                                                latestImageIndex = index
                                            }
                                            scrollTrigger++
                                        }
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                }
                            }

                            item {
                                Text(
                                    text = item.text,
                                    fontSize = 18.sp,
                                    fontWeight = if (isLast) FontWeight.Medium else FontWeight.Normal,
                                    color = textColor
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                            }
                        }
                    }
                }

                // Bottom sections (Map or Fallback) are now consolidated in the Top Box logic above
            }

            // Minimap overlay when NOT in "None" area (not out of bounds)
            if (!showMap) {
                Card(
                    modifier = Modifier
                        .padding(24.dp)
                        .size(120.dp)
                        .align(Alignment.TopEnd),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                    shape = MaterialTheme.shapes.small
                ) {
                    OsmMapPreview(
                        location = location,
                        isManualGpsMode = isManualGpsMode,
                        manualLat = manualLat,
                        manualLon = manualLon,
                        resetTrigger = resetTrigger,
                        onManualLocationChange = { lat, lon -> tourManager.updateManualLocation(lat, lon) },
                        modifier = Modifier.fillMaxSize(),
                        isMinimap = true
                    )
                }
            }
        }
        
        // Control Bar
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 4.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp, horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { tourManager.resetTour() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Reset Tour")
                }

                IconButton(onClick = { tourManager.prevIndex() }) {
                    Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "Previous")
                }

                FilledIconButton(
                    onClick = { 
                        if (!isPlaying) {
                            tourManager.setAutoMode(true)
                            tourManager.startTour()
                        } else {
                            tourManager.togglePlayPause() 
                        }
                    },
                    modifier = Modifier.size(56.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (isPlaying) Color(0xFFE91E63) else Color(0xFF4CAF50)
                    )
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Stop" else "Start",
                        modifier = Modifier.size(32.dp)
                    )
                }

                IconButton(onClick = { tourManager.nextIndex() }) {
                    Icon(Icons.Default.KeyboardArrowRight, contentDescription = "Next")
                }
            }
        }
    }
}

@Composable
fun GaitApp(
    features: GaitFeatures?,
    stepIntervals: List<Float>,
    analyzer: GaitAnalyzer, 
    bleManager: BleHeartRateManager, 
    voiceManager: VoiceSensorManager, 
    locationSensorManager: LocationSensorManager, 
    tourManager: TourManager, 
    blinkSensorManager: BlinkSensorManager,
    isManualGpsMode: Boolean = false,
    manualLat: Double? = null,
    manualLon: Double? = null,
    location: android.location.Location? = null
) {
    val hrValue by bleManager.heartRate.collectAsState()
    val bleState by bleManager.connectionState.collectAsState()
    val isSpeaking by voiceManager.isSpeaking.collectAsState()
    val isCooldown by voiceManager.isCooldown.collectAsState()
    val blinksLast30s by blinkSensorManager.blinksLast30s.collectAsState()
    val leftEyeProb by blinkSensorManager.leftEyeProb.collectAsState()
    val rightEyeProb by blinkSensorManager.rightEyeProb.collectAsState()

    val isPlaying by tourManager.isPlaying.collectAsState()
    val isDetailed by tourManager.isDetailed.collectAsState()
    val isAutoMode by tourManager.isAutoMode.collectAsState()
    val currentLocation by tourManager.currentLocation.collectAsState()
    val currentArea by tourManager.currentArea.collectAsState()
    val resetTrigger by tourManager.manualGpsResetTrigger.collectAsState()
    
    val isLargeImageMode by tourManager.isLargeImageMode.collectAsState()

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
                            Text("Large Img Box", fontSize = 12.sp, color = Color.Black)
                            Switch(
                                checked = isLargeImageMode,
                                onCheckedChange = { tourManager.toggleLargeImageMode() },
                                modifier = Modifier.scale(0.8f)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Auto Mode", fontSize = 12.sp, color = Color.Black)
                            Switch(
                                checked = isAutoMode,
                                onCheckedChange = { tourManager.toggleAutoMode() },
                                modifier = Modifier.scale(0.8f)
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isManualGpsMode) {
                                Button(
                                    onClick = { tourManager.resetManualLocation(location?.latitude, location?.longitude) },
                                    modifier = Modifier.padding(end = 8.dp)
                                ) {
                                    Text("Reset")
                                }
                            }
                            Switch(
                                checked = isManualGpsMode,
                                onCheckedChange = { tourManager.setManualGpsMode(it, location?.latitude, location?.longitude) }
                            )
                        }
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
                SmallLoadCard(
                    label = "Gait", 
                    load = features?.gaitLoadIndex ?: 0.0, 
                    modifier = Modifier.weight(1f),
                    subtitle = if (features?.isWalking == false) "not walking" else null
                )
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
                manualLat = manualLat,
                manualLon = manualLon,
                resetTrigger = resetTrigger,
                onManualLocationChange = { lat, lon -> tourManager.updateManualLocation(lat, lon) }
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
                    var lastResetTrigger = resetTrigger
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
                        setBuiltInZoomControls(!isMinimap)
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
fun SmallLoadCard(label: String, load: Double?, modifier: Modifier = Modifier, subtitle: String? = null) {
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
            if (subtitle != null) {
                Text(text = subtitle, fontSize = 10.sp, color = Color.White.copy(alpha = 0.8f))
            }
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
