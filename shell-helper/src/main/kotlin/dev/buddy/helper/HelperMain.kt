package dev.buddy.helper

import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.os.Process
import android.util.Base64
import dev.buddy.shell.ShellProtocol
import java.security.MessageDigest

object HelperMain {
    @JvmStatic fun main(args: Array<String>) = bootstrap(args, false)
}

object RevalidateMain {
    @JvmStatic fun main(args: Array<String>) = bootstrap(args, true)
}

// app_process runs under UID 2000; this is a version-probed shell adapter, not app-UID reflection.
@android.annotation.SuppressLint("PrivateApi")
@Suppress("DEPRECATION")
internal fun bootstrap(args: Array<String>, revalidate: Boolean) {
    try {
        check(Process.myUid() == ShellProtocol.SHELL_UID)
        require(args.size == 3)
        val (packageName, uidText, expectedDigest) = args
        val expectedUid = uidText.toInt()
        require(packageName.matches(Regex("[a-zA-Z][a-zA-Z0-9_.]+")) && expectedUid >= 10000)
        require(expectedDigest.matches(Regex("[a-f0-9]{64}")))
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
        val activityThread = Class.forName("android.app.ActivityThread")
        val thread = activityThread.getMethod("systemMain").invoke(null)
        val system = activityThread.getMethod("getSystemContext").invoke(thread) as Context
        val context = system.createPackageContext("com.android.shell", 0)
        val info = context.packageManager.getPackageInfo(
            packageName,
            android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
        )
        check(info.applicationInfo?.uid == expectedUid)
        val certificates = requireNotNull(info.signingInfo).apkContentsSigners
        check(certificates.size == 1)
        val digest = MessageDigest.getInstance("SHA-256").digest(certificates.single().toByteArray()).joinToString("") {
            "%02x".format(it)
        }
        check(MessageDigest.isEqual(digest.toByteArray(), expectedDigest.toByteArray()))
        val nonceLine = System.`in`.bufferedReader().run {
            val buffer = StringBuilder()
            while (buffer.length <= 44) {
                val char = read()
                check(char >= 0)
                if (char == 10) break
                buffer.append(char.toChar())
            }
            buffer.toString()
        }
        val nonce = Base64.decode(nonceLine, Base64.NO_WRAP)
        require(nonce.size == 32)
        val helper = if (revalidate) null else ShellHelper(context, expectedUid).also { it.authorizedNow() }
        val extras = Bundle().apply {
            putByteArray("nonce", nonce)
            putInt("protocol", ShellProtocol.VERSION)
            if (helper != null) putBinder("helper", helper)
        }
        val result = context.contentResolver.call(
            packageName + ShellProtocol.AUTHORITY_SUFFIX,
            if (revalidate) ShellProtocol.REVALIDATE else ShellProtocol.HANDOFF,
            null,
            extras
        )
        check(result?.getBoolean("accepted") == true)
        nonce.fill(0)
        println("BUDDY_HANDOFF_OK")
        System.out.flush()
        if (helper != null) {
            // A fresh UID-2000 callback establishes the first bounded authorization proof.
            Looper.loop()
        }
    } catch (_: Throwable) {
        // Never print nonce, certificate, command or app data.
        System.err.println("BUDDY_BOOTSTRAP_FAILED")
    }
}
