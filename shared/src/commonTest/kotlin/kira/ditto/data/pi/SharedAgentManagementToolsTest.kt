package kira.ditto.data.pi

import kira.ditto.data.AppSettings
import kira.ditto.data.SharedAetherExtensionSettingsPage
import kira.ditto.data.SharedAetherExtensionSnapshot
import kira.ditto.data.SharedSkillManager
import kira.ditto.runtime.MultiplatformLocalRuntime
import kira.ditto.runtime.RuntimeFileSystem
import kira.ditto.runtime.RuntimePiBridgeTransport
import kira.ditto.runtime.RuntimeProcess
import kira.ditto.runtime.RuntimeProcessSpec
import kira.ditto.runtime.RuntimeSetupProgress
import kira.ditto.runtime.SharedPiBridgeClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class SharedAgentManagementToolsTest {
    @Test
    fun exposesPortableAndroidParityToolsOnly() {
        val fixture = AgentManagementFixture()
        val names = fixture.tools.definitions.mapNotNull {
            (it as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
        }.toSet()

        assertEquals(
            setOf(
                "aether_config_get",
                "aether_config_set",
                "aether_skill_manage",
                "aether_extension_manage",
                "aether_developer_manage",
            ),
            names,
        )
    }

    @Test
    fun compositeRoutesSessionAwareDynamicTools() = runTest {
        val dynamic = object : SharedSessionAwareHostToolExecutor {
            override val definitions = JsonArray(emptyList())
            override fun definitions(sessionId: String): JsonArray = JsonArray(listOf(buildJsonObject {
                put("name", "mcp__server__tool")
            }))

            override suspend fun execute(name: String, arguments: JsonObject): SharedHostToolResult =
                SharedHostToolResult("{\"ok\":true}")
        }
        val result = SharedCompositeHostTools(listOf(dynamic)).execute(
            "mcp__server__tool",
            JsonObject(mapOf("__aether_session_id" to JsonPrimitive("session"))),
        )

        assertFalse(result.isError)
        assertEquals("{\"ok\":true}", result.outputJson)
    }

    @Test
    fun updatesPortableReliabilitySettings() = runTest {
        val fixture = AgentManagementFixture()

        val result = fixture.tools.execute(
            "aether_config_set",
            buildJsonObject {
                put("category", "reliability")
                put("settings", buildJsonObject {
                    put("llm_inactivity_reconnect_timeout_seconds", 5)
                    put("auto_clean_old_command_history", false)
                    put("old_command_history_retention_hours", 500)
                })
            },
        )

        assertFalse(result.isError)
        assertEquals(30, fixture.settings.llmInactivityReconnectTimeoutSeconds)
        assertFalse(fixture.settings.autoCleanOldCommandHistory)
        assertEquals(168, fixture.settings.oldCommandHistoryRetentionHours)
    }

    @Test
    fun readsNativeExtensionSettingsPages() = runTest {
        val fixture = AgentManagementFixture()

        val result = fixture.tools.execute(
            "aether_config_get",
            buildJsonObject { put("categories", JsonArray(listOf(JsonPrimitive("extensions")))) },
        )

        assertFalse(result.isError)
        val page = Json.parseToJsonElement(result.outputJson).jsonObject["extensions"]
            ?.let { it as JsonArray }
            ?.single()
            ?.jsonObject
            ?: error("Missing extension settings page")
        assertEquals("demo", page["extension_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("preferences", page["settings_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals(
            true,
            page["sections"]?.let { it as JsonArray }?.single()?.jsonObject
                ?.get("settings")?.let { it as JsonArray }?.first()?.jsonObject
                ?.get("value")?.jsonPrimitive?.booleanOrNull,
        )
    }

    @Test
    fun updatesNativeExtensionSettingsThroughRegisteredActions() = runTest {
        val fixture = AgentManagementFixture()

        val result = fixture.tools.execute(
            "aether_config_set",
            buildJsonObject {
                put("category", "extensions")
                put("settings", buildJsonObject {
                    put("extension_id", "demo")
                    put("settings_id", "preferences")
                    put("values", buildJsonObject {
                        put("enabled", false)
                        put("mode", "quality")
                    })
                })
            },
        )

        assertFalse(result.isError)
        assertEquals(
            listOf(
                ExtensionSettingUpdate("demo", "preferences", "enabled", JsonPrimitive(false)),
                ExtensionSettingUpdate("demo", "preferences", "mode", JsonPrimitive("quality")),
            ),
            fixture.extensionSettingUpdates,
        )
        val values = Json.parseToJsonElement(result.outputJson).jsonObject["values"]?.jsonObject
            ?: error("Missing updated extension values")
        assertEquals(false, values["enabled"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("quality", values["mode"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun rejectsNonValueNativeExtensionControls() = runTest {
        val fixture = AgentManagementFixture()

        val result = fixture.tools.execute(
            "aether_config_set",
            buildJsonObject {
                put("category", "extensions")
                put("settings", buildJsonObject {
                    put("extension_id", "demo")
                    put("settings_id", "preferences")
                    put("values", buildJsonObject {
                        put("enabled", false)
                        put("reset", "now")
                    })
                })
            },
        )

        assertTrue(result.isError)
        assertTrue(result.outputJson.contains("non-writable type 'button'"))
        assertTrue(fixture.extensionSettingUpdates.isEmpty())
    }

    @Test
    fun cancellationIsNotConvertedIntoToolFailure() = runTest {
        val fixture = AgentManagementFixture(
            updateSettings = { throw CancellationException("cancelled") },
        )

        assertFailsWith<CancellationException> {
            fixture.tools.execute(
                "aether_config_set",
                buildJsonObject {
                    put("category", "general")
                    put("settings", buildJsonObject { put("theme_mode", "dark") })
                },
            )
        }
    }
}

private class AgentManagementFixture(
    updateSettings: (suspend (AppSettings) -> Unit)? = null,
) {
    val runtime = AgentManagementFakeRuntime()
    private val bridge = SharedPiBridgeClient(RuntimePiBridgeTransport(runtime))
    var settings = AppSettings()
    var extensionSnapshot = testExtensionSnapshot()
    val extensionSettingUpdates = mutableListOf<ExtensionSettingUpdate>()
    val tools = SharedAgentManagementTools(
        runtime = runtime,
        bridge = bridge,
        skillManager = SharedSkillManager(runtime),
        settings = { settings },
        updateSettings = updateSettings ?: { settings = it },
        currentSessionId = { "test-session" },
        extensionSettings = { extensionSnapshot },
        updateExtensionSetting = { extensionId, settingsId, settingId, value ->
            extensionSettingUpdates += ExtensionSettingUpdate(
                extensionId,
                settingsId,
                settingId,
                value,
            )
            extensionSnapshot = extensionSnapshot.withSettingValue(settingId, value)
            extensionSnapshot
        },
    )
}

private data class ExtensionSettingUpdate(
    val extensionId: String,
    val settingsId: String,
    val settingId: String,
    val value: JsonElement,
)

private fun testExtensionSnapshot(): SharedAetherExtensionSnapshot = SharedAetherExtensionSnapshot(
    settings = listOf(
        SharedAetherExtensionSettingsPage(
            id = "demo:preferences",
            localId = "preferences",
            extensionId = "demo",
            extensionName = "Demo",
            title = "Preferences",
            subtitle = "Demo extension settings",
            icon = "settings",
            order = 0,
            sections = listOf(buildJsonObject {
                put("title", "General")
                put("settings", JsonArray(listOf(
                    buildJsonObject {
                        put("id", "enabled")
                        put("label", "Enabled")
                        put("type", "toggle")
                        put("value", true)
                    },
                    buildJsonObject {
                        put("id", "mode")
                        put("label", "Mode")
                        put("type", "select")
                        put("value", "fast")
                    },
                    buildJsonObject {
                        put("id", "reset")
                        put("label", "Reset")
                        put("type", "button")
                    },
                )))
            }),
        ),
    ),
)

private fun SharedAetherExtensionSnapshot.withSettingValue(
    settingId: String,
    value: JsonElement,
): SharedAetherExtensionSnapshot = copy(
    settings = settings.map { page ->
        page.copy(sections = page.sections.map { section ->
            JsonObject(section + ("settings" to JsonArray(
                (section["settings"] as? JsonArray).orEmpty().map { element ->
                    val setting = element.jsonObject
                    if (setting["id"]?.jsonPrimitive?.contentOrNull == settingId) {
                        JsonObject(setting + ("value" to value))
                    } else {
                        setting
                    }
                },
            )))
        })
    },
)

private class AgentManagementFakeRuntime : MultiplatformLocalRuntime {
    override val homeDirectory = "/root"
    override val workspaceRoot = "/workspace"
    val readPaths = mutableListOf<String>()
    val files = mutableMapOf<String, ByteArray>()

    override val fileSystem: RuntimeFileSystem = object : RuntimeFileSystem {
        override suspend fun exists(path: String): Boolean = path in files
        override suspend fun createDirectories(path: String) = Unit
        override suspend fun read(path: String): ByteArray {
            readPaths += path
            return files[path] ?: error("Unexpected read: $path")
        }
        override suspend fun write(path: String, content: ByteArray, executable: Boolean) = Unit
        override suspend fun remove(path: String, recursive: Boolean) = Unit
        override suspend fun bindHostDirectory(hostPath: String, guestPath: String, readOnly: Boolean) = Unit
    }

    override suspend fun initialize(onProgress: (RuntimeSetupProgress) -> Unit) = Unit

    override suspend fun startProcess(spec: RuntimeProcessSpec): RuntimeProcess =
        error("Unexpected process: ${spec.executable}")
}
