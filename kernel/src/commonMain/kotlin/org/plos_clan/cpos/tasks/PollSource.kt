@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package org.plos_clan.cpos.tasks

import org.plos_clan.cpos.utils.SnapshotSet
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.update

class PollSource {
    private val listeners = SnapshotSet<PollSubscription>()

    internal fun attach(subscription: PollSubscription) = listeners.add(subscription)

    internal fun detach(subscription: PollSubscription) = listeners.remove(subscription)

    fun signal() {
        var exclusive = false
        val snapshot = listeners.snapshot
        for (index in snapshot.indices) {
            val listener = snapshot[index]
            if (listener.exclusive && exclusive) continue
            if (listener.notify() && listener.exclusive) exclusive = true
        }
    }
}

abstract class PollSubscription(val exclusive: Boolean = false) : AutoCloseable {
    private sealed interface Sources {
        data object Closed : Sources
        class Link(val source: PollSource, val previous: Link?) : Sources
    }

    private val sources = AtomicReference<Sources?>(null)

    open val events: Int get() = -1

    fun watch(source: PollSource, events: Int = -1) {
        if (sources.load() === Sources.Closed || this.events and events == 0) return
        if (!source.attach(this)) return
        sources.update { head ->
            if (head === Sources.Closed) return source.detach(this)
            Sources.Link(source, head as Sources.Link?)
        }
    }

    internal fun notify(): Boolean = sources.load() !== Sources.Closed && changed()

    protected abstract fun changed(): Boolean

    protected open fun onClosed() {}

    final override fun close() {
        val head = sources.exchange(Sources.Closed)
        if (head === Sources.Closed) return
        var link = head as Sources.Link?
        while (link != null) {
            link.source.detach(this)
            link = link.previous
        }
        onClosed()
    }
}
