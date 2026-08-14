@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.syscall

import kotlinx.coroutines.DisposableHandle
import org.plos_clan.cpos.coroutines.KernelCoroutines
import org.plos_clan.cpos.drivers.TscClock
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.tasks.Scheduler
import org.plos_clan.cpos.tasks.Thread
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.IrqSpinLock
import org.plos_clan.cpos.utils.PtraceRegisters

private const val FUTEX_PRIVATE_FLAG = 0x80
private const val FUTEX_CLOCK_REALTIME = 0x100
private const val FUTEX_COMMAND_MASK = 0x7f
private const val FUTEX_SUPPORTED_FLAGS = FUTEX_PRIVATE_FLAG or FUTEX_CLOCK_REALTIME
private const val FUTEX_MATCH_ANY = UInt.MAX_VALUE
private const val NANOSECONDS_PER_SECOND = 1_000_000_000uL

private enum class FutexOperation(val code: Int) {
    WAIT(0),
    WAKE(1),
    REQUEUE(3),
    COMPARE_REQUEUE(4),
    WAIT_BITSET(9),
    WAKE_BITSET(10),
    ;

    companion object {
        fun from(code: Int): FutexOperation? = when (code) {
            WAIT.code -> WAIT
            WAKE.code -> WAKE
            REQUEUE.code -> REQUEUE
            COMPARE_REQUEUE.code -> COMPARE_REQUEUE
            WAIT_BITSET.code -> WAIT_BITSET
            WAKE_BITSET.code -> WAKE_BITSET
            else -> null
        }
    }
}

private data class FutexKey(
    val scope: Any,
    val address: ULong,
)

private data class FutexAddress(
    val key: FutexKey,
    val userAddress: ULong,
)

private enum class FutexWaitState {
    WAITING,
    WOKEN,
    TIMED_OUT,
}

private class FutexWaiter(
    var key: FutexKey,
    val thread: Thread,
    val bitset: UInt,
) {
    var state = FutexWaitState.WAITING
    var timeout: DisposableHandle? = null
}

object Futex {
    private val lock = IrqSpinLock()
    private val queues = mutableMapOf<FutexKey, ArrayDeque<FutexWaiter>>()

    fun syscall(regs: PtraceRegisters, process: Process): Long {
        val rawOperation = regs[PtraceRegisters.IDX_RSI]
        if (rawOperation > Int.MAX_VALUE.toULong()) return error(Errno.EINVAL)

        val encoded = rawOperation.toInt()
        if (encoded and (FUTEX_COMMAND_MASK or FUTEX_SUPPORTED_FLAGS).inv() != 0) {
            return error(Errno.EINVAL)
        }
        val operation = FutexOperation.from(encoded and FUTEX_COMMAND_MASK)
            ?: return error(Errno.ENOSYS)
        val private = encoded and FUTEX_PRIVATE_FLAG != 0
        val realtime = encoded and FUTEX_CLOCK_REALTIME != 0
        if (realtime && operation != FutexOperation.WAIT_BITSET) {
            return error(Errno.EINVAL)
        }

        val address = regs[PtraceRegisters.IDX_RDI]
        val primary = resolveAddress(process, address, private)
            ?: return addressError(address)
        return when (operation) {
            FutexOperation.WAIT -> wait(
                process = process,
                address = primary,
                expected = regs[PtraceRegisters.IDX_RDX].toUInt(),
                timeoutAddress = regs[PtraceRegisters.IDX_R10],
                absoluteTimeout = false,
                bitset = FUTEX_MATCH_ANY,
            )
            FutexOperation.WAIT_BITSET -> {
                val bitset = regs[PtraceRegisters.IDX_R9].toUInt()
                if (bitset == 0u) error(Errno.EINVAL) else wait(
                    process = process,
                    address = primary,
                    expected = regs[PtraceRegisters.IDX_RDX].toUInt(),
                    timeoutAddress = regs[PtraceRegisters.IDX_R10],
                    absoluteTimeout = true,
                    bitset = bitset,
                )
            }
            FutexOperation.WAKE -> wake(
                primary.key,
                wakeCount(regs[PtraceRegisters.IDX_RDX]) ?: return error(Errno.EINVAL),
                FUTEX_MATCH_ANY,
            )
            FutexOperation.WAKE_BITSET -> {
                val bitset = regs[PtraceRegisters.IDX_R9].toUInt()
                if (bitset == 0u) error(Errno.EINVAL) else wake(
                    primary.key,
                    wakeCount(regs[PtraceRegisters.IDX_RDX]) ?: return error(Errno.EINVAL),
                    bitset,
                )
            }
            FutexOperation.REQUEUE,
            FutexOperation.COMPARE_REQUEUE,
            -> requeue(
                process = process,
                source = primary,
                destinationAddress = regs[PtraceRegisters.IDX_R8],
                private = private,
                wakeCount = wakeCount(regs[PtraceRegisters.IDX_RDX])
                    ?: return error(Errno.EINVAL),
                requeueCount = wakeCount(regs[PtraceRegisters.IDX_R10])
                    ?: return error(Errno.EINVAL),
                comparison = regs[PtraceRegisters.IDX_R9].toUInt()
                    .takeIf { operation == FutexOperation.COMPARE_REQUEUE },
            )
        }
    }

    private fun wait(
        process: Process,
        address: FutexAddress,
        expected: UInt,
        timeoutAddress: ULong,
        absoluteTimeout: Boolean,
        bitset: UInt,
    ): Long {
        val expiresAt = when (
            val result = timeoutDeadline(process, timeoutAddress, absoluteTimeout)
        ) {
            is TimeoutResult.Value -> result.deadline
            is TimeoutResult.Error -> return error(result.errno)
        }
        val thread = ProcessManager.currentThread() ?: return error(Errno.ESRCH)
        val waiter = FutexWaiter(address.key, thread, bitset)

        var validationError = 0
        lock.withLock {
            val current = readWord(process, address.userAddress)
            when {
                current == null -> validationError = Errno.EFAULT
                current != expected -> validationError = Errno.EAGAIN
                expiresAt != null && expiresAt <= TscClock.nanoTime() ->
                    validationError = Errno.ETIMEDOUT
                else -> {
                    queues.getOrPut(address.key) { ArrayDeque() }.addLast(waiter)
                    waiter.timeout = expiresAt?.let { deadline ->
                        KernelCoroutines.dispatcher.scheduleAt(deadline) {
                            timeout(waiter)
                        }
                    }
                }
            }
        }
        if (validationError != 0) return error(validationError)

        if (!Scheduler.parkCurrent()) {
            lock.withLock { removeLocked(waiter) }
            return error(Errno.ESRCH)
        }
        return when (lock.withLock { waiter.state }) {
            FutexWaitState.WOKEN -> 0L
            FutexWaitState.TIMED_OUT -> error(Errno.ETIMEDOUT)
            FutexWaitState.WAITING -> {
                lock.withLock { removeLocked(waiter) }
                error(Errno.EINTR)
            }
        }
    }

    private fun wake(key: FutexKey, count: Int, bitset: UInt): Long {
        if (count == 0) return 0L
        val selected = lock.withLock { selectLocked(key, count, bitset) }
        selected.forEach { Scheduler.wake(it.thread) }
        return selected.size.toLong()
    }

    private fun requeue(
        process: Process,
        source: FutexAddress,
        destinationAddress: ULong,
        private: Boolean,
        wakeCount: Int,
        requeueCount: Int,
        comparison: UInt?,
    ): Long {
        val destination = resolveAddress(process, destinationAddress, private)
            ?: return addressError(destinationAddress)
        if (source.key == destination.key) return error(Errno.EINVAL)

        var mismatch = false
        var moved = 0
        val selected = lock.withLock {
            if (comparison != null && readWord(process, source.userAddress) != comparison) {
                mismatch = true
                return@withLock emptyList()
            }
            val woken = selectLocked(source.key, wakeCount, FUTEX_MATCH_ANY)
            val sourceQueue = queues[source.key]
            if (sourceQueue != null && requeueCount != 0) {
                val destinationQueue = queues.getOrPut(destination.key) { ArrayDeque() }
                moved = minOf(requeueCount, sourceQueue.size)
                repeat(moved) {
                    sourceQueue.removeFirst().also { waiter ->
                        waiter.key = destination.key
                        destinationQueue.addLast(waiter)
                    }
                }
                if (sourceQueue.isEmpty()) queues.remove(source.key)
            }
            woken
        }
        if (mismatch) return error(Errno.EAGAIN)
        selected.forEach { Scheduler.wake(it.thread) }
        return (selected.size + moved).toLong()
    }

    private fun selectLocked(key: FutexKey, count: Int, bitset: UInt): List<FutexWaiter> {
        val queue = queues[key] ?: return emptyList()
        val selected = ArrayList<FutexWaiter>(minOf(count, queue.size))
        repeat(queue.size) {
            val waiter = queue.removeFirst()
            if (selected.size < count && waiter.bitset and bitset != 0u) {
                waiter.state = FutexWaitState.WOKEN
                waiter.timeout?.dispose()
                waiter.timeout = null
                selected += waiter
            } else {
                queue.addLast(waiter)
            }
        }
        if (queue.isEmpty()) queues.remove(key)
        return selected
    }

    private fun timeout(waiter: FutexWaiter) {
        val expired = lock.withLock {
            removeLocked(waiter, FutexWaitState.TIMED_OUT)
        }
        if (expired) Scheduler.wake(waiter.thread)
    }

    private fun removeLocked(
        waiter: FutexWaiter,
        state: FutexWaitState? = null,
    ): Boolean {
        if (waiter.state != FutexWaitState.WAITING) return false
        val queue = queues[waiter.key] ?: return false
        repeat(queue.size) {
            val candidate = queue.removeFirst()
            if (candidate !== waiter) queue.addLast(candidate)
        }
        if (queue.isEmpty()) queues.remove(waiter.key)
        if (state != null) waiter.state = state
        waiter.timeout?.dispose()
        waiter.timeout = null
        return true
    }

    private fun resolveAddress(process: Process, address: ULong, private: Boolean): FutexAddress? {
        if (address and (Int.SIZE_BYTES - 1).toULong() != 0uL) return null
        readWord(process, address) ?: return null
        val key = if (private) {
            FutexKey(process.addressSpace, address)
        } else {
            val location = process.addressSpace.sharedMemoryLocation(
                address,
                Int.SIZE_BYTES.toULong(),
            ) ?: return null
            FutexKey(location.identity, location.offset)
        }
        return FutexAddress(key, address)
    }

    private fun readWord(process: Process, address: ULong): UInt? =
        UserMemory(process.addressSpace, address).readUIntLE()

    private fun timeoutDeadline(
        process: Process,
        address: ULong,
        absolute: Boolean,
    ): TimeoutResult {
        if (address == 0uL) return TimeoutResult.Value(null)
        val bytes = UserMemory(process.addressSpace, address).copyFromUser(TimeSpec.NATIVE_SIZE)
            ?: return TimeoutResult.Error(Errno.EFAULT)
        val time = TimeSpec(0, 0)
        if (!time.updateFromNativeBytes(bytes) ||
            time.sec < 0 || time.nsec !in 0 until NANOSECONDS_PER_SECOND.toLong()
        ) {
            return TimeoutResult.Error(Errno.EINVAL)
        }
        val seconds = time.sec.toULong()
        val nanoseconds = time.nsec.toULong()
        val value = if (seconds > (ULong.MAX_VALUE - nanoseconds) / NANOSECONDS_PER_SECOND) {
            ULong.MAX_VALUE
        } else {
            seconds * NANOSECONDS_PER_SECOND + nanoseconds
        }
        if (absolute) return TimeoutResult.Value(value)

        val now = TscClock.nanoTime()
        return TimeoutResult.Value(
            if (value > ULong.MAX_VALUE - now) ULong.MAX_VALUE else now + value,
        )
    }

    private fun wakeCount(value: ULong): Int? = value.toInt().takeIf { it >= 0 }

    private fun addressError(address: ULong): Long =
        error(if (address and (Int.SIZE_BYTES - 1).toULong() == 0uL) Errno.EFAULT else Errno.EINVAL)

    private fun error(errno: Int): Long = Syscall.errno(errno)

    private sealed interface TimeoutResult {
        data class Value(val deadline: ULong?) : TimeoutResult
        data class Error(val errno: Int) : TimeoutResult
    }
}
