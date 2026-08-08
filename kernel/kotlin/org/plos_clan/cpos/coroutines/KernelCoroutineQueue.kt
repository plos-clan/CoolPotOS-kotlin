package org.plos_clan.cpos.coroutines

import kotlinx.coroutines.Runnable

internal enum class DelayedTaskState {
    PENDING,
    CLAIMED,
    DISPOSED,
}

internal class DelayedCoroutineTask(
    val deadlineNanos: ULong,
    val sequence: ULong,
    val runnable: Runnable,
) {
    var state: DelayedTaskState = DelayedTaskState.PENDING
}

internal class KernelCoroutineQueue {
    private val immediate = ArrayDeque<Runnable>()
    private val delayed = mutableListOf<DelayedCoroutineTask>()
    private var nextSequence = 0uL

    fun enqueue(runnable: Runnable) {
        immediate.addLast(runnable)
    }

    fun schedule(nowNanos: ULong, delayMillis: Long, runnable: Runnable): DelayedCoroutineTask {
        return scheduleAt(deadlineNanos(nowNanos, delayMillis), runnable)
    }

    fun scheduleAt(deadlineNanos: ULong, runnable: Runnable): DelayedCoroutineTask {
        if (delayed.isEmpty()) {
            nextSequence = 0uL
        } else {
            check(nextSequence != ULong.MAX_VALUE) { "delayed task sequence exhausted" }
        }
        val task = DelayedCoroutineTask(
            deadlineNanos = deadlineNanos,
            sequence = nextSequence++,
            runnable = runnable,
        )
        heapPush(task)
        return task
    }

    fun dispose(task: DelayedCoroutineTask): Boolean {
        if (task.state != DelayedTaskState.PENDING) {
            return false
        }
        task.state = DelayedTaskState.DISPOSED
        return true
    }

    fun claimReady(nowNanos: ULong, limit: Int): List<Runnable> {
        require(limit > 0) { "limit must be positive" }

        while (immediate.size < limit && delayed.isNotEmpty()) {
            val next = delayed.first()
            if (next.state == DelayedTaskState.DISPOSED) {
                heapPop()
                continue
            }
            if (next.deadlineNanos > nowNanos) {
                break
            }

            val ready = heapPop()
            ready.state = DelayedTaskState.CLAIMED
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

    private fun heapPush(task: DelayedCoroutineTask) {
        delayed += task
        var childIndex = delayed.lastIndex
        while (childIndex > 0) {
            val parentIndex = (childIndex - 1) / 2
            if (!comesBefore(delayed[childIndex], delayed[parentIndex])) {
                break
            }
            swap(childIndex, parentIndex)
            childIndex = parentIndex
        }
    }

    private fun heapPop(): DelayedCoroutineTask {
        val root = delayed.first()
        val last = delayed.removeAt(delayed.lastIndex)
        if (delayed.isEmpty()) {
            return root
        }

        delayed[0] = last
        var parentIndex = 0
        while (true) {
            val leftIndex = parentIndex * 2 + 1
            if (leftIndex >= delayed.size) {
                break
            }
            val rightIndex = leftIndex + 1
            val firstChildIndex = if (
                rightIndex < delayed.size && comesBefore(delayed[rightIndex], delayed[leftIndex])
            ) {
                rightIndex
            } else {
                leftIndex
            }
            if (!comesBefore(delayed[firstChildIndex], delayed[parentIndex])) {
                break
            }
            swap(parentIndex, firstChildIndex)
            parentIndex = firstChildIndex
        }
        return root
    }

    private fun swap(firstIndex: Int, secondIndex: Int) {
        val first = delayed[firstIndex]
        delayed[firstIndex] = delayed[secondIndex]
        delayed[secondIndex] = first
    }

    private fun comesBefore(first: DelayedCoroutineTask, second: DelayedCoroutineTask): Boolean =
        first.deadlineNanos < second.deadlineNanos ||
            (first.deadlineNanos == second.deadlineNanos && first.sequence < second.sequence)

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
