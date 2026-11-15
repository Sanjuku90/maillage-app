package app.maillage

import org.junit.Assert.*
import org.junit.Test

class IdentityPinsTest {
    @Test fun sameShortTagWithDifferentSigningKeyIsRejected() {
        val pins = IdentityPins()
        val first = ByteArray(65) { 1 }
        assertTrue(pins.checkAndRemember("123456", first))
        assertTrue(pins.checkAndRemember("123456", first.copyOf()))
        assertFalse(pins.checkAndRemember("123456", ByteArray(65) { 2 }))
        assertTrue(pins.checkAndRemember("123456", first))
    }

    @Test fun storesCopyAndBoundsMemory() {
        val pins = IdentityPins(1)
        val key = byteArrayOf(1, 2)
        assertTrue(pins.checkAndRemember("a", key))
        key[0] = 9
        assertFalse(pins.checkAndRemember("a", key))
        assertTrue(pins.checkAndRemember("b", key))
        assertTrue(pins.checkAndRemember("a", key)) // old entry evicted
    }

    @Test fun exportedKeyCannotMutateThePin() {
        val pins = IdentityPins()
        val key = ByteArray(65) { 7 }
        assertTrue(pins.checkAndRemember("peer", key))
        val exported = pins.keyFor("peer")!!
        exported[0] = 0
        assertTrue(pins.checkAndRemember("peer", key))
        assertNull(pins.keyFor("unknown"))
    }
}
