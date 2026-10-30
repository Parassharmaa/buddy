package dev.buddy

import android.app.Application
import dev.buddy.storage.EncryptedStore

class BuddyApplication : Application() {
    val store by lazy { EncryptedStore(this) }
}
