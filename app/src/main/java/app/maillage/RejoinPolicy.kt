package app.maillage

/** Do not request permissions or turn Bluetooth on automatically. */
internal object RejoinPolicy {
    fun shouldRejoin(hasSavedChannel: Boolean, hasPermissions: Boolean, bluetoothEnabled: Boolean): Boolean =
        hasSavedChannel && hasPermissions && bluetoothEnabled
}
