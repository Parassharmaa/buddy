package dev.buddy.helper

import android.os.Build
import android.os.Process
import android.util.Size
import android.view.InputWindowHandle
import android.view.WindowManager
import android.window.WindowInfosListener
import dev.buddy.shell.ShellProtocol
import java.lang.reflect.Field
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
        val foreignWindows: List<Int> = emptyList(),
        val foreignGeometry: List<Int> = emptyList(),
        val monitorProbe: String = "unavailable"
    ) {
        val known: Boolean get() = status == Status.AVAILABLE
    }

    private fun summarize(
        windows: Array<InputWindowHandle>,
        displayId: Int,
        fixtureUid: Int,
        sinks: SystemInputSinkPolicy,
        monitor: Monitor?,
        monitorProbe: String
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
                ) && monitor?.matches(it) != true
            },
            windowCount = windows.size,
            scopedCount = scoped.size,
            displays = windows.map { it.displayId }.distinct().sorted().take(16),
            foreignWindows = scoped.filter { it.ownerUid != fixtureUid }.take(16).flatMap {
                listOf(it.ownerUid, it.layoutParamsType, it.inputConfig, it.layoutParamsFlags)
            },
            foreignGeometry = scoped.filter { it.ownerUid != fixtureUid }.take(16).flatMap {
                monitor?.geometry(it) ?: listOf(if (it.token != null) 1 else 0, -1, -1, 5)
            },
            monitorProbe = monitorProbe + ";fixture=" +
                (scoped.firstOrNull { it.ownerUid == fixtureUid }?.let { monitor?.geometry(it) } ?: emptyList<Int>())
                    .joinToString(",")
        )
    }

    private class Monitor(private val policy: SystemInputMonitorPolicy, private val contentSize: Field) {
        val contentType: String get() = contentSize.type.name

        fun geometry(window: InputWindowHandle): List<Int> {
            var status = 0
            val size = try {
                val value = contentSize.get(window)
                status = when (value) {
                    null -> 1
                    is Size -> 0
                    else -> 2
                }
                value as? Size
            } catch (_: Exception) {
                status = 3
                null
            } catch (_: LinkageError) {
                status = 4
                null
            }
            // token, width, height, status: missing geometry never implies a bufferless layer.
            return listOf(if (window.token != null) 1 else 0, size?.width ?: -1, size?.height ?: -1, status)
        }

        fun matches(window: InputWindowHandle): Boolean {
            val values = geometry(window)
            return policy.matches(
                window.ownerUid,
                window.layoutParamsType,
                window.inputConfig,
                window.layoutParamsFlags,
                window.token != null,
                values[1],
                values[2]
            )
        }
    }

    private class Listener(
        private val displayId: Int,
        private val fixtureUid: Int,
        private val sinks: SystemInputSinkPolicy,
        private val monitor: Monitor?,
        private val monitorProbe: String
    ) : WindowInfosListener() {
        val latest = AtomicReference<Result?>()
        val ready = CountDownLatch(1)
        fun initial(windows: Array<InputWindowHandle>) {
            val summary = summarize(windows, displayId, fixtureUid, sinks, monitor, monitorProbe)
            latest.compareAndSet(null, summary)
        }
        override fun onWindowInfosChanged(
            windows: Array<InputWindowHandle>,
            displays: Array<WindowInfosListener.DisplayInfo>
        ) {
            val summary = summarize(windows, displayId, fixtureUid, sinks, monitor, monitorProbe)
            latest.set(summary)
            ready.countDown()
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
            var monitorProbe = "TYPE_SECURE_SYSTEM_OVERLAY"
            val monitorBits = mutableListOf<Int>()
            fun monitorBit(name: String): Int {
                monitorProbe = name
                return bit(name).also { monitorBits += it }
            }
            val monitor = try {
                val type = WindowManager.LayoutParams::class.java.getDeclaredField("TYPE_SECURE_SYSTEM_OVERLAY")
                    .getInt(null).also { monitorBits += it }
                val policy = SystemInputMonitorPolicy(
                    type,
                    monitorBit("NOT_FOCUSABLE"),
                    monitorBit("SPY"),
                    monitorBit("TRUSTED_OVERLAY"),
                    monitorBit("DO_NOT_PILFER"),
                    monitorBit("DISPLAY_TOPOLOGY_AWARE")
                )
                monitorProbe = "contentSize"
                Monitor(policy, InputWindowHandle::class.java.getDeclaredField("contentSize")).also {
                    monitorProbe = "ready:${monitorBits.joinToString(",")}:${it.contentType}"
                }
            } catch (failure: ReflectiveOperationException) {
                monitorProbe += ":" + failure.javaClass.simpleName
                null
            } catch (failure: IllegalArgumentException) {
                monitorProbe += ":" + failure.javaClass.simpleName
                null
            } catch (failure: LinkageError) {
                monitorProbe += ":" + failure.javaClass.simpleName
                null
            }
            val listener = Listener(displayId, fixtureUid, sinks, monitor, monitorProbe)
            var registered = false
            try {
                val initial = listener.register()
                registered = true
                listener.initial(requireNotNull(initial.first))
                // register() returns a process-local cache. Never grant observation from that cache.
                if (listener.ready.await(200, TimeUnit.MILLISECONDS)) {
                    listener.latest.get() ?: Result(monitorProbe = monitorProbe)
                } else {
                    Result(monitorProbe = monitorProbe)
                }
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
