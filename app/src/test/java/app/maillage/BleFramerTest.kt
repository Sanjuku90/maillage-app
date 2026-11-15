package app.maillage

import org.junit.Assert.*
import org.junit.Test

class BleFramerTest {
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    @Test fun matchesDesktopFixtureAtMinimumMtu() {
        val payload = "abcdefghijklmnopqrstuvwxyz".toByteArray()
        val parts = BleFramer.fragment(payload, 20, 0x01020304)
        assertEquals(3, parts.size)
        assertEquals("4d460102030400036162636465666768696a6b6c", parts[0].hex())
        assertEquals("4d460102030401036d6e6f707172737475767778", parts[1].hex())
        assertEquals("4d46010203040203797a", parts[2].hex())
    }

    @Test fun validatesLimitsAndUnfragmentedFrames() {
        assertTrue(BleFramer.fragment(ByteArray(521), 20, 1).isEmpty())
        assertTrue(BleFramer.fragment(ByteArray(30), 19, 1).isEmpty())
        assertArrayEquals("short".toByteArray(), BleFramer.fragment("short".toByteArray(), 20, 1)[0])
    }
}
