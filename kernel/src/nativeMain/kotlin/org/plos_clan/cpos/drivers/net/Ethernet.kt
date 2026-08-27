@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
)

package org.plos_clan.cpos.drivers.net

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UByteVar
import org.plos_clan.cpos.utils.IrqSpinLock
import kotlin.concurrent.atomics.AtomicReference

value class MacAddress private constructor(private val value: ULong) {
    val isUnicast: Boolean
        get() = value != 0uL && this[0] and 1u.toUByte() == 0u.toUByte()

    operator fun get(index: Int): UByte {
        require(index in 0 until SIZE_BYTES)
        return (value shr ((SIZE_BYTES - index - 1) * Byte.SIZE_BITS)).toUByte()
    }

    fun toUByteArray(): UByteArray = UByteArray(SIZE_BYTES, ::get)

    override fun toString(): String = (0 until SIZE_BYTES).joinToString(":") { index ->
        this[index].toString(16).padStart(2, '0')
    }

    companion object {
        const val SIZE_BYTES = 6
        val ZERO = MacAddress(0uL)

        fun from(bytes: ByteArray, offset: Int = 0): MacAddress? {
            if (offset < 0 || offset > bytes.size - SIZE_BYTES) return null
            var value = 0uL
            repeat(SIZE_BYTES) { index ->
                value = (value shl Byte.SIZE_BITS) or bytes[offset + index].toUByte().toULong()
            }
            return MacAddress(value)
        }
    }
}

fun interface EthernetFrameReceiver {
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
    private val receiver = AtomicReference<EthernetFrameReceiver?>(null)

    fun register(device: EthernetDevice) {
        lock.withLock { devices.add(device) }
    }

    fun unregister(device: EthernetDevice) {
        lock.withLock { devices.remove(device) }
    }

    fun snapshot(): List<EthernetDevice> = lock.withLock { devices.toList() }

    fun installReceiver(receiver: EthernetFrameReceiver?) = this.receiver.store(receiver)

    internal fun receive(device: EthernetDevice, frame: CPointer<UByteVar>, length: UInt) {
        receiver.load()?.receive(device, frame, length)
    }
}
