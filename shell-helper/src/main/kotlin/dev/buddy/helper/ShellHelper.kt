package dev.buddy.helper

import android.app.KeyguardManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.InputEvent
import android.view.MotionEvent
import android.view.Surface
import dev.buddy.shell.IBuddyHelper
import dev.buddy.shell.ShellProtocol
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class ShellHelper(private val context: Context, private val appUid: Int, private val signingDigest: String) :
    IBuddyHelper.Stub() {
    private data class OwnedDisplay(
        val display: VirtualDisplay,
        val width: Int,
        val height: Int,
        var fixtureRoot: Int? = null,
        var fixtureUid: Int = -1,
        var launchAttempted: Boolean = false,
        var handedOver: Boolean = false
    )
    private val displays = mutableMapOf<String, OwnedDisplay>()
    private val requests = LinkedHashMap<String, Boolean>()
    private var lease: String? = null
    private var owner: IBinder? = null
    private var leaseDeadline = 0L
    private var authorizationDeadline = 0L
    private var authorizationChallenge: ByteArray? = null
    private var challengeDeadline = 0L
    private val usedChallenges = mutableSetOf<String>()
    private var idleSince = SystemClock.elapsedRealtime()
    private var stopped = false
    private val death = IBinder.DeathRecipient { synchronized(this) { cleanup() } }
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    init {
        scheduler.scheduleWithFixedDelay({
            synchronized(this) {
                val now = SystemClock.elapsedRealtime()
                if (lease != null && now >= minOf(leaseDeadline, authorizationDeadline)) cleanup()
                if (owner == null && now - idleSince >= 120_000) {
                    stopped = true
                    scheduler.shutdown()
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
            }
        }, 1, 1, TimeUnit.SECONDS)
    }

    @Synchronized internal fun authorizedNow() {
        authorizationDeadline =
            SystemClock.elapsedRealtime() + ShellProtocol.LEASE_MS
    }

    @Synchronized override fun prepareAuthorization(challenge: ByteArray) = asShell {
        check(SystemClock.elapsedRealtime() < authorizationDeadline) { "Authorization expired" }
        reserveChallenge(challenge)
        authorizationChallenge?.fill(0)
        authorizationChallenge = java.security.MessageDigest.getInstance("SHA-256").digest(challenge)
        challengeDeadline = SystemClock.elapsedRealtime() + 15_000
    }

    @Synchronized override fun completeAuthorization(challenge: ByteArray) {
        // Only the new shell process launched through a fresh authorized transport can finish
        // this proof. The app and this helper's own process cannot refresh the timestamp.
        check(Binder.getCallingUid() == ShellProtocol.SHELL_UID)
        check(Binder.getCallingPid() != android.os.Process.myPid())
        check(!stopped && challenge.size == 32)
        val now = SystemClock.elapsedRealtime()
        check(now < minOf(authorizationDeadline, challengeDeadline)) { "Authorization expired" }
        val expected = requireNotNull(authorizationChallenge)
        check(
            java.security.MessageDigest.isEqual(
                expected,
                java.security.MessageDigest.getInstance("SHA-256").digest(challenge)
            )
        ) { "Authorization challenge mismatch" }
        expected.fill(0)
        authorizationChallenge = null
        challengeDeadline = 0
        authorizationDeadline = now + ShellProtocol.LEASE_MS
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
            putInt("helperPid", android.os.Process.myPid())
            putLong("authorizationDeadline", authorizationDeadline)
            putBoolean(
                "secondaryDisplays",
                context.packageManager.hasSystemFeature("android.software.activities_on_secondary_displays")
            )
            putBoolean("qualified", false)
            putString("qualification", "Real-device focus, keyboard, revocation and secure-window tests are required")
        }
    }

    @Synchronized override fun acquireLease(newOwner: IBinder, challenge: ByteArray): Bundle = asShell {
        reserveChallenge(challenge)
        check(lease == null)
        check(SystemClock.elapsedRealtime() < authorizationDeadline)
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
        reserveChallenge(challenge)
        leaseDeadline = SystemClock.elapsedRealtime() + ShellProtocol.LEASE_MS
        // Deliberately does not extend authorization freshness. Revalidation is a separate proof.
    }

    @Synchronized
    @android.annotation.SuppressLint("PrivateApi", "BlockedPrivateApi")
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
        try {
            val actual = display.display
            check(actual.isValid && actual.displayId > Display.DEFAULT_DISPLAY) { "Owned display unavailable" }
            fun effectiveFlag(name: String) = Display::class.java.getDeclaredField("FLAG_$name").getInt(null)
            val required = listOf("TRUSTED", "OWN_FOCUS", "STEAL_TOP_FOCUS_DISABLED").fold(0) { mask, name ->
                mask or effectiveFlag(name)
            }
            val forbidden = Display.FLAG_PRIVATE or Display.FLAG_SECURE or effectiveFlag("ALWAYS_UNLOCKED")
            check(actual.flags and required == required && actual.flags and forbidden == 0) {
                "Required display isolation flags were not applied"
            }
        } catch (failure: Throwable) {
            runCatching { display.release() }
            throw failure
        }
        val token = UUID.randomUUID().toString()
        displays[token] = OwnedDisplay(display, width, height)
        token
    }

    @Synchronized override fun releaseDisplay(value: String, token: String) = asShell {
        validLease(value)
        requireNotNull(displays.remove(token)).display.release()
    }

    @Synchronized override fun displayState(value: String, token: String): Bundle = asShell {
        val display = owned(value, token)
        display.fixtureRoot?.let {
            ShellActivityAdapter.verifyOwnedFixture(it, display.display.display.displayId, appUid)
        }
        val privacy = WindowPrivacy.inspect(display.display.display.displayId, display.fixtureUid)
        Bundle().apply {
            putInt("windowMetadataStatus", privacy.status.ordinal)
            putBoolean("windowMetadataKnown", privacy.known)
            putBoolean("secureWindow", privacy.secure)
            putBoolean("mixedWindowOwners", privacy.mixed)
            putInt("displayId", display.display.display.displayId)
            putInt("width", display.width)
            putInt("height", display.height)
        }
    }

    @Synchronized override fun launchFixture(value: String, token: String, requestId: String): Int = asShell {
        val display = owned(value, token)
        check(!display.launchAttempted)
        reserve(requestId)
        val fixture = context.packageManager.getPackageInfo(
            "dev.buddy.fixture",
            android.content.pm.PackageManager.PackageInfoFlags.of(
                android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES.toLong()
            )
        )
        check(fixture.longVersionCode == 1L)
        val signer = requireNotNull(fixture.signingInfo).apkContentsSigners.single()
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(signer.toByteArray())
            .joinToString("") { "%02x".format(it) }
        check(java.security.MessageDigest.isEqual(digest.toByteArray(), signingDigest.toByteArray())) {
            "Fixture signature mismatch"
        }
        // Engineering spike launches only Buddy's controlled fixture; arbitrary app launch is gated later.
        display.launchAttempted = true
        display.fixtureUid = requireNotNull(fixture.applicationInfo).uid
        display.fixtureRoot = ShellActivityAdapter.launchFixture(display.display.display.displayId, appUid)
        display.display.display.displayId
    }

    @Synchronized override fun takeOverFixture(value: String, token: String, requestId: String) = asShell {
        val display = owned(value, token)
        reserve(requestId)
        val source = display.display.display.displayId
        val root = requireNotNull(display.fixtureRoot)
        ShellActivityAdapter.moveFixture(root, source, 0, appUid)
        display.handedOver = true
    }

    @Synchronized override fun returnFixture(value: String, token: String, requestId: String) = asShell {
        val display = owned(value, token, allowTakeover = true)
        check(display.handedOver)
        reserve(requestId)
        ShellActivityAdapter.moveFixture(
            requireNotNull(display.fixtureRoot),
            0,
            display.display.display.displayId,
            appUid
        )
        display.handedOver = false
    }

    // Hidden input APIs are restricted to this shell-UID process and require device qualification.
    @android.annotation.SuppressLint("PrivateApi", "BlockedPrivateApi")
    @Synchronized
    override fun tap(value: String, token: String, requestId: String, x: Int, y: Int) = asShell {
        val display = owned(value, token)
        require(x in 0 until display.width && y in 0 until display.height)
        reserve(requestId)
        ShellActivityAdapter.verifyOwnedFixture(
            requireNotNull(display.fixtureRoot),
            display.display.display.displayId,
            appUid
        )
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
        // Recheck expiry and lock after the task-manager call, immediately before DOWN.
        owned(value, token)
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
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.os.Process.killProcess(android.os.Process.myPid())
        }
        Unit
    }

    private fun validLease(value: String) {
        check(value == lease && owner?.isBinderAlive == true)
        check(SystemClock.elapsedRealtime() < minOf(leaseDeadline, authorizationDeadline)) { "Authorization expired" }
    }
    private fun owned(value: String, token: String, allowTakeover: Boolean = false): OwnedDisplay {
        validLease(value)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        check(!keyguard.isDeviceLocked && !keyguard.isKeyguardLocked)
        check(context.getSystemService(PowerManager::class.java).isInteractive)
        return requireNotNull(displays[token]).also {
            check(it.display.display.isValid && (allowTakeover || !it.handedOver))
        }
    }
    private fun reserveChallenge(challenge: ByteArray) {
        require(challenge.size == 32 && usedChallenges.size < 128)
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(challenge)
        check(usedChallenges.add(android.util.Base64.encodeToString(hash, android.util.Base64.NO_WRAP))) {
            "Replayed challenge"
        }
    }
    private fun reserve(id: String) {
        require(id.length in 1..128 && requests.size < 1024)
        check(requests.putIfAbsent(id, true) == null) { "Duplicate request" }
    }
    private fun cleanup() {
        authorizationChallenge?.fill(0)
        authorizationChallenge = null
        challengeDeadline = 0
        if (owner != null || lease != null) idleSince = SystemClock.elapsedRealtime()
        displays.values.forEach { runCatching { it.display.release() } }
        displays.clear()
        owner?.let { runCatching { it.unlinkToDeath(death, 0) } }
        owner = null
        lease = null
        leaseDeadline = 0
    }
}
