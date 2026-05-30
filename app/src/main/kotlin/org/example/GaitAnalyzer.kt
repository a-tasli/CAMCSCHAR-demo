package org.example

import kotlin.math.sqrt
import kotlin.math.pow
import kotlin.math.min
import kotlin.math.max

data class GaitFeatures(
    val stepTime: Double,
    val stepTimeVariability: Double,
    val stepRegularity: Double,
    val strideRegularity: Double,
    val hrValue: Int?,
    val sdnn: Double,
    val rmssd: Double,
    val gaitLoadIndex: Double,
    val hrLoadIndex: Double,
    val combinedLoadIndex: Double
)

data class GaitGraphData(
    val values: List<Float>,
    val steps: List<Int>
)

class GaitAnalyzer(private val sampleRate: Double = 100.0) {

    private val windowSize = (10.0 * sampleRate).toInt() // 10 second window as per article
    private val accelerationData = mutableListOf<Double>()
    
    // Butterworth filter coefficients (2nd order, 10Hz low-pass, 100Hz sample rate)
    // Calculated using standard formulas
    private val b = doubleArrayOf(0.067455, 0.13491, 0.067455)
    private val a = doubleArrayOf(1.0, -1.14298, 0.41280)
    
    private var x1 = 0.0; private var x2 = 0.0
    private var y1 = 0.0; private var y2 = 0.0

    private val rrData = mutableListOf<Int>()
    private val rrWindowSize = 60 // Roughly 60 seconds of data

    @Synchronized
    fun addAcceleration(x: Float, y: Float, z: Float, gravity: FloatArray?) {
        val linearAccel = if (gravity != null) {
            val dotProduct = x * gravity[0] + y * gravity[1] + z * gravity[2]
            val gravityMag = sqrt((gravity[0] * gravity[0] + gravity[1] * gravity[1] + gravity[2] * gravity[2]).toDouble())
            val verticalAccel = dotProduct / gravityMag
            verticalAccel - gravityMag
        } else {
            z.toDouble() - 9.81
        }

        val filtered = b[0] * linearAccel + b[1] * x1 + b[2] * x2 - a[1] * y1 - a[2] * y2
        
        x2 = x1; x1 = linearAccel
        y2 = y1; y1 = filtered

        accelerationData.add(filtered)
        if (accelerationData.size > windowSize) {
            accelerationData.removeAt(0)
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
    fun getGraphData(): GaitGraphData {
        val dataCopy = accelerationData.toDoubleArray()
        val valleys = detectValleys(dataCopy)
        return GaitGraphData(dataCopy.map { it.toFloat() }, valleys)
    }

    @Synchronized
    fun analyze(currentHr: Int?): GaitFeatures? {
        if (accelerationData.size < windowSize) return null

        val data = accelerationData.toDoubleArray()
        
        val valleys = detectValleys(data)
        if (valleys.size < 3) return null

        val stepTimes = mutableListOf<Double>()
        for (i in 1 until valleys.size) {
            stepTimes.add((valleys[i] - valleys[i - 1]) / sampleRate * 1000.0)
        }
        val avgStepTime = stepTimes.average()
        val stepTimeVar = if (stepTimes.size > 1) {
            sqrt(stepTimes.map { (it - avgStepTime).pow(2) }.average())
        } else 0.0

        val ac = calculateAutocorrelation(data)
        val p0 = ac[0]
        val p1Index = findPeakInRange(ac, (0.4 * sampleRate).toInt(), (0.8 * sampleRate).toInt())
        val p2Index = findPeakInRange(ac, (0.9 * sampleRate).toInt(), (1.4 * sampleRate).toInt())
        val stepRegularity = if (p1Index != -1) ac[p1Index] / p0 else 0.0
        val strideRegularity = if (p2Index != -1) ac[p2Index] / p0 else 0.0

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

        val gaitStepTimeScore = min(100.0, max(0.0, (avgStepTime - 510.0) / 70.0 * 100.0))
        val gaitVarScore = min(100.0, max(0.0, (stepTimeVar - 15.0) / 30.0 * 100.0))
        val gaitRegScore = min(100.0, max(0.0, (0.90 - stepRegularity) / 0.20 * 100.0))
        val gaitLoadIndex = (gaitStepTimeScore + gaitVarScore + gaitRegScore) / 3.0

        val hrSdnnScore = min(100.0, max(0.0, (sdnn - 60.0) / 60.0 * 100.0))
        val hrRmssdScore = min(100.0, max(0.0, (rmssd - 30.0) / 60.0 * 100.0))
        val hrMeanScore = currentHr?.let { min(100.0, max(0.0, (it - 65.0) / 50.0 * 100.0)) } ?: 0.0
        val hrLoadIndex = (hrSdnnScore + hrRmssdScore + hrMeanScore) / 3.0

        val combinedLoadIndex = (gaitLoadIndex + hrLoadIndex) / 2.0

        return GaitFeatures(
            stepTime = avgStepTime,
            stepTimeVariability = stepTimeVar,
            stepRegularity = stepRegularity,
            strideRegularity = strideRegularity,
            hrValue = currentHr,
            sdnn = sdnn,
            rmssd = rmssd,
            gaitLoadIndex = gaitLoadIndex,
            hrLoadIndex = hrLoadIndex,
            combinedLoadIndex = combinedLoadIndex
        )
    }

    private fun detectValleys(data: DoubleArray): List<Int> {
        val valleys = mutableListOf<Int>()
        val mean = data.average()
        val stdDev = sqrt(data.map { (it - mean).pow(2) }.average())
        val threshold = mean - (0.6 * max(stdDev, 0.5))

        for (i in 2 until data.size - 2) {
            // Check for local minimum (valley)
            if (data[i] < data[i - 1] && data[i] < data[i + 1] && data[i] < threshold) {
                // M-Pattern check: Look for peaks on either side within ~300ms
                val searchWindow = (0.3 * sampleRate).toInt()
                val leftPeak = findPeakInRange(data, max(0, i - searchWindow), i - 1)
                val rightPeak = findPeakInRange(data, i + 1, min(data.size - 1, i + searchWindow))
                
                if (leftPeak != -1 && rightPeak != -1) {
                    val hLeft = data[leftPeak] - data[i]
                    val hRight = data[rightPeak] - data[i]
                    // Valley must be deep enough compared to surrounding peaks
                    if (hLeft > 0.5 * stdDev && hRight > 0.5 * stdDev) {
                        valleys.add(i)
                    }
                }
            }
        }

        val filteredValleys = mutableListOf<Int>()
        if (valleys.isNotEmpty()) {
            filteredValleys.add(valleys[0])
            for (i in 1 until valleys.size) {
                // Filter out valleys that are too close (min 350ms between steps)
                if (valleys[i] - filteredValleys.last() > 0.35 * sampleRate) {
                    filteredValleys.add(valleys[i])
                }
            }
        }
        return filteredValleys
    }

    private fun calculateAutocorrelation(data: DoubleArray): DoubleArray {
        val n = data.size
        val mean = data.average()
        val variance = data.map { (it - mean).pow(2) }.sum()
        val lags = n / 2
        val result = DoubleArray(lags)
        
        for (l in 0 until lags) {
            var sum = 0.0
            for (i in 0 until n - l) {
                sum += (data[i] - mean) * (data[i + l] - mean)
            }
            result[l] = sum / variance
        }
        return result
    }

    private fun findPeakInRange(data: DoubleArray, start: Int, end: Int): Int {
        var maxVal = -Double.MAX_VALUE
        var maxIdx = -1
        val actualEnd = min(data.size - 1, end)
        if (start > actualEnd) return -1
        for (i in start..actualEnd) {
            if (data[i] > maxVal) {
                maxVal = data[i]
                maxIdx = i
            }
        }
        // Check if it's a local peak
        if (maxIdx > 0 && maxIdx < data.size - 1) {
            if (data[maxIdx] > data[maxIdx - 1] && data[maxIdx] > data[maxIdx + 1]) {
                return maxIdx
            }
        }
        return maxIdx
    }
}
