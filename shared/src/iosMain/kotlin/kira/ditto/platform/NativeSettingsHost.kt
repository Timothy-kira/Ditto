package kira.ditto.platform

/**
 * Optional host for a platform-native Settings surface. Android leaves this null and keeps using
 * the existing Compose implementation.
 */
interface NativeSettingsHost {
    fun publishSnapshot(snapshotJson: String)
    fun setCommandHandler(handler: NativeSettingsCommandHandler?)
    fun openSettings(): Boolean
}

interface NativeSettingsCommandHandler {
    fun handle(command: String, payloadJson: String)
}
