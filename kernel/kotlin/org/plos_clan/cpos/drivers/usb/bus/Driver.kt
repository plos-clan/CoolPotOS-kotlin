package org.plos_clan.cpos.drivers.usb.bus

import org.plos_clan.cpos.drivers.usb.defs.EndpointDescriptor
import org.plos_clan.cpos.drivers.usb.defs.SetupPacket
import org.plos_clan.cpos.drivers.usb.defs.SsEndpointCompanionDescriptor

enum class TransferStatus {
    COMPLETED,
    SHORT_PACKET,
    STALL,
    TRB_ERROR,
    BABBLE,
    DATA_ERROR,
    SPLIT_ERROR,
    TIMEOUT,
    DRIVER_ERROR,
    UNKNOWN,
}

data class CompletionEvent(
    val endpointAddress: UByte,
    val status: TransferStatus,
    val residualLength: UInt,
)