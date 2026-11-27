package org.example

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Result of a one-shot 10-second screen-on unfiltered BLE probe used in Debug/calibration
 * to empirically measure what fraction of nearby BLE advertisers our curated filter
 * catalog captures ([probeMatchedCount] / [probeTotalCount]).
 */
data class BleRecallProbeResult(
    val probeTotalCount: Int,
    val probeMatchedCount: Int,
    val probeCoverageRatio: Double?,
    val probeTimestampMs: Long
)

/**
 * 100% aggregate, non-PII environmental context snapshot emitted at 1 Hz.
 * Zero raw MACs, zero BSSIDs, zero SSIDs, zero device names, and zero audio samples.
 */
data class AmbientContextSnapshot(
    val bleTotalCount: Int = 0,
    val bleNearCount: Int = 0,
    val bleMidCount: Int = 0,
    val bleFarCount: Int = 0,
    val bleMeanRssi: Double? = null,
    val bleMaxRssi: Int? = null,
    val bleTurnoverRate: Double? = null,
    val blePersistentCount: Int = 0,
    val blePacketRateHz: Double = 0.0,
    val blePhoneEcosystemCount: Int = 0,
    val bleAudioWearableCount: Int = 0,
    val bleTrackerBeaconCount: Int = 0,
    val bleChipsetOtherCount: Int = 0,
    val bleFilterMode: String = "off",
    val bleFilterCount: Int = 0,
    val bleRecallProbe: BleRecallProbeResult? = null,

    val wifiApCount: Int? = null,
    val wifiStrongApCount: Int? = null,
    val wifi5GhzRatio: Double? = null,
    val wifiMeanRssi: Double? = null,
    val wifiRssiStd: Double? = null,
    val wifiRssiDeltaAbsMean: Double? = null,
    val wifiLaaBssidCount: Int? = null,
    val wifiTransientApCount: Int? = null,
    val wifiRttApCount: Int? = null,
    val wifiScanAgeMs: Long? = null,

    val ambientNoiseDbfs: Double? = null,
    val ambientNoisePeakDbfs: Double? = null,
    val ambientMidbandRatio: Double? = null,
    val speechDutyCycle10s: Float? = null,

    val envEnclosureHint: String = "Unknown",
    val envBusynessTier: String = "Quiet",
    val isAmbientSensingEnabled: Boolean = true
) {
    companion object {
        fun disabled(
            ambientNoiseDbfs: Double? = null,
            ambientNoisePeakDbfs: Double? = null,
            ambientMidbandRatio: Double? = null,
            speechDutyCycle10s: Float? = null
        ): AmbientContextSnapshot = AmbientContextSnapshot(
            bleFilterMode = "off",
            ambientNoiseDbfs = ambientNoiseDbfs,
            ambientNoisePeakDbfs = ambientNoisePeakDbfs,
            ambientMidbandRatio = ambientMidbandRatio,
            speechDutyCycle10s = speechDutyCycle10s,
            envEnclosureHint = "Unknown",
            envBusynessTier = "Disabled",
            isAmbientSensingEnabled = false
        )
    }
}

/**
 * Pure-Kotlin BLE sliding-window aggregator operating strictly on ephemeral RAM hashes.
 * Enforces a hard [maxTtlMs] (180 s) memory retention cap for all bystander device tokens.
 */
class BleWindowAggregator(
    private val activeWindowMs: Long = 60_000L,
    private val persistenceMinSpanMs: Long = 120_000L,
    private val maxTtlMs: Long = 180_000L,
    private val packetRateWindowMs: Long = 10_000L
) {
    private data class Entry(
        val firstSeenMs: Long,
        var lastSeenMs: Long,
        var latestRssi: Int,
        var category: BleDeviceCategory
    )

    private val entries = LinkedHashMap<String, Entry>()
    private val packetTimestamps = ArrayDeque<Long>()
    private var trackingStartMs: Long? = null

    data class BleSummary(
        val totalCount: Int,
        val nearCount: Int,
        val midCount: Int,
        val farCount: Int,
        val meanRssi: Double?,
        val maxRssi: Int?,
        val turnoverRate: Double?,
        val persistentCount: Int,
        val packetRateHz: Double,
        val phoneEcosystemCount: Int,
        val audioWearableCount: Int,
        val trackerBeaconCount: Int,
        val chipsetOtherCount: Int
    )

    @Synchronized
    fun recordObservation(
        ephemeralToken: String,
        rssi: Int,
        category: BleDeviceCategory,
        nowMs: Long
    ) {
        if (trackingStartMs == null) {
            trackingStartMs = nowMs
        }
        val existing = entries[ephemeralToken]
        if (existing == null) {
            entries[ephemeralToken] = Entry(
                firstSeenMs = nowMs,
                lastSeenMs = nowMs,
                latestRssi = rssi,
                category = category
            )
        } else {
            existing.lastSeenMs = nowMs
            existing.latestRssi = rssi
            existing.category = mergeCategory(existing.category, category)
        }

        packetTimestamps.addLast(nowMs)
        prunePacketTimestamps(nowMs)
    }

    @Synchronized
    fun summarize(nowMs: Long): BleSummary {
        pruneExpired(nowMs)
        prunePacketTimestamps(nowMs)

        val activeCutoff = nowMs - activeWindowMs
        val active = entries.values.filter { it.lastSeenMs >= activeCutoff }
        val packetRateHz = packetTimestamps.size / (packetRateWindowMs / 1000.0)

        if (active.isEmpty()) {
            return BleSummary(
                totalCount = 0,
                nearCount = 0,
                midCount = 0,
                farCount = 0,
                meanRssi = null,
                maxRssi = null,
                turnoverRate = null,
                persistentCount = 0,
                packetRateHz = packetRateHz,
                phoneEcosystemCount = 0,
                audioWearableCount = 0,
                trackerBeaconCount = 0,
                chipsetOtherCount = 0
            )
        }

        var near = 0
        var mid = 0
        var far = 0
        var rssiSum = 0.0
        var maxRssi = Int.MIN_VALUE
        var persistent = 0
        var newArrivals = 0
        var phoneCount = 0
        var audioCount = 0
        var trackerCount = 0
        var chipsetOtherCount = 0

        for (entry in active) {
            when {
                entry.latestRssi > -60 -> near++
                entry.latestRssi >= -78 -> mid++
                else -> far++
            }
            rssiSum += entry.latestRssi
            if (entry.latestRssi > maxRssi) maxRssi = entry.latestRssi

            if (entry.lastSeenMs - entry.firstSeenMs >= persistenceMinSpanMs) {
                persistent++
            }
            if (entry.firstSeenMs > activeCutoff) {
                newArrivals++
            }
            when (entry.category) {
                BleDeviceCategory.PHONE_ECOSYSTEM -> phoneCount++
                BleDeviceCategory.AUDIO_WEARABLE -> audioCount++
                BleDeviceCategory.TRACKER_BEACON -> trackerCount++
                BleDeviceCategory.CHIPSET_IOT, BleDeviceCategory.OTHER -> chipsetOtherCount++
            }
        }

        val warmedUp = trackingStartMs?.let { nowMs - it >= activeWindowMs } == true
        val turnoverRate = if (warmedUp) newArrivals.toDouble() / active.size else null

        return BleSummary(
            totalCount = active.size,
            nearCount = near,
            midCount = mid,
            farCount = far,
            meanRssi = rssiSum / active.size,
            maxRssi = maxRssi,
            turnoverRate = turnoverRate,
            persistentCount = persistent,
            packetRateHz = packetRateHz,
            phoneEcosystemCount = phoneCount,
            audioWearableCount = audioCount,
            trackerBeaconCount = trackerCount,
            chipsetOtherCount = chipsetOtherCount
        )
    }

    @Synchronized
    fun clear() {
        entries.clear()
        packetTimestamps.clear()
        trackingStartMs = null
    }

    private fun mergeCategory(current: BleDeviceCategory, incoming: BleDeviceCategory): BleDeviceCategory {
        if (current == incoming) return current
        val priority = mapOf(
            BleDeviceCategory.AUDIO_WEARABLE to 4,
            BleDeviceCategory.PHONE_ECOSYSTEM to 3,
            BleDeviceCategory.TRACKER_BEACON to 2,
            BleDeviceCategory.CHIPSET_IOT to 1,
            BleDeviceCategory.OTHER to 0
        )
        return if ((priority[incoming] ?: 0) > (priority[current] ?: 0)) incoming else current
    }

    private fun pruneExpired(nowMs: Long) {
        val ttlCutoff = nowMs - maxTtlMs
        val iter = entries.entries.iterator()
        while (iter.hasNext()) {
            if (iter.next().value.lastSeenMs < ttlCutoff) {
                iter.remove()
            }
        }
    }

    private fun prunePacketTimestamps(nowMs: Long) {
        val cutoff = nowMs - packetRateWindowMs
        while (packetTimestamps.isNotEmpty() && packetTimestamps.first() < cutoff) {
            packetTimestamps.removeFirst()
        }
    }
}

/**
 * SSID-blind Wi-Fi AP observation. Notice there is structurally no SSID field anywhere
 * in this class — network names are never read or passed into the aggregator.
 */
data class WifiApObservation(
    val ephemeralBssidToken: String,
    val rssi: Int,
    val frequencyMhz: Int,
    val isLocallyAdministered: Boolean,
    val is80211mcResponder: Boolean
)

/**
 * Computes SSID-blind structural and temporal Wi-Fi AP metrics across consecutive scans.
 */
class WifiScanAggregator {

    data class WifiSummary(
        val apCount: Int,
        val strongApCount: Int,
        val ratio5Ghz: Double,
        val meanRssi: Double?,
        val rssiStd: Double?,
        val rssiDeltaAbsMean: Double?,
        val laaBssidCount: Int,
        val transientApCount: Int?,
        val rttApCount: Int,
        val lastScanMs: Long
    )

    private var prevRssiByToken: Map<String, Int>? = null
    private var latestSummary: WifiSummary? = null

    @Synchronized
    fun onScanResults(observations: List<WifiApObservation>, scanTimestampMs: Long): WifiSummary {
        val deduped = LinkedHashMap<String, WifiApObservation>(observations.size)
        for (obs in observations) {
            val prev = deduped[obs.ephemeralBssidToken]
            if (prev == null || obs.rssi > prev.rssi) {
                deduped[obs.ephemeralBssidToken] = obs
            }
        }

        val unique = deduped.values.toList()
        if (unique.isEmpty()) {
            val empty = WifiSummary(
                apCount = 0,
                strongApCount = 0,
                ratio5Ghz = 0.0,
                meanRssi = null,
                rssiStd = null,
                rssiDeltaAbsMean = null,
                laaBssidCount = 0,
                transientApCount = prevRssiByToken?.let { 0 },
                rttApCount = 0,
                lastScanMs = scanTimestampMs
            )
            prevRssiByToken = emptyMap()
            latestSummary = empty
            return empty
        }

        var strongCount = 0
        var fiveGhzCount = 0
        var laaCount = 0
        var rttCount = 0
        var sumRssi = 0.0

        for (ap in unique) {
            if (ap.rssi >= -65) strongCount++
            if (ap.frequencyMhz >= 4900) fiveGhzCount++
            if (ap.isLocallyAdministered) laaCount++
            if (ap.is80211mcResponder) rttCount++
            sumRssi += ap.rssi
        }

        val meanRssi = sumRssi / unique.size
        var sqDiff = 0.0
        for (ap in unique) {
            val diff = ap.rssi - meanRssi
            sqDiff += diff * diff
        }
        val rssiStd = sqrt(sqDiff / unique.size)

        val prev = prevRssiByToken
        var deltaAbsMean: Double? = null
        var transientCount: Int? = null
        if (prev != null) {
            val deltas = ArrayList<Double>()
            var newcomers = 0
            for (ap in unique) {
                val oldRssi = prev[ap.ephemeralBssidToken]
                if (oldRssi != null) {
                    deltas.add(abs(ap.rssi - oldRssi).toDouble())
                } else {
                    newcomers++
                }
            }
            deltaAbsMean = if (deltas.isNotEmpty()) deltas.average() else null
            transientCount = newcomers
        }

        prevRssiByToken = unique.associate { it.ephemeralBssidToken to it.rssi }

        val summary = WifiSummary(
            apCount = unique.size,
            strongApCount = strongCount,
            ratio5Ghz = fiveGhzCount.toDouble() / unique.size,
            meanRssi = meanRssi,
            rssiStd = rssiStd,
            rssiDeltaAbsMean = deltaAbsMean,
            laaBssidCount = laaCount,
            transientApCount = transientCount,
            rttApCount = rttCount,
            lastScanMs = scanTimestampMs
        )
        latestSummary = summary
        return summary
    }

    @Synchronized
    fun currentSummary(): WifiSummary? = latestSummary

    @Synchronized
    fun clear() {
        prevRssiByToken = null
        latestSummary = null
    }

    companion object {
        /**
         * Inspects IEEE 802.11 U/L bit (0x02) of the first MAC octet without reading SSID.
         */
        fun isLocallyAdministeredMac(bssid: String): Boolean {
            val firstOctetHex = bssid.trim().split(':', '-').firstOrNull() ?: return false
            val firstByte = firstOctetHex.toIntOrNull(16) ?: return false
            return (firstByte and 0x02) != 0
        }
    }
}

object AmbientContextClassifier {
    fun inferEnclosureHint(wifi: WifiScanAggregator.WifiSummary?): String {
        if (wifi == null) return "Unknown"
        val mean = wifi.meanRssi ?: return "Outdoor / Open"
        return when {
            wifi.strongApCount >= 4 || (wifi.ratio5Ghz >= 0.45 && mean >= -70.0) -> "Indoor / Dense"
            wifi.apCount >= 4 || wifi.strongApCount >= 1 || wifi.ratio5Ghz >= 0.2 -> "Semi-Enclosed"
            else -> "Outdoor / Open"
        }
    }

    fun inferBusynessTier(
        ble: BleWindowAggregator.BleSummary,
        ambientNoiseDbfs: Double?,
        wifi: WifiScanAggregator.WifiSummary?
    ): String {
        var score = 0
        score += when {
            ble.nearCount >= 6 -> 3
            ble.nearCount >= 2 -> 2
            ble.nearCount >= 1 -> 1
            else -> 0
        }
        score += when {
            ble.totalCount >= 20 -> 3
            ble.totalCount >= 8 -> 2
            ble.totalCount >= 3 -> 1
            else -> 0
        }
        if ((ble.turnoverRate ?: 0.0) >= 0.45 && ble.totalCount >= 4) {
            score += 1
        }
        if (ambientNoiseDbfs != null) {
            when {
                ambientNoiseDbfs >= -25.0 -> score += 2
                ambientNoiseDbfs >= -38.0 -> score += 1
            }
        }
        if ((wifi?.laaBssidCount ?: 0) >= 3) {
            score += 1
        }

        return when {
            score >= 5 -> "Busy"
            score >= 2 -> "Moderate"
            else -> "Quiet"
        }
    }
}
