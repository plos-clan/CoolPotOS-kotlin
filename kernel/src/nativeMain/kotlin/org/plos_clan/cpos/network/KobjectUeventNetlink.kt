@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package org.plos_clan.cpos.network

import org.plos_clan.cpos.coroutines.KernelCoroutines
import kotlinx.coroutines.Runnable
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.concurrent.atomics.AtomicLong

internal class KobjectUeventNetlinkProtocol(network: NetworkStack) : NetlinkProtocol(
    network,
    NetlinkProtocolKind.KOBJECT_UEVENT,
), KobjectUeventPublisher {
    override val multicastGroupCount = UDEV_GROUP

    override fun publish(event: KobjectUevent) = multicastFromKernel(KERNEL_GROUP) {
        event.encode(nextSequence.fetchAndAdd(1L).toULong())
    }

    companion object : KobjectUeventPublisher {
        private val nextSequence = AtomicLong(1L)

        override fun publish(event: KobjectUevent) {
            val bytes = event.encode(nextSequence.fetchAndAdd(1L).toULong())
            val delivery = Runnable {
                for (network in NetworkStack.snapshot()) {
                    network.netlink.uevent.multicastFromKernel(KERNEL_GROUP) { bytes }
                }
            }
            KernelCoroutines.dispatcher.dispatch(EmptyCoroutineContext, delivery)
        }

        private const val KERNEL_GROUP = 1
        private const val UDEV_GROUP = 2
    }
}
