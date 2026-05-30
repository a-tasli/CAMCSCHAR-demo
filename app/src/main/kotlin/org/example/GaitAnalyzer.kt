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
    val cognitiveLoadIndex: Double
)

data class GaitGraphData(
    val values: List<Float>,
    val steps: List<Int>
)

class GaitAnalyzer(private val sampleRate: Double = 100.0) {

    private val windowSize = (10.0 * sampleRate).toInt() // 10 second window as per article
    private val accelerationData = mutableListOf<Double>()
    
    // Butterworth filter coefficients (2nd order, 2.5Hz low-pass, 100Hz sample rate)
    // Calculated using standard formulas to aggressively filter out hand shaking (3Hz+)
    private val b = doubleArrayOf(0.0055427, 0.011085, 0.0055427)
    private val a = doubleArrayOf(1.0, -1.77863, 0.80080)
    
    private var x1 = 0.0; private var x2 = 0.0
    private var y1 = 0.0; private var y2 = 0.0

    @Synchronized
    fun addAcceleration(x: Float, y: Float, z: Float, gravity: FloatArray?) {
        // Calculate linear vertical acceleration
        // In the article, they subtracted gravity. 
        // We'll assume the phone is upright in a pocket or chest mount.
        // Let's use the magnitude of acceleration minus gravity if gravity is known,
        // or just use the z-axis if we assume orientation.
        // The article used vertical acceleration.
        
        val linearAccel = if (gravity != null) {
            // Project acceleration onto gravity vector to get vertical component
            val dotProduct = x * gravity[0] + y * gravity[1] + z * gravity[2]
            val gravityMag = sqrt((gravity[0] * gravity[0] + gravity[1] * gravity[1] + gravity[2] * gravity[2]).toDouble())
            val verticalAccel = dotProduct / gravityMag
            verticalAccel - gravityMag
        } else {
            // Fallback to Z-axis minus gravity approx
            z.toDouble() - 9.81
        }

        // Apply low-pass filter
        val filtered = b[0] * linearAccel + b[1] * x1 + b[2] * x2 - a[1] * y1 - a[2] * y2
        
        x2 = x1; x1 = linearAccel
        y2 = y1; y1 = filtered

        accelerationData.add(filtered)
        if (accelerationData.size > windowSize) {
            accelerationData.removeAt(0)
        }
    }

    @Synchronized
    fun getGraphData(): GaitGraphData {
        val dataCopy = accelerationData.toDoubleArray()
        val valleys = detectValleys(dataCopy)
        return GaitGraphData(dataCopy.map { it.toFloat() }, valleys)
    }

    @Synchronized
    fun analyze(): GaitFeatures? {
        if (accelerationData.size < windowSize) return null

        val data = accelerationData.toDoubleArray()
        
        // 1. Detect Steps (Valleys)
        val valleys = detectValleys(data)
        if (valleys.size < 3) return null

        // 2. Calculate Step Time and Variability
        val stepTimes = mutableListOf<Double>()
        for (i in 1 until valleys.size) {
            stepTimes.add((valleys[i] - valleys[i - 1]) / sampleRate * 1000.0) // in ms
        }
        val avgStepTime = stepTimes.average()
        val stepTimeVar = if (stepTimes.size > 1) {
            sqrt(stepTimes.map { (it - avgStepTime).pow(2) }.average())
        } else 0.0

        // 3. Calculate Regularity via Autocorrelation
        val ac = calculateAutocorrelation(data)
        val p0 = ac[0]
        
        // Find peaks p1 (step) and p2 (stride)
        // Expected step time is ~500-600ms, so lag is ~50-60 samples
        val p1Index = findPeakInRange(ac, (0.4 * sampleRate).toInt(), (0.8 * sampleRate).toInt())
        val p2Index = findPeakInRange(ac, (0.9 * sampleRate).toInt(), (1.4 * sampleRate).toInt())
        
        val stepRegularity = if (p1Index != -1) ac[p1Index] / p0 else 0.0
        val strideRegularity = if (p2Index != -1) ac[p2Index] / p0 else 0.0

        // 4. Estimate Cognitive Load Index
        // Based on article: High load = High Step Time, High Variability, Low Regularity
        // We'll normalize these relative to some baseline or just provide a raw index.
        // Let's create a heuristic index (0-100)
        // Step time baseline ~520ms, High ~565ms (+45ms)
        // Step Var baseline ~22ms, High ~38ms (+16ms)
        // Step Reg baseline ~0.87, High ~0.80 (-0.07)
        
        val stepTimeScore = min(100.0, max(0.0, (avgStepTime - 500.0) / 100.0 * 100.0))
        val varScore = min(100.0, max(0.0, (stepTimeVar - 20.0) / 30.0 * 100.0))
        val regScore = min(100.0, max(0.0, (0.9 - stepRegularity) / 0.2 * 100.0))
        
        val cognitiveLoad = (stepTimeScore + varScore + regScore) / 3.0

        return GaitFeatures(
            stepTime = avgStepTime,
            stepTimeVariability = stepTimeVar,
            stepRegularity = stepRegularity,
            strideRegularity = strideRegularity,
            cognitiveLoadIndex = cognitiveLoad
        )
    }

    private fun detectValleys(data: DoubleArray): List<Int> {
        val valleys = mutableListOf<Int>()
        
        // Calculate dynamic threshold
        val mean = data.average()
        val stdDev = sqrt(data.map { (it - mean).pow(2) }.average())
        
        // Energy gate: If the overall energy (variance) is too low, it's just handling noise, not walking.
        // Walking typically produces a stdDev > 1.0 m/s^2 even at slow speeds.
        if (stdDev < 0.8) {
            return emptyList()
        }

        // Require a minimum standard deviation to avoid triggering on noise when still
        val effectiveStdDev = max(stdDev, 0.5) 
        // Valley must be significantly below the mean
        val threshold = mean - (0.8 * effectiveStdDev)

        for (i in 1 until data.size - 1) {
            if (data[i] < data[i - 1] && data[i] < data[i + 1] && data[i] < threshold) {
                valleys.add(i)
            }
        }
        // Further filter valleys to ensure they are at least some distance apart (e.g. 300ms)
        val filteredValleys = mutableListOf<Int>()
        if (valleys.isNotEmpty()) {
            filteredValleys.add(valleys[0])
            for (i in 1 until valleys.size) {
                if (valleys[i] - filteredValleys.last() > 0.3 * sampleRate) {
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