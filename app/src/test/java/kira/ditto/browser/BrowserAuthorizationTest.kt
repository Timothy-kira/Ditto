package kira.ditto.browser

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tier a browser action lands in, and the one it can never leave.
 *
 * These used to be sentences in the subagent profile. A profile is a request to a model; a page can
 * argue against it. This is the same rule as code, which a page cannot argue with.
 */
class BrowserAuthorizationTest {

    private fun classify(tool: String, args: JSONObject = JSONObject(), url: String = "") =
        classifyBrowserAction(tool, args, url)

    @Test
    fun readsAreFree() {
        listOf("page_read", "page_snapshot", "page_recall", "search_web", "tabs_list").forEach { tool ->
            assertEquals(tool, BrowserAuthorization.Read, classify(tool).level)
        }
    }

    @Test
    fun navigationIsFree() {
        listOf("browser_open", "tabs_navigate", "tabs_manage").forEach { tool ->
            assertEquals(tool, BrowserAuthorization.Navigate, classify(tool).level)
        }
    }

    @Test
    fun ordinaryFormWorkIsAChange() {
        val verdict = classify("page_form", JSONObject().put("action", "fill"))
        assertEquals(BrowserAuthorization.Mutate, verdict.level)
    }

    @Test
    fun aPasswordFieldIsNeverAutomatic() {
        val fields = JSONArray().put(JSONObject().put("kind", "password").put("value", "x"))
        val verdict = classify("page_form", JSONObject().put("fields", fields).put("submit", true))
        assertEquals(BrowserAuthorization.Never, verdict.level)
        assertTrue(verdict.reason, "credential" in verdict.reason)
    }

    @Test
    fun aCardNumberFieldIsNeverAutomatic() {
        val fields = JSONArray().put(JSONObject().put("name", "cardNumber"))
        assertEquals(
            BrowserAuthorization.Never,
            classify("page_form", JSONObject().put("fields", fields)).level,
        )
    }

    @Test
    fun clickingPayIsNeverAutomatic() {
        val verdict = classify("page_click", JSONObject().put("label", "立即支付"))
        assertEquals(BrowserAuthorization.Never, verdict.level)
        assertTrue(verdict.reason, "支付" in verdict.reason)
    }

    @Test
    fun aCheckoutPathIsNeverAutomatic() {
        val verdict = classify(
            "page_click",
            JSONObject().put("ref", "@e12"),
            url = "https://shop.example.com/checkout/confirm",
        )
        assertEquals(BrowserAuthorization.Never, verdict.level)
    }

    @Test
    fun submittingACaptchaIsNeverAutomatic() {
        assertEquals(
            BrowserAuthorization.Never,
            classify("page_form", JSONObject().put("submit", true).put("label", "人机验证")).level,
        )
    }

    @Test
    fun readingAPaymentPageIsStillJustReading() {
        // The tier is about what the action does, not about where it happens. Refusing to read a
        // checkout page would make the agent unable to tell the user what it found there.
        assertEquals(
            BrowserAuthorization.Read,
            classify("page_read", JSONObject(), url = "https://shop.example.com/checkout").level,
        )
    }

    @Test
    fun anUnknownToolIsTreatedAsAChange() {
        // A tool added later must be classified deliberately rather than default into the
        // permissive tier by being unrecognised.
        assertEquals(BrowserAuthorization.Mutate, classify("page_teleport").level)
    }

    @Test
    fun theRefusalUsesTheExistingTakeoverPath() {
        val verdict = BrowserAuthorizationVerdict(BrowserAuthorization.Never, "the target mentions \"支付\"")
        val refusal = browserAuthorizationRefusal(verdict, "page_click")
        assertEquals(false, refusal.optBoolean("ok"))
        assertEquals("input_required", refusal.optString("code"))
        assertTrue(refusal.optBoolean("user_takeover"))
        assertTrue(refusal.optString("reason").contains("page_click"))
    }
}
