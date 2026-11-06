package org.example

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@Composable
fun GaitApp(
    features: GaitFeatures?,
    stepIntervals: List<Float>,
    analyzer: GaitAnalyzer,
    bleManager: BleHeartRateManager,
    voiceManager: VoiceSensorManager,
    locationSensorManager: LocationSensorManager,
    tourManager: TourManager,
    isManualGpsMode: Boolean = false,
    manualLat: Double? = null,
    manualLon: Double? = null,
    location: android.location.Location? = null,
    serverUrl: String = "",
    onServerUrlChange: (String) -> Unit = {},
    onUploadClicked: () -> Unit = {},
    onSaveToDownloadsClicked: () -> Unit = {},
    onOpenSurvey: () -> Unit = {}
) {
    val hrValue by bleManager.heartRate.collectAsState()
    val bleState by bleManager.connectionState.collectAsState()
    val isSpeaking by voiceManager.isSpeaking.collectAsState()
    val isCooldown by voiceManager.isCooldown.collectAsState()

    val isAutoMode by tourManager.isAutoMode.collectAsState()
    val presentationMode by tourManager.presentationMode.collectAsState()
    val currentArea by tourManager.currentArea.collectAsState()
    val resetTrigger by tourManager.manualGpsResetTrigger.collectAsState()
    val isEndOfTourReached by tourManager.isEndOfTourReached.collectAsState()
    val completedPois by tourManager.completedPois.collectAsState()

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
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Predefined Tour Modes Toggle (Dynamic vs Static)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Tour Adaptation Mode",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = Color.Black
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (isAutoMode) "Dynamic" else "Static",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isAutoMode) Color(0xFF00796B) else Color(0xFFE65100)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Switch(
                                checked = isAutoMode,
                                onCheckedChange = { tourManager.setAutoMode(it) }
                            )
                        }
                    }

                    // Interactive Segmented Buttons for Predefined Modes
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFFB2EBF2))
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { tourManager.setAutoMode(true) },
                            shape = RoundedCornerShape(6.dp),
                            color = if (isAutoMode) MaterialTheme.colorScheme.primary else Color.Transparent
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "Dynamic Mode",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = if (isAutoMode) Color.White else Color.DarkGray
                                )
                                Text(
                                    text = "Adaptive content",
                                    fontSize = 10.sp,
                                    color = if (isAutoMode) Color.White.copy(alpha = 0.85f) else Color.Gray
                                )
                            }
                        }

                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { tourManager.setAutoMode(false) },
                            shape = RoundedCornerShape(6.dp),
                            color = if (!isAutoMode) MaterialTheme.colorScheme.primary else Color.Transparent
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "Static Mode",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = if (!isAutoMode) Color.White else Color.DarkGray
                                )
                                Text(
                                    text = "Fixed: low load setup",
                                    fontSize = 10.sp,
                                    color = if (!isAutoMode) Color.White.copy(alpha = 0.85f) else Color.Gray
                                )
                            }
                        }
                    }

                    Text(
                        text = if (isAutoMode)
                            "• Dynamic (Auto Mode): the audio guide adapts speech rate, inter-line pauses, script density, and key-fact repetition (checkpoints) to the real-time load level (pipeline-spec §7.1)."
                        else
                            "• Static (Control): fixed Low-configuration delivery — detailed script, 1.0x TTS, no adaptation (§7.5).",
                        fontSize = 11.sp,
                        color = Color(0xFF006064),
                        lineHeight = 15.sp
                    )

                    Text(
                        text = "Presentation Mode (audio-first pivot)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = Color.Black
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFFB2EBF2))
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf(
                            PresentationMode.MULTIMODAL to "Multimodal",
                            PresentationMode.AUDIO_FIRST to "Audio-First",
                            PresentationMode.AUDIO_ONLY to "Audio-Only"
                        ).forEach { (mode, label) ->
                            val selected = presentationMode == mode
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { tourManager.setPresentationMode(mode) },
                                shape = RoundedCornerShape(6.dp),
                                color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
                            ) {
                                Column(
                                    modifier = Modifier.padding(vertical = 8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = label,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = if (selected) Color.White else Color.DarkGray
                                    )
                                }
                            }
                        }
                    }

                    Text(
                        text = "• Audio modes move all adaptation into the narration (rate, pauses, density, checkpoints, earcons); map/images/transcript are hidden or reduced accordingly (§7.0).",
                        fontSize = 11.sp,
                        color = Color(0xFF006064),
                        lineHeight = 15.sp
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

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

                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = onServerUrlChange,
                        label = { Text("Visualizer Server URL") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(onClick = onUploadClicked, modifier = Modifier.weight(1f)) {
                            Text("Upload Last")
                        }
                        Button(onClick = onSaveToDownloadsClicked, modifier = Modifier.weight(1f)) {
                            Text("Save to Downloads")
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Text(
                        text = "POI Progress: ${completedPois.size}/3 completed ${if (completedPois.isEmpty()) "" else "(${completedPois.joinToString { it.replace('_', ' ') }})"}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (completedPois.size == 3) Color(0xFF1B5E20) else Color.DarkGray
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { tourManager.debugCompleteAllPois() },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text("Mark All 3 POIs Done", fontSize = 11.sp)
                        }
                        OutlinedButton(
                            onClick = { tourManager.resetCompletedPois() },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text("Reset POI Progress", fontSize = 11.sp)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Simulate End of Tour", fontSize = 14.sp, color = Color.Black)
                        Switch(
                            checked = isEndOfTourReached,
                            onCheckedChange = { tourManager.setEndOfTourReached(it) }
                        )
                    }

                    Button(
                        onClick = { tourManager.finishTour() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828))
                    ) {
                        Text("End Tour (Finish & Trigger Survey)")
                    }

                    Button(
                        onClick = onOpenSurvey,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6A1B9A))
                    ) {
                        Text("Open Retention Survey (Debug View)")
                    }
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
