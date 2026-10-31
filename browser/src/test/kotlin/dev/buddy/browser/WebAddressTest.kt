package dev.buddy.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebAddressTest {
    @Test fun canonicalOriginPreservesNondefaultPortsAndNormalizesHostnames() {
        val address = WebAddress.parse("HTTPS://Example.COM.:443/a?q=value#section")
        assertEquals("https://example.com", address.origin)
        assertEquals("https://example.com/a?q=value#section", address.url)
        assertEquals("http://127.0.0.1:8080", WebAddress.parse("http://127.0.0.1:8080/").origin)
    }

    @Test fun sourceLabelsDoNotExposeQueryTokensOrFragments() {
        val address = WebAddress.parse("https://example.com/article?access_token=private#private")
        assertEquals("https://example.com/article", address.sourceLabel)
        assertFalse(address.sourceLabel.contains("private"))
    }

    @Test fun executableAndLocalFileSchemesAreRejected() {
        for (address in listOf(
            "javascript:alert(1)",
            "data:text/html,hello",
            "file:///data/private",
            "content://private/a",
            "intent://x"
        )) {
            assertTrue(runCatching { WebAddress.parse(address) }.isFailure)
        }
    }

    @Test fun userInfoCannotConfuseTheVisibleOrigin() {
        for (address in listOf(
            "https://name:password@example.com/",
            "https://@example.com/",
            "https://example.com@evil.test/"
        )) {
            assertTrue(runCatching { WebAddress.parse(address) }.isFailure)
        }
    }

    @Test fun controlsAmbiguousSeparatorsAndOversizedInputsAreRejected() {
        for (address in listOf(
            " https://example.com",
            "https://example.com\n",
            "https://example.com\\@evil.test/",
            "https://example.com/\u202eexe",
            "https://example.com/" + "a".repeat(4096)
        )) {
            assertTrue(runCatching { WebAddress.parse(address) }.isFailure)
        }
    }

    @Test fun ipv6AndInternationalHostnamesHaveStableOrigins() {
        assertEquals("http://[::1]:8080", WebAddress.parse("http://[::1]:8080/a").origin)
        assertEquals("https://xn--bcher-kva.example", WebAddress.parse("https://bücher.example/a").origin)
    }
}
