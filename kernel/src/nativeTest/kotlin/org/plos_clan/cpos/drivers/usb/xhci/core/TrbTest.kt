package org.plos_clan.cpos.drivers.usb.xhci.core

import kotlin.test.Test
import kotlin.test.assertEquals

class TrbTest {
    @Test
    fun createsNoOpCommand() {
        assertEquals(TRB_NO_OP_CMD, Trb.newNoOpCmd().type)
    }

    @Test
    fun encodesNormalTransfer() {
        val trb = Trb.newNormal(
            buffer = 0xFEDC_BA98_7654_3210uL,
            length = 0x12_3456u,
        )

        assertEquals(0x7654_3210u, trb.paramLow)
        assertEquals(0xFEDC_BA98u, trb.paramHigh)
        assertEquals(0x12_3456u, trb.transferLength)
        assertEquals(TRB_NORMAL, trb.type)
        assertEquals(TRB_IOC or TRB_ISP, trb.control and (TRB_IOC or TRB_ISP))
    }

    @Test
    fun decodesEventFields() {
        val trb = Trb(
            status = (0xABu shl 24) or 0x12_3456u,
            control = (0x7Au shl 24) or (0x1Bu shl 16) or (TRB_TRANSFER_EVENT shl 10),
        )

        assertEquals(TRB_TRANSFER_EVENT, trb.type)
        assertEquals(0x7Au.toUByte(), trb.slotId)
        assertEquals(0x1Bu, trb.endpointId)
        assertEquals(0xABu, trb.completionCode)
        assertEquals(0x12_3456u, trb.transferLength)
    }
}
