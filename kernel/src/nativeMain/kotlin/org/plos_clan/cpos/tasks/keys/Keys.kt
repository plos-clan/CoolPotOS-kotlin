package org.plos_clan.cpos.tasks.keys

import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.plos_clan.cpos.coroutines.KernelCoroutines
import org.plos_clan.cpos.drivers.TscClock
import org.plos_clan.cpos.tasks.CapEnum
import org.plos_clan.cpos.tasks.Thread
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.IrqSpinLock

internal object Keys {
    private val lock = IrqSpinLock()
    private val store = KeyStore({ (TscClock.nanoTime() / 1_000_000_000uL).toLong() })
    private var collecting = false

    fun <T> access(thread: Thread, action: KeyStore.Context.() -> T): T = lock.withLock {
        if (!collecting) {
            collecting = true
            KernelCoroutines.launch("key-gc") {
                while (isActive) {
                    delay(60_000)
                    lock.withLock { store.collect() }
                }
            }
        }
        context(thread).apply { privileged = thread.capabilities.hasEffective(CapEnum.SYS_ADMIN) }.action()
    }

    fun fork(parent: Thread, child: Thread) = lock.withLock {
        child.keys = context(parent).fork(child.process.credentials, parent.process === child.process)
    }

    fun exec(thread: Thread) = lock.withLock { thread.keys?.exec() }

    fun updateIdentity(thread: Thread) = lock.withLock { thread.keys?.updateIdentity() }

    fun exit(thread: Thread) = lock.withLock { thread.keys?.close() }

    fun applyPendingSession(thread: Thread) {
        if (thread.keys?.hasPendingSession == true) lock.withLock { thread.keys?.applyPendingSession() }
    }

    fun sessionToParent(thread: Thread, parent: Thread) = lock.withLock {
        val target = parent.keys?.takeUnless { it.closed } ?: throw KeyFailure(Errno.EPERM)
        context(thread).sessionToParent(target)
    }

    private fun context(thread: Thread): KeyStore.Context =
        thread.keys ?: store.Context(thread.process.credentials).also { thread.keys = it }
}
