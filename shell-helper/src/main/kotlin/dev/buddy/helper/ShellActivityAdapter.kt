package dev.buddy.helper

import android.app.ActivityOptions
import android.app.TaskInfo
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock

// A shell-attributed launch has no registered ApplicationThread; pass a null caller
// to the version-probed system interface. This adapter can launch only the fixture.
@android.annotation.SuppressLint("PrivateApi", "BlockedPrivateApi")
internal object ShellActivityAdapter {
    private fun service(): Any = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)

    private val contract get() = Class.forName("android.app.IActivityTaskManager")

    fun launchFixture(displayId: Int, appUid: Int): Int {
        require(displayId > 0)
        val previous = roots(displayId).map { it.taskId }.toSet()
        check(roots(displayId).none { it.numActivities > 0 }) { "Workspace is occupied" }
        val intent = Intent().setClassName("dev.buddy.fixture", "dev.buddy.fixture.FixtureActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
        val result = contract.getMethod(
            "startActivityAsUser", Class.forName("android.app.IApplicationThread"),
            String::class.java, String::class.java, Intent::class.java, String::class.java,
            IBinder::class.java, String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Class.forName("android.app.ProfilerInfo"), Bundle::class.java, Int::class.javaPrimitiveType
        ).invoke(
            service(), null, "com.android.shell", null, intent, null, null, null, 0, 0, null, options, appUid / 100_000
        ) as Int
        check(result in 0..3) { "Fixture launch rejected ($result)" }
        val deadline = SystemClock.elapsedRealtime() + 2_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val created = roots(displayId).filter { it.taskId !in previous && it.numActivities > 0 }
            if (created.isNotEmpty()) {
                val fixture = created.single()
                verifyFixture(fixture, appUid)
                return fixture.taskId
            }
            SystemClock.sleep(25)
        }
        error("Fixture task could not be identified")
    }

    private fun roots(displayId: Int): List<TaskInfo> {
        val result = contract.getMethod("getAllRootTaskInfosOnDisplay", Int::class.javaPrimitiveType)
            .invoke(service(), displayId) as List<*>
        return result.map { it as TaskInfo }
    }

    private fun verifyFixture(root: TaskInfo, appUid: Int) {
        check(root.isRunning && root.numActivities == 1)
        check(root.topActivity?.packageName == "dev.buddy.fixture")
        check(root.baseActivity?.packageName == "dev.buddy.fixture")
        check(root.javaClass.getField("userId").getInt(root) == appUid / 100_000)
        val children = root.javaClass.getField("childTaskNames").get(root) as Array<*>?
        check(
            children != null && children.size <= 1 && children.all {
                it is String && (it.startsWith("dev.buddy.fixture/") || it == "dev.buddy.fixture.FixtureActivity")
            }
        ) { "Fixture task contains unowned activities" }
    }

    fun verifyOwnedFixture(rootId: Int, displayId: Int, appUid: Int) {
        val running = roots(displayId).filter { it.numActivities > 0 }
        check(running.size == 1 && running.single().taskId == rootId) { "Workspace changed" }
        verifyFixture(running.single(), appUid)
    }

    fun moveFixture(rootId: Int, fromDisplay: Int, toDisplay: Int, appUid: Int) {
        require(fromDisplay >= 0 && toDisplay >= 0 && fromDisplay != toDisplay)
        val root = roots(fromDisplay).single { it.taskId == rootId }
        verifyFixture(root, appUid)
        contract.getMethod("moveRootTaskToDisplay", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(service(), rootId, toDisplay)
        check(roots(toDisplay).any { it.taskId == rootId }) { "Task move could not be verified" }
    }
}
