package dev.buddy.core

import java.text.Normalizer
import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
data class MemoryRevision(val fact: String, val changedAt: Long) {
    override fun toString(): String = "MemoryRevision(redacted)"
}

@Serializable
data class SavedMemory(
    val id: String,
    val fact: String,
    val sourceMessageId: String,
    val createdAt: Long,
    val changedAt: Long,
    val revisions: List<MemoryRevision> = emptyList(),
    val deletedAt: Long? = null
) {
    override fun toString(): String = "SavedMemory(redacted)"
}

object MemoryPolicy {
    const val MAX_FACTS = 64
    const val MAX_REVISIONS = 8
    const val MAX_CHARACTERS = 256
    private val privateLabels = Regex(
        "(?i)\\b(password|passcode|pin|otp|cvv|cvc|iban|secret|api[ -]?key|" +
            "access[ -]?token|refresh[ -]?token|private[ -]?key|seed[ -]?phrase)\\b"
    )
    private val credential = Regex(
        "(?i)(?:sk-[a-z0-9_-]{12,}|gh[pousr]_[a-z0-9_]{12,}|AKIA[0-9A-Z]{16}|bearer\\s+\\S+|" +
            "https?://[^\\s/@:]+:[^\\s/@]+@)"
    )
    private val longNumber = Regex("(?<![0-9])(?:[0-9][ -]?){6,}(?![0-9])")

    fun fact(value: String): String {
        val normalized = Normalizer.normalize(value.trim(), Normalizer.Form.NFKC)
        require(normalized.length in 1..MAX_CHARACTERS)
        require(normalized.none { it.isISOControl() || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069' })
        val searchable = normalized.filterNot { Character.getType(it) == Character.FORMAT.toInt() }
        val digits = searchable.map { character ->
            val digit = Character.digit(character, 10)
            if (digit >= 0) '0' + digit else character
        }.joinToString("")
        require(!privateLabels.containsMatchIn(searchable) && !credential.containsMatchIn(searchable))
        require(!longNumber.containsMatchIn(digits))
        return normalized
    }
    fun sourceMessage(conversationId: String, index: Int): String {
        require(UUID.fromString(conversationId).toString() == conversationId && index in 0..999999)
        return "$conversationId:$index"
    }
    fun validate(memory: SavedMemory) {
        require(UUID.fromString(memory.id).toString() == memory.id)
        val source = memory.sourceMessageId.substringBeforeLast(':')
        val index = memory.sourceMessageId.substringAfterLast(':').toInt()
        require(sourceMessage(source, index) == memory.sourceMessageId)
        require(memory.createdAt > 0 && memory.changedAt >= memory.createdAt)
        require(memory.revisions.size <= MAX_REVISIONS)
        if (memory.deletedAt != null) {
            require(memory.deletedAt >= memory.createdAt && memory.fact.isEmpty() && memory.revisions.isEmpty())
        } else {
            require(fact(memory.fact) == memory.fact)
            memory.revisions.forEach {
                require(fact(it.fact) == it.fact && it.changedAt in memory.createdAt..memory.changedAt)
            }
        }
    }
}
