@file:OptIn(
    ExperimentalForeignApi::class,
    ExperimentalAtomicApi::class,
)

package org.plos_clan.cpos.drivers.net

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import org.plos_clan.cpos.utils.IrqSpinLock
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

interface EthernetProtocol {
    fun attach(device: EthernetDevice) {}

    fun detach(device: EthernetDevice) {}

    fun receive(device: EthernetDevice, frame: CPointer<UByteVar>, length: UInt)
}

abstract class EthernetDevice {
    abstract val macAddress: MacAddress
    abstract val maximumFrameSize: UInt
    abstract val linkSpeedBitsPerSecond: ULong
    abstract val linkUp: Boolean

    abstract suspend fun transmit(frame: ByteArray): Boolean

    protected fun receive(frame: CPointer<UByteVar>, length: UInt) {
        EthernetDevices.receive(this, frame, length)
    }

    companion object {
        const val HEADER_SIZE = 14
    }
}

object EthernetDevices {
    private val lock = IrqSpinLock()
    private val devices = mutableSetOf<EthernetDevice>()
    private val protocol = AtomicReference<EthernetProtocol?>(null)

    fun register(device: EthernetDevice) {
        if (lock.withLock { devices.add(device) }) protocol.load()?.attach(device)
    }

    fun unregister(device: EthernetDevice) {
        if (lock.withLock { devices.remove(device) }) protocol.load()?.detach(device)
    }

    fun snapshot(): List<EthernetDevice> = lock.withLock { devices.toList() }

    fun installProtocol(protocol: EthernetProtocol) {
        check(this.protocol.compareAndSet(null, protocol)) { "Ethernet protocol already installed" }
        snapshot().forEach(protocol::attach)
    }

    internal fun receive(device: EthernetDevice, frame: CPointer<UByteVar>, length: UInt) {
        protocol.load()?.receive(device, frame, length)
    }
}
