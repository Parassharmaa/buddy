package dev.buddy.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.buddy.core.scheduling.ScheduleDefinition
import dev.buddy.core.scheduling.ScheduleTime
import dev.buddy.scheduling.ScheduleController
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

@Composable
internal fun SchedulesScreen(controller: ScheduleController, available: Boolean) {
    val state by controller.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<ScheduleDefinition?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    if (editorOpen) {
        ScheduleEditor(editing, { editorOpen = false }) {
            controller.save(it)
            editorOpen = false
        }
        return
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Heading("Automations")
        Text("Daily website status checks", style = MaterialTheme.typography.titleMedium)
        Text(
            "Checks read HTTP status headers only. No page contents, website cookies, model calls, or phone actions. " +
                "Android may delay checks; occurrences more than 15 minutes late are skipped."
        )
        Button(
            onClick = {
                editing = null
                editorOpen = true
            },
            enabled = available && state.loaded && !state.busy &&
                state.snapshot.schedules.size < ScheduleTime.MAX_SCHEDULES
        ) { Text("Add check") }
        if (state.loaded && state.snapshot.schedules.isEmpty()) Text("No scheduled checks")
        state.snapshot.schedules.forEach { rule ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(rule.definition.label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Switch(
                            modifier = Modifier.semantics { contentDescription = "Enable ${rule.definition.label}" },
                            checked = rule.enabled,
                            onCheckedChange = { controller.enabled(rule.definition.id, it) },
                            enabled = available && !state.busy
                        )
                    }
                    Text(rule.definition.address)
                    Text(
                        if (rule.enabled) {
                            "Next: ${formatOccurrence(
                                rule.next.atMillis,
                                rule.next.zoneId
                            )}"
                        } else {
                            "Paused"
                        }
                    )
                    val last = state.snapshot.checks.firstOrNull { it.scheduleId == rule.definition.id }
                    Text(
                        if (last ==
                            null
                        ) {
                            "No checks yet"
                        } else {
                            "Last: ${last.state}${last.httpCode?.let { " $it" }.orEmpty()}"
                        }
                    )
                    Row {
                        TextButton(
                            onClick = {
                                editing = rule.definition
                                editorOpen = true
                            },
                            enabled =
                                available && !state.busy
                        ) {
                            Text("Edit")
                        }
                        TextButton(
                            onClick = { controller.delete(rule.definition.id) },
                            enabled =
                                available && !state.busy
                        ) {
                            Text("Delete")
                        }
                    }
                }
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    if (editorOpen) {
        ScheduleEditor(editing, { editorOpen = false }) {
            controller.save(it)
            editorOpen = false
        }
    }
}

private fun formatOccurrence(millis: Long, zone: String) = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm z")
    .format(Instant.ofEpochMilli(millis).atZone(ZoneId.of(zone)))

@Composable
private fun ScheduleEditor(initial: ScheduleDefinition?, dismiss: () -> Unit, save: (ScheduleDefinition) -> Unit) {
    val id = remember { initial?.id ?: UUID.randomUUID().toString() }
    var label by remember { mutableStateOf(initial?.label.orEmpty()) }
    var address by remember { mutableStateOf(initial?.address.orEmpty()) }
    var time by remember { mutableStateOf(initial?.let { "%02d:%02d".format(it.hour, it.minute) } ?: "09:00") }
    var follow by remember { mutableStateOf(initial?.followDeviceZone ?: true) }
    var zone by remember { mutableStateOf(initial?.zoneId ?: ZoneId.systemDefault().id) }
    var local by remember { mutableStateOf(initial?.localHttp ?: false) }
    var notify by remember { mutableStateOf(initial?.notify ?: false) }
    val context = LocalContext.current
    fun allowed() = context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED &&
        context.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()
    var notificationAccess by remember { mutableStateOf(allowed()) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationAccess = granted && allowed()
        notify = notificationAccess
    }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        notificationAccess = allowed()
    }
    val definition = runCatching {
        val parsed = LocalTime.parse(time)
        ScheduleDefinition(id, label.trim(), address.trim(), parsed.hour, parsed.minute, zone, follow, local, notify)
    }.getOrNull()
    val preview = definition?.let { ScheduleTime.preview(it, Instant.now(), ZoneId.systemDefault()) }.orEmpty()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Heading(if (initial == null) "Add website check" else "Edit website check")
        OutlinedTextField(label, { label = it.take(80) }, label = { Text("Name") }, singleLine = true)
        OutlinedTextField(address, { address = it.take(4096) }, label = { Text("Website URL") }, singleLine = true)
        Text("Use a public address without a login, query string, or credentials.")
        OutlinedTextField(time, { time = it.take(5) }, label = { Text("Daily time (HH:mm)") }, singleLine = true)
        Row(
            Modifier.toggleable(follow, role = Role.Checkbox, onValueChange = { follow = it }),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(follow, onCheckedChange = null)
            Text("Follow device timezone")
        }
        OutlinedTextField(zone, {
            zone = it.take(80)
        }, label = { Text("Timezone ID") }, enabled = !follow, singleLine = true)
        Row(
            Modifier.toggleable(local, role = Role.Checkbox, onValueChange = { local = it }),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(local, onCheckedChange = null)
            Text("Allow HTTP for a local server")
        }
        if (local) LocalNetworkAccess("Checks on your Wi-Fi network require local network access.")
        Text("Approximate timing. Missed checks are skipped; daylight-saving gaps are skipped and overlaps run once.")
        Row(
            Modifier.toggleable(notify, role = Role.Checkbox, onValueChange = { checked ->
                if (checked && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    notify = checked
                }
            }),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(notify, onCheckedChange = null)
            Text("Notify when a check finishes")
        }
        if (!notificationAccess) {
            Text("Notifications are off. Results still appear in Automations.")
            TextButton(onClick = {
                context.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                )
            }) { Text("Notification settings") }
        }
        preview.forEach { Text(formatOccurrence(it.atMillis, it.zoneId)) }
        if (definition == null) Text("Enter a name, valid URL, time, and timezone.")
        Row {
            Button(onClick = { definition?.let(save) }, enabled = definition != null) { Text("Save") }
            TextButton(onClick = dismiss) { Text("Cancel") }
        }
    }
}
