package dev.buddy.qualification

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import dev.buddy.BuddyApplication
import dev.buddy.EngineeringSessions
import dev.buddy.adb.AdbIdentity
import dev.buddy.adb.AdbTransport
import dev.buddy.connection.DaemonAuthorization
import dev.buddy.connection.PhoneConnection
import dev.buddy.core.LoopbackPort
import dev.buddy.core.ModelConfig
import dev.buddy.core.agent.SessionSupervisor
import dev.buddy.core.model.ModelClient
import dev.buddy.design.BuddyTheme
import dev.buddy.phone.FixtureScreenService
import dev.buddy.shell.IBuddyHelper
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json

class PhoneQualificationActivity : ComponentActivity() {
    private val focused = AtomicBoolean()
    private val visible = AtomicBoolean()
    private lateinit var phone: PhoneConnection
    private lateinit var physical: EditText
    private var edits = 0
    private var running: Job? = null

    @Volatile private var permit: SessionSupervisor.Permit? = null
    private var busy by mutableStateOf(false)
    private var connected by mutableStateOf(false)
    private var networkAllowed by mutableStateOf(false)
    private var notificationsAllowed by mutableStateOf(false)
    private var port by mutableStateOf("")
    internal var preview by mutableStateOf<FixturePreview?>(null)
        private set
    private var status by mutableStateOf("Ready for a foreground-only engineering check")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        phone = PhoneConnection(applicationContext)
        physical = EditText(this).apply {
            id = android.R.id.edit
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            filters = arrayOf(InputFilter.LengthFilter(256))
            contentDescription = "Physical typing fixture"
            hint = "Type test letters here while the check runs"
            setSingleLine(false)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    edits++
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        setContent {
            BuddyTheme {
                val network = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                    networkAllowed = it
                }
                val supervised by FixtureTaskService.state.collectAsState()
                val notificationPermission =
                    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                        notificationsAllowed = it
                    }
                QualificationFrame(
                    active = busy || supervised.id != null,
                    stop = {
                        if (supervised.id != null) {
                            FixtureTaskService.stop(this@PhoneQualificationActivity)
                        } else {
                            stopCheck()
                        }
                    }
                ) { insets ->
                    Column(
                        Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)
                            .verticalScroll(rememberScrollState()).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        TextButton(onClick = { finish() }) { Text("Back to Buddy") }
                        Text("Phone fixture checks", style = MaterialTheme.typography.headlineMedium)
                        Text("${Build.MANUFACTURER} ${Build.MODEL} · Android API ${Build.VERSION.SDK_INT}")
                        Text("Edits the signed offline test fixture and checks protected-page suppression.")
                        Text("Foreground checks stop when you leave this screen or it loses focus.")
                        Text("Install the matching fixture APK from the same CI run, then pair in Phone connection.")
                        TextButton(
                            onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                            enabled = !busy && supervised.id == null
                        ) { Text("Open fixture accessibility settings") }
                        Text(
                            if (connected) "Fixture accessibility is connected" else "Enable Buddy fixture access first"
                        )
                        if (!networkAllowed && Build.VERSION.SDK_INT >= 37) {
                            TextButton(
                                onClick = { network.launch("android.permission.ACCESS_LOCAL_NETWORK") },
                                enabled = !busy && supervised.id == null
                            ) { Text("Allow local network access") }
                        }
                        OutlinedTextField(
                            value = port,
                            onValueChange = { if (it.length <= 5 && it.all(Char::isDigit)) port = it },
                            label = { Text("Wireless debugging connection port") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            enabled = !busy && supervised.id == null,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("Physical-screen typing fixture", style = MaterialTheme.typography.titleMedium)
                        AndroidView(factory = { physical }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp))
                        Button(
                            onClick = { startCheck() },
                            enabled =
                                !busy && supervised.id == null && connected && networkAllowed &&
                                    Build.VERSION.SDK_INT >= 34 &&
                                    port.toIntOrNull() in 1..65535
                        ) { Text("Run foreground check") }
                        Text("The model check sends fixed synthetic fixture data to the model saved in Settings.")
                        Text(
                            "Returning resumes the fixed draft test. Custom edits stay out of model context " +
                                "and may be replaced by the test."
                        )
                        Button(
                            onClick = { startCheck(useModel = true) },
                            enabled =
                                !busy && supervised.id == null && connected && networkAllowed &&
                                    Build.VERSION.SDK_INT >= 34 &&
                                    port.toIntOrNull() in 1..65535
                        ) { Text("Check configured model on fixture") }
                        Text(
                            "Supervised checks can continue while you use another app. " +
                                "A notification provides Stop; locking the phone stops the check."
                        )
                        if (!notificationsAllowed) {
                            TextButton(onClick = {
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }) {
                                Text("Allow check notifications")
                            }
                        }
                        Button(
                            onClick = {
                                if (!FixtureTaskService.start(
                                        this@PhoneQualificationActivity,
                                        port.toIntOrNull() ?: 0
                                    )
                                ) {
                                    status = "Keep this screen open and allow notifications before starting."
                                }
                            },
                            enabled =
                                !busy && supervised.id == null && notificationsAllowed && connected && networkAllowed &&
                                    Build.VERSION.SDK_INT >= 34 && port.toIntOrNull() in 1..65535
                        ) { Text("Run model check with notification") }
                        Button(
                            onClick = {
                                FixtureTaskService.start(
                                    this@PhoneQualificationActivity,
                                    port.toIntOrNull() ?: 0,
                                    takeover = true
                                )
                            },
                            enabled =
                                !busy && supervised.id == null && notificationsAllowed && connected && networkAllowed &&
                                    Build.VERSION.SDK_INT >= 34 && port.toIntOrNull() in 1..65535
                        ) { Text("Check takeover and return") }
                        Text(
                            "The takeover check uses no model. Type the test word human in the moved fixture draft. " +
                                "Open Buddy through its notification, then choose Return. Finish within a minute."
                        )
                        if (supervised.id != null) {
                            if (supervised.takeover == FixtureTakeoverPhase.READY) {
                                Button(onClick = { FixtureTaskService.move(this@PhoneQualificationActivity, false) }) {
                                    Text("Take over fixture")
                                }
                            }
                            if (supervised.takeover == FixtureTakeoverPhase.HUMAN) {
                                Button(onClick = { FixtureTaskService.move(this@PhoneQualificationActivity, true) }) {
                                    Text("Return fixture to workspace")
                                }
                            }
                        }
                        Text(supervised.message)
                        FixturePreviewCard(if (supervised.id != null) supervised.preview else preview)
                        Text(status)
                        Text(
                            "This is partial M0 evidence. Same-app coexistence, takeover, lock, revocation and OEM " +
                                "qualification remain part of the full device suite. General phone control stays gated."
                        )
                    }
                }
            }
        }
    }
    override fun onResume() {
        super.onResume()
        notificationsAllowed = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED && getSystemService(android.app.NotificationManager::class.java)
                .areNotificationsEnabled()
        connected = FixtureScreenService.connected
        networkAllowed = Build.VERSION.SDK_INT < 37 ||
            checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") == PackageManager.PERMISSION_GRANTED
    }
    override fun onStart() {
        super.onStart()
        visible.set(true)
    }
    override fun onStop() {
        visible.set(false)
        stopCheck()
        super.onStop()
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        focused.set(hasFocus)
        if (!hasFocus) stopCheck()
    }
    internal suspend fun runConnectedCheck(
        helper: IBuddyHelper,
        onStage: suspend (String) -> Unit = {}
    ): ForegroundFixtureResult {
        withContext(Dispatchers.Main.immediate) { physical.requestFocus() }
        return ForegroundFixtureCheck.run(
            applicationContext,
            helper,
            visible = ::isCheckVisible,
            physical = { marker ->
                withContext(Dispatchers.Main.immediate) {
                    PhysicalFixtureSample(physical.hasFocus(), edits, physical.text.contains(marker))
                }
            },
            stage = { value ->
                withContext(Dispatchers.Main.immediate) { status = value }
                onStage(value)
            },
            preview = { value -> withContext(Dispatchers.Main.immediate) { preview = value } }
        )
    }
    internal fun isCheckVisible(): Boolean = visible.get() && focused.get() && permit?.isCurrent != false
    private suspend fun runConnectedModel(helper: IBuddyHelper, config: ModelConfig, port: LoopbackPort) {
        val socket = AtomicReference<AdbTransport?>()
        val authorization = DaemonAuthorization(applicationContext, AdbIdentity())
        ModelClient(allowLoopbackHttpForTests = true).use { model ->
            ModelFixtureCheck.run(
                applicationContext,
                helper,
                config,
                model,
                visible = ::isCheckVisible,
                refreshAuthorization = {
                    check(isCheckVisible())
                    authorization.refresh(
                        helper,
                        port,
                        onOpen = { socket.set(it) },
                        onClose = { socket.compareAndSet(it, null) }
                    )
                },
                stopTransport = { socket.getAndSet(null)?.close() },
                stage = { value -> withContext(Dispatchers.Main.immediate) { status = value } },
                preview = { value -> withContext(Dispatchers.Main.immediate) { preview = value } }
            )
        }
    }
    private fun stopCheck() {
        preview = null
        running?.cancel()
    }
    private fun startCheck(useModel: Boolean = false) {
        if (busy || FixtureTaskService.state.value.id != null || !connected || !networkAllowed || !visible.get() ||
            !focused.get() ||
            Build.VERSION.SDK_INT < 34
        ) {
            return
        }
        val selected = port.toIntOrNull()?.takeIf { it in 1..65535 } ?: return
        busy = true
        physical.requestFocus()
        running = lifecycleScope.launch {
            try {
                EngineeringSessions.supervisor.supervise session@{ current ->
                    permit = current
                    try {
                        val config = if (useModel) {
                            withContext(Dispatchers.IO) {
                                (application as BuddyApplication).store.get("model-config")?.let {
                                    Json.decodeFromString<ModelConfig>(it)
                                }
                            } ?: run {
                                status = "Save and test a model in Settings first."
                                return@session
                            }
                        } else {
                            null
                        }
                        status = "Connecting to local wireless debugging"
                        withTimeout(30_000) { phone.connect(selected) }
                        if (config != null) {
                            runConnectedModel(phone.engineeringHelper(), config, LoopbackPort(selected))
                            status =
                                "Configured model completed the controlled fixture task. Full M0 qualification is pending."
                        } else {
                            val result = runConnectedCheck(phone.engineeringHelper())
                            status = if (result.concurrentTypingObserved) {
                                "Foreground checks passed; concurrent physical typing was observed. Full M0 qualification is pending."
                            } else {
                                "Foreground checks passed. Concurrent typing was not assessed; full M0 qualification is pending."
                            }
                        }
                    } finally {
                        withContext(NonCancellable + Dispatchers.Main.immediate) {
                            phone.disconnect()
                            permit = null
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                status = "Check stopped. Start a fresh check when ready."
                throw cancelled
            } catch (_: Exception) {
                status =
                    "This device could not complete the foreground check. Check pairing, fixture access and APK versions."
            } finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    busy = false
                    running = null
                }
            }
        }
    }
}
