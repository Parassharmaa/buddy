package dev.buddy.helper

import android.os.Build
import android.os.Process
import android.view.InputWindowHandle
import android.view.WindowManager
import android.window.WindowInfosListener
import dev.buddy.shell.ShellProtocol
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

// Only called in the authenticated shell process for an already validated owned display.
internal object WindowPrivacy {
    enum class Status { UNAVAILABLE, AVAILABLE, EMPTY, LIMIT, UNSUPPORTED, PERMISSION_DENIED, QUERY_FAILED }
    data class Result(
        val status: Status = Status.UNAVAILABLE,
        val secure: Boolean = false,
        val mixed: Boolean = false,
        val windowCount: Int = 0,
        val scopedCount: Int = 0,
        val displays: List<Int> = emptyList(),
        val foreignWindows: List<Int> = emptyList()
    ) {
        val known: Boolean get() = status == Status.AVAILABLE
    }

    private fun summarize(
        windows: Array<InputWindowHandle>,
        displayId: Int,
        fixtureUid: Int,
        sinks: SystemInputSinkPolicy
    ): Result {
        if (windows.size > 2048) return Result(Status.LIMIT)
        // Include hidden windows too: a secure window must never be discarded by visibility filtering.
        // Do not access names, package strings, surfaces or pixels, including on display 0.
        val scoped = windows.filter { it.displayId == displayId }
        return Result(
            status = if (scoped.any { it.ownerUid == fixtureUid }) Status.AVAILABLE else Status.EMPTY,
            secure = scoped.any { it.layoutParamsFlags and WindowManager.LayoutParams.FLAG_SECURE != 0 },
            mixed = scoped.any {
                it.ownerUid != fixtureUid && !sinks.matches(
                    it.ownerUid,
                    it.layoutParamsType,
                    it.inputConfig,
                    it.layoutParamsFlags,
                    it.token != null
                )
            },
            windowCount = windows.size,
            scopedCount = scoped.size,
            displays = windows.map { it.displayId }.distinct().sorted().take(16),
            foreignWindows = scoped.filter { it.ownerUid != fixtureUid }.take(16).flatMap {
                listOf(it.ownerUid, it.layoutParamsType, it.inputConfig, it.layoutParamsFlags)
            }
        )
    }

    private class Listener(
        private val displayId: Int,
        private val fixtureUid: Int,
        private val sinks: SystemInputSinkPolicy
    ) : WindowInfosListener() {
        val latest = AtomicReference<Result?>()
        val ready = CountDownLatch(1)
        fun initial(windows: Array<InputWindowHandle>) {
            val summary = summarize(windows, displayId, fixtureUid, sinks)
            latest.compareAndSet(null, summary)
            if (latest.get()?.known == true) ready.countDown()
        }
        override fun onWindowInfosChanged(
            windows: Array<InputWindowHandle>,
            displays: Array<WindowInfosListener.DisplayInfo>
        ) {
            val summary = summarize(windows, displayId, fixtureUid, sinks)
            latest.set(summary)
            if (summary.known) ready.countDown()
        }
    }

    @android.annotation.SuppressLint("PrivateApi", "BlockedPrivateApi", "SoonBlockedPrivateApi")
    fun inspect(displayId: Int, fixtureUid: Int): Result {
        require(displayId > 0)
        if (Process.myUid() != ShellProtocol.SHELL_UID) return Result(Status.PERMISSION_DENIED)
        if (Build.VERSION.SDK_INT < 34 || fixtureUid < 10000) return Result(Status.UNSUPPORTED)
        return try {
            val config = Class.forName("android.os.InputConfig")
            fun bit(name: String) = config.getDeclaredField(name).getInt(null)
            val sinks = SystemInputSinkPolicy(
                WindowManager.LayoutParams::class.java.getDeclaredField("TYPE_INPUT_CONSUMER").getInt(null),
                bit("NO_INPUT_CHANNEL"),
                bit("NOT_FOCUSABLE"),
                bit("NOT_TOUCHABLE")
            )
            val listener = Listener(displayId, fixtureUid, sinks)
            var registered = false
            try {
                val initial = listener.register()
                registered = true
                listener.initial(requireNotNull(initial.first))
                // The initial snapshot can precede the owned display's first input-window update.
                listener.ready.await(200, TimeUnit.MILLISECONDS)
                listener.latest.get() ?: Result()
            } finally {
                if (registered) listener.unregister()
            }
        } catch (_: SecurityException) {
            Result(Status.PERMISSION_DENIED)
        } catch (_: ReflectiveOperationException) {
            Result(Status.UNSUPPORTED)
        } catch (_: Exception) {
            Result(Status.QUERY_FAILED)
        } catch (_: LinkageError) {
            Result(Status.UNSUPPORTED)
        }
    }
}
