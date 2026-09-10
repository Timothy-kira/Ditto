package kira.ditto.data.kimi

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class KimiAcpInteractionTest {
    private val options = listOf(
        AcpPermissionOption("approve_once", "Approve once", "allow_once"),
        AcpPermissionOption("approve_always", "Always approve", "allow_always"),
        AcpPermissionOption("reject", "Reject", "reject_once"),
    )

    private fun permissionRequest(): AcpPermissionRequest =
        KimiAcpProtocol.parsePermissionRequest(
            JSONObject()
                .put("sessionId", "s1")
                .put(
                    "options",
                    JSONArray().apply {
                        options.forEach {
                            put(
                                JSONObject()
                                    .put("optionId", it.optionId)
                                    .put("name", it.name)
                                    .put("kind", it.kind),
                            )
                        }
                    },
                ),
        )

    private fun elicitationRequest(): AcpElicitationRequest =
        KimiAcpProtocol.parseElicitationRequest(
            JSONObject()
                .put("sessionId", "s1")
                .put("message", "Pick")
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
    fun permissionRoundTripReturnsSelectedOption() = runBlocking {
        val resolver = KimiAcpInteractionResolver(
            timeoutMillis = 1_000,
            handlerProvider = {
                object : KimiAcpInteractionHandler {
                    override suspend fun onPermissionRequest(request: AcpPermissionRequest): String {
                        assertEquals("s1", request.sessionId)
                        return "approve_once"
                    }

                    override suspend fun onElicitation(request: AcpElicitationRequest): JSONObject? = null
                }
            },
        )

        val outcome = resolver.resolvePermission(permissionRequest()).getJSONObject("outcome")

        assertEquals("selected", outcome.getString("outcome"))
        assertEquals("approve_once", outcome.getString("optionId"))
    }

    @Test
    fun permissionFallsBackToRejectWhenHandlerMissingOrInvalid() = runBlocking {
        val noHandler = KimiAcpInteractionResolver(timeoutMillis = 1_000, handlerProvider = { null })
        assertEquals(
            "reject",
            noHandler.resolvePermission(permissionRequest())
                .getJSONObject("outcome").getString("optionId"),
        )

        val invalidOption = KimiAcpInteractionResolver(
            timeoutMillis = 1_000,
            handlerProvider = {
                object : KimiAcpInteractionHandler {
                    override suspend fun onPermissionRequest(request: AcpPermissionRequest) = "bogus"
                    override suspend fun onElicitation(request: AcpElicitationRequest): JSONObject? = null
                }
            },
        )
        assertEquals(
            "reject",
            invalidOption.resolvePermission(permissionRequest())
                .getJSONObject("outcome").getString("optionId"),
        )
    }

    @Test
    fun permissionTimesOutToSafeReject() = runBlocking {
        val resolver = KimiAcpInteractionResolver(
            timeoutMillis = 50,
            handlerProvider = {
                object : KimiAcpInteractionHandler {
                    override suspend fun onPermissionRequest(request: AcpPermissionRequest): String {
                        delay(5_000)
                        return "approve_always"
                    }

                    override suspend fun onElicitation(request: AcpElicitationRequest): JSONObject? = null
                }
            },
        )

        val outcome = resolver.resolvePermission(permissionRequest()).getJSONObject("outcome")

        assertEquals("selected", outcome.getString("outcome"))
        assertEquals("reject", outcome.getString("optionId"))
    }

    @Test
    fun elicitationAcceptsAnswersAndCancelsOnTimeout() = runBlocking {
        val answering = KimiAcpInteractionResolver(
            timeoutMillis = 1_000,
            handlerProvider = {
                object : KimiAcpInteractionHandler {
                    override suspend fun onPermissionRequest(request: AcpPermissionRequest) = "reject"
                    override suspend fun onElicitation(request: AcpElicitationRequest): JSONObject =
                        JSONObject().put("choice", "a")
                }
            },
        )
        val accepted = answering.resolveElicitation(elicitationRequest())
        assertEquals("accept", accepted.getString("action"))
        assertEquals("a", accepted.getJSONObject("content").getString("choice"))

        val slow = KimiAcpInteractionResolver(
            timeoutMillis = 50,
            handlerProvider = {
                object : KimiAcpInteractionHandler {
                    override suspend fun onPermissionRequest(request: AcpPermissionRequest) = "reject"
                    override suspend fun onElicitation(request: AcpElicitationRequest): JSONObject? {
                        delay(5_000)
                        return JSONObject()
                    }
                }
            },
        )
        assertEquals("cancel", slow.resolveElicitation(elicitationRequest()).getString("action"))

        val noHandler = KimiAcpInteractionResolver(timeoutMillis = 1_000, handlerProvider = { null })
        assertEquals("cancel", noHandler.resolveElicitation(elicitationRequest()).getString("action"))
    }
}
