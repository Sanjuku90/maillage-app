package app.maillage

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

/** Regression coverage, not a substitute for independent Noise vectors or a security audit. */
class NoiseTest {
    @Test fun channelDerivationMatchesReferenceVector() {
        val key = Kdf.argon2id("test-password")
        assertEquals("37eba482a65562838255eae8fca8fa6107c5ab9aa6ce018b328a190764aec8d2",
            key.joinToString("") { "%02x".format(it) })
    }

    @Test fun handshakeAndTamperChecks() {
        val result = SelfTest.run()
        assertTrue(result, result.startsWith("✅"))
    }

    @Test fun negativeNonceAndTruncatedFramesAreRejected() {
        val key = ByteArray(32) { it.toByte() }
        val receiver = Transport(key, key)
        val negative = ByteBuffer.allocate(24).putLong(-1L).put(ByteArray(16)).array()
        assertTrue(runCatching { receiver.dec(negative) }.isFailure)
        assertTrue(runCatching { receiver.dec(ByteArray(23)) }.isFailure)
    }

    @Test fun tamperingAndReplaysAreRejected() {
        val key = ByteArray(32) { (it + 1).toByte() }
        val sender = Transport(key, key)
        val receiver = Transport(key, key)
        val ciphertext = sender.enc("message".toByteArray())
        assertEquals("message", String(receiver.dec(ciphertext)))
        assertTrue(runCatching { receiver.dec(ciphertext) }.isFailure)
        val tampered = sender.enc("second".toByteArray())
        tampered[tampered.lastIndex] = (tampered.last().toInt() xor 1).toByte()
        assertTrue(runCatching { receiver.dec(tampered) }.isFailure)
    }
}
