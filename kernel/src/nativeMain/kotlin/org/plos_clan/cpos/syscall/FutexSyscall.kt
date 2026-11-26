@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.syscall

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.collections.immutable.persistentHashMapOf
import org.plos_clan.cpos.drivers.RealtimeClock
import org.plos_clan.cpos.drivers.TscClock
import org.plos_clan.cpos.mem.Hhdm
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.mem.page.USER_VIRTUAL_ADDRESS_LIMIT
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.tasks.SMProcessor
import org.plos_clan.cpos.tasks.Scheduler
import org.plos_clan.cpos.tasks.Thread
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.HashBuckets
import org.plos_clan.cpos.utils.IrqSpinLock
import org.plos_clan.cpos.utils.PtraceRegisters
import kotlin.concurrent.Volatile

private const val FUTEX_PRIVATE_FLAG = 0x80
private const val FUTEX_CLOCK_REALTIME = 0x100
private const val FUTEX_COMMAND_MASK = 0x7f
private const val FUTEX_SUPPORTED_BITS =
    FUTEX_COMMAND_MASK or FUTEX_PRIVATE_FLAG or FUTEX_CLOCK_REALTIME
private const val FUTEX_MATCH_ANY = UInt.MAX_VALUE

private enum class FutexOperation(val code: Int) {
    WAIT(0),
    WAKE(1),
    REQUEUE(3),
    COMPARE_REQUEUE(4),
    WAIT_BITSET(9),
    WAKE_BITSET(10),
    ;

    companion object {
        private val codes = arrayOfNulls<FutexOperation>(entries.maxOf { it.code } + 1).apply {
            for (operation in entries) this[operation.code] = operation
        }

        fun from(code: Int): FutexOperation? = codes.getOrNull(code)
    }
}

private data class FutexKey(
    val scope: Any,
    val address: ULong,
    val private: Boolean,
)

private class FutexWaiter(key: FutexKey, val thread: Thread, val bitset: UInt) {
    @Volatile var key: FutexKey? = key
}

private class FutexBucket {
    val lock = IrqSpinLock()
    @Volatile private var queues = persistentHashMapOf<FutexKey, LinkedHashSet<FutexWaiter>>()

    fun enqueue(
        key: FutexKey,
        waiter: FutexWaiter,
        word: CPointer<IntVar>,
        expected: UInt,
        expiresAt: ULong?,
    ): Long = lock.withLock {
        val queue = queue(key)
        bridge.smp_mb()
        val failure = when {
            bridge.futex_load(word).toUInt() != expected -> Errno.EAGAIN
            expiresAt != null && expiresAt <= TscClock.nanoTime() -> Errno.ETIMEDOUT
            else -> {
                queue.add(waiter)
                return@withLock 0L
            }
        }
        prune(key)
        Syscall.errno(failure)
    }

    private fun queue(key: FutexKey): LinkedHashSet<FutexWaiter> {
        queues[key]?.let { return it }
        val waiters = LinkedHashSet<FutexWaiter>()
        queues = queues.putting(key, waiters)
        return waiters
    }

    private fun prune(key: FutexKey) {
        if (queues[key]?.isEmpty() == true) queues = queues.removing(key)
    }

    fun cancel(key: FutexKey, waiter: FutexWaiter) {
        check(queues[key]?.remove(waiter) == true)
        prune(key)
    }

    fun wake(key: FutexKey, count: Int, bitset: UInt): List<Thread> {
        if (count == 0) return emptyList()
        bridge.smp_mb()
        if (key !in queues) return emptyList()
        return lock.withLock { select(key, count, bitset) }
    }

    fun select(key: FutexKey, count: Int, bitset: UInt): List<Thread> {
        if (count == 0) return emptyList()
        val queue = queues[key] ?: return emptyList()
        val selected = ArrayList<Thread>(minOf(count, queue.size))
        val iterator = queue.iterator()
        while (selected.size < count && iterator.hasNext()) {
            val waiter = iterator.next()
            if (waiter.bitset and bitset == 0u) continue
            iterator.remove()
            waiter.key = null
            selected.add(waiter.thread)
        }
        prune(key)
        return selected
    }

    fun move(source: FutexKey, destination: FutexKey, target: FutexBucket, count: Int): Int {
        if (count == 0) return 0
        val queue = queues[source] ?: return 0
        val receiving = target.queue(destination)
        val moved = minOf(count, queue.size)
        val iterator = queue.iterator()
        repeat(moved) {
            val waiter = iterator.next()
            iterator.remove()
            receiving.add(waiter)
            waiter.key = destination
        }
        prune(source)
        return moved
    }
}

object Futex {
    private val buckets = HashBuckets(SMProcessor.cpu_count.toInt(), ::FutexBucket)

    fun wake(process: Process, address: ULong): Long {
        val futex = resolveKey(process, address, false) ?: return error(Errno.EFAULT)
        return wake(futex, 1, FUTEX_MATCH_ANY)
    }

    fun handle(regs: PtraceRegisters, process: Process): Long {
        val rawOperation = regs[PtraceRegisters.IDX_RSI]
        val unsupported = rawOperation and FUTEX_SUPPORTED_BITS.toULong().inv()
        if (unsupported != 0uL) return error(Errno.EINVAL)
        val encoded = rawOperation.toInt()
        val operation = FutexOperation.from(encoded and FUTEX_COMMAND_MASK)
            ?: return error(Errno.ENOSYS)
        val private = encoded and FUTEX_PRIVATE_FLAG != 0
        val realtime = encoded and FUTEX_CLOCK_REALTIME != 0
        if (realtime && operation != FutexOperation.WAIT_BITSET) {
            return error(Errno.EINVAL)
        }

        val address = regs[PtraceRegisters.IDX_RDI]
        val key = resolveKey(process, address, private) ?: return addressError(address)
        val value = regs[PtraceRegisters.IDX_RDX]
        val maskOrComparison = regs[PtraceRegisters.IDX_R9].toUInt()
        val masked = operation == FutexOperation.WAIT_BITSET ||
            operation == FutexOperation.WAKE_BITSET
        val bitset = if (masked) maskOrComparison else FUTEX_MATCH_ANY
        if (bitset == 0u) return error(Errno.EINVAL)

        val timeoutOrCount = regs[PtraceRegisters.IDX_R10]
        return when (operation) {
            FutexOperation.WAIT, FutexOperation.WAIT_BITSET -> {
                val deadline = timeoutDeadline(process, timeoutOrCount, masked, realtime) { errno ->
                    return error(errno)
                }
                wait(process, address, key, value.toUInt(), deadline, bitset)
            }
            FutexOperation.WAKE, FutexOperation.WAKE_BITSET -> {
                val count = wakeCount(value) ?: return error(Errno.EINVAL)
                wake(key, count, bitset)
            }
            FutexOperation.REQUEUE, FutexOperation.COMPARE_REQUEUE -> {
                val wake = wakeCount(value) ?: return error(Errno.EINVAL)
                val move = wakeCount(timeoutOrCount) ?: return error(Errno.EINVAL)
                val other = regs[PtraceRegisters.IDX_R8]
                val target = resolveKey(process, other, private) ?: return addressError(other)
                val compare = operation == FutexOperation.COMPARE_REQUEUE
                val comparison = if (compare) maskOrComparison else null
                requeue(process, address, key, target, wake, move, comparison)
            }
        }
    }

    private inline fun withWord(
        process: Process,
        address: ULong,
        action: (CPointer<IntVar>) -> Long,
    ): Long {
        val space = process.addressSpace
        space.accessUserPage(address, writable = false, pin = false) { physical ->
            val word = checkNotNull(Hhdm.toVirtualPointer<IntVar>(physical))
            return action(word)
        }
        return error(Errno.EFAULT)
    }

    private fun wait(
        process: Process,
        address: ULong,
        key: FutexKey,
        expected: UInt,
        expiresAt: ULong?,
        bitset: UInt,
    ): Long {
        val thread = ProcessManager.currentThread() ?: return error(Errno.ESRCH)
        val waiter = FutexWaiter(key, thread, bitset)
        val bucket = buckets[key]
        val registered = withWord(process, address) { word ->
            bucket.enqueue(key, waiter, word, expected, expiresAt)
        }
        if (registered != 0L) return registered

        while (true) {
            val sequence = Scheduler.preparePark()
            if (waiter.key == null) return 0L
            if (expiresAt != null && TscClock.nanoTime() >= expiresAt) {
                return cancel(waiter, Errno.ETIMEDOUT)
            }
            if (thread.hasPendingSignal()) return cancel(waiter, Errno.EINTR)
            val parked = if (expiresAt == null) Scheduler.parkCurrent(sequence)
            else Scheduler.parkCurrentUntil(expiresAt, sequence)
            if (!parked) return cancel(waiter, Errno.ESRCH)
        }
    }

    private fun cancel(waiter: FutexWaiter, errno: Int): Long {
        while (true) {
            val key = waiter.key ?: return 0L
            val bucket = buckets[key]
            bucket.lock.withLock {
                if (waiter.key !== key) return@withLock
                bucket.cancel(key, waiter)
                return error(errno)
            }
        }
    }

    private fun wake(key: FutexKey, count: Int, bitset: UInt): Long {
        val selected = buckets[key].wake(key, count, bitset)
        selected.forEach(Scheduler::wake)
        return selected.size.toLong()
    }

    private fun requeue(
        process: Process,
        address: ULong,
        source: FutexKey,
        destination: FutexKey,
        wakeCount: Int,
        requeueCount: Int,
        comparison: UInt?,
    ): Long {
        if (source == destination) return error(Errno.EINVAL)
        val from = buckets[source]
        val to = buckets[destination]
        var selected = emptyList<Thread>()
        fun transfer(word: CPointer<IntVar>?): Long = from.lock.withLock(to.lock) {
            if (word != null && bridge.futex_load(word).toUInt() != comparison) {
                return@withLock error(Errno.EAGAIN)
            }
            selected = from.select(source, wakeCount, FUTEX_MATCH_ANY)
            val moved = from.move(source, destination, to, requeueCount)
            selected.size.toLong() + moved
        }
        val result = if (comparison == null) transfer(null)
        else withWord(process, address, ::transfer)
        selected.forEach(Scheduler::wake)
        return result
    }

    private fun resolveKey(process: Process, address: ULong, private: Boolean): FutexKey? {
        val size = Int.SIZE_BYTES.toULong()
        if (address and (size - 1uL) != 0uL) return null
        if (address >= USER_VIRTUAL_ADDRESS_LIMIT) return null
        val space = process.addressSpace
        val location = if (private) null else {
            val accessible = space.accessUserPage(address, writable = false, pin = false) { 1 }
            if (accessible == 0) return null
            space.sharedMemoryLocation(address, size)
        }
        return FutexKey(location?.identity ?: space, location?.offset ?: address, private)
    }

    private inline fun timeoutDeadline(
        process: Process,
        address: ULong,
        absolute: Boolean,
        realtime: Boolean,
        fail: (Int) -> Nothing,
    ): ULong? {
        if (address == 0uL) return null
        val time = UserMemory(process.addressSpace, address)
            .readRecord(TimeSpec.NATIVE_SIZE, decode = TimeSpec::readFrom)
            ?: fail(Errno.EFAULT)
        if (!time.isValidDuration) {
            fail(Errno.EINVAL)
        }
        if (absolute && realtime) {
            val now = TscClock.nanoTime()
            val remaining = RealtimeClock.atMonotonic(now)
                .durationUntil(time.sec, time.nsec.toUInt())
            return if (remaining > ULong.MAX_VALUE - now) ULong.MAX_VALUE else now + remaining
        }
        return if (absolute) time.durationNanos else time.deadlineFrom(TscClock.nanoTime())
    }

    private fun wakeCount(value: ULong): Int? = value.toInt().takeIf { it >= 0 }

    private fun addressError(address: ULong): Long =
        error(if (address and (Int.SIZE_BYTES - 1).toULong() == 0uL) Errno.EFAULT else Errno.EINVAL)

    private fun error(errno: Int): Long = Syscall.errno(errno)
}
