package org.plos_clan.cpos.coroutines

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.coroutines.Runnable

@State(Scope.Benchmark)
class KernelCoroutineQueueBenchmark {
    @Param("16", "4096")
    var taskCount = 0

    private lateinit var queue: KernelCoroutineQueue
    private var deadline = 0uL

    @Setup
    fun prepare() {
        queue = KernelCoroutineQueue()
        val runnable = Runnable {}
        repeat(taskCount) { queue.scheduleAt(it.toULong(), runnable) }
        deadline = taskCount.toULong() - 1uL
    }

    @Benchmark
    fun rescheduleReadyTask(): Runnable {
        val ready = queue.claimReady(deadline, 1).single()
        deadline++
        queue.scheduleAt(deadline, ready)
        return ready
    }
}
