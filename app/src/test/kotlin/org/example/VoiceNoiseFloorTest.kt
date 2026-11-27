package org.example

import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.assertTrue

class VoiceNoiseFloorTest {

    private fun generateSinePcm(freqHz: Double, amplitude: Double, samples: Int = 512, sampleRate: Int = 16000): ShortArray {
        return ShortArray(samples) { i ->
            (amplitude * sin(2.0 * PI * freqHz * i / sampleRate)).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    @Test
    fun testSilenceLowDbfs() {
        val silence = ShortArray(512)
        val metrics = AcousticFrameAnalyzer.computeMetrics(silence, silence.size)
        assertTrue(metrics.dbfs <= -119.0, "Silence should map to floor dBFS, got ${metrics.dbfs}")
    }

    @Test
    fun testMidbandSpeechToneVsLowFreqWind() {
        val speechBandTone = generateSinePcm(freqHz = 1000.0, amplitude = 4000.0)
        val windRumbleTone = generateSinePcm(freqHz = 100.0, amplitude = 4000.0)

        val speechMetrics = AcousticFrameAnalyzer.computeMetrics(speechBandTone, speechBandTone.size)
        val windMetrics = AcousticFrameAnalyzer.computeMetrics(windRumbleTone, windRumbleTone.size)

        assertTrue(speechMetrics.dbfs > -30.0, "Amplitude 4000 sine should be > -30 dBFS, got ${speechMetrics.dbfs}")
        assertTrue(speechMetrics.midbandRatio > 0.7, "1 kHz tone should dominate 300-3000 Hz band, got ${speechMetrics.midbandRatio}")
        assertTrue(speechMetrics.voiceToNoiseRatio > 1.5, "1 kHz tone should pass legacy voice ratio")

        assertTrue(windMetrics.midbandRatio < 0.2, "100 Hz tone should not dominate 300-3000 Hz band, got ${windMetrics.midbandRatio}")
        assertTrue(windMetrics.voiceToNoiseRatio < 1.0, "100 Hz tone should stay below voice ratio threshold")
    }
}
