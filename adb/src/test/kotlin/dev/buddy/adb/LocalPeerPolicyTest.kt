package dev.buddy.adb

import java.net.Inet6Address
import java.net.InetAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPeerPolicyTest {
    private val own = InetAddress.getByName("10.0.2.15")
    private val remote = InetAddress.getByName("10.0.2.16")
    private val kind = AdbServiceKind.PAIRING
    private fun accepts(addresses: List<InetAddress>, type: String = kind.serviceType, port: Int = 12345) =
        LocalPeerPolicy.accepts(kind, type, port, addresses, setOf(own))

    @Test fun ownServiceAcceptsTheCanonicalDnsSuffix() {
        assertTrue(accepts(listOf(own)))
        assertTrue(accepts(listOf(own), kind.serviceType.uppercase() + "."))
    }

    @Test fun remoteAndMixedRecordsNeverBecomeLoopbackCandidates() {
        assertFalse(accepts(listOf(remote)))
        assertFalse(accepts(listOf(own, remote)))
        assertFalse(accepts(listOf(InetAddress.getByName("0.0.0.0"))))
        assertFalse(accepts(listOf(InetAddress.getByName("224.0.0.1"))))
    }

    @Test fun serviceKindsAndPortBoundsCannotBeConfused() {
        assertFalse(accepts(listOf(own), AdbServiceKind.CONNECTION.serviceType))
        assertFalse(accepts(listOf(own), kind.serviceType + ".."))
        assertFalse(accepts(listOf(own), port = 0))
        assertFalse(accepts(listOf(own), port = 65536))
    }

    @Test fun unresolvedOrUnboundedAddressSetsFailClosed() {
        assertFalse(accepts(emptyList()))
        assertFalse(accepts(List(17) { own }))
        assertFalse(LocalPeerPolicy.accepts(kind, kind.serviceType, 12345, listOf(own), emptySet()))
    }

    @Test fun linkLocalIpv6RequiresTheSameKnownInterfaceScope() {
        val bytes = InetAddress.getByName("fe80::1234").address
        val local = Inet6Address.getByAddress(null, bytes, 7)
        fun scoped(scope: Int) = LocalPeerPolicy.accepts(
            kind,
            kind.serviceType,
            12345,
            listOf(Inet6Address.getByAddress(null, bytes, scope)),
            setOf(local)
        )
        assertTrue(scoped(7))
        assertFalse(scoped(9))
        assertFalse(scoped(0))
    }
}
