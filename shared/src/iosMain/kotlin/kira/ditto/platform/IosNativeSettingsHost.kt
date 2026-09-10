package kira.ditto.platform

interface IosNativeSettingsListener {
    fun onSnapshotChanged(snapshotJson: String)
    fun onPresentSettings()
}

object IosNativeSettingsHost : NativeSettingsHost {
    private var listener: IosNativeSettingsListener? = null
    private var commandHandler: NativeSettingsCommandHandler? = null

    var snapshotJson: String = "{}"
        private set

    fun setListener(listener: IosNativeSettingsListener?) {
        this.listener = listener
        listener?.onSnapshotChanged(snapshotJson)
    }

    fun perform(command: String, payloadJson: String = "{}") {
        commandHandler?.handle(command, payloadJson)
    }

    override fun publishSnapshot(snapshotJson: String) {
        if (snapshotJson == this.snapshotJson) return
        this.snapshotJson = snapshotJson
        listener?.onSnapshotChanged(snapshotJson)
    }

    override fun setCommandHandler(handler: NativeSettingsCommandHandler?) {
        commandHandler = handler
    }

    override fun openSettings(): Boolean {
        val current = listener ?: return false
        current.onPresentSettings()
        return true
    }
}
