package org.example

import kotlin.math.sqrt
import kotlin.math.pow
import kotlin.math.min
import kotlin.math.max

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
    val mentalHrScore: Double
)

class GaitAnalyzer(
    restingHrSeed: Double = 70.0,
    participantAge: Int = 25,
    val walkingHrCoefficient: Double = 0.30 // bpm of HR per step/min of cadence
) {

    // Store step timestamps in nanoseconds. We'll keep the last 20 steps.
    private val stepTimestamps = mutableListOf<Long>()
    private val maxStepsToKeep = 20

    private val rrData = mutableListOf<Int>()
    private val rrWindowSize = 60 // Roughly 60 seconds of data

    // ---- Real-time cardiac channel state ----
    private val maxHr = 208.0 - 0.7 * participantAge // Tanaka et al. (2001)
    private var restingHr = restingHrSeed
    private var hrSmoothed: Double? = null
    private var hrPredicted = restingHrSeed
    private var lastAnalyzeNanos = 0L
    private var recoveryTimerSecs = 0.0
    private var lastCadence = 0.0
    private var lastIsWalking = false

    private companion object {
        const val HR_SMOOTH_TAU_SEC = 15.0          // EMA time constant on measured HR
        const val HR_RESPONSE_TAU_SEC = 45.0        // cardiovascular response lag to activity
        const val RESTING_HR_DROP_ALPHA = 0.15      // fast adaptation of resting HR downward
        const val RESTING_HR_RISE_ALPHA = 0.001     // near-frozen upward drift
        const val RECOVERY_WINDOW_SEC = 60.0        // mental channel muted after pace transitions
        const val MENTAL_RAMP_SEC = 15.0            // ramp-in of mental channel at end of recovery
        const val CADENCE_TRANSITION_STEPS_PER_MIN = 15.0
        const val STRAIN_FULL_SCALE_HRR = 0.50      // 50 %HRR maps to 100 points
        const val MENTAL_HR_FULL_SCALE_BPM = 8.0    // 8 bpm of motion-corrected residual maps to 100 points
    }

    @Synchronized
    fun addStep(timestampNanos: Long) {
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
    fun analyze(currentHr: Int?, isSpeaking: Boolean = false): GaitFeatures? {
        val currentTimeNanos = try {
            android.os.SystemClock.elapsedRealtimeNanos()
        } catch (_: Throwable) {
            System.nanoTime()
        }
        val lastStepTimeNanos = stepTimestamps.lastOrNull() ?: 0L
        
        // If no steps yet, or last step was > 5s ago, we are not walking
        val isWalking = if (lastStepTimeNanos == 0L) {
            false
        } else {
            val secondsSinceLastStep = (currentTimeNanos - lastStepTimeNanos) / 1_000_000_000.0
            secondsSinceLastStep < 5.0 && stepTimestamps.size >= 2
        }

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
        var gaitLoadIndex: Double? = null

        if (stepTimestamps.size >= 3) {
            val stepTimes = mutableListOf<Double>()
            for (i in 1 until stepTimestamps.size) {
                val timeDiffMs = (stepTimestamps[i] - stepTimestamps[i - 1]) / 1_000_000.0
                if (timeDiffMs in 300.0..1200.0) {
                    stepTimes.add(timeDiffMs)
                }
            }

            if (stepTimes.isNotEmpty()) {
                avgStepTime = stepTimes.average()
                stepTimeVar = if (stepTimes.size > 1) {
                    sqrt(stepTimes.map { (it - avgStepTime).pow(2) }.average())
                } else 0.0

                val gaitStepTimeScore = (avgStepTime - 500.0) / 200.0 * 100.0
                val gaitVarScore = (stepTimeVar - 20.0) / 50.0 * 100.0
                
                gaitLoadIndex = (gaitStepTimeScore + gaitVarScore) / 2.0
            }
        }

        // Feature: if user stops for 5s, reset gait score to 25 (Low)
        if (!isWalking) {
            gaitLoadIndex = 25.0
        }

        // ---- Cardiac channel: physical strain + motion-corrected mental residual ----
        // Replaces the old SDNN/RMSSD-based scores: PPG RMSSD from the armband sits at
        // the noise floor while moving, so HRV is no longer used for scoring (it is
        // still measured and logged for comparison). All timing-dependent state advances
        // by dt on every tick, even when HR is missing, so the kinetics model stays
        // continuous across dropouts.
        val dtSeconds = if (lastAnalyzeNanos == 0L) 1.0
            else ((currentTimeNanos - lastAnalyzeNanos) / 1_000_000_000.0).coerceIn(0.1, 10.0)
        lastAnalyzeNanos = currentTimeNanos

        if (currentHr != null) {
            // Adaptive resting-HR estimate: snaps down quickly at any still moment,
            // drifts up only marginally afterwards.
            restingHr = if (currentHr < restingHr) {
                restingHr + (currentHr - restingHr) * RESTING_HR_DROP_ALPHA
            } else {
                restingHr + (currentHr - restingHr) * RESTING_HR_RISE_ALPHA
            }
            hrSmoothed = if (hrSmoothed == null) currentHr.toDouble()
            else hrSmoothed!! + (currentHr - hrSmoothed!!) * (1.0 - Math.exp(-dtSeconds / HR_SMOOTH_TAU_SEC))
        }

        val cadence = if (isWalking && avgStepTime > 0.0) 60000.0 / avgStepTime else 0.0
        if (Math.abs(cadence - lastCadence) > CADENCE_TRANSITION_STEPS_PER_MIN || isWalking != lastIsWalking) {
            recoveryTimerSecs = RECOVERY_WINDOW_SEC
        }
        recoveryTimerSecs = (recoveryTimerSecs - dtSeconds).coerceAtLeast(0.0)
        lastCadence = cadence
        lastIsWalking = isWalking

        // One-pole cardiac kinetics: predicted HR follows the activity-implied target
        // with the same ~45 s lag the cardiovascular system actually has, so pace
        // transitions do not leak into the residual as fake load.
        val hrTarget = restingHr + walkingHrCoefficient * cadence
        hrPredicted += (hrTarget - hrPredicted) * (dtSeconds / HR_RESPONSE_TAU_SEC).coerceAtMost(1.0)

        // Physical strain: Karvonen %HRR, rescaled so 50 %HRR == 100 points.
        val strainScore = if (hrSmoothed != null) {
            val hrrFraction = (hrSmoothed!! - restingHr) / (maxHr - restingHr).coerceAtLeast(1.0)
            (hrrFraction / STRAIN_FULL_SCALE_HRR * 100.0).coerceIn(0.0, 100.0)
        } else 0.0

        // Mental residual: HR above what locomotion alone predicts. Only trusted in
        // steady state; muted during and just after pace transitions (recovery lag).
        val mentalHr = if (hrSmoothed != null) hrSmoothed!! - hrPredicted else 0.0
        val mentalWeight = ((MENTAL_RAMP_SEC - recoveryTimerSecs) / MENTAL_RAMP_SEC).coerceIn(0.0, 1.0)
        val mentalScore = (mentalHr / MENTAL_HR_FULL_SCALE_BPM).coerceIn(0.0, 1.0) * 100.0 * mentalWeight

        val hrLoadIndex: Double? = if (currentHr != null) (strainScore + mentalScore) / 2.0 else null

        val combinedLoadIndex = if (gaitLoadIndex != null && hrLoadIndex != null) {
            (gaitLoadIndex + hrLoadIndex) / 2.0
        } else {
            gaitLoadIndex ?: hrLoadIndex
        }

        var finalLevel = "Waiting..."
        if (combinedLoadIndex != null) {
            val baseLevel = when {
                combinedLoadIndex < 50.0 -> "Low"
                combinedLoadIndex < 100.0 -> "Medium"
                else -> "High"
            }

            finalLevel = baseLevel
            // Adjust level if voice activity is detected
            if (isSpeaking) {
                finalLevel = when (baseLevel) {
                    "Low" -> "Medium"
                    "Medium" -> "High"
                    "High" -> "Overload"
                    else -> baseLevel
                }
            }
        }

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
            loadLevel = finalLevel,
            isWalking = isWalking,
            cadence = cadence,
            physicalStrainScore = strainScore,
            hrPredictedBpm = hrPredicted,
            mentalHrBpm = mentalHr,
            mentalHrScore = mentalScore
        )
    }
}