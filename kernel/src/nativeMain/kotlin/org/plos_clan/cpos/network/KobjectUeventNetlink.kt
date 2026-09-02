@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package org.plos_clan.cpos.network

import kotlin.concurrent.atomics.AtomicLong

internal object KobjectUeventNetlinkProtocol : NetlinkProtocol(
    NetlinkProtocolKind.KOBJECT_UEVENT,
) {
    private val nextSequence = AtomicLong(1L)

    override val multicastGroupCount = UDEV_GROUP

    fun publish(event: KobjectUevent) = multicastFromKernel(KERNEL_GROUP) {
        event.encode(nextSequence.fetchAndAdd(1L).toULong())
    }

    private const val KERNEL_GROUP = 1
    private const val UDEV_GROUP = 2
}
