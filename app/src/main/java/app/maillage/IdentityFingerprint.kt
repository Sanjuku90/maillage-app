package app.maillage

import java.security.MessageDigest

/** Human-comparable fingerprint for the device signing key and Noise static key. */
internal object IdentityFingerprint {
    fun of(signingKey: ByteArray, noiseKey: ByteArray): String {
        require(signingKey.size == 65 && noiseKey.size == 32) { "Clé d’identité invalide" }
        val md = MessageDigest.getInstance("SHA-256")
        md.update("maillage-identity-v1".toByteArray(Charsets.US_ASCII))
        md.update(signingKey)
        md.update(noiseKey)
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }.chunked(4).joinToString(" ")
    }
}
