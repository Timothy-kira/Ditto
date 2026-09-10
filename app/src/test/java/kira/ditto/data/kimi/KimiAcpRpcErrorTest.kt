package kira.ditto.data.kimi

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class KimiAcpRpcErrorTest {
    @Test
    fun blankMessageFallsBack() {
        assertEquals("Kimi ACP request failed.", formatKimiAcpRpcError(JSONObject()))
    }

    @Test
    fun internalErrorKeepsBareMessageWithoutData() {
        assertEquals(
            "Internal error",
            formatKimiAcpRpcError(JSONObject().put("message", "Internal error")),
        )
    }

    @Test
    fun internalErrorAppendsDetailsFromDataObject() {
        assertEquals(
            "Internal error: EACCES: permission denied, link 'a' -> 'b'",
            formatKimiAcpRpcError(
                JSONObject()
                    .put("message", "Internal error")
                    .put(
                        "data",
                        JSONObject().put(
                            "details",
                            "EACCES: permission denied, link 'a' -> 'b'",
                        ),
                    ),
            ),
        )
    }

    @Test
    fun doesNotDuplicateDetailAlreadyInMessage() {
        assertEquals(
            "Internal error: already there",
            formatKimiAcpRpcError(
                JSONObject()
                    .put("message", "Internal error: already there")
                    .put("data", JSONObject().put("details", "already there")),
            ),
        )
    }
}
