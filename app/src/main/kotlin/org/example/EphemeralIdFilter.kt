package org.example

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Volatile RAM-only identifier hasher for IRB/GDPR/KVKK privacy-by-design.
 *
 * Raw BLE MACs and Wi-Fi BSSIDs are immediately converted to 16-hex-char
 * session-scoped tokens using SHA-256(256-bit SecureRandom salt || normalizedId).
 * Neither the salt nor raw hardware addresses are ever logged or written to disk.
 * Calling [rotateSalt] or [clear] makes prior tokens cryptographically unlinkable.
 */
class EphemeralIdFilter(private val random: SecureRandom = SecureRandom()) {

    @Volatile
    private var salt: ByteArray = generateSalt()

    private fun generateSalt(): ByteArray = ByteArray(32).also { random.nextBytes(it) }

    @Synchronized
    fun rotateSalt() {
        val fresh = generateSalt()
        salt.fill(0)
        salt = fresh
    }

    @Synchronized
    fun clear() {
        salt.fill(0)
        salt = generateSalt()
    }

    /** Returns an 8-byte (16 lowercase hex chars) volatile token for RAM deduplication only. */
    fun hash(rawId: String): String {
        val currentSalt = salt
        val md = MessageDigest.getInstance("SHA-256")
        md.update(currentSalt)
        md.update(rawId.trim().uppercase().toByteArray(Charsets.UTF_8))
        val digest = md.digest()
        val sb = StringBuilder(16)
        for (i in 0 until 8) {
            sb.append("%02x".format(digest[i].toInt() and 0xFF))
        }
        return sb.toString()
    }
}
