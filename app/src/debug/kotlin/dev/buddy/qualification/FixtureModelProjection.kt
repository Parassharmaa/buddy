package dev.buddy.qualification

import dev.buddy.core.Bounds
import dev.buddy.core.Observation
import dev.buddy.core.sha256

internal class FixtureModelProjection(private val baseline: String, private val goal: String) {
    fun project(observation: Observation): Observation {
        check(observation.app == "dev.buddy.fixture")
        val unsafe = observation.nodes.any { it.password || it.packageName != "dev.buddy.fixture" }
        if (observation.protected || observation.credentialSurface || observation.excludedOverlay || unsafe) {
            return observation.copy(
                nodes = emptyList(),
                revision = 0,
                fingerprint = sha256("suppressed fixture"),
                excludedOverlay = observation.excludedOverlay || unsafe
            )
        }
        val editor = observation.nodes.single { it.editable && !it.password }
        check(editor.packageName == "dev.buddy.fixture")
        val label = if (editor.label == baseline || editor.label == goal) {
            editor.label
        } else {
            "Native user changed the draft; its contents are not shared"
        }
        val projected = editor.copy(id = "fixture_editor", label = label, bounds = Bounds(0, 0, 1, 1))
        return observation.copy(nodes = listOf(projected), revision = 0, fingerprint = sha256(label))
    }
}
