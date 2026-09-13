@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(InternalForKotlinNative::class)

package org.plos_clan.cpos.tasks

import kotlinx.coroutines.isActive
import kotlinx.coroutines.yield
import org.plos_clan.cpos.coroutines.KernelCoroutines
import org.plos_clan.cpos.coroutines.KernelEvent
import org.plos_clan.cpos.utils.IrqSpinLock
import kotlin.native.internal.GCUnsafeCall
import kotlin.native.internal.InternalForKotlinNative

@GCUnsafeCall("fast_handoff_task_has_exited")
private external fun taskHasExited(task: ULong): Boolean

object TaskReaper {
    private class Exit(val thread: Thread, val lastThread: Boolean)

    private val lock = IrqSpinLock()
    private val queued = ArrayDeque<Exit>()
    private lateinit var wakeup: KernelEvent

    internal fun enqueue(thread: Thread, lastThread: Boolean) {
        lock.withLock { queued.addLast(Exit(thread, lastThread)) }
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
                while (!taskHasExited(thread.nativeContext)) yield()
                thread.kernelStack?.close()
                if (exit.lastThread) ProcessManager.finishExited(thread.process)
                yield()
            }
        }
    }
}
