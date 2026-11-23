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
    private val delayed = IndexedHeap<DelayedCoroutineTask> { first, second ->
        val deadline = first.deadlineNanos.compareTo(second.deadlineNanos)
        if (deadline != 0) deadline else first.sequence.compareTo(second.sequence)
    }
    private var nextSequence = 0uL

    fun enqueue(runnable: Runnable) {
        immediate.addLast(runnable)
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

    inline fun poll(now: () -> ULong): Runnable? {
        val next = delayed.peek()
        if (next != null && next.deadlineNanos <= now()) {
            delayed.remove(next.entry)
            if (immediate.isEmpty()) return next.runnable
            immediate.addLast(next.runnable)
        }
        return immediate.removeFirstOrNull()
    }

    val readyCount: Int get() = immediate.size

    fun steal(): Runnable? = immediate.removeLastOrNull()

    fun nextDeadline(): ULong? = delayed.peek()?.deadlineNanos
}
