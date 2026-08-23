@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.syscall

import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.tasks.Scheduler
import org.plos_clan.cpos.tasks.Signal
import org.plos_clan.cpos.tasks.TaskState
import org.plos_clan.cpos.tasks.Thread

internal object ProcessExit {
    fun current(process: Process, status: Int, group: Boolean): Nothing {
        val thread = ProcessManager.currentThread() ?: error("exit without a current thread")
        check(thread.process === process) { "exit process does not own the current thread" }
        terminate(thread, (status and 0xff) shl 8, group)
    }

    fun bySignal(thread: Thread, signal: Signal, coreDump: Boolean): Nothing =
        terminate(
            current = thread,
            waitStatus = signal.number or if (coreDump) 0x80 else 0,
            group = true,
        )

    private fun terminate(current: Thread, waitStatus: Int, group: Boolean): Nothing {
        val process = current.process
        val exiting = if (group) process.threads.toList() else listOf(current)
        if (group) process.signals.resume(process)
        exiting.forEach { thread ->
            thread.state = TaskState.ZOMBIE
            thread.signals.pending.discard(ULong.MAX_VALUE)
            clearChildTid(thread)
        }
        val processExited = group || process.threads.none { it.state != TaskState.ZOMBIE }
        if (processExited) {
            process.signals.pending.discard(ULong.MAX_VALUE)
            ProcessManager.markExited(process, waitStatus)
        }

        Scheduler.yieldCurrent()
        while (true) bridge.wait_for_interrupt()
    }

    private fun clearChildTid(thread: Thread) {
        val address = thread.clearChildTid
        thread.clearChildTid = 0uL
        if (address != 0uL &&
            UserMemory(thread.process.addressSpace, address).copyToUser(ByteArray(Int.SIZE_BYTES))
        ) {
            Futex.wakePrivate(thread.process, address)
        }
    }
}
