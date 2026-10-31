package dev.buddy.core

import kotlinx.serialization.Serializable

@Serializable
enum class RunState {
    QUEUED,
    PREPARING,
    RUNNING,
    PAUSING,
    PAUSED,
    WAITING_FOR_USER,
    WAITING_FOR_APPROVAL,
    HANDED_TO_USER,
    STOPPING,
    VERIFYING,
    DONE,
    PARTIAL,
    BLOCKED,
    CANCELLED,
    INTERRUPTED,
    UNKNOWN;

    val terminal: Boolean get() = this in
        setOf(DONE, PARTIAL, BLOCKED, CANCELLED, INTERRUPTED, UNKNOWN)
}

@Serializable
data class RunRecord(
    val id: String,
    val conversationId: String,
    val request: String,
    val state: RunState = RunState.QUEUED,
    val summary: String = "Queued",
    val createdAt: Long,
    val evidenceIds: List<String> = emptyList()
)

@Serializable
data class UiElement(
    val id: String,
    val label: String,
    val packageName: String,
    val bounds: Bounds,
    val editable: Boolean = false,
    val password: Boolean = false
)

@Serializable
data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun contains(x: Int, y: Int): Boolean = x >= left && y >= top && x < right && y < bottom
}

@Serializable
data class Observation(
    val id: String,
    val runId: String,
    val revision: Long,
    val capturedAtElapsedMs: Long,
    val app: String,
    val width: Int,
    val height: Int,
    val nodes: List<UiElement>,
    val fingerprint: String,
    val protected: Boolean = false,
    val credentialSurface: Boolean = false,
    val excludedOverlay: Boolean = false
)

@Serializable
enum class Effect { READ, NAVIGATE, DRAFT, SEND, DELETE, SUBMIT, SECURITY, PAYMENT, CREDENTIAL, UNKNOWN }

@Serializable
data class ActionProposal(
    val id: String,
    val runId: String,
    val observationId: String,
    val revision: Long,
    val app: String,
    val effect: Effect,
    val target: String,
    val payload: String,
    val destination: String,
    val fingerprint: String
) {
    // Semantic classification must come from a local supported-action adapter, never the model.
    val operationKey: String get() = sha256(
        listOf(effect.name, app, target, payload, destination).joinToString("") { "${it.length}:$it" }
    )
}

@Serializable
data class Approval(
    val id: String,
    val runId: String,
    val operationKey: String,
    val fingerprint: String,
    val expiresAtElapsedMs: Long,
    val accepted: Boolean = false,
    val consumed: Boolean = false
)

@Serializable
enum class DispatchState { PREPARED, DISPATCHING, DISPATCHED, VERIFIED, UNCERTAIN, NOT_DISPATCHED }

@Serializable
enum class ActionFailure {
    FINAL_GATE_CHANGED,
    APPROVAL_EXPIRED,
    PREPARATION_FAILED,
    DISPATCH_FAILED,
    RESULT_STORAGE_FAILED,
    VERIFICATION_FAILED,
    NO_VERIFICATION_EVIDENCE,
    CANCELLED_BEFORE_DISPATCH,
    CANCELLED_IN_FLIGHT
}

@Serializable
data class ActionRecord(
    val proposal: ActionProposal,
    val state: DispatchState,
    val evidenceIds: List<String> = emptyList(),
    val failure: ActionFailure? = null,
    val elapsedMillis: Long = 0
)

@Serializable
data class Barrier(val operationKey: String, val runId: String, val reason: String)

fun sha256(value: String): String {
    // UTF-8's replacement mode would give an incomplete surrogate the same hash as "?".
    val encoded = Charsets.UTF_8.newEncoder()
        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        .encode(java.nio.CharBuffer.wrap(value))
    val digest = java.security.MessageDigest.getInstance("SHA-256").apply { update(encoded) }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
