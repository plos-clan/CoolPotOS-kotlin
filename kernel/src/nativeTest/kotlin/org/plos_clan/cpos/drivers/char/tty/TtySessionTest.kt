package org.plos_clan.cpos.drivers.char.tty

import org.plos_clan.cpos.drivers.Device
import org.plos_clan.cpos.drivers.DeviceType
import org.plos_clan.cpos.fs.vfs.DeviceNumber
import org.plos_clan.cpos.fs.vfs.IoMode
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.mem.ByteArrayBuffer
import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.mem.PreparedBufferSource
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.utils.Errno
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TtySessionTest {
    @Test
    fun consoleSurvivesHangupWhileTerminalHandlesAreRevoked() {
        val session = TtySession({ Backend })
        val console = ConsoleTty(session)
        val device = Device("console", DeviceType.CHARACTER, checkNotNull(DeviceNumber.create(5u, 1u)), console)
        val terminalFile = assertIs<TtySession.OpenFile>(
            assertIs<VfsResult.Ok<*>>(session.open(device)).value,
        )
        val consoleFile = assertIs<TtySession.OpenFile>(
            assertIs<VfsResult.Ok<*>>(console.open(device)).value,
        )
        val source = checkNotNull(ByteArrayBuffer(byteArrayOf(1)).prepareRead(0, 1))
        try {
            session.hangup()
            assertTrue(terminalFile.isHungUp)
            assertFalse(consoleFile.isHungUp)
            assertEquals(-Errno.EIO.toLong(), terminalFile.write(device, source, 0, 1uL, IoMode.NON_BLOCKING))
            assertEquals(1L, consoleFile.write(device, source, 0, 1uL, IoMode.NON_BLOCKING))
        } finally {
            terminalFile.close(device)
            consoleFile.close(device)
            session.destroy()
        }
    }

    private object Backend : TtySessionBackend {
        override fun receiveInput(session: TtySession, data: ByteArray, offset: Int, count: Int) = Unit
        override fun poll(session: TtySession, events: Int): Int = 0
        override fun ioctl(file: TtySession.OpenFile, command: Int, args: UserMemory): Int = -Errno.ENOTTY
        override fun read(
            file: TtySession.OpenFile, buffer: PreparedBufferDestination,
            offset: Int, count: ULong, mode: IoMode,
        ): Long = 0
        override fun write(
            file: TtySession.OpenFile, buffer: PreparedBufferSource,
            offset: Int, count: ULong, mode: IoMode,
        ): Long = count.toLong()
    }
}
