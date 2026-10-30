package dev.buddy.ui

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.buddy.BuddyState
import dev.buddy.BuddyViewModel
import dev.buddy.core.Conversation
import dev.buddy.core.RunRecord

private enum class Destination(val label: String) { CHAT("Chat"), ACTIVITY("Activity"), SETTINGS("Settings") }

@Composable fun BuddyApp(model: BuddyViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    var destination by rememberSaveable { mutableStateOf(Destination.CHAT) }
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
                        }, enabled = !state.active) { Text("New chat") }
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
                        Destination.CHAT -> Chat(state, { destination = Destination.SETTINGS }, model::draft)

                        Destination.ACTIVITY -> Activity(state.history, {
                            model.openConversation(it)
                            destination =
                                Destination.CHAT
                        })

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

@Composable private fun Heading(text: String) {
    Text(
        text,
        Modifier.semantics {
            heading()
        }.padding(bottom = 16.dp),
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable private fun Chat(state: BuddyState, settings: () -> Unit, draft: (String) -> Unit) {
    LazyColumn(
        Modifier.widthIn(max = 760.dp).fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = settings, label = {
                    Text(if (state.configured) state.modelName else "Choose a model")
                })
                AssistChip(onClick = settings, label = { Text("Phone setup") })
            }
        }
        if (state.conversation.messages.isEmpty()) {
            item {
                Spacer(Modifier.height(24.dp))
                Text(
                    "A little help.\nMore room for you.",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "Think through a question, make a plan, or get something started.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(24.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Your own assistant, your choice of model", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Connect a model API to start chatting. Your requests go directly to the provider you choose."
                        )
                        FilledTonalButton(onClick = settings) { Text("Set up Buddy") }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("A place to start", style = MaterialTheme.typography.labelLarge)
                    listOf(
                        "Help me plan a focused day",
                        "Break a big project into steps",
                        "Explain something I’m learning"
                    ).forEach { suggestion ->
                        OutlinedButton(onClick = {
                            draft(suggestion)
                        }, modifier = Modifier.fillMaxWidth()) { Text(suggestion) }
                    }
                }
            }
            item {
                Text(
                    "Development build · Background phone control is awaiting device qualification.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        items(state.conversation.messages.withIndex().toList(), key = { it.index }) { indexed ->
            val message = indexed.value
            if (message.content !=
                null
            ) {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = if (message.role ==
                        "user"
                    ) {
                        Alignment.End
                    } else {
                        Alignment.Start
                    }
                ) {
                    Text(
                        if (message.role ==
                            "user"
                        ) {
                            "You"
                        } else {
                            "Buddy"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (message.role ==
                                "user"
                            ) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainer
                            }
                        )
                    ) {
                        SelectionContainer {
                            Text(
                                requireNotNull(message.content),
                                Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            }
        }
        state.conversation.runs.lastOrNull()?.let { run -> item { RunCard(run) } }
    }
}

@Composable private fun Composer(state: BuddyState, change: (String) -> Unit, send: () -> Unit, stop: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().imePadding().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            Modifier.widthIn(max = 760.dp).fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(value = state.draft, onValueChange = change, label = {
                Text(if (state.active) "Add to your request" else "Ask Buddy")
            }, modifier = Modifier.weight(1f), maxLines = 5, shape = RoundedCornerShape(24.dp))
            Button(
                onClick = send,
                enabled = state.loaded && state.draft.isNotBlank() && state.draft.length <= 32_768,
                modifier = Modifier.height(56.dp)
            ) {
                Text("Send")
            }
        }
        if (state.active) {
            Row(
                Modifier.widthIn(max = 760.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Working with your model", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = stop) { Text("Stop") }
            }
        }
    }
}

@Composable private fun RunCard(run: RunRecord) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                run.state.name.lowercase().replace('_', ' ').replaceFirstChar {
                    it.uppercase()
                },
                style = MaterialTheme.typography.labelLarge
            )
            Text(run.summary, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable private fun Activity(history: List<Conversation>, open: (String) -> Unit) {
    LazyColumn(
        Modifier.widthIn(max = 760.dp).fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Heading("Activity") }
        if (history.isEmpty()) {
            item {
                Text("A clear record of what happened.", style = MaterialTheme.typography.titleLarge)
                Text("Your conversations and task outcomes will appear here.", Modifier.padding(top = 8.dp))
            }
        }
        items(history, key = { it.id }) { conversation ->
            Card(onClick = {
                open(conversation.id)
            }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(conversation.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        conversation.runs.lastOrNull()?.summary ?: "Conversation",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable private fun SettingsScreen(state: BuddyState, model: BuddyViewModel) {
    var section by rememberSaveable { mutableStateOf("Overview") }
    val context = LocalContext.current
    val status by model.phone.status.collectAsStateWithLifecycle()
    Column(
        Modifier.widthIn(max = 760.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Heading(if (section == "Overview") "Settings" else section)
        if (section != "Overview") TextButton(onClick = { section = "Overview" }) { Text("Back to settings") }
        when (section) {
            "Overview" -> {
                Text("Make Buddy yours", style = MaterialTheme.typography.titleLarge)
                listOf(
                    "Models" to state.modelStatus,
                    "Phone connection" to status,
                    "Privacy" to "Encrypted on this phone · screenshots off",
                    "Diagnostics" to "Capabilities and build details"
                ).forEach { (title, subtitle) ->
                    Card(onClick = { section = title }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(title, style = MaterialTheme.typography.titleMedium)
                            Text(subtitle, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            "Models" -> ModelSettings(state, model)

            "Phone connection" -> {
                Text(status, style = MaterialTheme.typography.titleMedium)
                Text(
                    "Buddy pairs with Android’s debugging service on this phone. Pairing alone does not enable phone control. The helper must pass device capability tests."
                )
                Text(
                    "1. Open Developer options and enable Wireless debugging.\n2. Choose Pair device with pairing code. Keep that dialog open using split screen.\n3. Enter its pairing port and six-digit code below. The connection port is shown on the previous Wireless debugging screen."
                )
                OutlinedButton(onClick = {
                    runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                }) { Text("Open Developer options") }
                var pairPort by rememberSaveable { mutableStateOf("") }
                var code by remember { mutableStateOf("") }
                var connectPort by rememberSaveable { mutableStateOf("") }
                OutlinedTextField(
                    pairPort,
                    {
                        pairPort = it
                    },
                    label = {
                        Text("Pairing port")
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    code,
                    {
                        code = it
                    },
                    label = {
                        Text("Six-digit pairing code")
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        model.pair(pairPort, code)
                        code = ""
                    },
                    enabled =
                        pairPort.isNotBlank() && code.isNotBlank()
                ) { Text("Pair this phone") }
                HorizontalDivider()
                OutlinedTextField(
                    connectPort,
                    {
                        connectPort = it
                    },
                    label = {
                        Text("Connection port")
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Button(onClick = {
                    model.connect(connectPort)
                }, enabled = connectPort.isNotBlank()) { Text("Connect and test helper") }
                OutlinedButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }) { Text("Open screen access settings") }
                Text(
                    "Screen access allows reading app controls. Buddy is a general assistant, not a verified accessibility tool. Protected and credential screens require you to take over.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(onClick = { model.disconnect() }) { Text("Disconnect") }
                TextButton(onClick = { model.disconnect(true) }) { Text("Forget local pairing") }
            }

            "Privacy" -> {
                Text("Your provider: " + if (state.configured) state.modelBase else "Not configured")
                Text(
                    "Model keys, conversations and action records are encrypted using a key in Android Keystore. Backups and telemetry are disabled. Screenshots are not retained. Phone observation is disabled in this development build."
                )
                Text(
                    "Local history expires after 30 days. Deleting local data does not delete copies retained by your model provider or Android’s paired debugging identity."
                )
                var confirm by remember { mutableStateOf(false) }
                OutlinedButton(onClick = { confirm = true }) { Text("Delete all local data") }
                if (confirm) {
                    AlertDialog(onDismissRequest = {
                        confirm = false
                    }, title = {
                        Text("Delete Buddy’s local data?")
                    }, text = {
                        Text(
                            "This removes model settings, keys, history and action records, and disconnects phone control. Also remove Buddy in Android’s Wireless debugging paired devices."
                        )
                    }, confirmButton = {
                        TextButton(onClick = {
                            model.deleteAll()
                            confirm =
                                false
                        }) { Text("Delete local data") }
                    }, dismissButton = { TextButton(onClick = { confirm = false }) { Text("Keep data") } })
                }
            }

            "Diagnostics" -> {
                Text("Android API ${Build.VERSION.SDK_INT} · ${Build.MANUFACTURER} ${Build.MODEL}")
                Text(
                    "Build 0.1.0-dev\nSDK 37.2 · target 37 · minimum 33\nKotlin 2.4.21 · AGP 9.5.0\nCompose BOM 2026.09.00 · NDK 30"
                )
                Text(
                    "Paired identity, debugging transport, helper authentication and screen access are checked independently. No phone-control capability has been qualified on this device."
                )
                Text(status)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable private fun ModelSettings(state: BuddyState, model: BuddyViewModel) {
    var base by rememberSaveable(state.modelBase) { mutableStateOf(state.modelBase) }
    var name by rememberSaveable(state.modelName) { mutableStateOf(state.modelName) }
    var key by remember { mutableStateOf("") }
    var local by rememberSaveable(state.localHttp) { mutableStateOf(state.localHttp) }
    Text("Bring your own model", style = MaterialTheme.typography.titleLarge)
    Text(
        "Use an OpenAI-compatible chat API with streaming and tool calling. Task-relevant text and screen content may go directly to this provider when those capabilities are enabled."
    )
    OutlinedTextField(
        base,
        {
            base = it
        },
        label = {
            Text("API base URL")
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Uri
        ),
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(name, {
        name = it
    }, label = { Text("Model ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(
        key,
        {
            key = it
        },
        label = {
            Text(if (state.configured) "API key · blank keeps saved key" else "API key")
        },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password
        ),
        modifier = Modifier.fillMaxWidth()
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Allow HTTP for a local-network model", Modifier.weight(1f))
        Switch(
            local,
            { local = it },
            modifier = Modifier.semantics {
                contentDescription =
                    "Allow HTTP for a local-network model"
            }
        )
    }
    Text(
        "HTTP is limited to explicit local IP addresses and localhost. Traffic to those endpoints is unencrypted.",
        style = MaterialTheme.typography.bodySmall
    )
    Button(
        onClick = {
            model.saveModel(base, name, key, local)
            key = ""
        },
        enabled =
            !state.testingModel && base.isNotBlank() && name.isNotBlank()
    ) {
        if (state.testingModel) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(if (state.testingModel) "Testing model" else "Save and test model")
    }
    Text(state.modelStatus)
}
