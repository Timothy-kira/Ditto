package kira.ditto.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PiExtensionReloadTest {
    @Test
    fun rejectedReloadIncludesAetherAndSessionErrors() {
        val reload = JSONObject()
            .put("succeeded", false)
            .put(
                "aether_reload",
                JSONObject().put(
                    "errors",
                    JSONArray().put(
                        JSONObject().put("error", "Script factory failed")
                    )
                )
            )
            .put(
                "sessions",
                JSONArray().put(
                    JSONObject()
                        .put("session_id", "session-1")
                        .put(
                            "errors",
                            JSONArray().put(
                                JSONObject().put("error", "Pi hook failed")
                            )
                        )
                )
            )

        val failure = try {
            requireExtensionReloadSucceeded(reload)
            fail("Expected reload rejection")
            return
        } catch (throwable: IllegalStateException) {
            throwable
        }

        assertTrue(failure.message.orEmpty().contains("Script factory failed"))
        assertTrue(failure.message.orEmpty().contains("session-1: Pi hook failed"))
    }

    @Test
    fun acceptedOrMissingReloadDoesNotThrow() {
        requireExtensionReloadSucceeded(null)
        requireExtensionReloadSucceeded(JSONObject().put("succeeded", true))
    }
}
