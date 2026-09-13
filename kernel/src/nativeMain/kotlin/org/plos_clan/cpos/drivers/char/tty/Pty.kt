@file:OptIn(ExperimentalAtomicApi::class)

package org.plos_clan.cpos.drivers.char.tty

import org.plos_clan.cpos.drivers.Device
import org.plos_clan.cpos.drivers.ModeAwareDeviceBackend
import org.plos_clan.cpos.drivers.char.TerminalBackend
import org.plos_clan.cpos.fs.vfs.IoMode
import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.mem.PreparedBufferSource
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.tasks.IoWaitQueue
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.tasks.Signal
import org.plos_clan.cpos.utils.ByteRingBuffer
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.IrqSpinLock
import org.plos_clan.cpos.utils.KernelMutex
import org.plos_clan.cpos.utils.PollEvents
import org.plos_clan.cpos.utils.TermiosConstants
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

internal class Pty(
    val number: Int,
    deviceNumber: ULong,
    private val unlink: () -> Unit,
    private val release: () -> Unit,
) : TerminalBackend() {
    val session = TtySession({ this }, deviceNumber, 38400)
    private val output = ByteRingBuffer(65536)
    private val lock = IrqSpinLock()
    private val readers = IoWaitQueue()
    private val masterWriteLock = KernelMutex()
    private val transfer = ByteArray(4096)
    private val masterClosed = AtomicBoolean(false)
    private var slaveCount = -1
    private var locked = true
    private var packetMode = false
    private var status = 0

    init {
        session.termios.cIflag = TermiosConstants.ICRNL or TermiosConstants.IXON
        session.termios.cCflag = TermiosConstants.CS8 or TermiosConstants.CREAD or TermiosConstants.B38400
        session.termios.cLflag = session.termios.cLflag or TermiosConstants.ECHOCTL or TermiosConstants.ECHOKE
        session.updateTermios(Termios2(session.termios, 38400))
        check(session.start())
    }

    override fun open(session: TtySession): Int = lock.withLock {
        if (locked || masterClosed.load()) return@withLock -Errno.EIO
        slaveCount = maxOf(0, slaveCount) + 1
        changed()
        readers.wakeAll()
        0
    }

    override fun close(session: TtySession) {
        val last = lock.withLock {
            check(slaveCount > 0)
            slaveCount--
            if (slaveCount == 0) readers.wakeAll()
            slaveCount == 0
        }
        if (!last) return
        input.flush()
        releaseIfClosed()
    }

    override val outputRoom: Int
        get() = output.remaining

    override fun writeOutput(data: ByteArray, offset: Int, count: Int) = lock.withLock {
        check(output.write(data, offset, count) == count)
        changed()
        readers.wakeAll()
    }

    override fun clearOutput() = lock.withLock { output.clear() }

    override fun notify(event: TtyEvent) = lock.withLock {
        if (!packetMode) return@withLock
        status = status and event.oppositeFlag.inv() or event.packetFlag
        changed()
        readers.wakeAll()
    }

    override fun windowSize() = WinSize(0, 0, 0, 0)

    private fun releaseIfClosed() {
        val unused = lock.withLock {
            if (!masterClosed.load() || slaveCount > 0 || slaveCount == -2) false
            else { slaveCount = -2; true }
        }
        if (unused) release()
    }

    inner class Master : ModeAwareDeviceBackend {
        override val readinessVersion: Int
            get() = this@Pty.readinessVersion

        private val control = session.masterFile()

        override fun close(device: Device) {
            if (masterClosed.exchange(true)) return
            unlink()
            session.hangup()
            lock.withLock { readers.wakeAll() }
            releaseIfClosed()
        }

        override fun ioctl(device: Device, command: Int, args: UserMemory): Long {
            val result = when (command) {
                IoctlConstants.TIOCGPTN.toInt() -> copyIntToUser(args, number)
                IoctlConstants.TIOCGPTLCK.toInt() -> copyIntToUser(args, lock.withLock { if (locked) 1 else 0 })
                IoctlConstants.TIOCSPTLCK -> {
                    val value = args.readUIntLE() ?: return -Errno.EFAULT.toLong()
                    lock.withLock { locked = value != 0u }
                    0
                }
                IoctlConstants.TIOCPKT -> {
                    val value = args.readUIntLE() ?: return -Errno.EFAULT.toLong()
                    lock.withLock { packetMode = value != 0u; status = 0 }
                    0
                }
                IoctlConstants.TIOCGPKT.toInt() -> copyIntToUser(args, lock.withLock { if (packetMode) 1 else 0 })
                IoctlConstants.FIONREAD -> copyIntToUser(args, output.available)
                IoctlConstants.TIOCSIG -> {
                    val signal = Signal.from(args.address)
                    if (signal != Signal.INTERRUPT && signal != Signal.QUIT && signal != Signal.TERMINAL_STOP) return -Errno.EINVAL.toLong()
                    session.signalForeground(checkNotNull(signal))
                    0
                }
                else -> return control.ioctl(device, command, args)
            }
            return result.toLong()
        }

        override fun poll(device: Device, events: Int): Long = lock.withLock {
            var ready = 0
            if (output.available != 0 || packetMode && status != 0) ready = PollEvents.NORMAL_INPUT
            if (packetMode && status != 0) ready = ready or PollEvents.POLLPRI
            if (input.writable(session)) ready = ready or PollEvents.NORMAL_OUTPUT
            if (slaveCount == 0) ready = ready or PollEvents.POLLHUP
            (ready and (events or PollEvents.UNCONDITIONALLY_REPORTED)).toLong()
        }

        override fun read(device: Device, buffer: PreparedBufferDestination, bufferOffset: Int, size: ULong, mode: IoMode): Long {
            if (size == 0uL) return 0
            if (size > Int.MAX_VALUE.toULong()) return -Errno.EINVAL.toLong()
            while (true) {
                val result = lock.withLock {
                    if (packetMode && status != 0) {
                        val copied = buffer.copyFrom(bufferOffset, byteArrayOf(status.toByte()), 0, 1)
                        if (copied == 0) return@withLock -Errno.EFAULT.toLong()
                        status = 0
                        return@withLock 1L
                    }
                    if (output.available == 0) return@withLock if (slaveCount == 0) -Errno.EIO.toLong() else -Errno.EAGAIN.toLong()
                    val prefix = if (packetMode) 1 else 0
                    if (prefix != 0 && buffer.copyFrom(bufferOffset, DATA_PACKET, 0, 1) == 0) return@withLock -Errno.EFAULT.toLong()
                    val copied = output.read(buffer, bufferOffset + prefix, size.toInt() - prefix)
                    if (copied == 0 && prefix == 0) -Errno.EFAULT.toLong() else (copied + prefix).toLong()
                }
                if (result != -Errno.EAGAIN.toLong() || mode == IoMode.NON_BLOCKING) {
                    if (result > 0) wakeOutput(session)
                    return result
                }
                val thread = ProcessManager.currentThread() ?: return -Errno.EINTR.toLong()
                val waiter = lock.withLock {
                    if (output.available != 0 || packetMode && status != 0 || slaveCount == 0) null else readers.add(thread)
                } ?: continue
                if (!readers.await(lock, waiter)) return -Errno.EINTR.toLong()
            }
        }

        override fun write(device: Device, buffer: PreparedBufferSource, bufferOffset: Int, size: ULong, mode: IoMode): Long {
            if (size > Int.MAX_VALUE.toULong()) return -Errno.EINVAL.toLong()
            var transferred = 0
            while (transferred < size.toInt()) {
                val accepted = masterWriteLock.withLock {
                    val copied = buffer.copyTo(bufferOffset + transferred, transfer, 0, minOf(transfer.size, size.toInt() - transferred))
                    if (copied == 0) return@withLock -Errno.EFAULT
                    input.receive(session, transfer, 0, copied)
                }
                if (accepted < 0) return if (transferred == 0) accepted.toLong() else transferred.toLong()
                transferred += accepted
                if (transferred == size.toInt() || mode == IoMode.NON_BLOCKING) {
                    return if (transferred == 0) -Errno.EAGAIN.toLong() else transferred.toLong()
                }
                if (!input.awaitWritable(session) { masterClosed.load() }) {
                    return if (transferred == 0) -Errno.EINTR.toLong() else transferred.toLong()
                }
            }
            return transferred.toLong()
        }
    }

    private companion object {
        val DATA_PACKET = byteArrayOf(0)
    }
}
