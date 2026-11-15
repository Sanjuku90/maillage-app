package app.maillage

import java.security.MessageDigest

/** Session-only TOFU collision guard; this does not verify identity with the user. */
class IdentityPins(private val capacity: Int = 2_000) {
    private val pins = LinkedHashMap<String, ByteArray>()

    init { require(capacity > 0) }

    fun checkAndRemember(tag: String, publicKey: ByteArray): Boolean {
        val previous = pins[tag]
        if (previous != null) return MessageDigest.isEqual(previous, publicKey)
        if (pins.size >= capacity) pins.remove(pins.keys.first())
        pins[tag] = publicKey.copyOf()
        return true
    }

    fun keyFor(tag: String): ByteArray? = pins[tag]?.copyOf()

    fun clear() = pins.clear()
}
