package org.example

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@Composable
fun CalibrationScreen(
    calibrationState: BaselineSnapshot,
    features: GaitFeatures?,
    hrValue: Int?,
    bleState: String,
    restingHrBpm: Double,
    isSpeaking: Boolean,
    onStartCalibration: () -> Unit,
    onResetCalibration: () -> Unit
) {
    val isCalibrating = calibrationState.phase == CalibrationPhase.CALIBRATING
    val isWalking = features?.isWalking == true

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Pre-Tour Baseline Calibration",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Text(
                text = "Wear the wired headset and HR armband. After seated resting HR stabilizes, press the button below and walk quietly at a natural pace for ${calibrationState.targetTicks} seconds before the tour begins.",
                fontSize = 14.sp,
                color = Color.DarkGray,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F7FA)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Sensor Readiness",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.Black
                    )
                    ParameterRow(
                        label = "HR Armband ($bleState)",
                        value = hrValue?.let { "$it BPM (rest ~${String.format(Locale.US, "%.0f", restingHrBpm)})" } ?: "Waiting..."
                    )
                    val walkingFeatures = features?.takeIf { it.isWalking }
                    val stepCvText = if (walkingFeatures != null && walkingFeatures.stepCvPercent > 0.0) {
                        String.format(Locale.US, "%.2f %%", walkingFeatures.stepCvPercent)
                    } else {
                        "--"
                    }
                    ParameterRow(
                        label = "Walking Status",
                        value = if (walkingFeatures != null) {
                            "Walking (${String.format(Locale.US, "%.0f", walkingFeatures.cadence)} steps/min)"
                        } else {
                            "Standing / Idle"
                        }
                    )
                    ParameterRow(
                        label = "Live Step CV",
                        value = stepCvText
                    )
                    ParameterRow(
                        label = "Silero VAD Mic",
                        value = if (isSpeaking) "Speech detected (stay quiet)" else "Quiet"
                    )
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isCalibrating) Color(0xFFE3F2FD) else Color(0xFFE8F5E9)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (!isCalibrating) {
                        Text(
                            text = "Ready to Calibrate Personal Walking Baseline",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF1B5E20),
                            textAlign = TextAlign.Center
                        )
                        Button(
                            onClick = onStartCalibration,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                        ) {
                            Text(
                                text = "Begin Calibration (${calibrationState.targetTicks}s Walk)",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        Text(
                            text = "Calibrating: ${calibrationState.collectedTicks} / ${calibrationState.targetTicks} s",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0D47A1)
                        )
                        LinearProgressIndicator(
                            progress = { calibrationState.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(10.dp)
                        )
                        val hintText = when {
                            isSpeaking -> "Speech detected — stay quiet so calibration captures calm walking."
                            !isWalking -> "Start walking smoothly at your comfortable pace..."
                            else -> "Recording calm stride timing & variability..."
                        }
                        Text(
                            text = hintText,
                            fontSize = 13.sp,
                            color = if (isSpeaking) Color(0xFFC62828) else Color(0xFF1565C0),
                            textAlign = TextAlign.Center
                        )
                        OutlinedButton(
                            onClick = onResetCalibration,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Cancel / Reset Calibration")
                        }
                    }
                }
            }
        }
    }
}
