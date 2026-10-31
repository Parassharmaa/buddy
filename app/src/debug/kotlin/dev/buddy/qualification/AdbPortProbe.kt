package dev.buddy.qualification

import android.os.IBinder
import android.os.Process
import dev.buddy.shell.ShellProtocol
import java.lang.reflect.InvocationTargetException

// Debug-only, read-only setup probe for disposable emulator tests. Never runs as the app UID.
object AdbPortProbe {
    @android.annotation.SuppressLint("PrivateApi", "BlockedPrivateApi", "SoonBlockedPrivateApi")
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            check(Process.myUid() == ShellProtocol.SHELL_UID && args.isEmpty())
            val service = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java).invoke(null, "adb") as IBinder
            val api = Class.forName("android.debug.IAdbManager\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, service)
            val port = Class.forName("android.debug.IAdbManager")
                .getMethod("getAdbWirelessPort").invoke(api) as Int
            println(if (port in 1..65535) "BUDDY_ADB_PORT:$port" else "BUDDY_ADB_PORT_NOT_READY")
        } catch (failure: Exception) {
            val cause = (failure as? InvocationTargetException)?.targetException ?: failure
            println("BUDDY_ADB_PORT_FAILED:${cause.javaClass.simpleName}")
        }
    }
}
