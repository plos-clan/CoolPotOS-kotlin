@file:OptIn(ExperimentalAtomicApi::class)

package org.plos_clan.cpos.fs.vfs

import org.plos_clan.cpos.tasks.PollSubscription
import org.plos_clan.cpos.coroutines.KernelCoroutines
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Runnable
import org.plos_clan.cpos.drivers.RealtimeClock
import org.plos_clan.cpos.drivers.TscClock
import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.mem.PreparedBufferSource
import org.plos_clan.cpos.tasks.IoWaitQueue
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.tasks.Scheduler
import org.plos_clan.cpos.utils.IrqSpinLock
import org.plos_clan.cpos.utils.LittleEndianBuffer
import org.plos_clan.cpos.utils.PollEvents
import kotlin.concurrent.atomics.ExperimentalAtomicApi

internal enum class TimerFdClock(val number: ULong) {
    REALTIME(0uL),
    MONOTONIC(1uL),
    BOOTTIME(7uL),
    ;

    fun read(monotonicNanos: ULong): ULong = if (this == REALTIME) {
        RealtimeClock.atMonotonic(monotonicNanos).toNanoseconds()
    } else {
        monotonicNanos
    }

    fun deadline(clockNanos: ULong, valueNanos: ULong, absolute: Boolean): ULong? {
        if (valueNanos == 0uL) return null
        if (absolute) return valueNanos

        return if (valueNanos > ULong.MAX_VALUE - clockNanos) ULong.MAX_VALUE
        else clockNanos + valueNanos
    }

    fun toMonotonicDeadline(
        monotonicNanos: ULong,
        clockNanos: ULong,
        deadlineNanos: ULong,
    ): ULong {
        if (this != REALTIME) return deadlineNanos
        val remaining = deadlineNanos - minOf(deadlineNanos, clockNanos)
        return if (remaining > ULong.MAX_VALUE - monotonicNanos) ULong.MAX_VALUE
        else monotonicNanos + remaining
    }

    companion object {
        fun from(number: ULong): TimerFdClock? = when (number) {
            REALTIME.number -> REALTIME
            MONOTONIC.number -> MONOTONIC
            BOOTTIME.number -> BOOTTIME
            else -> null
        }
    }
}

internal class TimerFd(
    private val clock: TimerFdClock,
) : AnonymousFileBackend(InodeType.TIMERFD, "timerfd"),
    ModeAwareOpenFileBackend,
    FixedSizeIoOpenFileBackend {
    private companion object {
        const val VALUE_BYTES = ULong.SIZE_BYTES
    }

    private val lock = IrqSpinLock()
    private val transfer = ByteArray(VALUE_BYTES)
    private val readWaiters = IoWaitQueue()
    private val state = TimerFdState()

    private inner class Alarm : Runnable {
        var handle: DisposableHandle? = null
        override fun run() = lock.withLock {
            if (alarm !== this) return@withLock
            alarm = null
            state.advance(clock.read(TscClock.nanoTime()))
            readWaiters.wakeAll()
        }
    }

    private var alarm: Alarm? = null

    override fun subscribe(
        caller: VfsOperationContext,
        inode: Inode,
        subscription: PollSubscription,
    ) {
        val events = PollEvents.NORMAL_INPUT or PollEvents.POLLRDHUP
        subscription.watch(readWaiters.events, events)
    }

    private fun arm() {
        alarm?.handle?.dispose()
        alarm = null
        if (state.expirations != 0uL) return
        val deadline = state.deadlineNanos ?: return
        val now = TscClock.nanoTime()
        val task = Alarm()
        alarm = task
        task.handle = KernelCoroutines.dispatcher.scheduleAt(
            clock.toMonotonicDeadline(now, clock.read(now), deadline), task,
        )
    }

    override fun release() = lock.withLock {
        alarm?.handle?.dispose()
        alarm = null
    }

    override val ioSize: Int
        get() = VALUE_BYTES

    override val seekable: Boolean
        get() = false

    fun setTime(setting: TimerFdSetting, absolute: Boolean): TimerFdSetting {
        lateinit var previous: TimerFdSetting
        val waiters = lock.withLock {
            val monotonicNow = TscClock.nanoTime()
            val clockNow = clock.read(monotonicNow)
            state.advance(clockNow)
            previous = state.snapshot(clockNow)
            state.replace(
                clock.deadline(clockNow, setting.valueNanos, absolute),
                setting.intervalNanos,
            )
            state.advance(clockNow)

            arm()
            readWaiters.takeAll()
        }
        for (waiter in waiters) Scheduler.wake(waiter)
        return previous
    }

    fun getTime(): TimerFdSetting = lock.withLock {
        val clockNow = clock.read(TscClock.nanoTime())
        state.advance(clockNow)
        state.snapshot(clockNow)
    }

    override fun read(
        caller: VfsOperationContext,
        inode: Inode,
        destination: PreparedBufferDestination,
        destinationOffset: Int,
        count: Int,
        mode: IoMode,
    ): IoResult {
        val thread = if (mode == IoMode.BLOCKING) {
            ProcessManager.currentThread() ?: return IoResult.failure(VfsError.INTERRUPTED)
        } else {
            null
        }
        while (true) {
            var waiter: IoWaitQueue.Waiter? = null
            var deadline: ULong? = null
            val result = lock.withLock {
                val monotonicNow = TscClock.nanoTime()
                val clockNow = clock.read(monotonicNow)
                state.advance(clockNow)
                if (state.expirations != 0uL) {
                    LittleEndianBuffer(transfer).writeU64(0, state.expirations)
                    if (destination.copyFrom(
                            destinationOffset,
                            transfer,
                            0,
                            VALUE_BYTES,
                        ) != VALUE_BYTES
                    ) {

                        return@withLock IoResult.failure(VfsError.FAULT)
                    }

                    state.consume()
                    arm()

                    return@withLock IoResult.success(VALUE_BYTES)
                }
                if (mode == IoMode.NON_BLOCKING) {
                    return@withLock IoResult.failure(VfsError.WOULD_BLOCK)
                }
                waiter = readWaiters.add(checkNotNull(thread))
                deadline = state.deadlineNanos?.let { nextExpiration ->
                    clock.toMonotonicDeadline(monotonicNow, clockNow, nextExpiration)
                }
                null
            }
            if (result != null) return result
            if (!readWaiters.await(lock, checkNotNull(waiter), deadline)) {
                return IoResult.failure(VfsError.INTERRUPTED)
            }
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

    override fun poll(caller: VfsOperationContext, inode: Inode, events: Int): Long =
        lock.withLock {
            val clockNow = clock.read(TscClock.nanoTime())
            state.advance(clockNow)
            val available = if (state.expirations == 0uL) 0 else PollEvents.NORMAL_INPUT
            (available and events).toLong()
        }
}
