package dev.buddy.helper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemInputMonitorPolicyTest {
    private val base = 4 or (1 shl 14) or (1 shl 8)
    private val policy = SystemInputMonitorPolicy(2015, 4, 1 shl 14, 1 shl 8, 1 shl 19, 1 shl 20)

    @Test fun acceptsOnlyBufferlessPlatformMonitorsWithKnownInputFlags() {
        for (extra in listOf(0, 1 shl 19, 1 shl 20, (1 shl 19) or (1 shl 20))) {
            assertTrue(policy.matches(1000, 2015, base or extra, 0, true, 0, 0))
        }
    }

    @Test fun visibleOrUnmeasuredLayersRemainForeign() {
        for ((width, height) in listOf(1 to 0, 0 to 1, 1 to 1, null to 0, 0 to null, null to null)) {
            assertFalse(policy.matches(1000, 2015, base, 0, true, width, height))
        }
    }

    @Test fun otherOwnersAndWindowTypesRemainForeign() {
        for (uid in listOf(0, 2000, 10001)) assertFalse(policy.matches(uid, 2015, base, 0, true, 0, 0))
        for (type in listOf(1, 2011, 2019, 2022, 2038)) {
            assertFalse(policy.matches(1000, type, base, 0, true, 0, 0))
        }
    }

    @Test fun secureChannelLessOrUnrecognizedMonitorsRemainForeign() {
        assertFalse(policy.matches(1000, 2015, base, 0, false, 0, 0))
        for (flags in listOf(1, 0x2000)) assertFalse(policy.matches(1000, 2015, base, flags, true, 0, 0))
        for (config in listOf(4, base xor (1 shl 14), base or (1 shl 18), base or (1 shl 22))) {
            assertFalse(policy.matches(1000, 2015, config, 0, true, 0, 0))
        }
    }
}
