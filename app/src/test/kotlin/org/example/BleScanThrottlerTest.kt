package org.example

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BleScanThrottlerTest {

    @Test
    fun testEnforcesMaxFourStartsPerThirtySeconds() {
        var fakeNow = 100_000L
        val throttler = BleScanThrottler(
            maxStartsPerWindow = 4,
            windowMs = 30_000L,
            nowProvider = { fakeNow }
        )

        repeat(4) { i ->
            assertTrue(throttler.tryAcquire(), "Start #${i + 1} should succeed")
            fakeNow += 1_000L
        }
        assertEquals(0, throttler.remainingQuota())
        assertFalse(throttler.tryAcquire(), "5th start inside 30s must be throttled below Android's 5/30s cutoff")
        assertTrue(throttler.msUntilNextSlot() > 0L)

        // Advance past 30s from first start (100_000 + 30_001)
        fakeNow = 130_001L
        assertTrue(throttler.tryAcquire(), "Quota should replenish once oldest start expires")
    }
}
