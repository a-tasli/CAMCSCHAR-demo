package org.example

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AmbientAggregatorTest {

    @Test
    fun testBleSlidingWindowBucketsTurnoverAndTtlEviction() {
        val agg = BleWindowAggregator(
            activeWindowMs = 60_000L,
            persistenceMinSpanMs = 120_000L,
            maxTtlMs = 180_000L
        )
        val t0 = 1_000_000L

        // Device A starts at t0 (will persist > 120 s)
        agg.recordObservation("tok_a", -55, BleDeviceCategory.CHIPSET_IOT, t0)
        // Before 60 s warmup completes, turnoverRate is null
        val early = agg.summarize(t0 + 10_000L)
        assertNull(early.turnoverRate)
        assertEquals(1, early.nearCount)

        // Device A keeps pinging at t0 + 130 s; Device B arrives newly at t0 + 130 s; Device C arrives at t0 + 135 s
        agg.recordObservation("tok_a", -58, BleDeviceCategory.AUDIO_WEARABLE, t0 + 130_000L)
        agg.recordObservation("tok_b", -70, BleDeviceCategory.PHONE_ECOSYSTEM, t0 + 130_000L)
        agg.recordObservation("tok_c", -85, BleDeviceCategory.TRACKER_BEACON, t0 + 135_000L)

        val summary = agg.summarize(t0 + 140_000L)
        assertEquals(3, summary.totalCount)
        assertEquals(1, summary.nearCount)
        assertEquals(1, summary.midCount)
        assertEquals(1, summary.farCount)
        assertEquals(-58, summary.maxRssi)
        assertEquals(1, summary.persistentCount, "tok_a spanned 130s >= 120s persistence threshold")
        assertEquals(1, summary.audioWearableCount, "tok_a should upgrade CHIPSET_IOT -> AUDIO_WEARABLE")
        assertEquals(1, summary.phoneEcosystemCount)
        assertEquals(1, summary.trackerBeaconCount)
        val turnover = assertNotNull(summary.turnoverRate)
        assertEquals(2.0 / 3.0, turnover, 0.001, "2 of 3 active devices arrived within last 60s")

        // Advance past 180s TTL from all observations -> complete RAM eviction
        val expired = agg.summarize(t0 + 350_000L)
        assertEquals(0, expired.totalCount)
        assertNull(expired.meanRssi)
    }

    @Test
    fun testWifiSsidBlindAggregationAndLaaCheck() {
        assertTrue(WifiScanAggregator.isLocallyAdministeredMac("02:11:22:33:44:55"))
        assertTrue(WifiScanAggregator.isLocallyAdministeredMac("EA:11:22:33:44:55"))
        assertFalse(WifiScanAggregator.isLocallyAdministeredMac("00:11:22:33:44:55"))
        assertFalse(WifiScanAggregator.isLocallyAdministeredMac("F8:11:22:33:44:55"))

        val wifiAgg = WifiScanAggregator()
        val scan1 = listOf(
            WifiApObservation("b1", -60, 5180, isLocallyAdministered = false, is80211mcResponder = true),
            WifiApObservation("b2", -80, 2412, isLocallyAdministered = true, is80211mcResponder = false)
        )
        val s1 = wifiAgg.onScanResults(scan1, 10_000L)
        assertEquals(2, s1.apCount)
        assertEquals(1, s1.strongApCount)
        assertEquals(0.5, s1.ratio5Ghz, 0.001)
        assertEquals(1, s1.laaBssidCount)
        assertEquals(1, s1.rttApCount)
        assertNull(s1.rssiDeltaAbsMean)

        // Second scan: b1 attenuated by 6 dBm, b2 unchanged, b3 new arrival
        val scan2 = listOf(
            WifiApObservation("b1", -66, 5180, isLocallyAdministered = false, is80211mcResponder = true),
            WifiApObservation("b2", -80, 2412, isLocallyAdministered = true, is80211mcResponder = false),
            WifiApObservation("b3", -58, 5240, isLocallyAdministered = false, is80211mcResponder = false)
        )
        val s2 = wifiAgg.onScanResults(scan2, 30_000L)
        assertEquals(3, s2.apCount)
        assertEquals(3.0, s2.rssiDeltaAbsMean!!, 0.001, "Mean |delta RSSI| of (6, 0) should be 3.0")
        assertEquals(1, s2.transientApCount, "b3 is a newcomer relative to scan 1")
        assertEquals("Indoor / Dense", AmbientContextClassifier.inferEnclosureHint(s2))

        val semiScan = listOf(
            WifiApObservation("c1", -72, 2412, isLocallyAdministered = false, is80211mcResponder = false),
            WifiApObservation("c2", -75, 2437, isLocallyAdministered = false, is80211mcResponder = false),
            WifiApObservation("c3", -78, 2462, isLocallyAdministered = false, is80211mcResponder = false),
            WifiApObservation("c4", -80, 2412, isLocallyAdministered = false, is80211mcResponder = false)
        )
        assertEquals("Semi-Enclosed", AmbientContextClassifier.inferEnclosureHint(wifiAgg.onScanResults(semiScan, 50_000L)))
    }

    @Test
    fun testSnapshotContainsZeroRawMacPatterns() {
        val snapshot = AmbientContextSnapshot(
            bleTotalCount = 12,
            bleNearCount = 4,
            bleFilterMode = "full",
            bleFilterCount = 90,
            wifiApCount = 5,
            wifiStrongApCount = 2,
            ambientNoiseDbfs = -31.5,
            envEnclosureHint = "Semi-Enclosed",
            envBusynessTier = "Moderate"
        )
        val macRegex = Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")
        assertFalse(macRegex.containsMatchIn(snapshot.toString()), "Snapshot must never contain raw MAC addresses")
    }
}
