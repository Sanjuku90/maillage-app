package app.maillage

/** A comparison is only trusted after the user explicitly confirms it out of band. */
internal enum class ContactVerification { UNVERIFIED, VERIFIED, CHANGED, UNAVAILABLE }

internal object ContactVerificationPolicy {
    fun evaluate(storageAvailable: Boolean, saved: String?, observed: String?, changedPreviously: Boolean = false): ContactVerification = when {
        !storageAvailable || observed == null -> ContactVerification.UNAVAILABLE
        saved == null -> ContactVerification.UNVERIFIED
        changedPreviously -> ContactVerification.CHANGED
        saved == observed -> ContactVerification.VERIFIED
        else -> ContactVerification.CHANGED
    }

    fun permitsPrivateMessages(state: ContactVerification): Boolean =
        state == ContactVerification.UNVERIFIED || state == ContactVerification.VERIFIED
}
