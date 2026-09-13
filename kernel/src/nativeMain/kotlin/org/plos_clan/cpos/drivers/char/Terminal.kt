@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package org.plos_clan.cpos.drivers.char

import org.plos_clan.cpos.drivers.TtyGraphicsDevice
import org.plos_clan.cpos.drivers.char.terminal.NativeTerminal
import org.plos_clan.cpos.drivers.char.terminal.TerminalInput
import org.plos_clan.cpos.drivers.char.tty.ConsoleDisplayMode
import org.plos_clan.cpos.drivers.char.tty.IoctlConstants
import org.plos_clan.cpos.drivers.char.tty.Termios
import org.plos_clan.cpos.drivers.char.tty.Termios2
import org.plos_clan.cpos.drivers.char.tty.TtyEvent
import org.plos_clan.cpos.drivers.char.tty.TtySession
import org.plos_clan.cpos.drivers.char.tty.TtySessionBackend
import org.plos_clan.cpos.drivers.char.tty.WinSize
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
import org.plos_clan.cpos.utils.LittleEndianBuffer
import org.plos_clan.cpos.utils.PollEvents
import org.plos_clan.cpos.utils.TermiosConstants

abstract class TerminalBackend : TtySessionBackend {
    internal val input = TerminalInput(this)
    protected val outputLock = KernelMutex()
    private val echoes = ByteRingBuffer(OUTPUT_CHUNK_SIZE)
    private val transferBuffer = ByteArray(OUTPUT_CHUNK_SIZE)
    private val processedOutput = ByteArray(OUTPUT_CHUNK_SIZE * TAB_WIDTH)
    private val version = kotlin.concurrent.atomics.AtomicInt(0)
    final override val readinessVersion: Int
        get() = version.load()

    internal fun changed() { version.fetchAndAdd(1) }

    private var outputColumn = 0
    private var outputStopped = false
    private val waiterLock = IrqSpinLock()
    private val outputWaiters = IoWaitQueue()
    private var resizedWindow: WinSize? = null

    final override fun receiveInput(
        session: TtySession,
        data: ByteArray,
        offset: Int,
        count: Int,
    ) { input.receive(session, data, offset, count) }

    final override fun write(
        file: TtySession.OpenFile,
        buffer: PreparedBufferSource,
        offset: Int,
        count: ULong,
        mode: IoMode,
    ): Long {
        if (offset < 0 || count > Int.MAX_VALUE.toULong()) return -Errno.EINVAL.toLong()
        val requested = count.toInt()
        var transferred = 0
        while (transferred < requested) {
            val copied = outputLock.withLock {
                if (file.isHungUp) return@withLock -Errno.EIO
                drainEcho(file.session)
                val room = if (outputStopped) 0 else outputRoom
                val expansion = if (file.session.termios.cOflag and TermiosConstants.OPOST == 0) 1 else TAB_WIDTH
                val chunkSize = minOf(requested - transferred, transferBuffer.size, room / expansion)
                if (chunkSize == 0) return@withLock -Errno.EAGAIN
                val copied = buffer.copyTo(offset + transferred, transferBuffer, 0, chunkSize)
                if (copied == 0) return@withLock -Errno.EFAULT
                processOutput(file.session, transferBuffer, 0, copied)
                copied
            }
            if (copied > 0) transferred += copied
            else if (copied != -Errno.EAGAIN) return if (transferred == 0) copied.toLong() else transferred.toLong()
            if (transferred == requested || mode == IoMode.NON_BLOCKING) {
                return if (transferred == 0) -Errno.EAGAIN.toLong() else transferred.toLong()
            }
            if (!awaitOutput(file)) return if (transferred == 0) -Errno.EINTR.toLong() else transferred.toLong()
        }
        return transferred.toLong()
    }

    final override fun read(
        file: TtySession.OpenFile,
        buffer: PreparedBufferDestination,
        offset: Int,
        count: ULong,
        mode: IoMode,
    ): Long = input.read(file, buffer, offset, count, mode)

    final override fun ioctl(
        file: TtySession.OpenFile,
        command: Int,
        args: UserMemory,
    ): Int {
        consoleIoctl(file, command, args)?.let { return it }
        return file.control(command) {
            val session = file.session
            when (command) {
                IoctlConstants.TIOCGWINSZ ->
                    if (args.copyToUser((resizedWindow ?: windowSize()).toNativeBytes())) Errno.EOK else -Errno.EFAULT

                IoctlConstants.TIOCSCTTY ->
                    if (!file.isMaster && session.attachCurrentProcess(args.address == 1uL)) Errno.EOK else -Errno.EPERM

                IoctlConstants.TIOCGPGRP ->
                    if (file.isMaster || ProcessManager.currentProcess()?.controllingTerminal === session)
                        copyIntToUser(args, session.foregroundProcessGroup) else -Errno.ENOTTY

                IoctlConstants.TIOCSPGRP -> {
                    val processGroup = args.readUIntLE()?.toInt()
                    val process = ProcessManager.currentProcess()
                    when {
                        processGroup == null -> -Errno.EFAULT
                        process == null -> -Errno.ESRCH
                        session.sessionId != process.sessionId -> -Errno.ENOTTY
                        processGroup < 0 -> -Errno.EINVAL
                        file.checkBackground(Signal.TERMINAL_OUTPUT_STOP) != 0 -> -Errno.EINTR
                        !session.setForegroundProcessGroup(process, processGroup) -> -Errno.EPERM
                        else -> Errno.EOK
                    }
                }

                IoctlConstants.TIOCGSID -> {
                    val sessionId = session.sessionId
                    if (sessionId == 0 || !file.isMaster && ProcessManager.currentProcess()?.controllingTerminal !== session)
                        -Errno.ENOTTY else copyIntToUser(args, sessionId)
                }

                IoctlConstants.TIOCNOTTY ->
                    if (session.detachCurrentProcess()) Errno.EOK else -Errno.ENOTTY

                IoctlConstants.TCGETS ->
                    if (args.copyToUser(session.termios.toNativeBytes().copyOf(Termios.NATIVE_SIZE))) Errno.EOK else -Errno.EFAULT

                IoctlConstants.TCGETS2.toInt() ->
                    if (args.copyToUser(session.termios2.toNativeBytes())) Errno.EOK else -Errno.EFAULT

                IoctlConstants.TCSETS,
                IoctlConstants.TCSETSW -> updateTermiosFromUser(file, args)

                IoctlConstants.TCSETSF -> {
                    val result = updateTermiosFromUser(file, args)
                    if (result == Errno.EOK) discardInput()
                    result
                }

                IoctlConstants.TCSETS2,
                IoctlConstants.TCSETSW2 -> updateTermios2FromUser(file, args)

                IoctlConstants.TCSETSF2 -> {
                    val result = updateTermios2FromUser(file, args)
                    if (result == Errno.EOK) discardInput()
                    result
                }

                IoctlConstants.TCFLSH -> {
                    val error = file.checkBackground(Signal.TERMINAL_OUTPUT_STOP)
                    when {
                        args.address > 2uL -> -Errno.EINVAL
                        error != 0 -> error
                        else -> {
                            if (args.address != 1uL) discardInput()
                            if (args.address != 0uL) discardOutput()
                            Errno.EOK
                        }
                    }
                }

                IoctlConstants.FIONREAD -> copyIntToUser(args, input.available(session))
                IoctlConstants.TIOCOUTQ -> copyIntToUser(args, 0)
                IoctlConstants.TIOCGDEV.toInt() -> copyIntToUser(args, session.deviceNumber.toInt())
                IoctlConstants.TIOCGETD -> copyIntToUser(args, 0)
                IoctlConstants.TIOCSETD -> when (args.readUIntLE()) {
                    null -> -Errno.EFAULT
                    0u -> Errno.EOK
                    else -> -Errno.EINVAL
                }
                IoctlConstants.TIOCEXCL -> { session.exclusive = true; Errno.EOK }
                IoctlConstants.TIOCNXCL -> { session.exclusive = false; Errno.EOK }
                IoctlConstants.TIOCGEXCL.toInt() -> copyIntToUser(args, if (session.exclusive) 1 else 0)
                IoctlConstants.TIOCSWINSZ -> {
                    val bytes = args.copyFromUser(8) ?: return@control -Errno.EFAULT
                    val size = WinSize(0, 0, 0, 0)
                    size.updateFromNativeBytes(bytes)
                    if (size != (resizedWindow ?: windowSize())) {
                        resizedWindow = size
                        session.signalForeground(Signal.WINDOW_CHANGE)
                    }
                    Errno.EOK
                }
                IoctlConstants.TCXONC -> when (args.address) {
                    0uL, 1uL -> { setOutputStopped(session, args.address == 0uL); Errno.EOK }
                    2uL, 3uL -> {
                        val value = session.termios.character(if (args.address == 2uL) TermiosConstants.VSTOP else TermiosConstants.VSTART)
                        if (value != 0) echo(session, byteArrayOf(value.toByte()), 0, 1)
                        Errno.EOK
                    }
                    else -> -Errno.EINVAL
                }
                IoctlConstants.TCSBRK,
                IoctlConstants.TCSBRKP,
                IoctlConstants.TIOCSBRK,
                IoctlConstants.TIOCCBRK -> Errno.EOK

                else -> -Errno.ENOTTY
            }
        }
    }

    override fun poll(session: TtySession, events: Int): Int = input.poll(session, events) or outputLock.withLock {
        if (!outputStopped && outputRoom >= TAB_WIDTH) events and PollEvents.NORMAL_OUTPUT else 0
    }

    final override fun flushIfDirty() = outputLock.withLock(::flushOutput)

    override fun hangup(session: TtySession) {
        input.hangup()
        outputLock.withLock { echoes.clear() }
        wakeOutput(session)
    }

    protected open fun consoleIoctl(file: TtySession.OpenFile, command: Int, args: UserMemory): Int? =
        null

    final override fun destroy() = outputLock.withLock(::closeOutput)

    protected abstract fun writeOutput(data: ByteArray, offset: Int, count: Int)

    protected abstract fun windowSize(): WinSize

    protected open fun flushOutput() {}

    protected open fun closeOutput() {}

    internal fun echo(session: TtySession, data: ByteArray, offset: Int, count: Int) = outputLock.withLock {
        if (!outputStopped && echoes.available == 0 && outputRoom >= count * TAB_WIDTH) {
            processOutput(session, data, offset, count)
        } else {
            echoes.write(data, offset, count)
            drainEcho(session)
        }
    }

    private fun drainEcho(session: TtySession) {
        while (!outputStopped && echoes.available != 0 && outputRoom >= TAB_WIDTH) {
            val count = echoes.read(transferBuffer, 0, minOf(transferBuffer.size, outputRoom / TAB_WIDTH))
            processOutput(session, transferBuffer, 0, count)
        }
    }

    protected open val outputRoom: Int
        get() = Int.MAX_VALUE

    internal fun setOutputStopped(session: TtySession, stopped: Boolean) {
        outputLock.withLock {
            if (outputStopped == stopped) return@withLock
            outputStopped = stopped
            notify(if (stopped) TtyEvent.OUTPUT_STOPPED else TtyEvent.OUTPUT_STARTED)
        }
        wakeOutput(session)
    }

    internal fun discardOutput() {
        outputLock.withLock {
            echoes.clear()
            clearOutput()
            notify(TtyEvent.OUTPUT_FLUSHED)
        }
        wakeOutput()
    }

    private fun discardInput() {
        input.flush()
        notify(TtyEvent.INPUT_FLUSHED)
    }

    protected open fun clearOutput() {}
    protected open fun notify(event: TtyEvent) {}

    protected fun wakeOutput(session: TtySession? = null) {
        if (session != null) outputLock.withLock { drainEcho(session) }
        changed()
        waiterLock.withLock { outputWaiters.wakeAll() }
    }

    private fun awaitOutput(file: TtySession.OpenFile): Boolean {
        val thread = ProcessManager.currentThread() ?: return false
        val waiter = waiterLock.withLock { outputWaiters.add(thread) }
        if (file.isHungUp || outputLock.withLock { !outputStopped && outputRoom >= TAB_WIDTH }) wakeOutput()
        return outputWaiters.await(waiterLock, waiter)
    }

    private fun processOutput(
        session: TtySession,
        data: ByteArray,
        offset: Int,
        count: Int,
    ) {
        val flags = session.termios.cOflag
        if (flags and TermiosConstants.OPOST == 0) {
            writeOutput(data, offset, count)
            return
        }

        var outputCount = 0
        for (index in offset until offset + count) {
            when (val original = data[index].toUByte().toInt()) {
                '\n'.code -> {
                    if (flags and TermiosConstants.ONLCR != 0) {
                        processedOutput[outputCount++] = '\r'.code.toByte()
                        outputColumn = 0
                    }
                    processedOutput[outputCount++] = original.toByte()
                    if (flags and (TermiosConstants.ONLCR or TermiosConstants.ONLRET) != 0) {
                        outputColumn = 0
                    }
                }

                '\r'.code -> {
                    if (flags and TermiosConstants.ONOCR != 0 && outputColumn == 0) continue
                    val value = if (flags and TermiosConstants.OCRNL != 0) '\n'.code else original
                    processedOutput[outputCount++] = value.toByte()
                    if (value == '\r'.code ||
                        value == '\n'.code && flags and TermiosConstants.ONLRET != 0
                    ) {
                        outputColumn = 0
                    }
                }

                '\t'.code -> {
                    val spaces = TAB_WIDTH - outputColumn % TAB_WIDTH
                    if (flags and 0x1800 == 0x1800) {
                        repeat(spaces) { processedOutput[outputCount++] = 32 }
                    } else processedOutput[outputCount++] = original.toByte()
                    outputColumn += spaces
                }

                '\b'.code -> {
                    processedOutput[outputCount++] = original.toByte()
                    if (outputColumn != 0) outputColumn--
                }

                else -> {
                    val value = if (flags and TermiosConstants.OLCUC != 0 &&
                        original in 'a'.code..'z'.code
                    ) {
                        original - ('a'.code - 'A'.code)
                    } else {
                        original
                    }
                    processedOutput[outputCount++] = value.toByte()
                    if (value >= ' '.code && value != 0x7F) outputColumn++
                }
            }
        }
        if (outputCount != 0) writeOutput(processedOutput, 0, outputCount)
    }

    private fun updateTermiosFromUser(file: TtySession.OpenFile, args: UserMemory): Int {
        val bytes = args.copyFromUser(Termios.NATIVE_SIZE) ?: return -Errno.EFAULT
        val value = Termios.defaults()
        value.updateFromNativeBytes(bytes)
        return updateTermios(file, Termios2(value, file.session.termios2.cIspeed, file.session.termios2.cOspeed))
    }

    private fun updateTermios2FromUser(file: TtySession.OpenFile, args: UserMemory): Int {
        val bytes = args.copyFromUser(Termios2.NATIVE_SIZE) ?: return -Errno.EFAULT
        val extended = Termios2(Termios.defaults())
        extended.updateFromNativeBytes(bytes)
        return updateTermios(file, extended)
    }

    private fun updateTermios(file: TtySession.OpenFile, value: Termios2): Int {
        val error = file.checkBackground(Signal.TERMINAL_OUTPUT_STOP)
        if (error != 0) return error
        if (value.cLine != 0.toByte()) return -Errno.EINVAL
        val session = file.session
        val previous = input.reconfigure(session, value)
        if (value.cIflag and TermiosConstants.IXON == 0) setOutputStopped(session, false)
        val standardFlow = value.cIflag and TermiosConstants.IXON != 0 &&
            value.character(TermiosConstants.VSTOP) == 19 && value.character(TermiosConstants.VSTART) == 17
        val previousFlow = previous.cIflag and TermiosConstants.IXON != 0 &&
            previous.character(TermiosConstants.VSTOP) == 19 && previous.character(TermiosConstants.VSTART) == 17
        if (standardFlow != previousFlow) notify(if (standardFlow) TtyEvent.FLOW_ENABLED else TtyEvent.FLOW_DISABLED)
        return Errno.EOK
    }

    protected fun copyIntToUser(args: UserMemory, value: Int): Int {
        val data = ByteArray(Int.SIZE_BYTES)
        LittleEndianBuffer(data).writeU32(0, value.toUInt())
        return if (args.copyToUser(data)) Errno.EOK else -Errno.EFAULT
    }

    private companion object {
        const val OUTPUT_CHUNK_SIZE = 4096
        const val TAB_WIDTH = 8
    }
}

internal class FrameBufferTerminal private constructor(
    private val terminal: NativeTerminal,
) : VirtualTerminal() {
    override fun writeOutput(data: ByteArray, offset: Int, count: Int) {
        if (displayMode == ConsoleDisplayMode.TEXT) terminal.process(data, offset, count)
    }

    override fun windowSize(): WinSize {
        val dimensions = terminal.dimensions()
        return WinSize(
            wsRow = minOf(dimensions.rows, UShort.MAX_VALUE.toULong()).toShort(),
            wsCol = minOf(dimensions.columns, UShort.MAX_VALUE.toULong()).toShort(),
            wsXpixel = 0,
            wsYpixel = 0,
        )
    }

    override fun flushOutput() {
        if (displayMode == ConsoleDisplayMode.TEXT) terminal.flushIfDirty()
    }

    override fun redrawOutput() = terminal.redraw()

    override fun closeOutput() = terminal.destroy()

    companion object {
        fun create(
            device: TtyGraphicsDevice,
            invalidate: () -> Unit,
        ): FrameBufferTerminal? = NativeTerminal.create(device, invalidate)?.let(::FrameBufferTerminal)
    }
}
