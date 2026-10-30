package dev.buddy.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BootstrapCommandTest {
    @Test fun packagedCommandPreservesLiteralArgumentsThroughShellParsing() {
        val apk = "/data/app/a ' \$(printf EXPANDED)/base.apk"
        val command = BootstrapCommand.helper(apk, "dev.buddy", 10042, "a".repeat(64))
        val process = ProcessBuilder("sh", "-c", "printf '%s\\n' " + command.value).start()
        val parsed = process.inputStream.bufferedReader().readLines()
        assertEquals(0, process.waitFor())
        assertEquals(command.arguments, parsed)
        assertEquals("CLASSPATH=$apk", parsed[1])
        assertEquals("dev.buddy.helper.HelperMain", parsed[4])
    }

    @Test fun malformedBootstrapIdentityIsRejectedBeforeExecution() {
        assertThrows(IllegalArgumentException::class.java) {
            BootstrapCommand.helper("/sdcard/helper.apk", "dev.buddy", 10042, "a".repeat(64))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BootstrapCommand.helper("/data/app/base.apk", "dev.buddy;id", 10042, "a".repeat(64))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BootstrapCommand.helper("/data/app/base.apk", "dev.buddy", 2000, "a".repeat(64))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BootstrapCommand.helper("/data/app/base.apk", "dev.buddy", 10042, "not-a-digest")
        }
    }
}
