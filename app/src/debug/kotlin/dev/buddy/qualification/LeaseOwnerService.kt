package dev.buddy.qualification

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process

// Debug-only Binder owner. It exposes no helper, storage or phone operations.
class LeaseOwnerService : Service() {
    private val owner = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != IBinder.FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags)
            check(Binder.getCallingUid() == Process.myUid())
            requireNotNull(reply).writeNoException()
            reply.writeInt(Process.myPid())
            return true
        }
    }
    override fun onBind(intent: Intent): IBinder = owner
}
