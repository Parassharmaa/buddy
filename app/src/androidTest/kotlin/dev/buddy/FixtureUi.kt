package dev.buddy

import android.app.UiAutomation
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

internal class FixtureUi(private val automation: UiAutomation) {
    suspend fun node(display: Int, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo =
        withTimeout(5_000) {
            while (true) {
                automation.clearCache()
                val found = automation.windowsOnAllDisplays.get(display).orEmpty().firstNotNullOfOrNull { window ->
                    window.root?.let { findFixtureNode(it, predicate) }
                }
                if (found != null) return@withTimeout found
                delay(50)
            }
            error("Fixture node unavailable")
        }
}

internal fun findFixtureNode(
    node: AccessibilityNodeInfo,
    predicate: (AccessibilityNodeInfo) -> Boolean,
    depth: Int = 0
): AccessibilityNodeInfo? {
    if (depth > 20 || node.packageName?.toString() != "dev.buddy.fixture") return null
    if (predicate(node)) return node
    for (index in 0 until node.childCount) {
        node.getChild(index)?.let { child -> findFixtureNode(child, predicate, depth + 1)?.let { return it } }
    }
    return null
}
