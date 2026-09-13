@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.tasks

import kotlinx.cinterop.pointed
import kotlinx.cinterop.toLong
import kotlinx.coroutines.isActive
import kotlinx.coroutines.yield
import org.plos_clan.cpos.coroutines.KernelCoroutines
import org.plos_clan.cpos.coroutines.KernelEvent
import org.plos_clan.cpos.utils.IrqSpinLock
import org.plos_clan.cpos.utils.toPointer

object TaskReaper {
    private class Exit(val thread: Thread, val waitStatus: Int)

    private val lock = IrqSpinLock()
    private val queued = ArrayDeque<Exit>()
    private val runtimeExits = ArrayList<NativeTask>()
    private lateinit var wakeup: KernelEvent

    internal fun reapRuntime(): Boolean {
        var handle = bridge.fast_handoff_take_exited_runtime()
        while (handle != 0uL) {
            val next = handle.toPointer<bridge.fast_task_t>()!!.pointed.next.toLong().toULong()
            runtimeExits += NativeTask.adoptRuntime(handle)
            handle = next
        }
        var pendingSwitch = false
        runtimeExits.removeAll { task ->
            if (task.hasExited) task.reapRuntime() else {
                pendingSwitch = true
                false
            }
        }
        return pendingSwitch
    }

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
