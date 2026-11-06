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
        println("Cognitive Load Level: ${features.loadLevel}")
    }

    @Test
    fun testVoiceDetectionModifiesCognitiveLoadWithoutDucking() {
        val analyzer = GaitAnalyzer()
        val startNanos = System.nanoTime()
        val stepIntervalMs = 500L

        for (i in 0 until 5) {
            val currentNanos = startNanos + (i * stepIntervalMs * 1_000_000L)
            analyzer.addStep(currentNanos)
        }

        // Without speech: baseline level is determined by gait/HR
        val baselineFeatures = analyzer.analyze(currentHr = 70, isSpeaking = false)
        assertNotNull(baselineFeatures)
        val baseLevel = baselineFeatures.loadLevel

        // With voice detection active (isSpeaking = true): cognitive load should be bumped +1 tier
        val voiceFeatures = analyzer.analyze(currentHr = 70, isSpeaking = true)
        assertNotNull(voiceFeatures)
        val voiceBumpedLevel = voiceFeatures.loadLevel

        val expectedLevel = when (baseLevel) {
            "Low" -> "Medium"
            "Medium" -> "High"
            "High" -> "Overload"
            else -> baseLevel
        }
        kotlin.test.assertEquals(expectedLevel, voiceBumpedLevel, "Voice activity should bump cognitive load tier")
    }

    @Test
    fun testAudioTourParamsDuckingDisabled() {
        // Ducking should be false across all default configurations
        kotlin.test.assertFalse(AudioTourParams.AUDIO_LOW.duckWhileSpeaking)
        kotlin.test.assertFalse(AudioTourParams.AUDIO_MEDIUM.duckWhileSpeaking)
        kotlin.test.assertFalse(AudioTourParams.AUDIO_HIGH.duckWhileSpeaking)
        kotlin.test.assertFalse(AudioTourParams.MM_LOW.duckWhileSpeaking)
        kotlin.test.assertFalse(AudioTourParams.MM_MEDIUM.duckWhileSpeaking)
        kotlin.test.assertFalse(AudioTourParams.MM_HIGH.duckWhileSpeaking)
        kotlin.test.assertFalse(AudioTourParams.static(PresentationMode.AUDIO_FIRST).duckWhileSpeaking)
        kotlin.test.assertFalse(AudioTourParams.static(PresentationMode.MULTIMODAL).duckWhileSpeaking)
    }
}
