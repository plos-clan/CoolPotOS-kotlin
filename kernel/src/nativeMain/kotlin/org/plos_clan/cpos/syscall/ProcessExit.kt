@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlin.native.internal.InternalForKotlinNative::class,
)

package org.plos_clan.cpos.syscall

import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.mem.page.KernelPageDirectory
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.tasks.Signal
import org.plos_clan.cpos.tasks.SignalInfo
import org.plos_clan.cpos.tasks.SignalRouter
import org.plos_clan.cpos.tasks.TaskState
import org.plos_clan.cpos.tasks.TaskReaper
import org.plos_clan.cpos.tasks.Thread
import kotlin.native.internal.GCUnsafeCall

@GCUnsafeCall("deinitRuntimeIfNeeded")
private external fun deinitializeRuntime()

@GCUnsafeCall("fast_handoff_set_task_state")
private external fun markNativeTaskExited(task: ULong, state: UByte)

@GCUnsafeCall("fast_handoff_yield")
private external fun yieldFromExitedTask(): Boolean

@GCUnsafeCall("fast_handoff_idle")
private external fun continueScheduling(): Nothing

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
        val exitingThreads = if (group) process.beginExit(waitStatus) else null
        if (exitingThreads != null) {
            process.signals.resume(process)
            for (thread in exitingThreads) {
                if (thread === current || thread.state == TaskState.ZOMBIE) continue
                SignalRouter.sendThread(
                    sender = null,
                    target = thread,
                    info = SignalInfo.fromSender(Signal.KILL, process, SignalInfo.KERNEL),
                )
            }
        }

        current.signals.pending.discard(ULong.MAX_VALUE)
        clearChildTid(current)
        ProcessManager.notifyThreadExited(current)
        check(current.replaceAddressSpace(KernelPageDirectory.addressSpace)) {
            "exiting thread is not current"
        }
        val lastThread = process.completeThreadExit(waitStatus)
        if (lastThread) {
            process.signals.pending.discard(ULong.MAX_VALUE)
            TaskReaper.enqueue(process)
        }

        val nativeContext = current.nativeContext
        val zombieState = TaskState.ZOMBIE.ordinal.toUByte()
        bridge.irq_save()
        deinitializeRuntime()
        markNativeTaskExited(nativeContext, zombieState)
        yieldFromExitedTask()
        continueScheduling()
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
