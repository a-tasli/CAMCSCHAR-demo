package org.example

import kotlin.math.abs
import kotlin.math.exp

enum class CalibrationPhase {
    IDLE,
    CALIBRATING,
    LOCKED,
    NORMATIVE_BYPASS
}

data class BaselineSnapshot(
    val stepTimeMs: Double,
    val stepCvPct: Double,
    val sigmaStepCvPct: Double,
    val phase: CalibrationPhase,
    val collectedTicks: Int,
    val targetTicks: Int
) {
    val isReadyForTour: Boolean
        get() = phase == CalibrationPhase.LOCKED || phase == CalibrationPhase.NORMATIVE_BYPASS

    val isPersonallyCalibrated: Boolean
        get() = phase == CalibrationPhase.LOCKED

    val progress: Float
        get() = when (phase) {
            CalibrationPhase.LOCKED, CalibrationPhase.NORMATIVE_BYPASS -> 1f
            CalibrationPhase.IDLE -> 0f
            CalibrationPhase.CALIBRATING -> (collectedTicks.toFloat() / targetTicks.coerceAtLeast(1)).coerceIn(0f, 1f)
        }
}

/**
 * Frozen domain-grounded configuration for statistical cognitive-load estimation
 * (see design-choices.md & calibration-strategy.md).
 */
data class StatisticalLoadConfig(
    // Normative young-adult calm walking priors (warm-start / Dev bypass)
    val priorStepTimeMs: Double = 500.0,
    val priorStepCvPct: Double = 1.5,
    val priorSigmaStepCvPct: Double = 0.5,
    val sigmaStepCvFloor: Double = 0.40,
    val baselineTargetTicks: Int = 60,

    // Gait outlier suppression & stop window parameters
    val stopTimeoutSec: Double = 4.0,
    val stepFreshnessSec: Double = 1.5,
    val stepOutlierLowRatio: Double = 0.65,
    val stepOutlierHighRatio: Double = 1.45,

    // Voice duty-cycle z-score parameters (10 s Silero VAD rolling window)
    val voiceDutyDeadzone: Double = 0.10,
    val voiceDutyScale: Double = 0.25,

    // Single-channel Winsorization ceilings:
    // Neither gait alone (<=3.0) nor speech alone (<=2.5) can reach High (4.0) or Overload (5.5).
    val zGaitMax: Double = 3.0,
    val zVoiceMax: Double = 2.5,

    // ACSM Moderate (40%) -> Vigorous (60%) %HRR capacity-reserve hinge
    val hrrDeadzone: Double = 0.40,
    val hrrFullScale: Double = 0.60,
    // Structurally capped below threshMedium (2.0) and half of threshHigh (4.0)
    // so aerobic exertion alone never forces Medium/High/Overload.
    val maxStrainReserveBonus: Double = 1.50,

    // Stationary EWMA smoothing time constant over (zGait + zVoice)
    // Converges to current steady-state load level without integrating upward indefinitely.
    val ewmaTauSec: Double = 6.0,

    // Tier boundaries in sigma-evidence units (scaled x25 for 0..150+ UI parity)
    val threshMedium: Double = 2.0,
    val threshHigh: Double = 4.0,
    val threshOverload: Double = 5.5,
    val minDownDwellTicks: Int = 5
)

/**
 * Collects clean calm silent walking ticks and locks per-participant
 * step-time and step-CV baselines using outlier-robust Median + scaled MAD.
 */
class BaselineCalibrator(
    private val config: StatisticalLoadConfig = StatisticalLoadConfig()
) {
    private val stepTimeSamples = mutableListOf<Double>()
    private val stepCvSamples = mutableListOf<Double>()

    var phase: CalibrationPhase = CalibrationPhase.IDLE
        private set

    private var lockedBaseline: BaselineSnapshot = normativeSnapshot(CalibrationPhase.IDLE, 0)

    fun startCalibration() {
        stepTimeSamples.clear()
        stepCvSamples.clear()
        phase = CalibrationPhase.CALIBRATING
        lockedBaseline = normativeSnapshot(CalibrationPhase.CALIBRATING, 0)
    }

    fun bypassWithNorms() {
        stepTimeSamples.clear()
        stepCvSamples.clear()
        phase = CalibrationPhase.NORMATIVE_BYPASS
        lockedBaseline = normativeSnapshot(CalibrationPhase.NORMATIVE_BYPASS, config.baselineTargetTicks)
    }

    fun reset() {
        stepTimeSamples.clear()
        stepCvSamples.clear()
        phase = CalibrationPhase.IDLE
        lockedBaseline = normativeSnapshot(CalibrationPhase.IDLE, 0)
    }

    fun observeTick(isWalking: Boolean, isSpeaking: Boolean, stepTimeMs: Double?, stepCvPct: Double?) {
        if (phase != CalibrationPhase.CALIBRATING) return
        if (!isWalking || isSpeaking || stepTimeMs == null || stepCvPct == null) return
        if (!stepTimeMs.isFinite() || !stepCvPct.isFinite() || stepTimeMs <= 0.0) return

        stepTimeSamples.add(stepTimeMs)
        stepCvSamples.add(stepCvPct)

        if (stepCvSamples.size >= config.baselineTargetTicks) {
            val medStepTime = median(stepTimeSamples)
            val medCv = median(stepCvSamples)
            val sigmaCv = maxOf(scaledMad(stepCvSamples, medCv), config.sigmaStepCvFloor)
            phase = CalibrationPhase.LOCKED
            lockedBaseline = BaselineSnapshot(
                stepTimeMs = medStepTime,
                stepCvPct = medCv,
                sigmaStepCvPct = sigmaCv,
                phase = CalibrationPhase.LOCKED,
                collectedTicks = stepCvSamples.size,
                targetTicks = config.baselineTargetTicks
            )
        } else {
            lockedBaseline = normativeSnapshot(CalibrationPhase.CALIBRATING, stepCvSamples.size)
        }
    }

    fun snapshot(): BaselineSnapshot = lockedBaseline

    private fun normativeSnapshot(currentPhase: CalibrationPhase, collected: Int): BaselineSnapshot =
        BaselineSnapshot(
            stepTimeMs = config.priorStepTimeMs,
            stepCvPct = config.priorStepCvPct,
            sigmaStepCvPct = config.priorSigmaStepCvPct.coerceAtLeast(config.sigmaStepCvFloor),
            phase = currentPhase,
            collectedTicks = collected,
            targetTicks = config.baselineTargetTicks
        )

    companion object {
        fun median(values: List<Double>): Double {
            if (values.isEmpty()) return 0.0
            val sorted = values.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 0) {
                (sorted[mid - 1] + sorted[mid]) / 2.0
            } else {
                sorted[mid]
            }
        }

        /** Consistent estimator for Gaussian SD (~1.4826 * MAD). */
        fun scaledMad(values: List<Double>, med: Double = median(values)): Double {
            if (values.isEmpty()) return 0.0
            val deviations = values.map { abs(it - med) }
            return 1.4826 * median(deviations)
        }

        /**
         * Drops braking tail intervals and missed-step double gaps relative to median pace
         * when enough intervals exist, preserving normal and dual-task gait variability.
         */
        fun filterStepInliers(
            intervals: List<Double>,
            lowRatio: Double = 0.65,
            highRatio: Double = 1.45
        ): List<Double> {
            if (intervals.size < 4) return intervals
            val med = median(intervals)
            if (med <= 0.0) return intervals
            val filtered = intervals.filter { it in (med * lowRatio)..(med * highRatio) }
            return if (filtered.size >= 2) filtered else intervals
        }
    }
}

data class StatisticalLoadOutput(
    val zGait: Double,
    val zVoice: Double,
    val strainReserveBonus: Double,
    val ewmaScore: Double,
    val effectiveEvidence: Double,
    val combinedLoadIndex: Double,
    val loadLevel: String
)

/**
 * Stationary EWMA controller fusing bounded within-subject gait CV departure
 * and sustained Silero VAD speech duty with an ACSM 40-60% HRR capacity-reserve hinge
 * and downward dwell hysteresis. Converges to current steady-state load level without
 * integrating upward indefinitely during prolonged conversation.
 */
class InnovationCusumController(
    private val config: StatisticalLoadConfig = StatisticalLoadConfig()
) {
    private var ewmaScore: Double = 0.0
    private var currentTier: Int = 0
    private var downCandidateTicks: Int = 0
    private var initialized: Boolean = false

    fun reset() {
        ewmaScore = 0.0
        currentTier = 0
        downCandidateTicks = 0
        initialized = false
    }

    fun update(
        isWalking: Boolean,
        stepCvPct: Double?,
        baseline: BaselineSnapshot,
        isSpeaking: Boolean,
        speechDutyCycle: Float?,
        hrrFraction: Double?,
        hasAnySignal: Boolean,
        dtSeconds: Double = 1.0
    ): StatisticalLoadOutput {
        if (!hasAnySignal && !initialized) {
            return StatisticalLoadOutput(
                zGait = 0.0,
                zVoice = 0.0,
                strainReserveBonus = 0.0,
                ewmaScore = 0.0,
                effectiveEvidence = 0.0,
                combinedLoadIndex = 0.0,
                loadLevel = "Waiting..."
            )
        }
        initialized = true

        val zGait = if (isWalking && stepCvPct != null && stepCvPct.isFinite()) {
            ((stepCvPct - baseline.stepCvPct) / baseline.sigmaStepCvPct).coerceIn(0.0, config.zGaitMax)
        } else {
            0.0
        }

        val vocalDuty = (speechDutyCycle?.toDouble() ?: if (isSpeaking) 0.50 else 0.0).coerceIn(0.0, 1.0)
        val zVoice = ((vocalDuty - config.voiceDutyDeadzone) / config.voiceDutyScale).coerceIn(0.0, config.zVoiceMax)

        val strainFractionOverThreshold = if (hrrFraction != null && hrrFraction.isFinite()) {
            ((hrrFraction - config.hrrDeadzone) / (config.hrrFullScale - config.hrrDeadzone)).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        val strainReserveBonus = strainFractionOverThreshold * config.maxStrainReserveBonus

        val zCog = zGait + zVoice
        val dt = dtSeconds.coerceIn(0.1, 5.0)
        val alpha = 1.0 - exp(-dt / config.ewmaTauSec.coerceAtLeast(0.5))
        ewmaScore += alpha * (zCog - ewmaScore)

        val effectiveEvidence = ewmaScore + strainReserveBonus
        val rawTier = when {
            effectiveEvidence >= config.threshOverload -> 3
            effectiveEvidence >= config.threshHigh -> 2
            effectiveEvidence >= config.threshMedium -> 1
            else -> 0
        }

        if (rawTier > currentTier) {
            currentTier = rawTier
            downCandidateTicks = 0
        } else if (rawTier < currentTier) {
            downCandidateTicks++
            if (downCandidateTicks >= config.minDownDwellTicks) {
                currentTier = rawTier
                downCandidateTicks = 0
            }
        } else {
            downCandidateTicks = 0
        }

        val levelName = when (currentTier) {
            3 -> "Overload"
            2 -> "High"
            1 -> "Medium"
            else -> "Low"
        }

        return StatisticalLoadOutput(
            zGait = zGait,
            zVoice = zVoice,
            strainReserveBonus = strainReserveBonus,
            ewmaScore = ewmaScore,
            effectiveEvidence = effectiveEvidence,
            combinedLoadIndex = effectiveEvidence * 25.0,
            loadLevel = levelName
        )
    }
}
