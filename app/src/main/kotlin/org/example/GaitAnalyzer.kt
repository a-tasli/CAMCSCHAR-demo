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
    val gaitLoadIndex: Double,
    val hrLoadIndex: Double,
    val combinedLoadIndex: Double
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

        var hrLoadIndex = 0.0
        if (currentHr != null) {
            // Heart rate increases under load
            val hrMeanScore = min(100.0, max(0.0, (currentHr - 70.0) / 40.0 * 100.0))
            
            if (rrData.size > 5) {
                // Following the project's literature: Cognitive load is POSITIVELY correlated with HRV.
                // Higher SDNN/RMSSD = higher cognitive load.
                // Baselines are adjusted so normal resting variability doesn't instantly equal 100% load.
                val hrSdnnScore = min(100.0, max(0.0, (sdnn - 40.0) / 60.0 * 100.0)) // 40ms = 0%, 100ms = 100%
                val hrRmssdScore = min(100.0, max(0.0, (rmssd - 20.0) / 60.0 * 100.0)) // 20ms = 0%, 80ms = 100%
                hrLoadIndex = (hrSdnnScore + hrRmssdScore + hrMeanScore) / 3.0
            } else {
                hrLoadIndex = hrMeanScore
            }
        }

        var avgStepTime = 0.0
        var stepTimeVar = 0.0
        var gaitLoadIndex = 0.0

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

                // Widen bounds: Paper averages are too tight for real-world variance.
                // 500ms = 0%, 700ms = 100%
                val gaitStepTimeScore = min(100.0, max(0.0, (avgStepTime - 500.0) / 200.0 * 100.0))
                // 20ms = 0%, 70ms = 100%
                val gaitVarScore = min(100.0, max(0.0, (stepTimeVar - 20.0) / 50.0 * 100.0))
                
                gaitLoadIndex = (gaitStepTimeScore + gaitVarScore) / 2.0
            }
        }

        var combinedLoadIndex = if (gaitLoadIndex > 0.0 && hrLoadIndex > 0.0) {
            (gaitLoadIndex + hrLoadIndex) / 2.0
        } else if (gaitLoadIndex > 0.0) {
            gaitLoadIndex
        } else {
            hrLoadIndex
        }

        // Apply a penalty if the user is engaged in conversation
        if (isSpeaking && combinedLoadIndex > 0.0) {
            combinedLoadIndex = min(100.0, combinedLoadIndex + 15.0)
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
            combinedLoadIndex = combinedLoadIndex
        )
    }
}