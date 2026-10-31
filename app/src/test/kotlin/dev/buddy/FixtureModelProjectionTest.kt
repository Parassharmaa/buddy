package dev.buddy

import dev.buddy.core.Bounds
import dev.buddy.core.Observation
import dev.buddy.core.UiElement
import dev.buddy.qualification.FixtureModelProjection
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FixtureModelProjectionTest {
    private val projection = FixtureModelProjection("fixed baseline", "fixed goal")
    private val input = Observation(
        "id", "run", 99, 1_000, "dev.buddy.fixture", 720, 1280,
        listOf(
            UiElement(
                "raw-editor-path",
                "private-human-canary",
                "dev.buddy.fixture",
                Bounds(20, 30, 70, 90),
                editable = true
            )
        ),
        "private-fingerprint-canary"
    )

    @Test fun customUserTextAndItsDerivedFingerprintNeverReachTheModelProjection() {
        val safe = projection.project(input)
        val encoded = Json.encodeToString(safe)
        assertFalse(encoded.contains("private-human-canary"))
        assertFalse(encoded.contains("private-fingerprint-canary"))
        assertFalse(encoded.contains("raw-editor-path"))
        assertEquals(Bounds(0, 0, 1, 1), safe.nodes.single().bounds)
        assertEquals(0L, safe.revision)
        assertEquals(
            safe.fingerprint,
            projection.project(
                input.copy(
                    fingerprint = "another-private-hash",
                    nodes = input.nodes.map { it.copy(label = "another private value") }
                )
            ).fingerprint
        )
        assertEquals(
            "fixed goal",
            projection.project(
                input.copy(
                    nodes = input.nodes.map {
                        it.copy(label = "fixed goal")
                    }
                )
            ).nodes.single().label
        )
    }

    @Test fun protectedAndUncertainProjectionsContainNoNodesOrPrivateFingerprint() {
        listOf(
            input.copy(protected = true),
            input.copy(credentialSurface = true),
            input.copy(excludedOverlay = true),
            input.copy(nodes = input.nodes.map { it.copy(password = true) }),
            input.copy(nodes = input.nodes.map { it.copy(packageName = "other.app") })
        ).forEach { observation ->
            val safe = projection.project(observation)
            assertTrue(safe.nodes.isEmpty())
            assertTrue(safe.protected || safe.credentialSurface || safe.excludedOverlay)
            assertFalse(Json.encodeToString(safe).contains("private-fingerprint-canary"))
        }
    }
}
