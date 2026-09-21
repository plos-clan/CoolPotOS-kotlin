@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package org.plos_clan.cpos.tasks

import kotlin.concurrent.atomics.AtomicBoolean

internal class PollWait(
    private val thread: Thread,
    private val cleanup: () -> Unit = {},
) : PollSubscription() {
    init { check(thread.waitResource.compareAndSet(null, this)) }

    override fun onClosed() {
        thread.waitResource.compareAndSet(this, null)
        cleanup()
    }

    private val pending = AtomicBoolean(false)

    override fun changed(): Boolean {
        if (!pending.exchange(true)) Scheduler.wake(thread)
        return true
    }

    fun prepare() = pending.store(false)

    fun await(deadline: ULong?) {
        val sequence = Scheduler.preparePark()
        if (pending.load() || thread.hasPendingSignal()) return
        if (deadline == null) Scheduler.parkCurrent(sequence) else Scheduler.parkCurrentUntil(deadline, sequence)
    }
}
