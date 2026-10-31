package dev.buddy.ui

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.buddy.BuddyViewModel

private enum class Destination(val label: String) {
    CHAT(
        "Chat"
    ),
    BROWSER("Browser"),
    ACTIVITY("Activity"),
    AUTOMATIONS("Automations"),
    SETTINGS("Settings")
}

@Composable fun BuddyApp(model: BuddyViewModel, automationRequests: Int = 0) {
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_STOP) {
        model.browser.foreground(false)
    }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_START) {
        model.browser.foreground(true)
    }
    val state by model.state.collectAsStateWithLifecycle()
    var destination by rememberSaveable { mutableStateOf(Destination.CHAT) }
    androidx.compose.runtime.LaunchedEffect(automationRequests) {
        if (automationRequests > 0) destination = Destination.AUTOMATIONS
    }
    val expanded = currentWindowAdaptiveInfoV2().windowSizeClass.isWidthAtLeastBreakpoint(840)
    NavigationSuiteScaffold(navigationSuiteItems = {
        Destination.entries.forEach { item ->
            item(selected = destination == item, onClick = {
                destination = item
            }, icon = { NavIcon(item) }, label = { Text(item.label) })
        }
    }) {
        Scaffold(
            topBar = {
                Column(
                    Modifier.fillMaxWidth().windowInsetsPadding(
                        WindowInsets.statusBars
                    ).padding(horizontal = 20.dp).padding(top = 12.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(36.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "b",
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Text("Buddy", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        }
                        TextButton(onClick = {
                            model.newChat()
                            destination = Destination.CHAT
                        }, enabled = !state.active && !state.importing && !state.exporting) { Text("New chat") }
                    }
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                }
            },
            bottomBar = {
                if (destination == Destination.CHAT) Composer(state, model::draft, model::send, model::stop)
            },
            contentWindowInsets = WindowInsets(0, 0, 0, 0)
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                    when (destination) {
                        Destination.CHAT -> Chat(
                            state,
                            { destination = Destination.SETTINGS },
                            model::draft,
                            model.memory::remember
                        ) {
                            FilesPanel(state, model)
                            SourcePanel(state, model.sources)
                        }

                        Destination.BROWSER -> BrowserScreen(
                            model.browser,
                            state.artifacts,
                            model.sources::page,
                            model::downloadFile,
                            state.importing || state.exporting,
                            state.active,
                            model::cancelFileOperation
                        )

                        Destination.ACTIVITY -> Activity(state.history, {
                            model.openConversation(it)
                            destination =
                                Destination.CHAT
                        })

                        Destination.AUTOMATIONS -> SchedulesScreen(model.schedules, state.loaded && !state.clearingData)

                        Destination.SETTINGS -> SettingsScreen(state, model)
                    }
                }
                if (expanded && destination == Destination.CHAT && state.conversation.runs.isNotEmpty()) {
                    Column(
                        Modifier.width(
                            300.dp
                        ).fillMaxHeight().background(
                            MaterialTheme.colorScheme.surfaceContainer
                        ).padding(20.dp).verticalScroll(rememberScrollState())
                    ) {
                        Heading("Task inspector")
                        Text("Summary", style = MaterialTheme.typography.labelLarge)
                        RunCard(state.conversation.runs.last())
                        Spacer(Modifier.height(16.dp))
                        Text("Preview unavailable", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Phone control is awaiting device qualification. No screen is being observed.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
    SourceConsentDialog(model.sources) { destination = Destination.CHAT }
    MemoryEditorDialog(model.memory)
    state.error?.let { error ->
        AlertDialog(onDismissRequest = model::dismissError, title = {
            Text("Needs attention")
        }, text = { Text(error) }, confirmButton = { TextButton(onClick = model::dismissError) { Text("Close") } })
    }
}

@Composable private fun NavIcon(destination: Destination) {
    val color = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.size(24.dp)) {
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(1.8.dp.toPx())
        when (destination) {
            Destination.CHAT -> {
                drawRoundRect(
                    color,
                    Offset(3.dp.toPx(), 4.dp.toPx()),
                    Size(18.dp.toPx(), 14.dp.toPx()),
                    CornerRadius(4.dp.toPx()),
                    style = stroke
                )
                drawLine(color, Offset(8.dp.toPx(), 18.dp.toPx()), Offset(5.dp.toPx(), 22.dp.toPx()), 1.8.dp.toPx())
            }

            Destination.BROWSER -> {
                drawCircle(
                    color,
                    radius = size.minDimension * 0.36f,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
                )
                drawLine(
                    color,
                    Offset(size.width * 0.14f, size.height / 2),
                    Offset(size.width * 0.86f, size.height / 2),
                    strokeWidth = 2.dp.toPx()
                )
                drawLine(
                    color,
                    Offset(size.width / 2, size.height * 0.14f),
                    Offset(size.width / 2, size.height * 0.86f),
                    strokeWidth = 2.dp.toPx()
                )
            }

            Destination.AUTOMATIONS -> {
                drawRoundRect(
                    color,
                    Offset(3.dp.toPx(), 5.dp.toPx()),
                    Size(18.dp.toPx(), 16.dp.toPx()),
                    CornerRadius(2.dp.toPx()),
                    style = stroke
                )
                drawLine(color, Offset(3.dp.toPx(), 10.dp.toPx()), Offset(21.dp.toPx(), 10.dp.toPx()), 1.8.dp.toPx())
                drawLine(color, Offset(8.dp.toPx(), 3.dp.toPx()), Offset(8.dp.toPx(), 7.dp.toPx()), 1.8.dp.toPx())
                drawLine(color, Offset(16.dp.toPx(), 3.dp.toPx()), Offset(16.dp.toPx(), 7.dp.toPx()), 1.8.dp.toPx())
                drawCircle(color, 1.5.dp.toPx(), Offset(9.dp.toPx(), 15.dp.toPx()))
            }

            Destination.ACTIVITY -> {
                drawCircle(color, 9.dp.toPx(), style = stroke)
                drawLine(color, center, Offset(center.x, 7.dp.toPx()), 1.8.dp.toPx())
                drawLine(color, center, Offset(16.dp.toPx(), center.y), 1.8.dp.toPx())
            }

            Destination.SETTINGS -> {
                drawCircle(color, 8.dp.toPx(), style = stroke)
                drawCircle(color, 3.dp.toPx(), style = stroke)
            }
        }
    }
}
