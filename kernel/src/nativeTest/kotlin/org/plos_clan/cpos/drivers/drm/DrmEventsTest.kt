package org.plos_clan.cpos.drivers.drm

import org.plos_clan.cpos.mem.ByteArrayBuffer
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.LittleEndianBuffer
import org.plos_clan.cpos.utils.PollEvents
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DrmEventsTest {
    @Test
    fun flipBecomesReadableOnlyAtCompletion() {
        val events = DrmEvents()
        val cookie = 0x100000002uL
        val event = assertNotNull(events.prepare(cookie, 3u))
        val bytes = ByteArray(32)
        val buffer = ByteArrayBuffer(bytes)
        val destination = assertNotNull(buffer.prepareWrite(0, bytes.size))
        assertEquals(0L, events.poll(PollEvents.POLLIN))
        event.complete(1_234_567_890uL, UInt.MAX_VALUE)
        assertEquals(PollEvents.POLLIN.toLong(), events.poll(PollEvents.POLLIN))
        assertEquals(-Errno.EINVAL.toLong(), events.read(destination, 0, 31uL))
        assertEquals(32L, events.read(destination, 0, 32uL))
        val data = LittleEndianBuffer(bytes)
        assertEquals(2u, data.readU32(0))
        assertEquals(32u, data.readU32(4))
        assertEquals(cookie, data.readU64(8))
        assertEquals(1u, data.readU32(16))
        assertEquals(234_567u, data.readU32(20))
        assertEquals(UInt.MAX_VALUE, data.readU32(24))
        assertEquals(3u, data.readU32(28))
        assertEquals(0L, events.poll(PollEvents.POLLIN))
    }
}
