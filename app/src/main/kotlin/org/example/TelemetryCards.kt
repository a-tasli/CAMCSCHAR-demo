package org.example

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

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

@Composable
fun AudioTourStatusCard(
    isPlaying: Boolean,
    currentArea: String,
    loadLevel: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = getLevelColor(loadLevel))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (isPlaying) "Audio tour playing" else "Audio tour paused",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "At: ${currentArea.replace('_', ' ')}  •  Load: $loadLevel",
                fontSize = 14.sp,
                color = Color.White.copy(alpha = 0.9f)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Eyes-free mode: playback continues with the screen off.\nUse the notification or headset buttons to control the tour.",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.75f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
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
