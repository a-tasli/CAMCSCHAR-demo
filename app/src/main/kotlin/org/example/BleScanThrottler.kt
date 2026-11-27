package org.example

/**
 * Enforces Android's BLE scanner rate limit (5 startScan() calls per 30 s rolling window
 * per app UID). Keeping a safe ceiling of [maxStartsPerWindow] prevents silent OS scan
 * drops (SCAN_FAILED_SCANNING_TOO_FREQUENTLY).
 */
class BleScanThrottler(
    private val maxStartsPerWindow: Int = 4,
    private val windowMs: Long = 30_000L,
    private val nowProvider: () -> Long = { System.currentTimeMillis() }
) {
    private val startTimestamps = ArrayDeque<Long>()

    @Synchronized
    fun tryAcquire(): Boolean {
        val now = nowProvider()
        pruneExpired(now)
        if (startTimestamps.size >= maxStartsPerWindow) {
            return false
        }
        startTimestamps.addLast(now)
        return true
    }

    @Synchronized
    fun msUntilNextSlot(): Long {
        val now = nowProvider()
        pruneExpired(now)
        if (startTimestamps.size < maxStartsPerWindow) return 0L
        val oldest = startTimestamps.first()
        return ((oldest + windowMs) - now).coerceAtLeast(1L)
    }

    @Synchronized
    fun remainingQuota(): Int {
        pruneExpired(nowProvider())
        return (maxStartsPerWindow - startTimestamps.size).coerceAtLeast(0)
    }

    private fun pruneExpired(now: Long) {
        val cutoff = now - windowMs
        while (startTimestamps.isNotEmpty() && startTimestamps.first() <= cutoff) {
            startTimestamps.removeFirst()
        }
    }
}
