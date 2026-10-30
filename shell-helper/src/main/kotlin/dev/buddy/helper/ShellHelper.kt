package dev.buddy.helper

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.view.InputEvent
import android.view.MotionEvent
import android.view.Surface
import dev.buddy.shell.IBuddyHelper
import dev.buddy.shell.ShellProtocol
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class ShellHelper(private val context: Context, private val appUid: Int) : IBuddyHelper.Stub() {
    private data class OwnedDisplay(val display: VirtualDisplay, val width: Int, val height: Int)
    private val displays = mutableMapOf<String, OwnedDisplay>()
    private val requests = LinkedHashMap<String, Boolean>()
    private var lease: String? = null
    private var owner: IBinder? = null
    private var leaseDeadline = 0L
    private var authorizationDeadline = 0L
    private var lastChallenge = byteArrayOf()
    private var stopped = false
    private val death = IBinder.DeathRecipient { synchronized(this) { cleanup() } }
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    init {
        scheduler.scheduleAtFixedRate({
            synchronized(this) {
                if (SystemClock.elapsedRealtime() >= minOf(leaseDeadline, authorizationDeadline)) cleanup()
            }
        }, 1, 1, TimeUnit.SECONDS)
    }

    @Synchronized internal fun authorizedNow() {
        authorizationDeadline =
            SystemClock.elapsedRealtime() + ShellProtocol.LEASE_MS
    }

    private inline fun <T> asShell(block: () -> T): T {
        check(Binder.getCallingUid() == appUid) { "Unauthorized caller" }
        check(!stopped) { "Disconnected" }
        val identity = Binder.clearCallingIdentity()
        return try {
            block()
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    @Synchronized override fun capabilities(): Bundle = asShell {
        Bundle().apply {
            putInt("protocol", ShellProtocol.VERSION)
            putInt("helperUid", android.os.Process.myUid())
            putBoolean(
                "secondaryDisplays",
                context.packageManager.hasSystemFeature("android.software.activities_on_secondary_displays")
            )
            putBoolean("qualified", false)
            putString("qualification", "Real-device focus, keyboard, revocation and secure-window tests are required")
        }
    }

    @Synchronized override fun acquireLease(newOwner: IBinder, challenge: ByteArray): Bundle = asShell {
        require(challenge.size == 32 && !challenge.contentEquals(lastChallenge))
        check(lease == null)
        check(SystemClock.elapsedRealtime() < authorizationDeadline)
        lastChallenge = challenge.copyOf()
        owner = newOwner
        newOwner.linkToDeath(death, 0)
        lease = UUID.randomUUID().toString()
        leaseDeadline = SystemClock.elapsedRealtime() + ShellProtocol.LEASE_MS
        Bundle().apply {
            putString("lease", lease)
            putLong("deadline", minOf(leaseDeadline, authorizationDeadline))
        }
    }

    @Synchronized override fun renewLease(value: String, challenge: ByteArray) = asShell {
        validLease(value)
        require(challenge.size == 32 && !challenge.contentEquals(lastChallenge))
        lastChallenge = challenge.copyOf()
        leaseDeadline = SystemClock.elapsedRealtime() + ShellProtocol.LEASE_MS
        // Deliberately does not extend authorization freshness. Revalidation is a separate proof.
    }

    @Synchronized
    override fun createDisplay(value: String, width: Int, height: Int, dpi: Int, surface: Surface): String = asShell {
        validLease(value)
        require(
            width in 320..1920 && height in 320..2400 &&
                width.toLong() * height <= 2_073_600 && dpi in 120..640
        )
        require(surface.isValid && displays.isEmpty())
        val requiredFlags =
            listOf(
                "PUBLIC",
                "OWN_CONTENT_ONLY",
                "TRUSTED",
                "SUPPORTS_TOUCH",
                "DESTROY_CONTENT_ON_REMOVAL",
                "OWN_FOCUS",
                "STEAL_TOP_FOCUS_DISABLED"
            )
        val flags = requiredFlags.fold(0) { acc, name ->
            acc or
                DisplayManager::class.java.getDeclaredField("VIRTUAL_DISPLAY_FLAG_" + name).getInt(null)
        }
        val manager = context.getSystemService(DisplayManager::class.java)
        val display =
            requireNotNull(manager.createVirtualDisplay("Buddy workspace", width, height, dpi, surface, flags))
        check(display.display.displayId != android.view.Display.DEFAULT_DISPLAY)
        val token = UUID.randomUUID().toString()
        displays[token] = OwnedDisplay(display, width, height)
        token
    }

    @Synchronized override fun releaseDisplay(value: String, token: String) = asShell {
        validLease(value)
        requireNotNull(displays.remove(token)).display.release()
    }

    @Synchronized override fun launchFixture(value: String, token: String, requestId: String): Int = asShell {
        val display = owned(value, token)
        reserve(requestId)
        // Engineering spike launches only Buddy's controlled fixture; arbitrary app launch is gated later.
        val intent = Intent().setClassName("dev.buddy.fixture", "dev.buddy.fixture.FixtureActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        val options = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(display.display.display.displayId)
        context.startActivity(intent, options.toBundle())
        display.display.display.displayId
    }

    @Synchronized override fun tap(value: String, token: String, requestId: String, x: Int, y: Int) = asShell {
        val display = owned(value, token)
        require(x in 0 until display.width && y in 0 until display.height)
        reserve(requestId)
        val managerClass = Class.forName("android.hardware.input.InputManagerGlobal")
        val manager = managerClass.getMethod("getInstance").invoke(null)
        val inject = managerClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
        val setDisplay = InputEvent::class.java.getDeclaredMethod("setDisplayId", Int::class.javaPrimitiveType)
        val start = SystemClock.uptimeMillis()
        fun event(action: Int) {
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x.toFloat(), y.toFloat(), 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try {
                setDisplay.invoke(event, display.display.display.displayId)
                check(inject.invoke(manager, event, 2) == true)
            } finally {
                event.recycle()
            }
        }
        try {
            event(MotionEvent.ACTION_DOWN)
        } finally {
            event(MotionEvent.ACTION_UP)
        }
    }

    @Synchronized override fun releaseLease(value: String) = asShell {
        check(value == lease)
        cleanup()
    }

    @Synchronized override fun disconnect() = asShell {
        cleanup()
        stopped = true
        scheduler.shutdownNow()
        android.os.Handler(android.os.Looper.getMainLooper()).post { android.os.Looper.getMainLooper().quitSafely() }
        Unit
    }

    private fun validLease(value: String) {
        check(value == lease && owner?.isBinderAlive == true)
        check(SystemClock.elapsedRealtime() < minOf(leaseDeadline, authorizationDeadline)) { "Authorization expired" }
    }
    private fun owned(value: String, token: String): OwnedDisplay {
        validLease(value)
        check(!context.getSystemService(KeyguardManager::class.java).isDeviceLocked)
        check(context.getSystemService(PowerManager::class.java).isInteractive)
        return requireNotNull(displays[token])
    }
    private fun reserve(id: String) {
        require(id.length in 1..128 && requests.size < 1024)
        check(requests.putIfAbsent(id, true) == null) { "Duplicate request" }
    }
    private fun cleanup() {
        displays.values.forEach { runCatching { it.display.release() } }
        displays.clear()
        owner?.let { runCatching { it.unlinkToDeath(death, 0) } }
        owner = null
        lease = null
        leaseDeadline = 0
    }
}
