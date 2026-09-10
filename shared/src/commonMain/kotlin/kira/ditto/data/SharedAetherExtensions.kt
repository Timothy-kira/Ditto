package kira.ditto.data

import kira.ditto.runtime.SharedPiBridgeClient
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

data class SharedAetherExtensionInfo(
    val id: String,
    val name: String,
    val path: String,
)

data class SharedAetherExtensionSurface(
    val id: String,
    val extensionId: String,
    val extensionName: String,
    val slot: String,
    val order: Int,
    val tree: JsonElement?,
)

data class SharedAetherExtensionComponent(
    val id: String,
    val extensionId: String,
    val extensionName: String,
    val target: String,
    val mode: String,
    val order: Int,
    val tree: JsonElement?,
)

data class SharedAetherExtensionComposerMenuItem(
    val id: String,
    val localId: String,
    val extensionId: String,
    val extensionName: String,
    val title: String,
    val subtitle: String,
    val icon: String,
    val order: Int,
    val action: String,
    val args: JsonObject,
    val selected: Boolean,
)

data class SharedAetherExtensionSettingsPage(
    val id: String,
    val localId: String,
    val extensionId: String,
    val extensionName: String,
    val title: String,
    val subtitle: String,
    val icon: String,
    val order: Int,
    val trailingIcon: String = "",
    val trailingAction: String = "",
    val trailingCategory: String = "",
    val trailingArgs: JsonObject = JsonObject(emptyMap()),
    val sections: List<JsonObject>,
    val categories: List<SharedAetherExtensionSettingsCategory> = emptyList(),
)

data class SharedAetherExtensionSettingsCategory(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: String,
    val order: Int,
    val trailingIcon: String = "",
    val trailingAction: String = "",
    val trailingCategory: String = "",
    val trailingArgs: JsonObject = JsonObject(emptyMap()),
    val hidden: Boolean = false,
    val sections: List<JsonObject>,
)

data class SharedAetherExtensionMessageType(
    val id: String,
    val type: String,
    val extensionId: String,
    val extensionName: String,
    val title: String,
    val icon: String,
)

data class SharedAetherExtensionCustomMessage(
    val id: String,
    val type: String,
    val extensionId: String,
    val tree: JsonElement?,
)

data class SharedAetherExtensionError(
    val path: String,
    val extensionId: String,
    val phase: String,
    val message: String,
)

data class SharedAetherExtensionNotification(
    val message: String,
    val level: String,
)

data class SharedPiExtensionUiRequest(
    val callId: String,
    val method: String,
    val title: String,
    val message: String = "",
    val placeholder: String = "",
    val options: List<String> = emptyList(),
)

data class SharedAetherExtensionSnapshot(
    val apiVersion: Int = 2,
    val version: Long = 0,
    val extensions: List<SharedAetherExtensionInfo> = emptyList(),
    val surfaces: List<SharedAetherExtensionSurface> = emptyList(),
    val components: List<SharedAetherExtensionComponent> = emptyList(),
    val composerMenuItems: List<SharedAetherExtensionComposerMenuItem> = emptyList(),
    val settings: List<SharedAetherExtensionSettingsPage> = emptyList(),
    val messageTypes: List<SharedAetherExtensionMessageType> = emptyList(),
    val customMessages: List<SharedAetherExtensionCustomMessage> = emptyList(),
    val eventNames: Set<String> = emptySet(),
    val errors: List<SharedAetherExtensionError> = emptyList(),
) {
    fun surfacesAt(slot: String): List<SharedAetherExtensionSurface> =
        surfaces.filter { it.slot == slot }.sortedWith(
            compareBy<SharedAetherExtensionSurface> { it.order }.thenBy { it.id }
        )

    fun componentsAt(target: String): List<SharedAetherExtensionComponent> =
        components.filter { it.target == target }.sortedWith(
            compareBy<SharedAetherExtensionComponent> { it.order }.thenBy { it.id }
        )
}

class SharedAetherExtensionManager(
    private val bridge: SharedPiBridgeClient,
    private val hostHandler: suspend (String, JsonObject) -> JsonObject,
) {
    private val mutex = Mutex()
    private val uiRequestMutex = Mutex()
    private val pendingUiRequests = ArrayDeque<SharedPiExtensionUiRequest>()
    private val _notifications = MutableSharedFlow<SharedAetherExtensionNotification>(
        extraBufferCapacity = 8,
    )
    private val _piUiRequest = MutableStateFlow<SharedPiExtensionUiRequest?>(null)
    var snapshot: SharedAetherExtensionSnapshot = SharedAetherExtensionSnapshot()
        private set
    var error: String = ""
        private set

    val notifications: SharedFlow<SharedAetherExtensionNotification> = _notifications.asSharedFlow()
    val piUiRequest: StateFlow<SharedPiExtensionUiRequest?> = _piUiRequest.asStateFlow()

    suspend fun refresh(
        context: JsonObject = JsonObject(emptyMap()),
    ): SharedAetherExtensionSnapshot = mutex.withLock {
        val response = bridge.getAetherExtensions(context, ::handleEvent)
        parseResponse(response)
    }

    suspend fun reload(
        context: JsonObject = JsonObject(emptyMap()),
    ): SharedAetherExtensionSnapshot = mutex.withLock {
        val response = bridge.reloadAetherExtensions(context, ::handleEvent)
        parseResponse(response)
    }

    suspend fun invokeAction(
        extensionId: String,
        action: String,
        args: JsonObject = JsonObject(emptyMap()),
        context: JsonObject = JsonObject(emptyMap()),
    ): SharedAetherExtensionSnapshot = mutex.withLock {
        val response = bridge.invokeAetherExtensionAction(
            extensionId = extensionId,
            action = action,
            args = args,
            context = context,
            onEvent = ::handleEvent,
        )
        parseResponse(response)
    }

    suspend fun dispatchEvent(
        event: String,
        data: JsonObject = JsonObject(emptyMap()),
        context: JsonObject = JsonObject(emptyMap()),
    ): JsonObject {
        if (event !in snapshot.eventNames) return JsonObject(emptyMap())
        return mutex.withLock {
            val response = bridge.dispatchAetherExtensionEvent(
                event = event,
                data = data,
                context = context,
                onEvent = ::handleEvent,
            )
            response.objectOrNull("snapshot")?.let {
                snapshot = parseSharedAetherExtensionSnapshot(it)
            }
            response
        }
    }

    suspend fun subscribe(
        onInvalidated: suspend () -> Unit,
    ) {
        bridge.subscribeAetherExtensions { event, payload ->
            handleEvent(event, payload)
            if (event == "aether_invalidated") onInvalidated()
        }
    }

    suspend fun respondToPiExtensionUiRequest(
        callId: String,
        value: JsonElement?,
    ) {
        val request = uiRequestMutex.withLock {
            val current = _piUiRequest.value
            if (current?.callId != callId) return
            _piUiRequest.value = pendingUiRequests.removeFirstOrNull()
            current
        }
        bridge.sendAetherHostResult(
            callId = request.callId,
            result = JsonObject(mapOf("value" to (value ?: JsonNull))),
        )
    }

    private suspend fun handleEvent(event: String, payload: JsonObject) {
        when (event) {
            "aether_notification" -> _notifications.emit(
                SharedAetherExtensionNotification(
                    message = payload.string("message"),
                    level = payload.string("level").ifBlank { "info" },
                )
            )
            "aether_host_call" -> {
                val callId = payload.string("call_id")
                val method = payload.string("method")
                val args = payload.objectOrNull("args") ?: JsonObject(emptyMap())
                if (method == "pi_extension_notify") {
                    _notifications.emit(
                        SharedAetherExtensionNotification(
                            message = args.string("message"),
                            level = args.string("type").ifBlank { "info" },
                        )
                    )
                    bridge.sendAetherHostResult(
                        callId = callId,
                        result = JsonObject(mapOf("notified" to JsonPrimitive(true))),
                    )
                    return
                }
                if (method in SharedPiExtensionInteractiveUiMethods) {
                    val request = SharedPiExtensionUiRequest(
                        callId = callId,
                        method = method,
                        title = args.string("title"),
                        message = args.string("message"),
                        placeholder = args.string("placeholder"),
                        options = (args["options"] as? JsonArray)
                            .orEmpty()
                            .mapNotNull { option ->
                                (option as? JsonPrimitive)
                                    ?.contentOrNull
                                    ?.takeIf(String::isNotBlank)
                            },
                    )
                    uiRequestMutex.withLock {
                        if (_piUiRequest.value == null) {
                            _piUiRequest.value = request
                        } else {
                            pendingUiRequests.addLast(request)
                        }
                    }
                    return
                }
                val result = runCatching { hostHandler(method, args) }
                bridge.sendAetherHostResult(
                    callId = callId,
                    result = result.getOrDefault(JsonObject(emptyMap())),
                    error = result.exceptionOrNull()?.message.orEmpty(),
                )
            }
        }
    }

    private fun parseResponse(response: JsonObject): SharedAetherExtensionSnapshot {
        snapshot = parseSharedAetherExtensionSnapshot(response.objectOrNull("snapshot"))
        error = (response["errors"] as? JsonArray)
            .orEmpty()
            .mapNotNull { (it as? JsonObject)?.string("error")?.takeIf(String::isNotBlank) }
            .take(3)
            .joinToString("; ")
        return snapshot
    }
}

private val SharedPiExtensionInteractiveUiMethods = setOf(
    "pi_extension_select",
    "pi_extension_confirm",
    "pi_extension_input",
)

internal fun parseSharedAetherExtensionSnapshot(
    json: JsonObject?,
): SharedAetherExtensionSnapshot {
    if (json == null) return SharedAetherExtensionSnapshot()
    return SharedAetherExtensionSnapshot(
        apiVersion = json.int("api_version") ?: 2,
        version = json.long("version") ?: 0,
        extensions = json.objects("extensions").map { item ->
            SharedAetherExtensionInfo(
                id = item.string("id"),
                name = item.string("name"),
                path = item.string("path"),
            )
        },
        surfaces = json.objects("surfaces").map { item ->
            SharedAetherExtensionSurface(
                id = item.string("id"),
                extensionId = item.string("extension_id"),
                extensionName = item.string("extension_name"),
                slot = item.string("slot"),
                order = item.int("order") ?: 0,
                tree = item["tree"],
            )
        },
        components = json.objects("components").map { item ->
            SharedAetherExtensionComponent(
                id = item.string("id"),
                extensionId = item.string("extension_id"),
                extensionName = item.string("extension_name"),
                target = item.string("target"),
                mode = item.string("mode").ifBlank { "wrap" },
                order = item.int("order") ?: 0,
                tree = item["tree"],
            )
        },
        composerMenuItems = json.objects("composer_menu_items").map { item ->
            SharedAetherExtensionComposerMenuItem(
                id = item.string("id"),
                localId = item.string("local_id"),
                extensionId = item.string("extension_id"),
                extensionName = item.string("extension_name"),
                title = item.string("title"),
                subtitle = item.string("subtitle"),
                icon = item.string("icon").ifBlank { "extension" },
                order = item.int("order") ?: 0,
                action = item.string("action"),
                args = item["args"] as? JsonObject ?: JsonObject(emptyMap()),
                selected = item.boolean("selected"),
            )
        },
        settings = json.objects("settings").map { item ->
            SharedAetherExtensionSettingsPage(
                id = item.string("id"),
                localId = item.string("local_id"),
                extensionId = item.string("extension_id"),
                extensionName = item.string("extension_name"),
                title = item.string("title"),
                subtitle = item.string("subtitle"),
                icon = item.string("icon").ifBlank { "settings" },
                order = item.int("order") ?: 0,
                trailingIcon = item.string("trailing_icon").ifBlank { item.string("trailingIcon") },
                trailingAction = item.string("trailing_action").ifBlank { item.string("trailingAction") },
                trailingCategory = item.string("trailing_category").ifBlank { item.string("trailingCategory") },
                trailingArgs = item["trailing_args"] as? JsonObject
                    ?: item["trailingArgs"] as? JsonObject ?: JsonObject(emptyMap()),
                sections = item.objects("sections"),
                categories = item.objects("categories").map { category ->
                    SharedAetherExtensionSettingsCategory(
                        id = category.string("id"),
                        title = category.string("title"),
                        subtitle = category.string("subtitle"),
                        icon = category.string("icon").ifBlank { "settings" },
                        order = category.int("order") ?: 0,
                        trailingIcon = category.string("trailing_icon").ifBlank { category.string("trailingIcon") },
                        trailingAction = category.string("trailing_action").ifBlank { category.string("trailingAction") },
                        trailingCategory = category.string("trailing_category").ifBlank { category.string("trailingCategory") },
                        trailingArgs = category["trailing_args"] as? JsonObject
                            ?: category["trailingArgs"] as? JsonObject ?: JsonObject(emptyMap()),
                        hidden = category["hidden"]?.jsonPrimitive?.booleanOrNull ?: false,
                        sections = category.objects("sections"),
                    )
                }.sortedWith(compareBy<SharedAetherExtensionSettingsCategory> { it.order }.thenBy { it.id }),
            )
        },
        messageTypes = json.objects("message_types").map { item ->
            SharedAetherExtensionMessageType(
                id = item.string("id"),
                type = item.string("type"),
                extensionId = item.string("extension_id"),
                extensionName = item.string("extension_name"),
                title = item.string("title"),
                icon = item.string("icon").ifBlank { "extension" },
            )
        },
        customMessages = json.objects("custom_messages").map { item ->
            SharedAetherExtensionCustomMessage(
                id = item.string("id"),
                type = item.string("type"),
                extensionId = item.string("extension_id"),
                tree = item["tree"],
            )
        },
        eventNames = (json["event_names"] as? JsonArray)
            .orEmpty()
            .mapNotNull { it.jsonPrimitive.contentOrNull }
            .toSet(),
        errors = json.objects("errors").map { item ->
            SharedAetherExtensionError(
                path = item.string("path"),
                extensionId = item.string("extension_id"),
                phase = item.string("phase"),
                message = item.string("error"),
            )
        },
    )
}

private fun JsonObject.string(name: String): String =
    get(name)?.jsonPrimitive?.contentOrNull.orEmpty()

private fun JsonObject.int(name: String): Int? =
    get(name)?.jsonPrimitive?.intOrNull

private fun JsonObject.long(name: String): Long? =
    get(name)?.jsonPrimitive?.longOrNull

private fun JsonObject.boolean(name: String): Boolean =
    get(name)?.jsonPrimitive?.booleanOrNull ?: false

private fun JsonObject.objectOrNull(name: String): JsonObject? = get(name) as? JsonObject

private fun JsonObject.objects(name: String): List<JsonObject> =
    (get(name) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
