package dev.buddy.ui

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.buddy.BuddyState
import dev.buddy.BuddyViewModel

@Composable internal fun SettingsScreen(state: BuddyState, model: BuddyViewModel) {
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
