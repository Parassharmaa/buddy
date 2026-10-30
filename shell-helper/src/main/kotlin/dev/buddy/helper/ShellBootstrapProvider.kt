package dev.buddy.helper

import android.content.AttributionSource
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import dev.buddy.shell.ShellProtocol

// app_process has no AMS ProcessRecord/ApplicationThread. Use the external-provider shell
// adapter, with the real shell attribution and a balanced provider reference.
@android.annotation.SuppressLint("PrivateApi", "BlockedPrivateApi")
internal object ShellBootstrapProvider {
    fun call(packageName: String, appUid: Int, method: String, extras: Bundle): Bundle? {
        check(Process.myUid() == ShellProtocol.SHELL_UID)
        require(method in setOf(ShellProtocol.HANDOFF, ShellProtocol.REVALIDATE))
        val authority = packageName + ShellProtocol.AUTHORITY_SUFFIX
        val user = appUid / 100_000
        val token = Binder()
        val service = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)
        val manager = Class.forName("android.app.IActivityManager")
        val holder = requireNotNull(
            manager.getMethod(
                "getContentProviderExternal",
                String::class.java,
                Int::class.javaPrimitiveType,
                IBinder::class.java,
                String::class.java
            ).invoke(service, authority, user, token, "buddy-bootstrap")
        )
        try {
            val provider = requireNotNull(holder.javaClass.getField("provider").get(holder))
            val attribution = AttributionSource.Builder(Process.myUid()).setPackageName("com.android.shell").build()
            return Class.forName("android.content.IContentProvider").getMethod(
                "call",
                AttributionSource::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
                Bundle::class.java
            ).invoke(provider, attribution, authority, method, null, extras) as Bundle?
        } finally {
            manager.getMethod(
                "removeContentProviderExternalAsUser",
                String::class.java,
                IBinder::class.java,
                Int::class.javaPrimitiveType
            ).invoke(service, authority, token, user)
        }
    }
}
