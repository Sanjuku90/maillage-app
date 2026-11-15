package app.maillage

import org.junit.Assert.*
import org.junit.Test

class BleWriteGateTest {
    @Test fun oneWriteAtATimeAndOldTimeoutCannotKillNewWrite() {
        val gate = BleWriteGate()
        val first = gate.begin()!!
        assertNull(gate.begin())
        assertTrue(gate.complete(true))
        val second = gate.begin()!!
        assertFalse(gate.expire(first))
        assertTrue(gate.inFlight)
        assertTrue(gate.expire(second))
        assertTrue(gate.failed)
        assertFalse(gate.complete(true))
        assertNull(gate.begin())
    }
    @Test fun failedWriteQuarantinesPeer() {
        val gate = BleWriteGate()
        gate.begin()
        assertTrue(gate.complete(false))
        assertTrue(gate.failed)
        assertFalse(gate.complete(true))
        assertNull(gate.begin())
    }
    @Test fun explicitFailureDoesNotAcceptLateCallbacks() {
        val gate = BleWriteGate()
        val ticket = gate.begin()!!
        gate.fail()
        assertFalse(gate.expire(ticket))
        assertFalse(gate.complete(true))
        assertTrue(gate.failed)
    }
}
