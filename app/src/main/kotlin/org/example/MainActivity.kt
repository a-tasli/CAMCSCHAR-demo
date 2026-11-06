package org.example

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration

class MainActivity : ComponentActivity() {

    private val analyzer = GaitAnalyzer()
    private lateinit var sensorManager: GaitSensorManager
    private lateinit var bleManager: BleHeartRateManager
    private lateinit var voiceManager: VoiceSensorManager
    private lateinit var locationSensorManager: LocationSensorManager
    private lateinit var ttsManager: TTSManager
    private lateinit var tourManager: TourManager
    private lateinit var dataManager: ExperimentDataManager

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.all { it.value }
        if (granted) {
            bleManager.startScan()
            locationSensorManager.start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        volumeControlStream = AudioManager.STREAM_MUSIC
        Configuration.getInstance().userAgentValue = packageName

        sensorManager = GaitSensorManager(this, analyzer)
        bleManager = BleHeartRateManager(this)
        voiceManager = VoiceSensorManager()
        locationSensorManager = LocationSensorManager(this)
        ttsManager = TTSManager(this)
        tourManager = TourManager(this, ttsManager)
        dataManager = ExperimentDataManager(this)

        // Eyes-free playback control (§7.3): media notification / MediaSession in TourService
        TourControlBridge.playPause = {
            if (tourManager.isPlaying.value) tourManager.togglePlayPause() else tourManager.startTour()
        }
        TourControlBridge.next = { tourManager.nextIndex() }
        TourControlBridge.previous = { tourManager.prevIndex() }
        TourControlBridge.isPlaying = { tourManager.isPlaying.value }

        checkAndRequestPermissions()

        setContent {
            MaterialTheme {
                Surface(color = Color.White) {
                    MainAppScreen(
                        analyzer = analyzer,
                        bleManager = bleManager,
                        voiceManager = voiceManager,
                        locationSensorManager = locationSensorManager,
                        tourManager = tourManager,
                        dataManager = dataManager
                    )
                }
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsList = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO
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
            try {
                sensorManager.stop()
                voiceManager.stop()
                locationSensorManager.stop()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (!tourManager.isPlaying.value) {
            try {
                sensorManager.stop()
                voiceManager.stop()
                locationSensorManager.stop()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        TourControlBridge.reset()
        bleManager.disconnect()
        ttsManager.shutdown()
    }
}

@Composable
fun MainAppScreen(
    analyzer: GaitAnalyzer,
    bleManager: BleHeartRateManager,
    voiceManager: VoiceSensorManager,
    locationSensorManager: LocationSensorManager,
    tourManager: TourManager,
    dataManager: ExperimentDataManager
) {
    var selectedTabIndex by remember { mutableStateOf(0) }
    val tabs = listOf("Tour", "Debug")
    val location by locationSensorManager.location.collectAsState()

    val context = androidx.compose.ui.platform.LocalContext.current
    val sharedPreferences = context.getSharedPreferences("CAMCSCHAR_PREFS", Context.MODE_PRIVATE)
    var serverUrl by remember {
        mutableStateOf(sharedPreferences.getString("server_url", "http://192.168.1.5:3000") ?: "http://192.168.1.5:3000")
    }
    val coroutineScope = rememberCoroutineScope()
    var showSurvey by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        tourManager.tourFinishedEvent.collect {
            if (dataManager.isRecording) {
                dataManager.stopRecording()
            }
            try {
                TourService.stopService(context)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            showSurvey = true
        }
    }

    val isManualGpsMode by tourManager.isManualGpsMode.collectAsState()
    val manualLat by tourManager.manualLocationLat.collectAsState()
    val manualLon by tourManager.manualLocationLon.collectAsState()
    val isAutoMode by tourManager.isAutoMode.collectAsState()

    val effectiveLat = if (isManualGpsMode) manualLat else location?.latitude
    val effectiveLon = if (isManualGpsMode) manualLon else location?.longitude

    LaunchedEffect(effectiveLat, effectiveLon) {
        if (effectiveLat != null && effectiveLon != null) {
            tourManager.checkLocation(effectiveLat, effectiveLon)
        }
    }

    val isPlaying by tourManager.isPlaying.collectAsState()
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            val hasLocation = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (hasLocation) {
                try {
                    TourService.startService(context)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            dataManager.startRecording()
        } else {
            try {
                TourService.stopService(context)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    val rrIntervals by bleManager.rrIntervals.collectAsState()
    var features by remember { mutableStateOf<GaitFeatures?>(null) }
    var stepIntervals by remember { mutableStateOf<List<Float>>(emptyList()) }

    LaunchedEffect(rrIntervals) {
        analyzer.addRRIntervals(rrIntervals)
    }

    // Steady 1 Hz ticker loop: reading latest state without restarting on speech toggle
    LaunchedEffect(Unit) {
        while (true) {
            try {
                val currentHr = bleManager.heartRate.value
                val speaking = voiceManager.isSpeaking.value
                val curLat = if (tourManager.isManualGpsMode.value) tourManager.manualLocationLat.value else locationSensorManager.location.value?.latitude
                val curLon = if (tourManager.isManualGpsMode.value) tourManager.manualLocationLon.value else locationSensorManager.location.value?.longitude

                val computedFeatures = analyzer.analyze(currentHr, speaking)
                features = computedFeatures
                stepIntervals = analyzer.getStepIntervals()
                tourManager.updateContext(computedFeatures?.loadLevel ?: "Waiting...", speaking)

                dataManager.recordData(
                    hr = currentHr,
                    hrv = computedFeatures?.rmssd ?: 0.0,
                    stepVariance = computedFeatures?.stepTimeVariability ?: 0.0,
                    isSpeaking = speaking,
                    lat = curLat,
                    lon = curLon,
                    poi = tourManager.currentArea.value,
                    loadLevel = computedFeatures?.loadLevel ?: "Waiting...",
                    combinedLoadIndex = computedFeatures?.combinedLoadIndex,
                    physicalStrain = computedFeatures?.physicalStrainScore ?: 0.0,
                    mentalHr = computedFeatures?.mentalHrBpm ?: 0.0,
                    isAutoMode = tourManager.isAutoMode.value,
                    isDetailed = tourManager.isDetailed.value,
                    isLargeImageMode = tourManager.isLargeImageMode.value,
                    scriptLine = tourManager.currentIndex + 1,
                    ttsSpeed = tourManager.audioParams.value.speed,
                    interLineDelayMs = tourManager.audioParams.value.pauseMs,
                    isCheckpointActive = tourManager.audioParams.value.checkpoint,
                    presentationMode = tourManager.presentationMode.value.name.lowercase()
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
            delay(1000)
        }
    }

    if (showSurvey) {
        RetentionSurveyScreen(
            initialIsAutoMode = isAutoMode,
            serverUrl = serverUrl,
            dataManager = dataManager,
            onSurveySubmitted = {
                showSurvey = false
                tourManager.resetAppState()
                dataManager.resetState()
            },
            onDismiss = { showSurvey = false }
        )
    } else {
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
                    manualLon = manualLon,
                    onOpenSurvey = { showSurvey = true }
                )
                1 -> GaitApp(
                    features = features,
                    stepIntervals = stepIntervals,
                    analyzer = analyzer,
                    bleManager = bleManager,
                    voiceManager = voiceManager,
                    locationSensorManager = locationSensorManager,
                    tourManager = tourManager,
                    isManualGpsMode = isManualGpsMode,
                    manualLat = manualLat,
                    manualLon = manualLon,
                    location = location,
                    serverUrl = serverUrl,
                    onServerUrlChange = {
                        serverUrl = it
                        sharedPreferences.edit().putString("server_url", it).apply()
                    },
                    onUploadClicked = {
                        coroutineScope.launch {
                            val success = dataManager.uploadLatestFile(serverUrl)
                            Toast.makeText(context, if (success) "Upload successful" else "Upload failed", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onSaveToDownloadsClicked = {
                        coroutineScope.launch {
                            val success = dataManager.saveLatestToDownloads()
                            Toast.makeText(context, if (success) "Saved to Downloads" else "Save failed", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onOpenSurvey = { showSurvey = true }
                )
            }
        }
    }
}
