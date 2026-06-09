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
    val isWalking: Boolean
)

class GaitAnalyzer {

    // Store step timestamps in nanoseconds. We'll keep the last 20 steps.
    private val stepTimestamps = mutableListOf<Long>()
    private val maxStepsToKeep = 20

    private val rrData = mutableListOf<Int>()
    private val rrWindowSize = 60 // Roughly 60 seconds of data

    @Synchronized
    fun addStep(timestampNanos: Long) {
        stepTimestamps.add(timestampNanos)
        if (stepTimestamps.size > maxStepsToKeep) {
            stepTimestamps.removeAt(0)
        }
    }

    @Synchronized
    fun addRRIntervals(intervals: List<Int>) {
        rrData.addAll(intervals)
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
        val currentTimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
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

        var hrLoadIndex: Double? = null
        if (currentHr != null) {
            // Heart rate increases under load
            val hrMeanScore = (currentHr - 70.0) / 40.0 * 100.0
            
            if (rrData.size > 5) {
                // Following the project's literature: Cognitive load is POSITIVELY correlated with HRV.
                // Higher SDNN/RMSSD = higher cognitive load.
                val hrSdnnScore = (sdnn - 40.0) / 60.0 * 100.0
                val hrRmssdScore = (rmssd - 20.0) / 60.0 * 100.0
                hrLoadIndex = (hrSdnnScore + hrRmssdScore + hrMeanScore) / 3.0
            } else {
                hrLoadIndex = hrMeanScore
            }
        }

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
            isWalking = isWalking
        )
    }
}