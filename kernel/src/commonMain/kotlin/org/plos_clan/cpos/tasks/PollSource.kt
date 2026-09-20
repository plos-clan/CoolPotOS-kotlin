@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package org.plos_clan.cpos.tasks

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference

class PollSource {
    private val listeners = AtomicReference(emptyList<PollSubscription>())

    internal fun attach(subscription: PollSubscription) {
        while (true) {
            val previous = listeners.load()
            if (subscription in previous) return
            val replacement = previous + subscription
            if (listeners.compareAndSet(previous, replacement)) return
        }
    }

    internal fun detach(subscription: PollSubscription) {
        while (true) {
            val previous = listeners.load()
            if (subscription !in previous) return
            val replacement = previous - subscription
            if (listeners.compareAndSet(previous, replacement)) return
        }
    }

    fun signal() {
        var exclusive = false
        for (listener in listeners.load()) {
            if (listener.exclusive && exclusive) continue
            if (listener.notify() && listener.exclusive) exclusive = true
        }
    }
}

abstract class PollSubscription(val exclusive: Boolean = false) : AutoCloseable {
    private val active = AtomicBoolean(true)
    private val sources = LinkedHashSet<PollSource>()

    open val events: Int get() = -1

    fun watch(source: PollSource, events: Int = -1) {
        if (!active.load() || this.events and events == 0) return
        if (sources.add(source)) source.attach(this)
    }

    internal fun notify(): Boolean = active.load() && changed()

    protected abstract fun changed(): Boolean

    protected open fun onClosed() {}

    final override fun close() {
        if (!active.exchange(false)) return
        for (source in sources) source.detach(this)
        sources.clear()
        onClosed()
    }
}
