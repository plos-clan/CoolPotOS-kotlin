package org.plos_clan.cpos.drivers.input

import org.plos_clan.cpos.drivers.Device
import org.plos_clan.cpos.drivers.DeviceBackend
import org.plos_clan.cpos.drivers.DeviceType
import org.plos_clan.cpos.fs.vfs.DeviceNumber
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.mem.ByteArrayBuffer
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.LittleEndianBuffer
import org.plos_clan.cpos.utils.PollEvents
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class EvdevTest {
    private class Fixture : AutoCloseable {
        private val id = InputId(InputId.BUS_USB)
        private val axis = AbsoluteAxisInfo(minimum = -100, maximum = 100, fuzz = 4)
        val backend = EvdevDevice(
            "test", "test/input", id, listOf(272u), listOf(RelativeAxis.X), null,
            mapOf(AbsoluteAxis.X to axis),
        )
        private val number = checkNotNull(DeviceNumber.create(13u, 64u))
        val device = Device("test", DeviceType.CHARACTER, number, backend)
        val client = assertIs<VfsResult.Ok<DeviceBackend>>(backend.open(device)).value
        val bytes = ByteArray(InputEvent.SIZE_BYTES * 8)

        fun event(type: InputEventType, code: UShort, value: Int) {
            val event = InputEvent(1uL, type, code, value)
            backend.receive(event)
        }

        fun sync() = event(InputEventType.SYNCHRONIZATION, InputEvent.SYN_REPORT, 0)

        fun read(): Long {
            val buffer = ByteArrayBuffer(bytes)
            val destination = checkNotNull(buffer.prepareWrite(0, bytes.size))
            return client.read(device, destination, 0, 0uL, bytes.size.toULong())
        }

        override fun close() = client.close(device)
    }

    @Test
    fun publishesCompleteFramesAndFiltersRepeatedButtonState() {
        Fixture().use { fixture ->
            fixture.event(InputEventType.KEY, 272u, 1)
            fixture.event(InputEventType.KEY, 272u, 1)
            assertEquals(0L, fixture.client.poll(fixture.device, PollEvents.POLLIN))
            assertEquals(-Errno.EAGAIN.toLong(), fixture.read())
            fixture.event(InputEventType.RELATIVE, 0u, -7)
            fixture.sync()
            assertEquals(72L, fixture.read())
            val bytes = LittleEndianBuffer(fixture.bytes)
            assertEquals(272u.toUShort(), bytes.readU16(18))
            assertEquals(1u, bytes.readU32(20))
            assertEquals((-7).toUInt(), bytes.readU32(44))
        }
    }

    @Test
    fun queueOverflowRequiresResynchronizationAtTheNextFrameBoundary() {
        Fixture().use { fixture ->
            repeat(300) { fixture.event(InputEventType.RELATIVE, 0u, 1) }
            fixture.sync()
            assertEquals(48L, fixture.read())
            val bytes = LittleEndianBuffer(fixture.bytes)
            assertEquals(InputEvent.SYN_DROPPED, bytes.readU16(18))
            assertEquals(InputEvent.SYN_REPORT, bytes.readU16(42))
            fixture.event(InputEventType.RELATIVE, 0u, 2)
            fixture.sync()
            assertEquals(48L, fixture.read())
            assertEquals(2u, bytes.readU32(20))
        }
    }

    @Test
    fun absoluteAxesFilterNoisePublishFilteredValuesAndReturnToZero() {
        Fixture().use { fixture ->
            fixture.event(InputEventType.ABSOLUTE, 0u, 1)
            fixture.event(InputEventType.ABSOLUTE, 0u, 6)
            fixture.event(InputEventType.ABSOLUTE, 0u, 3)
            fixture.event(InputEventType.ABSOLUTE, 1u, 20)
            fixture.event(InputEventType.ABSOLUTE, 0u, 20)
            fixture.event(InputEventType.ABSOLUTE, 0u, 0)
            fixture.sync()
            assertEquals(96L, fixture.read())
            val bytes = LittleEndianBuffer(fixture.bytes)
            assertEquals(3u, bytes.readU32(20))
            assertEquals(20u, bytes.readU32(44))
            assertEquals(0u, bytes.readU32(68))
        }
    }

    @Test
    fun disconnectInvalidatesExistingClientsAndPreventsReopen() {
        Fixture().use { fixture ->
            fixture.backend.uninstall()
            fixture.event(InputEventType.RELATIVE, 0u, 1)
            fixture.sync()
            assertEquals(-Errno.ENODEV.toLong(), fixture.read())
            val flags = PollEvents.POLLHUP or PollEvents.POLLERR
            assertEquals(flags.toLong(), fixture.client.poll(fixture.device, PollEvents.POLLIN))
            val result = assertIs<VfsResult.Err>(fixture.backend.open(fixture.device))
            assertEquals(VfsError.NO_DEVICE, result.error)
        }
    }
}
