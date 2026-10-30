package dev.buddy.core

import java.nio.charset.CharacterCodingException
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UnicodeIntegrityTest {
    @Test fun malformedUnicodeCannotReuseAnApprovalOrObservationHashForReplacementText() {
        assertThrows(CharacterCodingException::class.java) { sha256("\uD800") }
        assertThrows(CharacterCodingException::class.java) { sha256("\uDC00") }
        assertNotEquals(sha256("?"), sha256("こんにちは · café · \uD83D\uDE00"))
    }
}
