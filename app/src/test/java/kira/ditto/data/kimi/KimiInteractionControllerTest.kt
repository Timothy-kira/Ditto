package kira.ditto.data.kimi

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class KimiInteractionControllerTest {
    private fun permissionRequest(
        sessionId: String = "kimi-1",
        toolCall: JSONObject? = JSONObject()
            .put("toolCallId", "tc-1")
            .put("title", "Edit file")
            .put("kind", "edit"),
    ): AcpPermissionRequest = KimiAcpProtocol.parsePermissionRequest(
        JSONObject()
            .put("sessionId", sessionId)
            .put("toolCall", toolCall ?: JSONObject.NULL)
            .put(
                "options",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("optionId", "approve_once")
                            .put("name", "Approve once")
                            .put("kind", "allow_once"),
                    )
                    .put(
                        JSONObject()
                            .put("optionId", "reject_once")
                            .put("name", "Reject")
                            .put("kind", "reject_once"),
                    ),
            ),
    )

    private fun elicitationRequest(sessionId: String = "kimi-1"): AcpElicitationRequest =
        KimiAcpProtocol.parseElicitationRequest(
            JSONObject()
                .put("sessionId", sessionId)
                .put("message", "Pick one")
                .put(
                    "requestedSchema",
                    JSONObject().put(
                        "properties",
                        JSONObject().put(
                            "choice",
                            JSONObject().put(
                                "oneOf",
                                JSONArray().put(JSONObject().put("const", "a")),
                            ),
                        ),
                    ),
                ),
        )

    @Test
    fun permissionAnswerResumesRequestAndClearsPending() : Unit = runBlocking {
        val controller = KimiInteractionController(aetherSessionIdFor = { "aether-$it" })
        val deferred = async { controller.onPermissionRequest(permissionRequest()) }

        val pending = controller.pendingPermissions.waitForSize(1)
        assertEquals("aether-kimi-1", pending[0].sessionId)
        assertEquals("kimi-1", pending[0].kimiSessionId)
        assertEquals("tc-1", pending[0].toolCallId)
        assertEquals("Edit file", pending[0].toolCallTitle)
        assertEquals("edit", pending[0].toolCallKind)
        assertEquals(listOf("approve_once", "reject_once"), pending[0].options.map { it.optionId })

        assertTrue(controller.answerPermission(pending[0].requestId, "approve_once"))
        assertEquals("approve_once", deferred.await())
        controller.pendingPermissions.waitForSize(0)
    }

    @Test
    fun permissionAnswerForUnknownOrSettledRequestReturnsFalse() : Unit = runBlocking {
        val controller = KimiInteractionController()
        assertFalse(controller.answerPermission("missing", "approve_once"))

        val deferred = async { controller.onPermissionRequest(permissionRequest()) }
        val pending = controller.pendingPermissions.waitForSize(1)
        assertTrue(controller.answerPermission(pending[0].requestId, "reject_once"))
        assertFalse(controller.answerPermission(pending[0].requestId, "approve_once"))
        assertEquals("reject_once", deferred.await())
    }

    @Test
    fun concurrentPermissionRequestsAreQueuedAndAnsweredIndependently() : Unit = runBlocking {
        val controller = KimiInteractionController()
        val first = async { controller.onPermissionRequest(permissionRequest(sessionId = "kimi-1")) }
        val second = async { controller.onPermissionRequest(permissionRequest(sessionId = "kimi-2")) }

        val pending = controller.pendingPermissions.waitForSize(2)
        assertEquals(setOf("kimi-1", "kimi-2"), pending.map { it.kimiSessionId }.toSet())

        assertTrue(controller.answerPermission(pending[1].requestId, "approve_once"))
        assertEquals("approve_once", second.await())
        controller.pendingPermissions.waitForSize(1)

        assertTrue(controller.answerPermission(pending[0].requestId, "reject_once"))
        assertEquals("reject_once", first.await())
        controller.pendingPermissions.waitForSize(0)
    }

    @Test
    fun cancelAllRemovesPendingRequestsAndCancelsWaiters() : Unit = runBlocking {
        val controller = KimiInteractionController()
        val permission = async { controller.onPermissionRequest(permissionRequest()) }
        val elicitation = async { controller.onElicitation(elicitationRequest()) }
        controller.pendingPermissions.waitForSize(1)
        controller.pendingElicitations.waitForSize(1)

        controller.cancelAll()

        assertThrows<CancellationException> { permission.await() }
        assertThrows<CancellationException> { elicitation.await() }
        controller.pendingPermissions.waitForSize(0)
        controller.pendingElicitations.waitForSize(0)
    }

    @Test
    fun cancelSessionOnlyCancelsMatchingRequests() : Unit = runBlocking {
        val controller = KimiInteractionController(aetherSessionIdFor = { it })
        val keep = async { controller.onPermissionRequest(permissionRequest(sessionId = "kimi-keep")) }
        val drop = async { controller.onPermissionRequest(permissionRequest(sessionId = "kimi-drop")) }
        controller.pendingPermissions.waitForSize(2)

        controller.cancelSession("kimi-drop")
        assertThrows<CancellationException> { drop.await() }
        val remaining = controller.pendingPermissions.waitForSize(1)
        assertEquals("kimi-keep", remaining[0].kimiSessionId)

        assertTrue(controller.answerPermission(remaining[0].requestId, "approve_once"))
        assertEquals("approve_once", keep.await())
    }

    @Test
    fun cancelledAwaitStillRemovesPendingEntry() : Unit = runBlocking {
        val controller = KimiInteractionController()
        val deferred = async { controller.onPermissionRequest(permissionRequest()) }
        controller.pendingPermissions.waitForSize(1)

        deferred.cancel()
        assertThrows<CancellationException> { deferred.await() }
        controller.pendingPermissions.waitForSize(0)
    }

    @Test
    fun elicitationAnswerPassesContentThroughAndNullCancels() : Unit = runBlocking {
        val controller = KimiInteractionController()
        val accepted = async { controller.onElicitation(elicitationRequest(sessionId = "kimi-a")) }
        val declined = async { controller.onElicitation(elicitationRequest(sessionId = "kimi-b")) }

        val pending = controller.pendingElicitations.waitForSize(2)
        assertEquals("Pick one", pending[0].message)
        assertEquals(1, pending[0].questions.size)
        assertEquals("choice", pending[0].questions[0].id)
        assertFalse(pending[0].questions[0].multiSelect)
        assertEquals(listOf("a"), pending[0].questions[0].options.map { it.value })

        val answers = JSONObject().put("choice", "a")
        assertTrue(controller.answerElicitation(pending[0].requestId, answers))
        assertEquals("a", accepted.await()?.getString("choice"))

        assertTrue(controller.answerElicitation(pending[1].requestId, null))
        assertNull(declined.await())
        controller.pendingElicitations.waitForSize(0)
    }

    private suspend fun <T> kotlinx.coroutines.flow.StateFlow<List<T>>.waitForSize(
        expected: Int,
    ): List<T> {
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            val current = value
            if (current.size == expected) return current
            kotlinx.coroutines.delay(5L)
        }
        fail("StateFlow did not reach size $expected (current: ${value.size})")
        error("unreachable")
    }

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (throwable: Throwable) {
            if (throwable is T) return
            fail("Expected ${T::class.simpleName} but got ${throwable.javaClass.simpleName}")
        }
        fail("Expected ${T::class.simpleName} but nothing was thrown")
    }
}
