package dev.buddy.qualification

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.buddy.core.Observation
import kotlinx.coroutines.delay

internal class FixturePreview private constructor(val text: String?, val capturedAt: Long, val hidden: Boolean) {
    fun isFresh(now: Long): Boolean = capturedAt >= 0 && now >= capturedAt && now - capturedAt <= 2_000
    override fun toString(): String = "FixturePreview(redacted)"
    companion object {
        fun from(observation: Observation): FixturePreview {
            val hidden = observation.app != "dev.buddy.fixture" || observation.protected ||
                observation.credentialSurface || observation.excludedOverlay ||
                observation.nodes.any { it.password || it.packageName != "dev.buddy.fixture" }
            if (hidden) return FixturePreview(null, observation.capturedAtElapsedMs, true)
            var text = observation.nodes.asSequence().map { it.label }.filter { it.isNotBlank() }
                .take(6).joinToString("\n").take(512)
            if (text.lastOrNull()?.isHighSurrogate() == true) text = text.dropLast(1)
            return FixturePreview(text, observation.capturedAtElapsedMs, false)
        }
    }
}

@Composable internal fun FixturePreviewCard(preview: FixturePreview?) {
    var now by remember(preview) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(preview) {
        if (preview != null && preview.isFresh(now)) {
            delay(2_001 - (now - preview.capturedAt))
            now = SystemClock.elapsedRealtime()
        }
    }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                when {
                    preview == null -> "No current private-app preview"
                    preview.hidden -> "Private-app preview hidden"
                    preview.isFresh(now) -> "Recent filtered text excerpt"
                    else -> "Stale filtered text excerpt"
                }
            )
            if (preview?.hidden == true) {
                Text("Protected or uncertain screen. No text is shown.")
            } else if (preview != null) {
                Text(
                    preview.text?.ifBlank { "No readable fixture text." }.orEmpty(),
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
