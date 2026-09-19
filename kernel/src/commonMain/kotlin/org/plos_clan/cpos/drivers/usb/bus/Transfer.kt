@file:OptIn(ExperimentalAtomicApi::class)

package org.plos_clan.cpos.drivers.usb.bus

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CompletableDeferred
import org.plos_clan.cpos.drivers.usb.defs.SetupPacket

enum class TransferStatus {
    COMPLETED,
    SHORT_PACKET,
    STALL,
    TRB_ERROR,
    BABBLE,
    DATA_ERROR,
    SPLIT_ERROR,
    TIMEOUT,
    CANCELLED,
    DISCONNECTED,
    DRIVER_ERROR,
    UNKNOWN;

    val successful: Boolean
        get() = this == COMPLETED || this == SHORT_PACKET
}

data class TransferResult(val status: TransferStatus, val actualLength: UInt) {
    val successful: Boolean
        get() = status.successful
}

class UsbTransfer(
    val endpointAddress: UByte,
    buffers: List<UsbBuffer> = emptyList(),
    val streamId: UShort = 0u,
    val setup: SetupPacket? = null,
    private val completion: ((TransferResult) -> Unit)? = null,
) {
    private val result = CompletableDeferred<TransferResult>()
    private val submitted = AtomicBoolean(false)
    val buffers = buffers.toList()
    val length: UInt

    init {
        val total = this.buffers.sumOf { it.length.toULong() }
        require(total <= UInt.MAX_VALUE.toULong())
        require(this.buffers.all { it.physicalAddress <= ULong.MAX_VALUE - it.length })
        require(this.buffers.all { it.virtualAddress <= ULong.MAX_VALUE - it.length })
        require(setup == null || endpointAddress == 0u.toUByte() && streamId == 0u.toUShort())
        require(setup == null || total == setup.length.toULong())
        length = total.toUInt()
    }

    val isCompleted: Boolean
        get() = result.isCompleted

    internal fun claim(): Boolean = submitted.compareAndSet(false, true)

    internal fun complete(value: TransferResult) {
        if (result.complete(value)) completion?.invoke(value)
    }

    suspend fun await(): TransferResult = result.await()
}
