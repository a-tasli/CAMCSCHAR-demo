package org.example

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

data class GaitFeatures(
    val stepTime: Double,
    val stepTimeVariability: Double,
    val stepRegularity: Double, // Deprecated: No longer calculated
    val strideRegularity: Double, // Deprecated: No longer calculated
    val hrValue: Int?,
    val sdnn: Double,
    val rmssd: Double,
    val gaitLoadIndex: Double?,
    val hrLoadIndex: Double?,
    val combinedLoadIndex: Double?,
    val loadLevel: String,
    val isWalking: Boolean,
    val cadence: Double,
    val physicalStrainScore: Double,
    val hrPredictedBpm: Double,
    val mentalHrBpm: Double,
    val mentalHrScore: Double,
    // Statistical + CUSUM controller telemetry (design-choices.md)
    val hrrFraction: Double = 0.0,
    val stepCvPercent: Double = 0.0,
    val gaitZScore: Double = 0.0,
    val voiceZScore: Double = 0.0,
    val strainReserveBonus: Double = 0.0,
    val cusumScore: Double = 0.0,
    val baselineCalibrated: Boolean = false,
    val baselineProgress: Float = 0f,
    val baselineStepCvPct: Double = 1.5
)

class GaitAnalyzer(
    private val restingHrSeed: Double = 70.0,
    participantAge: Int = 25,
    val walkingHrCoefficient: Double = 0.30, // bpm of HR per step/min of cadence
    val config: StatisticalLoadConfig = StatisticalLoadConfig()
) {

    // Store step timestamps in nanoseconds. We'll keep the last 20 steps.
    private val stepTimestamps = mutableListOf<Long>()
    private val maxStepsToKeep = 20

    private val rrData = mutableListOf<Int>()
    private val rrWindowSize = 60 // Roughly 60 seconds of data

    // ---- Real-time cardiac channel state (logged-only exploratory diagnostics) ----
    private val maxHr = 208.0 - 0.7 * participantAge // Tanaka et al. (2001)
    private var restingHr = restingHrSeed
    private var hrSmoothed: Double? = null
    private var hrPredicted = restingHrSeed
    private var lastAnalyzeNanos = 0L
    private var recoveryTimerSecs = 0.0
    private var lastIsWalking = false

    // ---- Per-subject statistical baseline + CUSUM state machine ----
    private val baselineCalibrator = BaselineCalibrator(config)
    private val cusumController = InnovationCusumController(config)

    private val _calibrationState = MutableStateFlow(baselineCalibrator.snapshot())
    val calibrationState: StateFlow<BaselineSnapshot> = _calibrationState.asStateFlow()

    val currentRestingHr: Double
        @Synchronized get() = restingHr

    private companion object {
        const val HR_SMOOTH_TAU_SEC = 15.0          // EMA time constant on measured HR
        const val HR_ONSET_TAU_SEC = 20.0           // fast vagal withdrawal when activity rises
        const val HR_RECOVERY_TAU_SEC = 40.0        // slower parasympathetic recovery on drop/stop
        const val RESTING_HR_DROP_ALPHA = 0.15      // fast adaptation of resting HR downward
        const val RESTING_HR_RISE_ALPHA = 0.001     // near-frozen upward drift
        const val RECOVERY_WINDOW_SEC = 20.0        // mental residual muted only on walk->stop tail
        const val MENTAL_RAMP_SEC = 10.0            // ramp-in at end of stop recovery window
        const val STRAIN_FULL_SCALE_HRR = 0.50      // %HRR scale for 0-100 diagnostic display
        const val MENTAL_HR_FULL_SCALE_BPM = 8.0    // 8 bpm residual scale for diagnostic display
    }

    @Synchronized
    fun startCalibration() {
        baselineCalibrator.startCalibration()
        cusumController.reset()
        _calibrationState.value = baselineCalibrator.snapshot()
    }

    @Synchronized
    fun resetCalibration() {
        baselineCalibrator.reset()
        cusumController.reset()
        _calibrationState.value = baselineCalibrator.snapshot()
    }

    @Synchronized
    fun bypassCalibrationWithNorms() {
        baselineCalibrator.bypassWithNorms()
        _calibrationState.value = baselineCalibrator.snapshot()
    }

    @Synchronized
    fun resetSession() {
        stepTimestamps.clear()
        rrData.clear()
        restingHr = restingHrSeed
        hrSmoothed = null
        hrPredicted = restingHrSeed
        lastAnalyzeNanos = 0L
        recoveryTimerSecs = 0.0
        lastIsWalking = false
        baselineCalibrator.reset()
        cusumController.reset()
        _calibrationState.value = baselineCalibrator.snapshot()
    }

    @Synchronized
    fun addStep(timestampNanos: Long) {
        val prev = stepTimestamps.lastOrNull()
        if (prev != null && (timestampNanos - prev) / 1_000_000_000.0 >= config.stopTimeoutSec) {
            // Flush stale steps from a prior walking bout after a >=4s stop
            stepTimestamps.clear()
        }
        stepTimestamps.add(timestampNanos)
        if (stepTimestamps.size > maxStepsToKeep) {
            stepTimestamps.removeAt(0)
        }
    }

    @Synchronized
    fun addRRIntervals(intervals: List<Int>) {
        for (rr in intervals) {
            // Artifact rejection: RR intervals must be physiologically plausible (ms),
            // and successive intervals may not jump by more than 30% (missed/extra beat).
            if (rr < 300.0 || rr > 2000.0) continue
            val last = rrData.lastOrNull()
            if (last != null && Math.abs(rr - last) > 0.30 * last) continue
            rrData.add(rr)
        }
        if (rrData.size > rrWindowSize) {
            rrData.subList(0, rrData.size - rrWindowSize).clear()
        }
    }

    @Synchronized
    fun getStepIntervals(): List<Float> {
        val intervals = mutableListOf<Float>()
        for (i in 1 until stepTimestamps.size) {
            val timeDiffMs = (stepTimestamps[i] - stepTimestamps[i - 1]) / 1_000_000.0
            // Tighten upper bound so pacing/standing still doesn't count as walking
            if (timeDiffMs in 300.0..1200.0) {
                intervals.add(timeDiffMs.toFloat())
            }
        }
        return intervals
    }

    @Synchronized
    fun analyze(
        currentHr: Int?,
        isSpeaking: Boolean = false,
        speechDutyCycle: Float? = null,
        nowNanos: Long? = null
    ): GaitFeatures? {
        val currentTimeNanos = nowNanos ?: try {
            android.os.SystemClock.elapsedRealtimeNanos()
        } catch (_: Throwable) {
            System.nanoTime()
        }
        val lastStepTimeNanos = stepTimestamps.lastOrNull() ?: 0L

        // If no steps yet, or last step was >= stopTimeoutSec (4s) ago, we are not walking
        val secondsSinceLastStep = if (lastStepTimeNanos == 0L) {
            Double.POSITIVE_INFINITY
        } else {
            (currentTimeNanos - lastStepTimeNanos) / 1_000_000_000.0
        }
        val isWalking = lastStepTimeNanos != 0L &&
            secondsSinceLastStep < config.stopTimeoutSec &&
            stepTimestamps.size >= 2
        // When no new step arrives within stepFreshnessSec (1.5s), the user is halting/paused:
        // drop gait departure neutral immediately so trailing deceleration steps never spike score.
        val isActivelyStepping = isWalking && secondsSinceLastStep <= config.stepFreshnessSec

        val sdnn = if (rrData.size > 5) {
            val meanRR = rrData.average()
            sqrt(rrData.map { (it - meanRR).pow(2) }.average())
        } else 0.0

        val rmssd = if (rrData.size > 5) {
            val diffs = mutableListOf<Double>()
            for (i in 1 until rrData.size) {
                diffs.add((rrData[i] - rrData[i - 1]).toDouble().pow(2))
            }
            sqrt(diffs.average())
        } else 0.0

        var avgStepTime = 0.0
        var stepTimeVar = 0.0
        var validStepCount = 0

        if (isActivelyStepping && stepTimestamps.size >= 3) {
            val rawIntervals = mutableListOf<Double>()
            for (i in 1 until stepTimestamps.size) {
                val timeDiffMs = (stepTimestamps[i] - stepTimestamps[i - 1]) / 1_000_000.0
                if (timeDiffMs in 300.0..1200.0) {
                    rawIntervals.add(timeDiffMs)
                }
            }
            val stepTimes = BaselineCalibrator.filterStepInliers(
                intervals = rawIntervals,
                lowRatio = config.stepOutlierLowRatio,
                highRatio = config.stepOutlierHighRatio
            )
            validStepCount = stepTimes.size
            if (stepTimes.isNotEmpty()) {
                avgStepTime = stepTimes.average()
                stepTimeVar = if (stepTimes.size > 1) {
                    sqrt(stepTimes.map { (it - avgStepTime).pow(2) }.average())
                } else 0.0
            }
        }

        val stepCvPct: Double? = if (isActivelyStepping && validStepCount >= 2 && avgStepTime > 0.0) {
            (stepTimeVar / avgStepTime) * 100.0
        } else {
            null
        }

        baselineCalibrator.observeTick(
            isWalking = isActivelyStepping,
            isSpeaking = isSpeaking,
            stepTimeMs = if (avgStepTime > 0.0) avgStepTime else null,
            stepCvPct = stepCvPct
        )
        val baseline = baselineCalibrator.snapshot()
        _calibrationState.value = baseline

        // ---- Cardiac channel: physical strain (%HRR) + stop-gated exploratory mental residual ----
        val dtSeconds = if (lastAnalyzeNanos == 0L) 1.0
            else ((currentTimeNanos - lastAnalyzeNanos) / 1_000_000_000.0).coerceIn(0.1, 10.0)
        lastAnalyzeNanos = currentTimeNanos

        if (currentHr != null) {
            restingHr = if (currentHr < restingHr) {
                restingHr + (currentHr - restingHr) * RESTING_HR_DROP_ALPHA
            } else {
                restingHr + (currentHr - restingHr) * RESTING_HR_RISE_ALPHA
            }
            hrSmoothed = if (hrSmoothed == null) currentHr.toDouble()
            else hrSmoothed!! + (currentHr - hrSmoothed!!) * (1.0 - exp(-dtSeconds / HR_SMOOTH_TAU_SEC))
        }

        val cadence = if (isWalking && avgStepTime > 0.0) 60000.0 / avgStepTime else 0.0

        // Only trigger the 20s recovery mute on walking -> stopped transitions;
        // gradual walking pace changes are tracked directly by asymmetric kinetics below.
        if (lastIsWalking && !isWalking) {
            recoveryTimerSecs = RECOVERY_WINDOW_SEC
        }
        recoveryTimerSecs = (recoveryTimerSecs - dtSeconds).coerceAtLeast(0.0)
        lastIsWalking = isWalking

        val hrTarget = restingHr + walkingHrCoefficient * cadence
        val kineticsTau = if (hrTarget > hrPredicted) HR_ONSET_TAU_SEC else HR_RECOVERY_TAU_SEC
        hrPredicted += (hrTarget - hrPredicted) * (1.0 - exp(-dtSeconds / kineticsTau))

        val hrrFraction: Double? = if (hrSmoothed != null) {
            (hrSmoothed!! - restingHr) / (maxHr - restingHr).coerceAtLeast(1.0)
        } else {
            null
        }
        val strainScore = if (hrrFraction != null) {
            (hrrFraction / STRAIN_FULL_SCALE_HRR * 100.0).coerceIn(0.0, 100.0)
        } else {
            0.0
        }

        val mentalHr = if (hrSmoothed != null) hrSmoothed!! - hrPredicted else 0.0
        val mentalWeight = if (!isWalking) {
            0.0
        } else {
            ((MENTAL_RAMP_SEC - recoveryTimerSecs) / MENTAL_RAMP_SEC).coerceIn(0.0, 1.0)
        }
        val mentalScore = (mentalHr / MENTAL_HR_FULL_SCALE_BPM).coerceIn(0.0, 1.0) * 100.0 * mentalWeight

        val hasAnySignal = (currentHr != null) || (stepCvPct != null) || isSpeaking || ((speechDutyCycle ?: 0f) > 0.10f)
        val statOut = cusumController.update(
            isWalking = isActivelyStepping,
            stepCvPct = stepCvPct,
            baseline = baseline,
            isSpeaking = isSpeaking,
            speechDutyCycle = speechDutyCycle,
            hrrFraction = hrrFraction,
            hasAnySignal = hasAnySignal,
            dtSeconds = dtSeconds
        )

        val gaitLoadIndex: Double? = when {
            stepCvPct != null -> (statOut.zGait * 50.0).coerceAtLeast(0.0)
            !isActivelyStepping -> 0.0
            else -> null
        }
        val hrLoadIndex: Double? = if (hrrFraction != null) {
            (statOut.strainReserveBonus / config.maxStrainReserveBonus.coerceAtLeast(1e-6) * 100.0).coerceIn(0.0, 100.0)
        } else {
            null
        }
        val combinedLoadIndex: Double? = if (statOut.loadLevel == "Waiting...") null else statOut.combinedLoadIndex

        return GaitFeatures(
            stepTime = avgStepTime,
            stepTimeVariability = stepTimeVar,
            stepRegularity = 0.0, // Deprecated
            strideRegularity = 0.0, // Deprecated
            hrValue = currentHr,
            sdnn = sdnn,
            rmssd = rmssd,
            gaitLoadIndex = gaitLoadIndex,
            hrLoadIndex = hrLoadIndex,
            combinedLoadIndex = combinedLoadIndex,
            loadLevel = statOut.loadLevel,
            isWalking = isWalking,
            cadence = cadence,
            physicalStrainScore = strainScore,
            hrPredictedBpm = hrPredicted,
            mentalHrBpm = mentalHr,
            mentalHrScore = mentalScore,
            hrrFraction = hrrFraction ?: 0.0,
            stepCvPercent = stepCvPct ?: 0.0,
            gaitZScore = statOut.zGait,
            voiceZScore = statOut.zVoice,
            strainReserveBonus = statOut.strainReserveBonus,
            cusumScore = statOut.ewmaScore,
            baselineCalibrated = baseline.isPersonallyCalibrated,
            baselineProgress = baseline.progress,
            baselineStepCvPct = baseline.stepCvPct
        )
    }
}
