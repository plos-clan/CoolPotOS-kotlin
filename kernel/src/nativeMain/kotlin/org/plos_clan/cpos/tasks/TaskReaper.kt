package org.plos_clan.cpos.tasks

import kotlinx.coroutines.isActive
import kotlinx.coroutines.yield
import org.plos_clan.cpos.coroutines.KernelCoroutines
import org.plos_clan.cpos.coroutines.KernelEvent
import org.plos_clan.cpos.utils.IrqSpinLock

object TaskReaper {
    private val lock = IrqSpinLock()
    private val queued = LinkedHashSet<Process>()
    private lateinit var wakeup: KernelEvent

    internal fun enqueue(process: Process) {
        lock.withLock {
            check(queued.add(process)) { "process ${process.id} is already queued for reaping" }
        }
        wakeup.signal()
    }

    fun initialize() {
        check(!::wakeup.isInitialized) { "process reaper is already initialized" }
        val event = KernelCoroutines.dispatcher.createEvent()
        wakeup = event
        KernelCoroutines.launch("process-reaper") {
            while (isActive) {
                val process = lock.withLock {
                    val iterator = queued.iterator()
                    if (!iterator.hasNext()) null else iterator.next().also { iterator.remove() }
                }
                if (process == null) {
                    event.await()
                    continue
                }
                ProcessManager.finishExited(process)
                yield()
            }
        }
    }
}
