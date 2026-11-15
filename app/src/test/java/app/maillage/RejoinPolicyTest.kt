package app.maillage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RejoinPolicyTest {
    @Test fun rejoinOnlyWhenAllPrerequisitesArePresent() {
        assertTrue(RejoinPolicy.shouldRejoin(true, true, true))
        assertFalse(RejoinPolicy.shouldRejoin(false, true, true))
        assertFalse(RejoinPolicy.shouldRejoin(true, false, true))
        assertFalse(RejoinPolicy.shouldRejoin(true, true, false))
        assertFalse(RejoinPolicy.shouldRejoin(false, false, false))
    }
}
