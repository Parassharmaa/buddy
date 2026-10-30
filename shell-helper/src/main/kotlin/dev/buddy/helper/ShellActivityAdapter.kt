package dev.buddy.helper

import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import android.os.IBinder

// A shell-attributed launch has no registered ApplicationThread; pass a null caller
// to the version-probed system interface. This adapter can launch only the fixture.
@android.annotation.SuppressLint("PrivateApi", "BlockedPrivateApi")
internal object ShellActivityAdapter {
    fun launchFixture(displayId: Int, appUid: Int) {
        require(displayId > 0)
        val intent = Intent().setClassName("dev.buddy.fixture", "dev.buddy.fixture.FixtureActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
        val service = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
        val result = Class.forName("android.app.IActivityTaskManager").getMethod(
            "startActivityAsUser", Class.forName("android.app.IApplicationThread"),
            String::class.java, String::class.java, Intent::class.java, String::class.java,
            IBinder::class.java, String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Class.forName("android.app.ProfilerInfo"), Bundle::class.java, Int::class.javaPrimitiveType
        ).invoke(
            service, null, "com.android.shell", null, intent, null, null, null, 0, 0, null, options, appUid / 100_000
        ) as Int
        check(result in 0..3) { "Fixture launch rejected" }
    }
}
