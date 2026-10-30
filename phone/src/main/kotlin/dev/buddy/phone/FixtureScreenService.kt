package dev.buddy.phone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import dev.buddy.core.Bounds
import dev.buddy.core.FilteredObservation
import dev.buddy.core.Observation
import dev.buddy.core.ObservationPrivacyGate
import dev.buddy.core.UiElement
import dev.buddy.core.sha256
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

internal const val FIXTURE_PACKAGE = "dev.buddy.fixture"
internal class FixtureScreenChanged : IllegalStateException("Fixture changed during observation")

// Only FixtureWorkspace can mint this scope after the helper creates and launches its display.
internal data class FixtureDisplayScope(val displayId: Int, val width: Int, val height: Int, val runId: String) {
    init {
        require(displayId > 0)
    }
}

internal data class FixtureSnapshot(val observation: Observation, val nodes: Map<String, AccessibilityNodeInfo>)

internal fun fixtureFingerprint(nodes: List<UiElement>): String = sha256(
    nodes.joinToString("") { element ->
        listOf(
            element.id,
            element.label,
            element.packageName,
            element.bounds.toString(),
            element.editable.toString(),
            element.password.toString()
        ).joinToString("") { "${it.length}:$it" }
    }
)

class FixtureScreenService : AccessibilityService() {
    private val revision = AtomicLong()
    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        revision.incrementAndGet()
        current = this
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        revision.incrementAndGet()
    }
    override fun onInterrupt() {
        if (current === this) current = null
    }
    override fun onUnbind(intent: Intent?): Boolean {
        if (current === this) current = null
        return super.onUnbind(intent)
    }
    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    private fun snapshot(scope: FixtureDisplayScope): FixtureSnapshot {
        check(getSystemService(DisplayManager::class.java).getDisplay(scope.displayId)?.isValid == true)
        val startRevision = revision.get()
        val windows = windowsOnAllDisplays.get(scope.displayId).orEmpty()
        var excluded = windows.size != 1 || windows.any { it.type != AccessibilityWindowInfo.TYPE_APPLICATION }
        var privateInput = false
        val references = linkedMapOf<String, AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo, path: String, depth: Int) {
            if (depth > 20 || references.size >= 256 || node.packageName?.toString() != FIXTURE_PACKAGE) {
                excluded = true
                return
            }
            references[path] = node
            val inputClass = node.inputType and InputType.TYPE_MASK_CLASS
            val variation = node.inputType and InputType.TYPE_MASK_VARIATION
            privateInput = privateInput || node.isPassword ||
                (
                    inputClass == InputType.TYPE_CLASS_TEXT && variation in setOf(
                        InputType.TYPE_TEXT_VARIATION_PASSWORD,
                        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                        InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                    )
                    ) ||
                (inputClass == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
            for (index in 0 until node.childCount) node.getChild(index)?.let { visit(it, "$path/$index", depth + 1) }
        }
        // Inspect only metadata first. Never request node text on a credential or mixed window.
        if (!excluded) windows.single().root?.let { visit(it, "0", 0) } ?: run { excluded = true }
        val elements = mutableListOf<UiElement>()
        if (!excluded && !privateInput) {
            for ((id, node) in references) {
                val label = node.text ?: node.contentDescription ?: node.hintText ?: ""
                if (label.length > 2048) {
                    excluded = true
                    break
                }
                val bounds = Rect().also(node::getBoundsInScreen)
                elements += UiElement(
                    id,
                    label.toString(),
                    FIXTURE_PACKAGE,
                    Bounds(bounds.left, bounds.top, bounds.right, bounds.bottom),
                    node.isEditable
                )
            }
        }
        if (startRevision != revision.get()) throw FixtureScreenChanged()
        val safeElements = if (excluded || privateInput) emptyList() else elements
        val fingerprint = fixtureFingerprint(safeElements)
        val observation = Observation(
            UUID.randomUUID().toString(), scope.runId, startRevision, SystemClock.elapsedRealtime(),
            FIXTURE_PACKAGE, scope.width, scope.height, safeElements, fingerprint,
            credentialSurface = privateInput, excludedOverlay = excluded
        )
        val filtered = ObservationPrivacyGate(emptySet()).filter(observation)
        return when (filtered) {
            is FilteredObservation.Allowed -> FixtureSnapshot(filtered.observation, references)

            is FilteredObservation.Suppressed -> FixtureSnapshot(
                observation.copy(nodes = emptyList(), excludedOverlay = excluded || !privateInput),
                emptyMap()
            )
        }
    }

    companion object {
        @Volatile private var current: FixtureScreenService? = null
        val connected: Boolean get() = current != null

        internal fun capture(scope: FixtureDisplayScope): FixtureSnapshot =
            requireNotNull(current) { "Enable fixture screen access first" }.snapshot(scope)

        internal fun edit(snapshot: FixtureSnapshot, nodeId: String, value: String) {
            val node = requireNotNull(snapshot.nodes[nodeId])
            check(node.isEditable && node.isVisibleToUser && !node.isPassword)
            check(node.packageName?.toString() == FIXTURE_PACKAGE)
            check(
                node.performAction(
                    AccessibilityNodeInfo.ACTION_SET_TEXT,
                    Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                    }
                )
            ) { "Fixture editor rejected text" }
        }
    }
}
