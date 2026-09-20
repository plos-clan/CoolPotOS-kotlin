@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.tasks.cgroup

import kotlinx.cinterop.ExperimentalForeignApi
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.tasks.Scheduler
import org.plos_clan.cpos.tasks.Signal
import org.plos_clan.cpos.tasks.SignalInfo
import org.plos_clan.cpos.tasks.SignalRouter
import org.plos_clan.cpos.tasks.Thread
import org.plos_clan.cpos.utils.IrqSpinLock

internal fun interface CgroupPlacement {
    fun fork(id: Int, processId: Int, parent: CgroupHierarchy.Task?): VfsResult<CgroupHierarchy.Task>
}

internal object Cgroups {
    val lock = IrqSpinLock()
    var observer: ((CgroupHierarchy.Group, CgroupHierarchy.Event) -> Unit)? = null
    private val accounting = HashMap<CgroupHierarchy.Group, CpuAccounting>()
    private fun account(group: CgroupHierarchy.Group): CpuAccounting {
        accounting[group]?.let { return it }
        val parent = group.parent?.let(::account)
        val created = CpuAccounting(parent)
        accounting[group] = created
        return created
    }

    fun cpuStat(group: CgroupHierarchy.Group): String = account(group).render()
    fun remove(group: CgroupHierarchy.Group) = accounting.remove(group)?.close()
    fun account(tasks: Collection<CgroupHierarchy.Task>) {
        for (task in tasks) {
            val thread = ProcessManager.findThread(task.id) ?: continue
            account(task.group).attach(thread)
        }
    }

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
        account(task.group).attach(thread)
        if (task.killed) {
            val signal = SignalInfo(Signal.KILL, SignalInfo.KERNEL)
            SignalRouter.sendProcess(null, thread.process, signal)
        }
        if (task.freezing) thread.nativeTask.access { bridge.fast_handoff_request_user_interrupt(it) }
    }

    fun kill(group: CgroupHierarchy.Group) {
        val processes = mutableSetOf<Process>()
        for (child in group.subtree()) {
            for (task in child.tasks) {
                task.killed = true
                ProcessManager.findThread(task.id)?.process?.let(processes::add)
            }
        }
        for (process in processes) {
            val signal = SignalInfo(Signal.KILL, SignalInfo.KERNEL)
            SignalRouter.sendProcess(null, process, signal)
        }
    }

    fun wake(tasks: Collection<CgroupHierarchy.Task>) {
        for (task in tasks) {
            val thread = ProcessManager.findThread(task.id) ?: continue
            if (task.freezing) {
                thread.nativeTask.access {
                    bridge.fast_handoff_request_user_interrupt(it)
                    bridge.fast_handoff_unpark(it)
                }
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
            if (!bridge.fast_handoff_park_current(0uL)) bridge.fast_handoff_yield()
        }
    }
}
