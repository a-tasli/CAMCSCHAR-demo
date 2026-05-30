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

class MainActivity : ComponentActivity() {

    private val analyzer = GaitAnalyzer()
    private lateinit var sensorManager: GaitSensorManager
    private lateinit var bleManager: BleHeartRateManager

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.all { it.value }
        if (granted) {
            bleManager.startScan()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensorManager = GaitSensorManager(this, analyzer)
        bleManager = BleHeartRateManager(this)
        
        checkAndRequestBluetoothPermissions()

        setContent {
            MaterialTheme {
                GaitApp(analyzer, bleManager)
            }
        }
    }

    private fun checkAndRequestBluetoothPermissions() {
        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }

        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        } else {
            bleManager.startScan()
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            sensorManager.start()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.stop()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        bleManager.disconnect()
    }
}

@Composable
fun GaitApp(analyzer: GaitAnalyzer, bleManager: BleHeartRateManager) {
    var features by remember { mutableStateOf<GaitFeatures?>(null) }
    var graphData by remember { mutableStateOf(GaitGraphData(emptyList(), emptyList())) }
    val hrValue by bleManager.heartRate.collectAsState()
    val rrIntervals by bleManager.rrIntervals.collectAsState()
    val bleState by bleManager.connectionState.collectAsState()
    
    LaunchedEffect(rrIntervals) {
        analyzer.addRRIntervals(rrIntervals)
    }

    LaunchedEffect(Unit) {
        while (true) {
            try {
                features = analyzer.analyze(hrValue)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            delay(1000) // Update every second
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            try {
                graphData = analyzer.getGraphData()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            delay(33) // ~30 FPS update for the graph
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
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

            CognitiveLoadCard("Combined Load", features?.combinedLoadIndex ?: 0.0)

            Spacer(modifier = Modifier.height(8.dp))
            
            HeartRateCard(hrValue, bleState, features?.sdnn ?: 0.0)

            Spacer(modifier = Modifier.height(8.dp))
            
            LiveGaitGraph(graphData)

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Inner Workings (Gait Parameters)",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.Start)
            )

            ParameterRow("Step Time", String.format(Locale.US, "%.0f ms", features?.stepTime ?: 0.0))
            ParameterRow("Step Variability", String.format(Locale.US, "%.1f ms", features?.stepTimeVariability ?: 0.0))
            ParameterRow("Step Regularity", String.format(Locale.US, "%.2f", features?.stepRegularity ?: 0.0))
            ParameterRow("Stride Regularity", String.format(Locale.US, "%.2f", features?.strideRegularity ?: 0.0))

            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = "Mount phone securely to chest for best results.",
                fontSize = 12.sp,
                color = Color.Gray
            )
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
fun SmallLoadCard(label: String, load: Double, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.height(80.dp),
        colors = CardDefaults.cardColors(
            containerColor = getLoadColor(load)
        )
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(text = label, fontSize = 12.sp, color = Color.White)
            Text(
                text = if (load < 1.0) "--" else String.format(Locale.US, "%.0f%%", load),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}

@Composable
fun CognitiveLoadCard(title: String, load: Double) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
        colors = CardDefaults.cardColors(
            containerColor = getLoadColor(load)
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
                text = if (load < 1.0) "Waiting..." else String.format(Locale.US, "%.1f%%", load),
                fontSize = 38.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}

fun getLoadColor(load: Double): Color {
    return when {
        load < 1.0 -> Color.Gray
        load < 35 -> Color(0xFF4CAF50) // Low - Green
        load < 65 -> Color(0xFFFFC107) // Medium - Amber
        else -> Color(0xFFF44336)      // High - Red
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
fun LiveGaitGraph(graphData: GaitGraphData) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
        ) {
            val values = graphData.values
            val steps = graphData.steps
            if (values.isEmpty()) return@Canvas

            val width = size.width
            val height = size.height

            val maxVal = values.maxOrNull() ?: 1f
            val minVal = values.minOrNull() ?: -1f
            
            // To prevent jitter, set a minimum range
            val range = maxOf(maxVal - minVal, 4f)
            val center = minVal + range / 2f
            
            val yRange = range * 1.2f // Add some padding

            val points = values.mapIndexed { index, value ->
                val x = index.toFloat() / (values.size - 1) * width
                // Invert Y so positive is up
                val y = height / 2f - ((value - center) / yRange * height)
                Offset(x, y)
            }

            val path = Path()
            points.forEachIndexed { index, offset ->
                if (index == 0) {
                    path.moveTo(offset.x, offset.y)
                } else {
                    path.lineTo(offset.x, offset.y)
                }
            }

            drawPath(
                path = path,
                color = Color.Blue,
                style = Stroke(width = 2.dp.toPx())
            )

            // Draw steps clearly
            steps.forEach { stepIndex ->
                if (stepIndex in points.indices) {
                    val stepPoint = points[stepIndex]
                    drawCircle(
                        color = Color.Red,
                        radius = 4.dp.toPx(),
                        center = stepPoint
                    )
                    // Draw a vertical line as well
                    drawLine(
                        color = Color.Red.copy(alpha = 0.5f),
                        start = Offset(stepPoint.x, 0f),
                        end = Offset(stepPoint.x, height),
                        strokeWidth = 1.dp.toPx()
                    )
                }
            }
        }
    }
}
