package org.plos_clan.cpos.drivers.usb.bus

data class UsbBuffer(
    val physicalAddress: ULong,
    val length: UInt,
    val virtualAddress: ULong = 0uL,
)
