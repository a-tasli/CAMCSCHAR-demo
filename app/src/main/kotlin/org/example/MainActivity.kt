package org.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.*

class MainActivity : ComponentActivity() {

    private val analyzer = GaitAnalyzer()
    private lateinit var sensorManager: GaitSensorManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensorManager = GaitSensorManager(this, analyzer)
        
        setContent {
            MaterialTheme {
                GaitApp(analyzer, sensorManager)
            }
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
}

@Composable
fun GaitApp(analyzer: GaitAnalyzer, sensorManager: GaitSensorManager) {
    var features by remember { mutableStateOf<GaitFeatures?>(null) }
    
    LaunchedEffect(Unit) {
        while (true) {
            try {
                features = analyzer.analyze()
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
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Gait Cognitive Load Detection",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            CognitiveLoadCard(features?.cognitiveLoadIndex ?: 0.0)

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Inner Workings (Gait Parameters)",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.Start)
            )

            ParameterRow("Step Time", String.format("%.0f ms", features?.stepTime ?: 0.0))
            ParameterRow("Step Variability", String.format("%.1f ms", features?.stepTimeVariability ?: 0.0))
            ParameterRow("Step Regularity", String.format("%.2f", features?.stepRegularity ?: 0.0))
            ParameterRow("Stride Regularity", String.format("%.2f", features?.strideRegularity ?: 0.0))

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
fun CognitiveLoadCard(load: Double) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                load < 1.0 -> Color.Gray       // No data
                load < 30 -> Color(0xFF4CAF50) // Low - Green
                load < 60 -> Color(0xFFFFC107) // Medium - Amber
                else -> Color(0xFFF44336)      // High - Red
            }
        )
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Cognitive Load",
                fontSize = 20.sp,
                color = Color.White
            )
            Text(
                text = if (load < 1.0) "Waiting..." else String.format("%.1f%%", load),
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = when {
                    load < 1.0 -> "N/A"
                    load < 30 -> "LOW"
                    load < 60 -> "MEDIUM"
                    else -> "HIGH"
                },
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White
            )
        }
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
