package org.example

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GaitAnalyzerTest {

    private fun feedStepsAtInterval(
        analyzer: GaitAnalyzer,
        nowNanos: Long,
        count: Int,
        intervalsMs: List<Long>
    ): Long {
        var t = nowNanos
        for (i in 0 until count) {
            val stepMs = intervalsMs[i % intervalsMs.size]
            t += stepMs * 1_000_000L
            analyzer.addStep(t)
        }
        return t
    }

    @Test
    fun testGaitAnalysisWithSimulatedWalking() {
        val analyzer = GaitAnalyzer()

        val startNanos = System.nanoTime()
        val stepIntervalMs = 500L

        for (i in 0 until 5) {
            val currentNanos = startNanos + (i * stepIntervalMs * 1_000_000L)
            analyzer.addStep(currentNanos)
        }

        val features = analyzer.analyze(70)

        assertNotNull(features, "Analysis should return features")
        assertTrue(features.stepTime in 499.0..501.0, "Step time should be ~500ms, got ${features.stepTime}")
        assertEquals("Low", features.loadLevel, "Calm regular walking at resting HR should remain Low")
    }

    @Test
    fun testSustainedVoiceDutyEscalatesToMediumWithoutWindingUpToOverload() {
        val analyzer = GaitAnalyzer()
        analyzer.bypassCalibrationWithNorms()
        var t = 1_000_000_000_000L

        // Feed steady 500ms steps so gait departure is 0
        t = feedStepsAtInterval(analyzer, t, 6, listOf(500L))

        // Single brief vocal transient (5% duty in 10s window) while walking must NOT spike tier
        t = feedStepsAtInterval(analyzer, t, 2, listOf(500L))
        val transientOut = analyzer.analyze(currentHr = 70, isSpeaking = true, speechDutyCycle = 0.05f, nowNanos = t)
        assertNotNull(transientOut)
        assertEquals(0.0, transientOut.voiceZScore, 1e-6)
        assertEquals("Low", transientOut.loadLevel, "Brief <10% speech transient should stay Low")

        // Sustained 100% continuous speech for 60s on steady calm steps must settle at Medium, NEVER High/Overload
        var lastRes = requireNotNull(transientOut)
        repeat(60) {
            t = feedStepsAtInterval(analyzer, t, 2, listOf(500L))
            lastRes = analyzer.analyze(currentHr = 70, isSpeaking = true, speechDutyCycle = 1.0f, nowNanos = t)!!
            assertTrue(
                lastRes.loadLevel in listOf("Low", "Medium"),
                "Continuous speech alone must never spiral into ${lastRes.loadLevel}"
            )
        }
        assertEquals("Medium", lastRes.loadLevel, "Sustained speech should converge smoothly to Medium")
    }

    @Test
    fun testStopBrakingTailDoesNotSpikeTo200AndRespectsFourSecondTimeout() {
        val analyzer = GaitAnalyzer()
        analyzer.bypassCalibrationWithNorms()
        var t = 200_000_000_000L

        // 1. Steady walking (~500ms +/- 8ms) -> gaitLoadIndex stays calm (~0..25)
        repeat(10) {
            t = feedStepsAtInterval(analyzer, t, 2, listOf(492L, 508L))
            analyzer.analyze(currentHr = null, isSpeaking = false, speechDutyCycle = 0f, nowNanos = t)
        }
        val walkFeat = analyzer.analyze(currentHr = null, isSpeaking = false, speechDutyCycle = 0f, nowNanos = t)!!
        assertTrue(walkFeat.isWalking)
        assertTrue((walkFeat.gaitLoadIndex ?: 0.0) <= 30.0, "Steady gait index should be ~0..25, got ${walkFeat.gaitLoadIndex}")

        // 2. Come to a sudden stop with slow deceleration tail steps (980ms, 1120ms)
        t += 980_000_000L
        analyzer.addStep(t)
        t += 1_120_000_000L
        analyzer.addStep(t)
        val brakingFeat = analyzer.analyze(currentHr = null, isSpeaking = false, speechDutyCycle = 0f, nowNanos = t)!!
        assertTrue((brakingFeat.gaitLoadIndex ?: 0.0) < 40.0, "Braking tail steps must not spike Gait to 200, got ${brakingFeat.gaitLoadIndex}")

        // 3. Standing idle: freshness > 1.5s drops gait departure to 0 immediately while isWalking remains true up to 4.0s
        val idle2s = analyzer.analyze(currentHr = null, isSpeaking = false, speechDutyCycle = 0f, nowNanos = t + 2_000_000_000L)!!
        assertTrue(idle2s.isWalking, "isWalking should remain true prior to 4s stop timeout")
        assertEquals(0.0, idle2s.gaitZScore, 1e-6)
        assertEquals(0.0, idle2s.gaitLoadIndex ?: -1.0, 1e-6)

        val idle39s = analyzer.analyze(currentHr = null, isSpeaking = false, speechDutyCycle = 0f, nowNanos = t + 3_900_000_000L)!!
        assertTrue(idle39s.isWalking, "isWalking should still be true at 3.9s < 4.0s")

        val stopped41s = analyzer.analyze(currentHr = null, isSpeaking = false, speechDutyCycle = 0f, nowNanos = t + 4_100_000_000L)!!
        assertFalse(stopped41s.isWalking, "isWalking must turn false after 4.0s stop timeout")
        assertEquals("Low", stopped41s.loadLevel)
    }

    @Test
    fun testBaselineCalibratorManualGateMadRobustnessAndReset() {
        val analyzer = GaitAnalyzer()
        var cal = analyzer.calibrationState.value
        assertEquals(CalibrationPhase.IDLE, cal.phase)
        assertFalse(cal.isReadyForTour, "TourScreen must stay gated before manual calibration")

        analyzer.startCalibration()
        assertEquals(CalibrationPhase.CALIBRATING, analyzer.calibrationState.value.phase)

        var t = 10_000_000_000L
        // Seed initial steps (~1.6% CV: alternating 492ms / 508ms steps)
        t = feedStepsAtInterval(analyzer, t, 5, listOf(492L, 508L))

        // Speech ticks must not count toward calm walking calibration
        t += 500_000_000L
        analyzer.addStep(t)
        analyzer.analyze(70, isSpeaking = true, speechDutyCycle = 0.4f, nowNanos = t)
        assertEquals(0, analyzer.calibrationState.value.collectedTicks, "Speaking ticks must be ignored during calibration")

        // Collect 60 valid calm walking ticks, with 2 transient outlier ticks injected mid-walk
        for (tick in 0 until 60) {
            val pair = if (tick in 20..21) listOf(430L, 580L) else listOf(492L, 508L)
            t = feedStepsAtInterval(analyzer, t, 2, pair)
            val feat = analyzer.analyze(70, isSpeaking = false, speechDutyCycle = 0f, nowNanos = t)
            assertNotNull(feat)
        }

        cal = analyzer.calibrationState.value
        assertEquals(CalibrationPhase.LOCKED, cal.phase)
        assertTrue(cal.isReadyForTour, "TourScreen should unlock once 60 ticks finish")
        assertTrue(cal.stepCvPct in 1.2..2.2, "Median CV should resist transient outliers, got ${cal.stepCvPct}")
        assertTrue(cal.sigmaStepCvPct >= analyzer.config.sigmaStepCvFloor, "Variance floor must hold")

        analyzer.resetSession()
        cal = analyzer.calibrationState.value
        assertEquals(CalibrationPhase.IDLE, cal.phase)
        assertFalse(cal.isReadyForTour)
    }

    @Test
    fun testAcsmStrainHingeStairsAloneCannotTriggerMediumOrHigh() {
        val analyzer = GaitAnalyzer(restingHrSeed = 65.0, participantAge = 25)
        analyzer.bypassCalibrationWithNorms()
        var t = 100_000_000_000L

        // Feed vigorous HR (~155 bpm -> HRR ~ (155-65)/(190.5-65) ~ 72% HRR >= 60% Vigorous)
        // for 40 ticks while walking with completely calm gait (CV ~ 1.5%) and zero speech.
        var lastFeat: GaitFeatures? = null
        repeat(40) {
            t = feedStepsAtInterval(analyzer, t, 2, listOf(493L, 507L))
            lastFeat = analyzer.analyze(currentHr = 155, isSpeaking = false, speechDutyCycle = 0f, nowNanos = t)
        }

        val resolvedFeat = requireNotNull(lastFeat)
        assertEquals(0.0, resolvedFeat.cusumScore, 1e-6, "Physical exertion alone must NOT accumulate cognitive CUSUM")
        assertTrue(
            resolvedFeat.strainReserveBonus <= analyzer.config.maxStrainReserveBonus + 1e-6,
            "Reserve boost must respect structural cap"
        )
        assertEquals("Low", resolvedFeat.loadLevel, "Vigorous stairs alone without gait/vocal load must stay Low")

        // Now add moderate cognitive-motor gait jitter (~4% CV) + speaking on top of vigorous HR
        var escalated = "Low"
        repeat(15) {
            t = feedStepsAtInterval(analyzer, t, 2, listOf(480L, 520L)) // ~4% CV -> Winsorized max
            val f = analyzer.analyze(currentHr = 155, isSpeaking = true, speechDutyCycle = 0.30f, nowNanos = t)!!
            escalated = f.loadLevel
        }
        assertTrue(escalated in listOf("High", "Overload"), "High exertion + dual-task load should escalate, got $escalated")
    }

    @Test
    fun testCusumRejectsSingleGlitchEnforcesDownwardDwellAndIgnoresPaceChangesInMentalHr() {
        val analyzer = GaitAnalyzer(restingHrSeed = 65.0)
        analyzer.bypassCalibrationWithNorms()
        var t = 500_000_000_000L

        // Steady warm-up at HR 85 while walking
        repeat(10) {
            t = feedStepsAtInterval(analyzer, t, 2, listOf(495L, 505L))
            analyzer.analyze(currentHr = 85, isSpeaking = false, speechDutyCycle = 0f, nowNanos = t)
        }

        // Single missed/outlier 1000ms step interval must NOT flip out of Low in 1 tick
        t += 1_000_000_000L
        analyzer.addStep(t)
        val spikeFeat = analyzer.analyze(currentHr = 85, isSpeaking = false, speechDutyCycle = 0f, nowNanos = t)!!
        assertEquals("Low", spikeFeat.loadLevel, "Single missed/noisy step must not spike load level")

        // Push up into High / Overload via simultaneous dual-task load (gait jitter + sustained speech),
        // then test downward dwell hysteresis and recovery
        repeat(20) {
            t = feedStepsAtInterval(analyzer, t, 2, listOf(475L, 525L)) // ~5% CV -> zGait = 3.0
            analyzer.analyze(currentHr = 85, isSpeaking = true, speechDutyCycle = 0.75f, nowNanos = t)
        }
        val peakLevel = analyzer.analyze(currentHr = 85, isSpeaking = true, speechDutyCycle = 0.75f, nowNanos = t)!!.loadLevel
        assertTrue(peakLevel in listOf("High", "Overload"), "Expected combined dual-task High/Overload, got $peakLevel")

        // First calm recovery tick must stay elevated due to EWMA + minDownDwellTicks hysteresis
        t = feedStepsAtInterval(analyzer, t, 2, listOf(495L, 505L))
        val firstCalmFeat = analyzer.analyze(currentHr = 85, isSpeaking = false, speechDutyCycle = 0f, nowNanos = t)!!
        assertEquals(peakLevel, firstCalmFeat.loadLevel, "First calm tick must not instantly drop tier")

        // Continuous walking slowdown (500ms -> 620ms = ~23 spm cadence drop while still walking!)
        // Must NOT trigger a stop recovery mute on mentalHrScore if HR is elevated above target
        var calmWalkFeat: GaitFeatures? = null
        repeat(12) {
            t = feedStepsAtInterval(analyzer, t, 2, listOf(615L, 625L))
            calmWalkFeat = analyzer.analyze(currentHr = 120, isSpeaking = false, speechDutyCycle = 0f, nowNanos = t)
        }
        val slowWalkFeat = requireNotNull(calmWalkFeat)
        assertTrue(slowWalkFeat.isWalking)
        assertTrue(slowWalkFeat.mentalHrScore > 0.0, "Continuous walking slowdown should not zero mentalHrScore via false stop reset")

        // Sustained calm ticks past EWMA decay + dwell hysteresis must recover back to Low
        var recoveredFeat = slowWalkFeat
        repeat(35) {
            t = feedStepsAtInterval(analyzer, t, 2, listOf(495L, 505L))
            recoveredFeat = analyzer.analyze(currentHr = 85, isSpeaking = false, speechDutyCycle = 0f, nowNanos = t)!!
        }
        assertEquals("Low", recoveredFeat.loadLevel, "Sustained calm walking must recover back to Low")

        // Actual walking -> stopped transition (>=4s gap without steps) DOES engage recovery mute
        t += 4_500_000_000L
        val stoppedFeat = analyzer.analyze(currentHr = 120, isSpeaking = false, speechDutyCycle = 0f, nowNanos = t)!!
        assertFalse(stoppedFeat.isWalking)
        assertEquals(0.0, stoppedFeat.mentalHrScore, 1e-6)
        assertEquals(0.0, stoppedFeat.gaitZScore, 1e-6, "Stopped state should keep gait departure neutral")
    }
}

