package org.example

import org.junit.Test
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.math.sin
import kotlin.math.PI

class GaitAnalyzerTest {

    @Test
    fun testGaitAnalysisWithSimulatedWalking() {
        val analyzer = GaitAnalyzer(sampleRate = 100.0)
        
        // Simulate 12 seconds of walking (1200 samples)
        // Average human gait is ~2 steps per second (2Hz)
        // Vertical acceleration is periodic. 
        // We'll simulate a 2Hz sine wave with some noise to represent steps.
        for (i in 0 until 1200) {
            val t = i / 100.0
            // "M" shape is roughly two peaks per gait cycle (stride), one per step.
            // We'll simplify with a 2Hz signal.
            val signal = 2.0 * sin(2.0 * PI * 2.0 * t) + 1.0 * sin(2.0 * PI * 4.0 * t)
            analyzer.addAcceleration(0f, 0f, (signal + 9.81).toFloat(), floatArrayOf(0f, 0f, 9.81f))
        }

        val features = analyzer.analyze()

        assertNotNull(features, "Analysis should return features for 10s of data")
        
        // With a 2Hz signal, step time should be around 500ms
        assertTrue(features.stepTime in 400.0..600.0, "Step time should be ~500ms, got ${features.stepTime}")
        
        // In a perfectly periodic signal, regularity should be high
        assertTrue(features.stepRegularity > 0.5, "Step regularity should be high for periodic signal")
        
        println("Simulated Gait Features:")
        println("Step Time: ${features.stepTime} ms")
        println("Step Regularity: ${features.stepRegularity}")
        println("Cognitive Load Index: ${features.cognitiveLoadIndex}")
    }
}
