package kira.ditto.data

object UpaPluginHostState {
    @Volatile
    var disabledUiPlugins: Set<String> = emptySet()
        private set

    @Volatile
    var revokedPermissions: Map<String, Set<String>> = emptyMap()
        private set

    @Volatile
    var mcpBindings: Map<String, Set<String>> = emptyMap()
        private set

    fun publish(settings: AppSettings) {
        disabledUiPlugins = settings.upaDisabledUiPlugins
        revokedPermissions = settings.upaRevokedPermissions.mapValues { it.value.toSet() }
        mcpBindings = settings.upaMcpBindings.mapValues { it.value.toSet() }
    }

    fun rendersUi(pluginId: String, globalOn: Boolean = true): Boolean =
        globalOn && pluginId !in disabledUiPlugins

    fun allows(pluginId: String, wire: String): Boolean =
        wire !in revokedPermissions[pluginId].orEmpty()
}
