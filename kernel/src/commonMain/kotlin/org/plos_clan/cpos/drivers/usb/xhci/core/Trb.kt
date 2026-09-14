package org.plos_clan.cpos.drivers.usb.xhci.core

const val TRB_CYCLE = 0x0000_0001u
const val TRB_ENT = 0x0000_0002u
const val TRB_ISP = 0x0000_0004u
const val TRB_NS = 0x0000_0008u
const val TRB_CHAIN = 0x0000_0010u
const val TRB_IOC = 0x0000_0020u
const val TRB_IDT = 0x0000_0040u

const val TRB_NORMAL = 1u
const val TRB_SETUP_STAGE = 2u
const val TRB_DATA_STAGE = 3u
const val TRB_STATUS_STAGE = 4u
const val TRB_LINK = 6u
const val TRB_ENABLE_SLOT = 9u
const val TRB_DISABLE_SLOT = 10u
const val TRB_ADDRESS_DEVICE = 11u
const val TRB_CONFIG_ENDPOINT = 12u
const val TRB_EVALUATE_CONTEXT = 13u
const val TRB_NO_OP_CMD = 23u
const val TRB_TRANSFER_EVENT = 32u
const val TRB_CMD_COMPLETION = 33u
const val TRB_PORT_STATUS_CHANGE = 34u

const val TRB_SIZE_BYTES = 16
const val TRB_WORD_COUNT = TRB_SIZE_BYTES / UInt.SIZE_BYTES

data class Trb(
    val paramLow: UInt = 0u,
    val paramHigh: UInt = 0u,
    val status: UInt = 0u,
    val control: UInt = 0u,
) {
    val type: UInt
        get() = (control shr 10) and 0x3fu

    val slotId: UByte
        get() = ((control shr 24) and 0xffu).toUByte()

    val endpointId: UInt
        get() = (control shr 16) and 0x1fu

    val completionCode: UInt
        get() = (status shr 24) and 0xffu

    val transferLength: UInt
        get() = status and 0xff_ff_ffu

    companion object {
        fun newNoOpCmd(): Trb = Trb(
            control = TRB_NO_OP_CMD shl 10,
        )

        fun newNormal(buffer: ULong, length: UInt): Trb = Trb(
            paramLow = buffer.toUInt(),
            paramHigh = (buffer shr 32).toUInt(),
            status = length,
            control = (TRB_NORMAL shl 10) or TRB_IOC or TRB_ISP,
        )

        fun newEnableSlot(): Trb = Trb(
            control = TRB_ENABLE_SLOT shl 10,
        )

        fun newDisableSlot(slotId: UByte): Trb = Trb(
            control = (TRB_DISABLE_SLOT shl 10) or (slotId.toUInt() shl 24),
        )

        fun newSetupStage(requestLow: UInt, requestHigh: UInt, transferType: UInt): Trb = Trb(
            paramLow = requestLow,
            paramHigh = requestHigh,
            status = 8u,
            control = (TRB_SETUP_STAGE shl 10) or TRB_IDT or (transferType shl 16),
        )

        fun newDataStage(buffer: ULong, length: UInt, directionIn: Boolean): Trb {
            val directionBit = if (directionIn) 1u shl 16 else 0u
            return Trb(
                paramLow = buffer.toUInt(),
                paramHigh = (buffer shr 32).toUInt(),
                status = length,
                control = (TRB_DATA_STAGE shl 10) or directionBit,
            )
        }

        fun newStatusStage(directionIn: Boolean): Trb {
            val directionBit = if (directionIn) 1u shl 16 else 0u
            return Trb(
                control = (TRB_STATUS_STAGE shl 10) or directionBit or TRB_IOC,
            )
        }

        fun newAddressDevice(contextPointer: ULong, slotId: UByte): Trb = Trb(
            paramLow = contextPointer.toUInt(),
            paramHigh = (contextPointer shr 32).toUInt(),
            control = (TRB_ADDRESS_DEVICE shl 10) or (slotId.toUInt() shl 24),
        )

        fun newConfigureEndpoint(contextPointer: ULong, slotId: UByte): Trb = Trb(
            paramLow = contextPointer.toUInt(),
            paramHigh = (contextPointer shr 32).toUInt(),
            control = (TRB_CONFIG_ENDPOINT shl 10) or (slotId.toUInt() shl 24),
        )

        fun newEvaluateContext(contextPointer: ULong, slotId: UByte): Trb = Trb(
            paramLow = contextPointer.toUInt(),
            paramHigh = (contextPointer shr 32).toUInt(),
            control = (TRB_EVALUATE_CONTEXT shl 10) or (slotId.toUInt() shl 24),
        )
    }
}
