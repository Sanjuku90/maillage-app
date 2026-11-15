package app.maillage

import org.junit.Assert.*
import org.junit.Test

class ContactVerificationTest {
    @Test fun trustRequiresExplicitSavedMatch() {
        assertEquals(ContactVerification.UNVERIFIED, ContactVerificationPolicy.evaluate(true, null, "new"))
        assertEquals(ContactVerification.VERIFIED, ContactVerificationPolicy.evaluate(true, "same", "same"))
        assertEquals(ContactVerification.CHANGED, ContactVerificationPolicy.evaluate(true, "old", "new"))
    }
    @Test fun missingKeyOrStorageCannotBeTreatedAsVerified() {
        assertEquals(ContactVerification.UNAVAILABLE, ContactVerificationPolicy.evaluate(false, null, "key"))
        assertEquals(ContactVerification.UNAVAILABLE, ContactVerificationPolicy.evaluate(true, "key", null))
        assertEquals(ContactVerification.UNAVAILABLE, ContactVerificationPolicy.evaluate(false, "key", "key"))
    }
    @Test fun aDetectedChangeRemainsBlockedEvenIfTheOldKeyReappears() {
        assertEquals(ContactVerification.CHANGED, ContactVerificationPolicy.evaluate(true, "old", "old", changedPreviously = true))
        assertFalse(ContactVerificationPolicy.permitsPrivateMessages(ContactVerification.CHANGED))
    }
    @Test fun changedOrUnavailableBlocksPrivateSessions() {
        assertTrue(ContactVerificationPolicy.permitsPrivateMessages(ContactVerification.UNVERIFIED))
        assertTrue(ContactVerificationPolicy.permitsPrivateMessages(ContactVerification.VERIFIED))
        assertFalse(ContactVerificationPolicy.permitsPrivateMessages(ContactVerification.CHANGED))
        assertFalse(ContactVerificationPolicy.permitsPrivateMessages(ContactVerification.UNAVAILABLE))
    }
}
