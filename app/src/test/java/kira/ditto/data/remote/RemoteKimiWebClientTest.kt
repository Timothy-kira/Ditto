package kira.ditto.data.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class RemoteKimiWebClientTest {
    @Test
    fun mapsAssistantAndThinkingDeltas() {
        val text = mapRemoteEvent(
            JSONObject().put("type", "assistant.delta").put("delta", "Hi"),
        )
        assertEquals("assistant_text_delta", text?.first)
        assertEquals("Hi", text?.second?.optString("delta"))

        val thought = mapRemoteEvent(
            JSONObject()
                .put("type", "thinking.delta")
                .put("payload", JSONObject().put("delta", "plan")),
        )
        assertEquals("assistant_reasoning_delta", thought?.first)
        assertEquals("plan", thought?.second?.optString("delta"))
    }

    @Test
    fun mapsPrefixedEventTypes() {
        val text = mapRemoteEvent(
            JSONObject()
                .put("type", "event.assistant.delta")
                .put("payload", JSONObject().put("delta", "Hi")),
        )
        assertEquals("assistant_text_delta", text?.first)
        assertEquals("Hi", text?.second?.optString("delta"))

        assertEquals(
            "turn_ended",
            mapRemoteEvent(JSONObject().put("type", "event.turn.ended"))?.first,
        )
    }

    @Test
    fun extractsAssistantCatchUpForThisPrompt() {
        val data = JSONObject().put(
            "items",
            org.json.JSONArray()
                .put(
                    JSONObject()
                        .put("role", "assistant")
                        .put("prompt_id", "p-new")
                        .put("created_at", "2026-08-27T01:28:10.000Z")
                        .put(
                            "content",
                            org.json.JSONArray().put(
                                JSONObject().put("type", "text").put("text", "new reply"),
                            ),
                        ),
                )
                .put(
                    JSONObject()
                        .put("role", "assistant")
                        .put("prompt_id", "p-old")
                        .put("created_at", "2026-08-27T01:10:00.000Z")
                        .put(
                            "content",
                            org.json.JSONArray().put(
                                JSONObject().put("type", "text").put("text", "old reply"),
                            ),
                        ),
                ),
        )
        assertEquals("new reply", extractLatestAssistantMessage(data, "p-new").text)
        assertEquals(
            "new reply",
            extractLatestAssistantMessage(data, "", "2026-08-27T01:28:09.000Z").text,
        )
        assertEquals(
            "",
            extractLatestAssistantMessage(data, "p-missing", "2026-08-27T01:29:00.000Z").text,
        )
    }

    @Test
    fun mapsTurnLifecycle() {
        assertEquals(
            "assistant_request_start",
            mapRemoteEvent(JSONObject().put("type", "turn.started"))?.first,
        )
        assertEquals(
            "turn_ended",
            mapRemoteEvent(JSONObject().put("type", "turn.ended"))?.first,
        )
        assertNotNull(mapRemoteEvent(JSONObject().put("type", "tool.call.started").put("name", "bash")))
    }

    @Test
    fun parsesWorkspaceList() {
        val workspaces = parseRemoteWorkspaces(
            JSONObject().put(
                "items",
                org.json.JSONArray()
                    .put(
                        JSONObject()
                            .put("id", "wd_agent")
                            .put("name", "手机agent")
                            .put("root", "C:\\Users\\feng\\Desktop\\手机agent")
                            .put("last_opened_at", "2026-08-25T03:29:05.568Z"),
                    )
                    .put(
                        JSONObject()
                            .put("id", "wd_old")
                            .put("name", "old")
                            .put("root", "C:\\old")
                            .put("last_opened_at", "2026-07-01T00:00:00.000Z"),
                    ),
            ),
        )
        assertEquals("wd_agent", workspaces.first().id)
        assertEquals("手机agent", workspaces.first().name)
        assertEquals("C:\\Users\\feng\\Desktop\\手机agent", workspaces.first().root)
    }

    @Test
    fun parsesKimiWebModelList() {
        val models = parseRemoteModels(
            JSONObject().put(
                "items",
                org.json.JSONArray()
                    .put(
                        JSONObject()
                            .put("provider", "managed:kimi-code")
                            .put("model", "kimi-code/k3-256k")
                            .put("display_name", "K3-256k")
                            .put("max_context_size", 262144)
                            .put("capabilities", org.json.JSONArray().put("thinking"))
                            .put("support_efforts", org.json.JSONArray().put("low").put("high").put("max"))
                            .put("default_effort", "high"),
                    ),
            ),
        )
        assertEquals(1, models.size)
        assertEquals("kimi-code/k3-256k", models.first().id)
        assertEquals("K3-256k", models.first().displayName)
        assertEquals(listOf("low", "high", "max"), models.first().supportEfforts)
    }

    @Test
    fun parsesKimiWebPermissionConfig() {
        assertEquals(
            "auto",
            parseRemotePermissionMode(JSONObject().put("default_permission_mode", "auto")),
        )
        assertEquals(
            "default",
            parseRemotePermissionMode(JSONObject().put("default_permission_mode", "manual")),
        )
        assertEquals(
            "plan",
            parseRemotePermissionMode(
                JSONObject()
                    .put("default_permission_mode", "auto")
                    .put("default_plan_mode", true),
            ),
        )
        assertEquals(
            "yolo",
            parseRemotePermissionMode(JSONObject().put("yolo", true)),
        )
        val agent = JSONObject()
        applyRemotePermissionToAgentConfig(agent, "yolo")
        assertEquals("yolo", agent.optString("permission_mode"))
        assertEquals(false, agent.optBoolean("plan_mode"))
        val global = remoteGlobalConfigForPermission("plan")
        assertEquals(true, global.optBoolean("default_plan_mode"))
        assertEquals(false, global.has("default_permission_mode"))
    }
}
