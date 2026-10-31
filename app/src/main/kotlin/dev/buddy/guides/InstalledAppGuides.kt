package dev.buddy.guides

import android.content.Context
import android.content.pm.PackageManager
import dev.buddy.core.guides.BundledAppGuides
import dev.buddy.core.guides.GuideLookup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object InstalledAppGuides {
    suspend fun current(context: Context): GuideLookup = withContext(Dispatchers.IO) {
        val information = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(0)
        )
        BundledAppGuides.catalog.lookup(context.packageName, information.longVersionCode)
    }
}
