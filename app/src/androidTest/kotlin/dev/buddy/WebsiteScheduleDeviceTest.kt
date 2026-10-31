package dev.buddy

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.buddy.core.scheduling.ScheduleCheckState
import dev.buddy.core.scheduling.ScheduleDefinition
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebsiteScheduleDeviceTest {
    @Test(timeout = 60_000)
    fun workManagerPerformsOneHeaderCheckWithoutCookiesAndPersistsTheNextOccurrence() = runBlocking {
        val application =
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as BuddyApplication
        application.schedules.cancelAll()
        application.store.clear()
        val socket = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        val executor = Executors.newSingleThreadExecutor()
        val requests = AtomicInteger()
        val server = executor.submit {
            socket.accept().use { client ->
                client.soTimeout = 5_000
                val reader = client.getInputStream().bufferedReader()
                assertEquals("HEAD /status HTTP/1.1", reader.readLine())
                var count = 0
                while (true) {
                    val line = reader.readLine() ?: error("Missing request terminator")
                    if (line.isEmpty()) break
                    assertTrue(++count <= 32)
                    assertTrue(!line.startsWith("Cookie:", true))
                    assertTrue(!line.startsWith("Authorization:", true))
                }
                requests.incrementAndGet()
                client.getOutputStream().write("HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n".toByteArray())
            }
        }
        try {
            val zone = ZoneId.systemDefault()
            val due = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MINUTES)
            val local = due.atZone(zone)
            val rule = application.store.schedules.save(
                ScheduleDefinition(
                    UUID.randomUUID().toString(),
                    "Synthetic status",
                    "http://127.0.0.1:${socket.localPort}/status",
                    local.hour,
                    local.minute,
                    zone.id,
                    localHttp = true,
                    notify = true
                ),
                due.minusSeconds(1),
                zone
            )
            application.schedules.enqueue(rule)
            application.schedules.enqueue(rule)
            val result = withTimeout(30_000) {
                application.store.schedules.observe().first { snapshot ->
                    snapshot.checks.any { it.scheduleId == rule.definition.id && it.state == ScheduleCheckState.HTTP }
                }
            }
            server.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(1, requests.get())
            assertEquals(204, result.checks.single().httpCode)
            if (application.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                assertTrue(
                    application.getSystemService(android.app.NotificationManager::class.java)
                        .activeNotifications.isEmpty()
                )
            }
            assertTrue(
                requireNotNull(application.store.schedules.get(rule.definition.id)).next.atMillis >
                    Instant.now().toEpochMilli()
            )
        } finally {
            application.schedules.cancelAll()
            application.store.clear()
            application.schedules.cancelAll()
            application.schedules.updateReceiver()
            socket.close()
            executor.shutdownNow()
        }
    }
}
