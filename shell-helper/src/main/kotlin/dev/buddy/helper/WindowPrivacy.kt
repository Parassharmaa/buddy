package dev.buddy.helper

import android.os.Build
import android.view.InputWindowHandle
import android.view.WindowManager
import android.window.WindowInfosListener

// Only called in the authenticated shell process for an already validated owned display.
internal object WindowPrivacy {
    enum class Status { UNAVAILABLE, AVAILABLE, EMPTY, LIMIT, UNSUPPORTED, PERMISSION_DENIED, QUERY_FAILED }
    data class Result(
        val status: Status = Status.UNAVAILABLE,
        val secure: Boolean = false,
        val mixed: Boolean = false
    ) {
        val known: Boolean get() = status == Status.AVAILABLE
    }

    private class Listener : WindowInfosListener() {
        override fun onWindowInfosChanged(
            windows: Array<InputWindowHandle>,
            displays: Array<WindowInfosListener.DisplayInfo>
        ) = Unit
    }

    @android.annotation.SuppressLint("PrivateApi", "BlockedPrivateApi")
    fun inspect(displayId: Int, fixtureUid: Int): Result {
        require(displayId > 0)
        if (Build.VERSION.SDK_INT < 34 || fixtureUid < 10000) return Result(Status.UNSUPPORTED)
        return try {
            val notVisible = Class.forName("android.os.InputConfig").getField("NOT_VISIBLE").getInt(null)
            val listener = Listener()
            var registered = false
            try {
                val initial = listener.register()
                registered = true
                val windows = requireNotNull(initial.first)
                if (windows.size > 2048) return Result(Status.LIMIT)
                // Do not access names, package strings, surfaces or pixel buffers, including on display 0.
                val scoped = windows.filter { it.displayId == displayId && it.inputConfig and notVisible == 0 }
                Result(
                    status = if (scoped.isNotEmpty()) Status.AVAILABLE else Status.EMPTY,
                    secure = scoped.any { it.layoutParamsFlags and WindowManager.LayoutParams.FLAG_SECURE != 0 },
                    mixed = scoped.any { it.ownerUid != fixtureUid }
                )
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
