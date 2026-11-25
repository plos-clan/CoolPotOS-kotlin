package org.plos_clan.cpos.drivers.usb.xhci.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

internal class RingSpace {
    private var change: CompletableDeferred<Boolean>? = null

    fun nextChange(): Deferred<Boolean> =
        change ?: CompletableDeferred<Boolean>().also { change = it }

    fun signal(available: Boolean) {
        val waiting = change ?: return
        change = null
        waiting.complete(available)
    }
}
