package kira.ditto.data.kimi

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/**
 * Implemented by the UI layer to answer agent-initiated requests. Returning an
 * unknown optionId from [onPermissionRequest] is treated as "no answer" and falls
 * back to the safe reject default. Returning null from [onElicitation] cancels the
 * form. Any throw likewise falls back to the safe default; the agent is never
 * left hanging.
 */
interface KimiAcpInteractionHandler {
    /** Returns the chosen optionId (options are passed through verbatim). */
    suspend fun onPermissionRequest(request: AcpPermissionRequest): String

    /** Returns the answers content object, or null to cancel the form. */
    suspend fun onElicitation(request: AcpElicitationRequest): JSONObject?
}

/**
 * Resolves agent-initiated interaction requests with a bounded wait for the UI.
 * On handler absence, handler failure, or timeout the safe default is produced
 * (reject for permissions, cancel for elicitations).
 */
internal class KimiAcpInteractionResolver(
    private val timeoutMillis: Long,
    private val handlerProvider: () -> KimiAcpInteractionHandler?,
    private val log: (event: String, message: String) -> Unit = { _, _ -> },
) {
    suspend fun resolvePermission(request: AcpPermissionRequest): JSONObject {
        val handler = handlerProvider() ?: return KimiAcpProtocol.safePermissionOutcome(request.options)
        return try {
            val selected = withTimeout(timeoutMillis) { handler.onPermissionRequest(request) }
            if (request.options.any { it.optionId == selected }) {
                KimiAcpProtocol.permissionOutcome(selected)
            } else {
                log("permission_invalid_option", "UI returned unknown permission option '$selected'.")
                KimiAcpProtocol.safePermissionOutcome(request.options)
            }
        } catch (timeout: TimeoutCancellationException) {
            log("permission_timeout", "Permission request timed out after ${timeoutMillis}ms; rejecting.")
            KimiAcpProtocol.safePermissionOutcome(request.options)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            log("permission_handler_error", error.message ?: "Permission handler failed; rejecting.")
            KimiAcpProtocol.safePermissionOutcome(request.options)
        }
    }

    suspend fun resolveElicitation(request: AcpElicitationRequest): JSONObject {
        val handler = handlerProvider() ?: return KimiAcpProtocol.elicitationCancelled()
        return try {
            val answers = withTimeout(timeoutMillis) { handler.onElicitation(request) }
            if (answers == null) {
                KimiAcpProtocol.elicitationCancelled()
            } else {
                KimiAcpProtocol.elicitationAccepted(answers)
            }
        } catch (timeout: TimeoutCancellationException) {
            log("elicitation_timeout", "Elicitation request timed out after ${timeoutMillis}ms; cancelling.")
            KimiAcpProtocol.elicitationCancelled()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            log("elicitation_handler_error", error.message ?: "Elicitation handler failed; cancelling.")
            KimiAcpProtocol.elicitationCancelled()
        }
    }
}
