package dev.buddy.helper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemInputSinkPolicyTest {
    private val policy = SystemInputSinkPolicy(2022, 1, 4, 8)

    @Test fun acceptsThePlatformInputSinkWithEitherTouchabilityState() {
        assertTrue(policy.matches(1000, 2022, 5, 0, false))
        assertTrue(policy.matches(1000, 2022, 13, 0, false))
    }

    @Test fun userAppsAndOtherSystemWindowTypesRemainForeign() {
        for (uid in listOf(0, 2000, 10001)) assertFalse(policy.matches(uid, 2022, 5, 0, false))
        for (type in listOf(1, 2011, 2019, 2038)) assertFalse(policy.matches(1000, type, 5, 0, false))
    }

    @Test fun secureFocusableChannelBackedOrUnknownLayersRemainForeign() {
        assertFalse(policy.matches(1000, 2022, 5, 0x2000, false))
        assertFalse(policy.matches(1000, 2022, 5, 1, false))
        assertFalse(policy.matches(1000, 2022, 5, 0, true))
        for (config in listOf(0, 1, 4, 7, 5 or 0x1000)) {
            assertFalse(policy.matches(1000, 2022, config, 0, false))
        }
    }
}
