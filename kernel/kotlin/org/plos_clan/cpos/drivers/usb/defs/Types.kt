package org.plos_clan.cpos.drivers.usb.defs

data class SetupPacket(
    val requestType: UByte,
    val request: UByte,
    val value: UShort = 0u,
    val index: UShort = 0u,
    val length: UShort = 0u,
) {
    fun toNativeBytes(): ByteArray = ByteArray(8).also { bytes ->
        bytes[0] = requestType.toByte()
        bytes[1] = request.toByte()
        bytes[2] = value.toByte()
        bytes[3] = (value.toUInt() shr 8).toByte()
        bytes[4] = index.toByte()
        bytes[5] = (index.toUInt() shr 8).toByte()
        bytes[6] = length.toByte()
        bytes[7] = (length.toUInt() shr 8).toByte()
    }
}

data class EndpointDescriptor(
    val endpointAddress: UByte,
    val attributes: UByte,
    val maxPacketSize: UShort,
    val interval: UByte,
)

data class SsEndpointCompanionDescriptor(
    val maxBurst: UByte,
    val attributes: UByte,
    val bytesPerInterval: UShort,
)
