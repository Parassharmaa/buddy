package dev.buddy.files

import android.system.ErrnoException
import java.io.FileNotFoundException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PrivateFileDescriptorsTest {
    @Test fun revocationWipesBuffersRejectsFurtherReadsAndPreventsLatePublication() {
        PrivateFileDescriptors.revokeAll()
        val ticket = PrivateFileDescriptors.ticket()
        val data = "private fixture".toByteArray()
        val handle = PrivateFileDescriptors.reserve(data.size, ticket)
        assertTrue(handle.assign(data))
        val slice = ByteArray(4)
        assertTrue(handle.onRead(2, 4, slice) == 4)
        assertArrayEquals(data.copyOfRange(2, 6), slice)
        PrivateFileDescriptors.revokeAll()
        assertTrue(data.all { it == 0.toByte() })
        assertThrows(ErrnoException::class.java) { handle.onRead(0, 4, slice) }
        assertThrows(FileNotFoundException::class.java) { PrivateFileDescriptors.reserve(4, ticket) }
        val late = "late private buffer".toByteArray()
        assertFalse(handle.assign(late))
        assertTrue(late.all { it == 0.toByte() })
    }

    @Test fun descriptorReservationsAreBoundedAndReleasedOnClose() {
        PrivateFileDescriptors.revokeAll()
        val ticket = PrivateFileDescriptors.ticket()
        val handles = List(16) { PrivateFileDescriptors.reserve(1, ticket) }
        assertThrows(FileNotFoundException::class.java) { PrivateFileDescriptors.reserve(1, ticket) }
        handles.first().onRelease()
        val next = PrivateFileDescriptors.reserve(1, ticket)
        next.onRelease()
        PrivateFileDescriptors.revokeAll()
    }
}
