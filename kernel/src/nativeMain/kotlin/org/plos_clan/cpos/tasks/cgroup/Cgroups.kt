@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.tasks.cgroup

import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.tasks.Scheduler
import org.plos_clan.cpos.tasks.Signal
import org.plos_clan.cpos.tasks.SignalInfo
import org.plos_clan.cpos.tasks.SignalRouter
import org.plos_clan.cpos.tasks.Thread
import org.plos_clan.cpos.utils.IrqSpinLock

/** Admission supplied by a cgroup directory descriptor; invoked with the hierarchy locked. */
internal fun interface CgroupPlacement {
    fun fork(id: Int, processId: Int, parent: CgroupHierarchy.Task?): VfsResult<CgroupHierarchy.Task>
}

/** Lock order: hierarchy, then process/thread tables. No caller parks with this lock held. */
internal object Cgroups {
    val lock = IrqSpinLock()
    var observer: ((CgroupHierarchy.Group, CgroupHierarchy.Event) -> Unit)? = null
    val hierarchy = CgroupHierarchy { group, event -> observer?.invoke(group, event) }

    fun path(process: Process): ByteArray = lock.withLock {
        val task = hierarchy.process(process.id).firstOrNull()
            ?: process.threads.firstOrNull()?.cgroup
        (task?.group ?: hierarchy.root).path()
    }

    fun exit(thread: Thread) {
        val task = thread.cgroup ?: return
        lock.withLock { hierarchy.exit(task) }
    }

    fun published(thread: Thread) = lock.withLock {
        val task = thread.cgroup ?: return@withLock
        if (task.killed) {
            SignalRouter.sendProcess(null, thread.process, SignalInfo(Signal.KILL, SignalInfo.KERNEL))
        }
        if (task.freezing) bridge.fast_handoff_request_user_interrupt(thread.nativeContext)
    }

    /** Called under [lock], including for tasks reserved by a concurrent clone. */
    fun kill(group: CgroupHierarchy.Group) {
        val processes = mutableSetOf<Process>()
        for (child in group.subtree()) {
            for (task in child.tasks) {
                task.killed = true
                ProcessManager.findThread(task.id)?.process?.let(processes::add)
            }
        }
        for (process in processes) {
            SignalRouter.sendProcess(null, process, SignalInfo(Signal.KILL, SignalInfo.KERNEL))
        }
    }

    /** Interrupts user execution and wakes kernel waits so they can reach a safe point. */
    fun wake(tasks: Collection<CgroupHierarchy.Task>) {
        for (task in tasks) {
            val thread = ProcessManager.findThread(task.id) ?: continue
            if (task.freezing) {
                bridge.fast_handoff_request_user_interrupt(thread.nativeContext)
                bridge.fast_handoff_unpark(thread.nativeContext)
            } else if (task.frozen) Scheduler.wake(thread)
        }
    }

    fun awaitThaw(thread: Thread?): Boolean {
        val task = thread?.cgroup ?: return false
        if (!task.freezing && !task.frozen) return false
        var waited = false
        while (true) {
            val frozen = lock.withLock {
                val freeze = task.freezing && !task.killed &&
                    thread.pendingSignalMask and Signal.KILL.bit == 0uL &&
                    thread.process.state.canReceiveSignals
                hierarchy.acknowledgeFreeze(task, freeze)
                freeze
            }
            if (!frozen) return waited
            waited = true
            if (!bridge.fast_handoff_park_current()) bridge.fast_handoff_yield()
        }
    }
}
