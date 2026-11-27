package org.example

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class EphemeralIdFilterTest {

    @Test
    fun testDeterministicWithinEpochAndNormalizedCase() {
        val filter = EphemeralIdFilter()
        val rawUpper = "AA:BB:CC:DD:EE:FF"
        val rawLower = " aa:bb:cc:dd:ee:ff "

        val token1 = filter.hash(rawUpper)
        val token2 = filter.hash(rawLower)

        assertEquals(token1, token2, "Same MAC should hash identically within an epoch regardless of case/whitespace")
        assertEquals(16, token1.length, "Token should be 16 hex characters")
        assertTrue(token1.all { it in '0'..'9' || it in 'a'..'f' }, "Token must be lowercase hex")
        assertFalse(token1.contains("aabb", ignoreCase = true), "Token must not leak raw MAC substrings")
    }

    @Test
    fun testUnlinkableAcrossSaltRotationAndClear() {
        val filter = EphemeralIdFilter()
        val mac = "12:34:56:78:9A:BC"

        val beforeRotation = filter.hash(mac)
        filter.rotateSalt()
        val afterRotation = filter.hash(mac)
        filter.clear()
        val afterClear = filter.hash(mac)

        assertNotEquals(beforeRotation, afterRotation, "Rotating salt must break cross-epoch linkability")
        assertNotEquals(afterRotation, afterClear, "Clearing filter must rotate/wipe salt")
        assertNotEquals(beforeRotation, afterClear)
    }
}
