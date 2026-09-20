package org.plos_clan.cpos.coroutines

import kotlinx.coroutines.Runnable
import org.plos_clan.cpos.utils.IndexedHeap

internal class DelayedCoroutineTask(
    val deadlineNanos: ULong,
    val sequence: ULong,
    val runnable: Runnable,
) {
    lateinit var entry: IndexedHeap.Entry<DelayedCoroutineTask>
}

internal class KernelCoroutineQueue {
    private val immediate = ArrayDeque<Runnable>()
    private val delayed = run {
        val order = compareBy<DelayedCoroutineTask> { it.deadlineNanos }.thenBy { it.sequence }
        IndexedHeap(order)
    }
    private var nextSequence = 0uL

    fun enqueue(runnable: Runnable) {
        immediate.addLast(runnable)
    }

    fun schedule(nowNanos: ULong, delayMillis: Long, runnable: Runnable): DelayedCoroutineTask {
        return scheduleAt(deadlineNanos(nowNanos, delayMillis), runnable)
    }

    fun scheduleAt(deadlineNanos: ULong, runnable: Runnable): DelayedCoroutineTask {
        if (delayed.size == 0) {
            nextSequence = 0uL
        } else {
            check(nextSequence != ULong.MAX_VALUE) { "delayed task sequence exhausted" }
        }
        val task = DelayedCoroutineTask(
            deadlineNanos = deadlineNanos,
            sequence = nextSequence++,
            runnable = runnable,
        )
        task.entry = delayed.add(task)
        return task
    }

    fun dispose(task: DelayedCoroutineTask): Boolean = delayed.remove(task.entry)

    fun claimReady(nowNanos: ULong, limit: Int): List<Runnable> {
        require(limit > 0) { "limit must be positive" }

        while (immediate.size < limit && delayed.size != 0) {
            val next = checkNotNull(delayed.peek())
            if (next.deadlineNanos > nowNanos) {
                break
            }

            val ready = checkNotNull(delayed.removeFirst())
            immediate.addLast(ready.runnable)
        }

        if (immediate.isEmpty()) {
            return emptyList()
        }
        val claimed = ArrayList<Runnable>(minOf(limit, immediate.size))
        while (claimed.size < limit && immediate.isNotEmpty()) {
            claimed += immediate.removeFirst()
        }
        return claimed
    }

    fun hasImmediateWork(): Boolean = immediate.isNotEmpty()

    fun nextDeadline(): ULong? = delayed.peek()?.deadlineNanos

    private fun deadlineNanos(nowNanos: ULong, delayMillis: Long): ULong {
        if (delayMillis <= 0) {
            return nowNanos
        }

        val millis = delayMillis.toULong()
        val delayNanos = if (millis > ULong.MAX_VALUE / NANOS_PER_MILLISECOND) {
            ULong.MAX_VALUE
        } else {
            millis * NANOS_PER_MILLISECOND
        }
        return if (delayNanos > ULong.MAX_VALUE - nowNanos) {
            ULong.MAX_VALUE
        } else {
            nowNanos + delayNanos
        }
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000uL
    }
}
