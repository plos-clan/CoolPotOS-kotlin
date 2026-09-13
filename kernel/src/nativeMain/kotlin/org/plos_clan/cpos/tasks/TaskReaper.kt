package org.plos_clan.cpos.tasks

import kotlinx.coroutines.isActive
import kotlinx.coroutines.yield
import org.plos_clan.cpos.coroutines.KernelCoroutines
import org.plos_clan.cpos.coroutines.KernelEvent
import org.plos_clan.cpos.utils.IrqSpinLock

object TaskReaper {
    private class Exit(val thread: Thread, val waitStatus: Int)

    private val lock = IrqSpinLock()
    private val queued = ArrayDeque<Exit>()
    private lateinit var wakeup: KernelEvent

    internal fun enqueue(thread: Thread, waitStatus: Int) {
        lock.withLock { queued.addLast(Exit(thread, waitStatus)) }
        wakeup.signal()
    }

    fun initialize() {
        check(!::wakeup.isInitialized) { "process reaper is already initialized" }
        val event = KernelCoroutines.dispatcher.createEvent()
        wakeup = event
        KernelCoroutines.launch("process-reaper") {
            while (isActive) {
                val exit = lock.withLock { queued.removeFirstOrNull() }
                if (exit == null) {
                    event.await()
                    continue
                }
                val thread = exit.thread
                while (!thread.nativeTask.hasExited) yield()
                ProcessManager.finishThreadExit(thread, exit.waitStatus)
                yield()
            }
        }
    }
}
