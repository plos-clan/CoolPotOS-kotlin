@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package org.plos_clan.cpos.fs.vfs

import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.mem.PreparedBufferSource
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.tasks.Scheduler
import org.plos_clan.cpos.tasks.Signal
import org.plos_clan.cpos.tasks.SignalInfo
import org.plos_clan.cpos.tasks.SignalPayload
import org.plos_clan.cpos.utils.IrqSpinLock
import org.plos_clan.cpos.utils.LittleEndianBuffer
import org.plos_clan.cpos.utils.PollEvents
import kotlin.concurrent.atomics.AtomicInt

internal class SignalFd(initialMask: ULong) :
    AnonymousFileBackend(InodeType.SIGNALFD, "signalfd"),
    ModeAwareOpenFileBackend,
    NoopSeekOpenFileBackend {
    private val lock = IrqSpinLock()
    private val transfer = ByteArray(SignalFdSigInfoAbi.SIZE)
    private val version = AtomicInt(0)
    private var mask = initialMask and Signal.BLOCKABLE_MASK

    override val minimumReadSize: Int
        get() = SignalFdSigInfoAbi.SIZE

    override val readinessVersion: Int
        get() = version.load() +
            (ProcessManager.currentThread()?.pendingSignalVersion ?: 0)

    fun updateMask(replacement: ULong) {
        lock.withLock {
            mask = replacement and Signal.BLOCKABLE_MASK
            version.store(version.load() + 1)
        }
    }

    override fun read(
        caller: VfsOperationContext,
        inode: Inode,
        destination: PreparedBufferDestination,
        destinationOffset: Int,
        count: Int,
        mode: IoMode,
    ): IoResult {
        if (count < SignalFdSigInfoAbi.SIZE) {
            return IoResult.failure(VfsError.INVALID_ARGUMENT)
        }
        val thread = ProcessManager.currentThread()
            ?: return IoResult.failure(VfsError.INTERRUPTED)
        val blocking = mode == IoMode.BLOCKING
        if (blocking) thread.process.signals.registerSignalFdWaiter(thread)

        try {
            val limit = count / SignalFdSigInfoAbi.SIZE * SignalFdSigInfoAbi.SIZE
            var transferred = 0
            while (transferred < limit) {
                val info = lock.withLock { thread.takePendingSignal(mask) }
                if (info == null) {
                    if (transferred != 0) return IoResult.success(transferred)
                    if (!blocking) return IoResult.failure(VfsError.WOULD_BLOCK)
                    if (thread.hasPendingSignal()) {
                        return IoResult.failure(VfsError.INTERRUPTED)
                    }
                    if (!Scheduler.parkCurrent()) Scheduler.yieldCurrent()
                    continue
                }

                val copied = lock.withLock {
                    SignalFdSigInfoAbi.write(transfer, info)
                    destination.copyFrom(
                        destinationOffset + transferred,
                        transfer,
                        0,
                        SignalFdSigInfoAbi.SIZE,
                    )
                }
                if (copied != SignalFdSigInfoAbi.SIZE) {
                    return if (transferred == 0) IoResult.failure(VfsError.FAULT)
                    else IoResult.success(transferred)
                }
                transferred += SignalFdSigInfoAbi.SIZE
            }
            return IoResult.success(transferred)
        } finally {
            if (blocking) thread.process.signals.unregisterSignalFdWaiter(thread)
        }
    }

    override fun write(
        caller: VfsOperationContext,
        inode: Inode,
        source: PreparedBufferSource,
        sourceOffset: Int,
        count: Int,
        mode: IoMode,
    ): IoResult = IoResult.failure(VfsError.INVALID_ARGUMENT)

    override fun poll(caller: VfsOperationContext, inode: Inode, events: Int): Long {
        val thread = ProcessManager.currentThread() ?: return 0L
        val accepted = lock.withLock { mask }
        val available = if (thread.pendingSignalMask and accepted == 0uL) {
            0
        } else {
            PollEvents.POLLIN
        }
        return (available and events).toLong()
    }
}

internal object SignalFdSigInfoAbi {
    const val SIZE = 128

    private const val SIGNAL_OFFSET = 0
    private const val ERROR_OFFSET = 4
    private const val CODE_OFFSET = 8
    private const val PID_OFFSET = 12
    private const val UID_OFFSET = 16
    private const val FD_OFFSET = 20
    private const val TIMER_ID_OFFSET = 24
    private const val BAND_OFFSET = 28
    private const val OVERRUN_OFFSET = 32
    private const val STATUS_OFFSET = 40
    private const val INTEGER_OFFSET = 44
    private const val POINTER_OFFSET = 48
    private const val USER_TIME_OFFSET = 56
    private const val SYSTEM_TIME_OFFSET = 64
    private const val ADDRESS_OFFSET = 72
    private const val ADDRESS_LSB_OFFSET = 80
    private const val SYSCALL_OFFSET = 84
    private const val CALL_ADDRESS_OFFSET = 88
    private const val ARCHITECTURE_OFFSET = 96

    private const val SI_TIMER = -2
    private const val SI_SIGIO = -5
    private const val SI_KERNEL = 128
    private const val MAX_POLL_CODE = 6

    fun write(destination: ByteArray, info: SignalInfo) {
        require(destination.size >= SIZE)
        destination.fill(0, 0, SIZE)
        val output = LittleEndianBuffer(destination)
        output.writeU32(SIGNAL_OFFSET, info.signal.number.toUInt())
        output.writeU32(ERROR_OFFSET, info.error.toUInt())
        output.writeU32(CODE_OFFSET, info.code.toUInt())
        when (val payload = info.payload) {
            is SignalPayload.Sender -> {
                output.writeU32(PID_OFFSET, payload.pid.toUInt())
                output.writeU32(UID_OFFSET, payload.uid.toUInt())
                if (info.code < 0) {
                    output.writeU32(INTEGER_OFFSET, payload.value.toUInt())
                    output.writeU64(POINTER_OFFSET, payload.value)
                }
            }

            is SignalPayload.Child -> {
                output.writeU32(PID_OFFSET, payload.pid.toUInt())
                output.writeU32(UID_OFFSET, payload.uid.toUInt())
                output.writeU32(STATUS_OFFSET, payload.status.toUInt())
                output.writeU64(USER_TIME_OFFSET, payload.userTime.toULong())
                output.writeU64(SYSTEM_TIME_OFFSET, payload.systemTime.toULong())
            }

            is SignalPayload.Fault -> output.writeU64(ADDRESS_OFFSET, payload.address)
            is SignalPayload.Raw -> writeRaw(output, info.signal, info.code, payload.bytes)
            SignalPayload.None -> Unit
        }
    }

    private fun writeRaw(
        output: LittleEndianBuffer,
        signal: Signal,
        code: Int,
        payload: ByteArray,
    ) {
        val input = LittleEndianBuffer(payload)
        when (layout(signal, code)) {
            Layout.KILL -> if (payload.size >= 8) {
                output.writeU32(PID_OFFSET, input.readU32(0))
                output.writeU32(UID_OFFSET, input.readU32(4))
            }

            Layout.TIMER -> if (payload.size >= 16) {
                output.writeU32(TIMER_ID_OFFSET, input.readU32(0))
                output.writeU32(OVERRUN_OFFSET, input.readU32(4))
                output.writeU32(INTEGER_OFFSET, input.readU32(8))
                output.writeU64(POINTER_OFFSET, input.readU64(8))
            }

            Layout.POLL -> if (payload.size >= 12) {
                output.writeU32(BAND_OFFSET, input.readU64(0).toUInt())
                output.writeU32(FD_OFFSET, input.readU32(8))
            }

            Layout.FAULT -> if (payload.size >= 8) {
                output.writeU64(ADDRESS_OFFSET, input.readU64(0))
            }

            Layout.FAULT_MCE -> if (payload.size >= 10) {
                output.writeU64(ADDRESS_OFFSET, input.readU64(0))
                output.writeU16(ADDRESS_LSB_OFFSET, input.readU16(8))
            }

            Layout.CHILD -> if (payload.size >= 32) {
                output.writeU32(PID_OFFSET, input.readU32(0))
                output.writeU32(UID_OFFSET, input.readU32(4))
                output.writeU32(STATUS_OFFSET, input.readU32(8))
                output.writeU64(USER_TIME_OFFSET, input.readU64(16))
                output.writeU64(SYSTEM_TIME_OFFSET, input.readU64(24))
            }

            Layout.REALTIME -> if (payload.size >= 16) {
                output.writeU32(PID_OFFSET, input.readU32(0))
                output.writeU32(UID_OFFSET, input.readU32(4))
                output.writeU32(INTEGER_OFFSET, input.readU32(8))
                output.writeU64(POINTER_OFFSET, input.readU64(8))
            }

            Layout.SYSCALL -> if (payload.size >= 16) {
                output.writeU64(CALL_ADDRESS_OFFSET, input.readU64(0))
                output.writeU32(SYSCALL_OFFSET, input.readU32(8))
                output.writeU32(ARCHITECTURE_OFFSET, input.readU32(12))
            }
        }
    }

    private fun layout(signal: Signal, code: Int): Layout {
        if (code <= 0) return when (code) {
            SI_TIMER -> Layout.TIMER
            SI_SIGIO -> Layout.POLL
            0 -> Layout.KILL
            else -> Layout.REALTIME
        }
        if (code >= SI_KERNEL) return Layout.KILL

        val fallback = if (code <= MAX_POLL_CODE) Layout.POLL else Layout.KILL
        return when (signal) {
            Signal.ILLEGAL_INSTRUCTION -> if (code <= 11) Layout.FAULT else fallback
            Signal.FLOATING_POINT_EXCEPTION -> if (code <= 15) Layout.FAULT else fallback
            Signal.SEGV -> if (code <= 10) Layout.FAULT else fallback
            Signal.BUS -> when {
                code in 4..5 -> Layout.FAULT_MCE
                code <= 5 -> Layout.FAULT
                else -> fallback
            }
            Signal.TRAP -> if (code <= 6) Layout.FAULT else fallback
            Signal.CHILD -> if (code <= 6) Layout.CHILD else fallback
            Signal.SYS -> if (code <= 2) Layout.SYSCALL else fallback
            else -> fallback
        }
    }

    private enum class Layout {
        KILL,
        TIMER,
        POLL,
        FAULT,
        FAULT_MCE,
        CHILD,
        REALTIME,
        SYSCALL,
    }
}
