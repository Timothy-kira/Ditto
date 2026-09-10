package kira.ditto.data.kimi

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * UI-facing model of a pending agent permission request. [options] are passed
 * through verbatim from the ACP request; answering with an unknown optionId is
 * treated as no answer by the protocol layer (safe reject fallback).
 */
data class PendingPermissionOption(
    val optionId: String,
    val name: String,
    val kind: String,
)

data class PendingPermissionRequest(
    val requestId: String,
    /** Aether chat session id (resolved from the kimi session id when known). */
    val sessionId: String,
    val kimiSessionId: String,
    val toolCallId: String,
    val toolCallTitle: String,
    val toolCallKind: String,
    val questionText: String = "",
    val options: List<PendingPermissionOption>,
    val requestedAtMillis: Long,
)

data class PendingElicitationOption(
    val value: String,
    val label: String,
)

data class PendingElicitationQuestion(
    val id: String,
    /** Short header; falls back to [body] when the CLI sent no header. */
    val title: String,
    /** Full question body; may be blank for multi-question forms. */
    val body: String,
    val multiSelect: Boolean,
    val required: Boolean,
    val options: List<PendingElicitationOption>,
) {
    /** Question text rendered above the options. */
    val displayText: String
        get() = body.ifBlank { title }
}

data class PendingElicitationRequest(
    val requestId: String,
    /** Aether chat session id (resolved from the kimi session id when known). */
    val sessionId: String,
    val kimiSessionId: String,
    val message: String,
    val questions: List<PendingElicitationQuestion>,
    val requestedAtMillis: Long,
)

/**
 * Bridges agent-initiated ACP interactions (permission prompts, elicitation
 * forms) to the UI layer. Each request is published on a StateFlow list and
 * backed by a [CompletableDeferred]; the UI answers via [answerPermission] /
 * [answerElicitation], which resumes the suspended agent coroutine.
 *
 * Lifecycle: a request leaves the StateFlow exactly once — when the UI answers,
 * when the resolver times out, when the turn/process is cancelled, or when
 * [cancelAll] / [cancelSession] runs. Cleanup lives in a `finally` around the
 * deferred await, so no path can leak a stale entry. All public methods are
 * thread-safe; answers for unknown or already-settled requestIds are ignored.
 */
class KimiInteractionController(
    private val aetherSessionIdFor: (kimiSessionId: String) -> String = { "" },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : KimiAcpInteractionHandler {
    private val lock = Any()
    private val permissionAnswerWaiters = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val elicitationAnswerWaiters = ConcurrentHashMap<String, CompletableDeferred<JSONObject?>>()
    private val _pendingPermissions = MutableStateFlow<List<PendingPermissionRequest>>(emptyList())
    private val _pendingElicitations = MutableStateFlow<List<PendingElicitationRequest>>(emptyList())

    val pendingPermissions: StateFlow<List<PendingPermissionRequest>> = _pendingPermissions.asStateFlow()
    val pendingElicitations: StateFlow<List<PendingElicitationRequest>> = _pendingElicitations.asStateFlow()

    override suspend fun onPermissionRequest(request: AcpPermissionRequest): String {
        val requestId = newRequestId()
        val waiter = CompletableDeferred<String>()
        permissionAnswerWaiters[requestId] = waiter
        val pending = PendingPermissionRequest(
            requestId = requestId,
            sessionId = aetherSessionIdFor(request.sessionId),
            kimiSessionId = request.sessionId,
            toolCallId = request.toolCall?.optString("toolCallId").orEmpty(),
            toolCallTitle = request.toolCall?.optString("title").orEmpty(),
            toolCallKind = request.toolCall?.optString("kind").orEmpty(),
            questionText = KimiAcpProtocol.permissionQuestionText(request.toolCall),
            options = request.options.map { option ->
                PendingPermissionOption(
                    optionId = option.optionId,
                    name = option.name,
                    kind = option.kind,
                )
            },
            requestedAtMillis = nowMillis(),
        )
        synchronized(lock) {
            _pendingPermissions.value = _pendingPermissions.value + pending
        }
        try {
            return waiter.await()
        } finally {
            removePermission(requestId, waiter)
        }
    }

    override suspend fun onElicitation(request: AcpElicitationRequest): JSONObject? {
        val requestId = newRequestId()
        val waiter = CompletableDeferred<JSONObject?>()
        elicitationAnswerWaiters[requestId] = waiter
        val pending = PendingElicitationRequest(
            requestId = requestId,
            sessionId = aetherSessionIdFor(request.sessionId),
            kimiSessionId = request.sessionId,
            message = request.message,
            questions = request.questions.map { question ->
                PendingElicitationQuestion(
                    id = question.id,
                    title = question.title,
                    body = question.body,
                    multiSelect = question.multiSelect,
                    required = question.required,
                    options = question.options.map { option ->
                        PendingElicitationOption(value = option.value, label = option.label)
                    },
                )
            },
            requestedAtMillis = nowMillis(),
        )
        synchronized(lock) {
            _pendingElicitations.value = _pendingElicitations.value + pending
        }
        try {
            return waiter.await()
        } finally {
            removeElicitation(requestId, waiter)
        }
    }

    /**
     * Answers a pending permission request. Returns false when the requestId is
     * unknown or already settled (stale UI event), true when the answer was
     * delivered. [optionId] is passed through verbatim; the protocol layer
     * validates it against the offered options.
     */
    fun answerPermission(requestId: String, optionId: String): Boolean {
        val waiter = permissionAnswerWaiters[requestId] ?: return false
        return waiter.complete(optionId)
    }

    /**
     * Answers a pending elicitation. [answers] is the content object expected by
     * `KimiAcpProtocol.elicitationAccepted`: questionId -> value (string) for
     * single-select questions, questionId -> JSONArray of values for
     * multi-select. Null skips/cancels the form. Returns false for unknown or
     * already-settled requestIds.
     */
    fun answerElicitation(requestId: String, answers: JSONObject?): Boolean {
        val waiter = elicitationAnswerWaiters[requestId] ?: return false
        return waiter.complete(answers)
    }

    /**
     * Cancels every pending interaction (e.g. runtime teardown). The suspended
     * handler coroutines receive a CancellationException, which the protocol
     * layer converts into its safe default / propagates as cancellation.
     */
    fun cancelAll() {
        cancelPermissions { true }
        cancelElicitations { true }
    }

    /** Cancels pending interactions belonging to one Aether session. */
    fun cancelSession(sessionId: String) {
        cancelPermissions { it.sessionId == sessionId || it.kimiSessionId == sessionId }
        cancelElicitations { it.sessionId == sessionId || it.kimiSessionId == sessionId }
    }

    private fun cancelPermissions(matches: (PendingPermissionRequest) -> Boolean) {
        val targets = _pendingPermissions.value.filter(matches)
        targets.forEach { pending ->
            permissionAnswerWaiters[pending.requestId]
                ?.cancel(CancellationException("Permission request cancelled."))
        }
    }

    private fun cancelElicitations(matches: (PendingElicitationRequest) -> Boolean) {
        val targets = _pendingElicitations.value.filter(matches)
        targets.forEach { pending ->
            elicitationAnswerWaiters[pending.requestId]
                ?.cancel(CancellationException("Elicitation request cancelled."))
        }
    }

    private fun removePermission(requestId: String, waiter: CompletableDeferred<String>) {
        permissionAnswerWaiters.remove(requestId, waiter)
        synchronized(lock) {
            _pendingPermissions.value = _pendingPermissions.value.filterNot { it.requestId == requestId }
        }
    }

    private fun removeElicitation(requestId: String, waiter: CompletableDeferred<JSONObject?>) {
        elicitationAnswerWaiters.remove(requestId, waiter)
        synchronized(lock) {
            _pendingElicitations.value = _pendingElicitations.value.filterNot { it.requestId == requestId }
        }
    }

    private fun newRequestId(): String = "acp-interaction-${UUID.randomUUID()}"
}
