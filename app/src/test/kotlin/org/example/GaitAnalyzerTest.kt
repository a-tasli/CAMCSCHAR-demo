package org.example

import org.junit.Test
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

class GaitAnalyzerTest {

    @Test
    fun testGaitAnalysisWithSimulatedWalking() {
        val analyzer = GaitAnalyzer()

        // Simulate a few steps. Steps happen ~every 500ms
        // Timestamps should be in nanoseconds
        val startNanos = System.nanoTime()
        val stepIntervalMs = 500L

        for (i in 0 until 5) {
            val currentNanos = startNanos + (i * stepIntervalMs * 1_000_000L)
            analyzer.addStep(currentNanos)
        }

        val features = analyzer.analyze(70)

        assertNotNull(features, "Analysis should return features")

        // With a 2Hz signal, step time should be exactly 500ms
        assertTrue(features.stepTime in 499.0..501.0, "Step time should be ~500ms, got ${features.stepTime}")

        println("Simulated Gait Features:")
        println("Step Time: ${features.stepTime} ms")
        println("Cognitive Load Index: ${features.combinedLoadIndex}")
    }
}
