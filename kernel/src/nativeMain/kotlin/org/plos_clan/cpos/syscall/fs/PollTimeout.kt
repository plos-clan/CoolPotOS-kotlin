@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.syscall.fs

import org.plos_clan.cpos.drivers.TscClock
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.syscall.SignalDelivery
import org.plos_clan.cpos.syscall.TimeSpec
import org.plos_clan.cpos.tasks.PollSubscription
import org.plos_clan.cpos.tasks.PollWait
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.tasks.Signal
import org.plos_clan.cpos.tasks.Thread
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.PtraceRegisters

internal class PollTimeout private constructor(private val deadline: ULong?) {
    inline fun wait(
        regs: PtraceRegisters,
        thread: Thread,
        mask: ULong?,
        openWait: () -> PollWait = { PollWait(thread) },
        scan: (PollSubscription?) -> Int,
    ): Int {
        var waiter: PollWait? = null
        val previous = mask?.let { thread.signals.replaceMask(it) }
        try {
            while (true) {
                waiter?.prepare()
                val ready = scan(waiter)
                if (ready != 0 || deadline == 0uL) return ready
                if (deadline != null && TscClock.nanoTime() >= deadline) return 0
                if (thread.hasPendingSignal()) {
                    if (previous == null) return -Errno.EINTR
                    regs[PtraceRegisters.IDX_RAX] = (-Errno.EINTR).toULong()
                    if (SignalDelivery.deliverPending(regs, thread, previous)) return -Errno.EINTR
                }
                if (waiter == null) waiter = openWait() else waiter.await(deadline)
            }
        } finally {
            waiter?.close()
            if (previous != null && !regs.signalFrameInstalled) thread.signals.mask = previous
        }
    }

    companion object {
        private val infinite = PollTimeout(null)
        private val immediate = PollTimeout(0uL)

        fun fromMilliseconds(value: Int): PollTimeout? {
            if (value < 0) return infinite
            return after(value.toULong() * FsConstants.NANOSECONDS_PER_MILLISECOND)
        }

        inline fun read(process: Process, address: ULong, fail: (Int) -> Nothing): PollTimeout {
            if (address == 0uL) return infinite
            val memory = UserMemory(process.addressSpace, address)
            val value = memory.readRecord(TimeSpec.NATIVE_SIZE, decode = TimeSpec::readFrom)
                ?: fail(Errno.EFAULT)
            if (!value.isValidDuration) fail(Errno.EINVAL)
            return after(value.durationNanos) ?: fail(Errno.EIO)
        }

        inline fun readMask(
            process: Process,
            address: ULong,
            size: ULong,
            fail: (Int) -> Nothing,
        ): ULong? {
            if (address == 0uL) return null
            if (size != ULong.SIZE_BYTES.toULong()) fail(Errno.EINVAL)
            val memory = UserMemory(process.addressSpace, address)
            val mask = memory.readULongLE() ?: fail(Errno.EFAULT)
            return mask and Signal.BLOCKABLE_MASK
        }

        private fun after(duration: ULong): PollTimeout? {
            if (duration == 0uL) return immediate
            if (!TscClock.isReady) return null
            val now = TscClock.nanoTime()
            return PollTimeout(now + minOf(duration, ULong.MAX_VALUE - now))
        }
    }
}
