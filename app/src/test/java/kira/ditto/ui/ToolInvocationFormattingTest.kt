package kira.ditto.ui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolInvocationFormattingTest {
    @Test
    fun mcpContentArrayIsShownInsteadOfNoOutput() {
        val output = JSONObject()
            .put(
                "content",
                JSONArray().put(
                    JSONObject().put("type", "text").put("text", """{"now":{"temp":"26","text":"晴"}}"""),
                ),
            )
            .put("structuredContent", JSONObject().put("now", JSONObject().put("temp", "26")))
            .put("isError", false)

        assertEquals(
            """{"now":{"temp":"26","text":"晴"}}""",
            toolInvocationResultText(output, output.toString(), "No output"),
        )
    }

    @Test
    fun emptyUnknownObjectFallsBackToPrettyJson() {
        val output = JSONObject().put("city", "上海").put("temp", "26")
        val text = toolInvocationResultText(output, output.toString(), "No output")
        assert(text.contains("上海"))
        assert(text.contains("26"))
    }
}
