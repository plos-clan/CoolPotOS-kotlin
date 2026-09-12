package org.plos_clan.cpos.tasks

import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Runnable
import org.plos_clan.cpos.coroutines.KernelCoroutines
import org.plos_clan.cpos.drivers.TscClock
import org.plos_clan.cpos.utils.IrqSpinLock

internal class ProcessAlarm(private val process: Process) {
    private val lock = IrqSpinLock()
    private var pending: Expiration? = null

    fun replace(seconds: UInt): UInt = lock.withLock {
        val now = TscClock.nanoTime()
        val previous = pending
        val remaining = previous?.let { it.deadlineNanos - minOf(it.deadlineNanos, now) } ?: 0uL
        previous?.handle?.dispose()
        pending = null

        if (seconds != 0u && process.state.canReceiveSignals) {
            val duration = seconds.toULong() * NANOS_PER_SECOND
            val deadline = now + minOf(duration, ULong.MAX_VALUE - now)
            val expiration = Expiration(deadline)
            pending = expiration
            expiration.handle = KernelCoroutines.dispatcher.scheduleAt(deadline, expiration)
        }

        // Linux rounds to the nearest second; an armed timer returns at least one,
        // including an overdue timer whose callback has not run yet.
        if (previous == null) 0u
        else maxOf(1uL, (remaining + NANOS_PER_SECOND / 2u) / NANOS_PER_SECOND).toUInt()
    }

    private inner class Expiration(val deadlineNanos: ULong) : Runnable {
        lateinit var handle: DisposableHandle

        override fun run() {
            lock.withLock {
                // Disposal cannot retract a callback already claimed by the dispatcher.
                if (pending !== this) return
                pending = null
                SignalRouter.sendProcess(
                    sender = null,
                    target = process,
                    info = SignalInfo(Signal.ALARM, SignalInfo.KERNEL),
                )
            }
        }
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000uL
    }
}
