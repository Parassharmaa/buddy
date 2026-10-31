package dev.buddy.adb

import java.net.Inet6Address
import java.net.InetAddress

enum class AdbServiceKind(val serviceType: String) {
    PAIRING("_adb-tls-pairing._tcp"),
    CONNECTION("_adb-tls-connect._tcp")
}

// Service names and GUIDs are routing hints. This establishes locality, never authorization.
internal object LocalPeerPolicy {
    fun accepts(
        kind: AdbServiceKind,
        type: String,
        port: Int,
        addresses: List<InetAddress>,
        localAddresses: Set<InetAddress>
    ): Boolean {
        if (!type.removeSuffix(".").equals(kind.serviceType, ignoreCase = true) || port !in 1..65535) return false
        if (addresses.isEmpty() || addresses.size > 16 || localAddresses.isEmpty()) return false
        return addresses.all { candidate ->
            !candidate.isAnyLocalAddress && !candidate.isMulticastAddress && localAddresses.any { local ->
                candidate.address.contentEquals(local.address) && sameInterface(candidate, local)
            }
        }
    }

    private fun sameInterface(candidate: InetAddress, local: InetAddress): Boolean {
        if (candidate !is Inet6Address || !candidate.isLinkLocalAddress) return true
        return local is Inet6Address && candidate.scopeId > 0 && candidate.scopeId == local.scopeId
    }
}
