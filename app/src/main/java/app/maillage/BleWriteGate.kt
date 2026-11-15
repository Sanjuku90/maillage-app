package app.maillage

/** One pending write per peer. Late callbacks never recover a failed connection. */
internal class BleWriteGate {
    var inFlight: Boolean = false
        private set
    var failed: Boolean = false
        private set
    private var generation = 0L

    fun begin(): Long? {
        if (inFlight || failed) return null
        inFlight = true
        generation += 1
        return generation
    }

    fun complete(success: Boolean): Boolean {
        if (!inFlight || failed) return false
        inFlight = false
        if (!success) failed = true
        return true
    }

    fun expire(ticket: Long): Boolean {
        if (!inFlight || failed || ticket != generation) return false
        fail()
        return true
    }

    fun fail() {
        inFlight = false
        failed = true
    }
}
