package app.maillage

import org.junit.Assert.*
import org.junit.Test

class IdentityFingerprintTest {
    @Test fun matchesSha256ReferenceAndChangesWithEitherKey() {
        val signing = ByteArray(65) { it.toByte() }
        val noise = ByteArray(32) { it.toByte() }
        val fingerprint = IdentityFingerprint.of(signing, noise)
        assertEquals("fcb9 3b79 9c71 ce6a d4dc daf2 c142 bf90 58c4 f690 2d2f 8f5e 426e 00f2 6a0f 1795", fingerprint)
        val alteredSigning = signing.copyOf().also { it[0] = 99 }
        val alteredNoise = noise.copyOf().also { it[0] = 99 }
        assertNotEquals(fingerprint, IdentityFingerprint.of(alteredSigning, noise))
        assertNotEquals(fingerprint, IdentityFingerprint.of(signing, alteredNoise))
    }
    @Test fun rejectsMalformedKeys() {
        assertTrue(runCatching { IdentityFingerprint.of(ByteArray(64), ByteArray(32)) }.isFailure)
        assertTrue(runCatching { IdentityFingerprint.of(ByteArray(65), ByteArray(31)) }.isFailure)
    }
}
