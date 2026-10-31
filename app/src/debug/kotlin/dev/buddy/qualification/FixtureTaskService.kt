package dev.buddy.qualification

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.net.toUri
import dev.buddy.BuddyApplication
import dev.buddy.EngineeringSessions
import dev.buddy.R
import dev.buddy.adb.AdbIdentity
import dev.buddy.adb.AdbTransport
import dev.buddy.connection.DaemonAuthorization
import dev.buddy.connection.PhoneConnection
import dev.buddy.core.LoopbackPort
import dev.buddy.core.ModelConfig
import dev.buddy.core.model.ModelClient
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json

internal data class FixtureTaskStatus(
    val id: String? = null,
    val message: String = "No supervised check running",
    val preview: FixturePreview? = null
)

class FixtureTaskService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val manager get() = getSystemService(NotificationManager::class.java)
    private var running: Job? = null
    private var runId: String? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            if (intent.data?.lastPathSegment == runId) running?.cancel()
            if (runId == null) stopSelf()
            return START_NOT_STICKY
        }
        if (runId != null) return START_NOT_STICKY
        val id = intent?.getStringExtra("run")
        val port = intent?.getIntExtra("port", 0) ?: 0
        if (Build.VERSION.SDK_INT < 34 || intent?.action != START ||
            runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false).not() ||
            port !in 1..65535 || !notificationsAllowed()
        ) {
            stopSelf()
            return START_NOT_STICKY
        }
        runId = id
        try {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Supervised fixture checks", NotificationManager.IMPORTANCE_LOW)
            )
            startForeground(
                NOTIFICATION,
                notification(requireNotNull(id), "Starting controlled fixture check"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } catch (_: RuntimeException) {
            status.value = FixtureTaskStatus(message = "Open the check screen and try again.")
            stopSelf()
            return START_NOT_STICKY
        }
        status.value = FixtureTaskStatus(id, "Starting supervised fixture check")
        running = scope.launch {
            try {
                EngineeringSessions.supervisor.supervise { permit ->
                    val phone = PhoneConnection(applicationContext)
                    val owner = currentCoroutineContext()[Job]!!
                    val monitor = launch {
                        withTimeout(5_000) {
                            while (manager.activeNotifications.none {
                                    it.id == NOTIFICATION && it.tag == null
                                }
                            ) {
                                delay(50)
                            }
                        }
                        while (isActive) {
                            if (!permit.isCurrent || !supervisionAvailable()) owner.cancel()
                            delay(100)
                        }
                    }
                    try {
                        withTimeout(100_000) {
                            withTimeout(5_000) {
                                while (!supervisionAvailable()) delay(50)
                            }
                            val config = withContext(Dispatchers.IO) {
                                (application as BuddyApplication).store.get("model-config")?.let {
                                    Json.decodeFromString<ModelConfig>(it)
                                }
                            } ?: error("No configured model")
                            publish(id, "Connecting to local wireless debugging")
                            withTimeout(30_000) { phone.connect(port) }
                            val helper = phone.engineeringHelper()
                            val socket = AtomicReference<AdbTransport?>()
                            val authorization = DaemonAuthorization(applicationContext, AdbIdentity())
                            fun admitted() = permit.isCurrent && supervisionAvailable()
                            ModelClient().use { model ->
                                ModelFixtureCheck.run(
                                    applicationContext,
                                    helper,
                                    config,
                                    model,
                                    visible = ::admitted,
                                    refreshAuthorization = {
                                        check(admitted())
                                        authorization.refresh(
                                            helper,
                                            LoopbackPort(port),
                                            onOpen = { socket.set(it) },
                                            onClose = { socket.compareAndSet(it, null) }
                                        )
                                    },
                                    stopTransport = { socket.getAndSet(null)?.close() },
                                    stage = { message ->
                                        withContext(Dispatchers.Main.immediate) {
                                            publish(id, message)
                                        }
                                    },
                                    preview = { value ->
                                        withContext(Dispatchers.Main.immediate) {
                                            status.value = status.value.copy(preview = value)
                                        }
                                    }
                                )
                            }
                            publish(id, "Controlled fixture task verified")
                        }
                    } finally {
                        withContext(NonCancellable) {
                            status.value = status.value.copy(preview = null)
                            monitor.cancelAndJoin()
                            phone.disconnect()
                        }
                    }
                }
            } catch (_: CancellationException) {
                status.value = FixtureTaskStatus(id, "Check stopped; no automatic retry")
            } catch (_: Exception) {
                status.value = FixtureTaskStatus(id, "Check could not finish. Review connection and fixture access.")
            } finally {
                status.value = status.value.copy(id = null)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    private fun notificationsAllowed() = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED && manager.areNotificationsEnabled()
    private fun supervisionAvailable(): Boolean {
        val keyguard = getSystemService(KeyguardManager::class.java)
        return notificationsAllowed() && manager.activeNotifications.any { it.id == NOTIFICATION && it.tag == null } &&
            getSystemService(PowerManager::class.java).isInteractive &&
            !keyguard.isDeviceLocked && !keyguard.isKeyguardLocked
    }
    private fun publish(id: String, message: String) {
        check(runId == id && notificationsAllowed())
        status.value = status.value.copy(id = id, message = message)
        manager.notify(NOTIFICATION, notification(id, message))
    }
    private fun notification(id: String, message: String): Notification {
        val stop = PendingIntent.getService(
            this,
            0,
            Intent(this, FixtureTaskService::class.java).setAction(STOP).setData("buddy://fixture/$id".toUri()),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, PhoneQualificationActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val public = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_buddy)
            .setContentTitle("Buddy").setContentText("A supervised task is active.").build()
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_buddy)
            .setContentTitle("Supervised fixture check").setContentText(message.take(120)).setOnlyAlertOnce(true)
            .setContentIntent(open).setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(public)
            .setLocalOnly(true).setOngoing(true).setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(Notification.Action.Builder(null, "Stop check", stop).build()).build()
    }
    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
    companion object {
        internal const val NOTIFICATION = 7301
        private const val CHANNEL = "supervised-fixture-checks"
        private const val START = "dev.buddy.fixture.START"
        private const val STOP = "dev.buddy.fixture.STOP"
        private val status = MutableStateFlow(FixtureTaskStatus())
        internal val state = status.asStateFlow()
        internal fun stop(activity: Activity) {
            val id = state.value.id ?: return
            activity.startService(
                Intent(activity, FixtureTaskService::class.java).setAction(STOP)
                    .setData("buddy://fixture/$id".toUri())
            )
        }
        internal fun start(activity: Activity, port: Int): Boolean {
            if (Build.VERSION.SDK_INT < 34 || !activity.hasWindowFocus() || port !in 1..65535 ||
                state.value.id != null || activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED ||
                !activity.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
            ) {
                return false
            }
            return try {
                activity.startForegroundService(
                    Intent(activity, FixtureTaskService::class.java).setAction(START)
                        .putExtra("run", UUID.randomUUID().toString()).putExtra("port", port)
                )
                true
            } catch (_: RuntimeException) {
                false
            }
        }
    }
}
