package kira.ditto.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpaChatOutputTest {
    @Test
    fun recognizesEscapedUpaInsideMcpText() {
        val raw = """{"content":[{"type":"text","text":"{\"_upa\":{\"pluginId\":\"example.plugin.card\",\"present\":true}}"}]}"""
        assertTrue(looksLikeUpaChatOutput(raw))
        val parsed = parseUpaToolOutput(raw)
        assertNotNull(parsed)
        assertEquals(
            "example.plugin.card",
            parsed!!.optJSONObject("_upa")?.optString("pluginId"),
        )
    }

    @Test
    fun ignoresPoiPayloadWithoutUpaEnvelope() {
        val raw = """{"pois":[{"name":"蜜雪冰城","location":"120.1,30.2","address":"湖滨"}]}"""
        assertFalse(looksLikeUpaChatOutput(raw))
        assertNull(parseUpaToolOutput(raw))
    }

    @Test
    fun upaFieldsMapKeepsPlacesStoredAsJsonArray() {
        val fields = org.json.JSONObject().put(
            "places",
            org.json.JSONArray().put(org.json.JSONObject().put("name", "莱蒂尔")),
        )
        val mapped = upaFieldsMap(fields)
        assertTrue(mapped.getValue("places").contains("莱蒂尔"))
        val parsed = parseUpaToolOutput(
            """{"_upa":{"pluginId":"example.plugin.card","templateId":"card","present":true,"fields":{"places":[{"name":"莱蒂尔","address":"某路"}]}}}""",
        )
        assertNotNull(parsed)
        val extracted = parsed!!.optJSONObject("_upa")?.optJSONObject("fields")
        assertTrue(jsonStringOrArray(extracted!!, "places").contains("莱蒂尔"))
    }

    @Test
    fun parsesKimiExceededPreviewWithCompleteJson() {
        val json = """{"_upa":{"pluginId":"example.plugin.card","templateId":"card","present":true,"fields":{"query":"火锅","places":[{"name":"莱蒂尔","address":"某路"}]}}}"""
        val raw = "Tool output exceeded 50000 characters; showing a preview only...\n\n$json"
        assertTrue(looksLikeUpaChatOutput(raw))
        val parsed = parseUpaToolOutput(raw)
        assertNotNull(parsed)
        val fields = parsed!!.optJSONObject("_upa")?.optJSONObject("fields")
        assertTrue(jsonStringOrArray(fields!!, "places").contains("莱蒂尔"))
    }
}
