package org.plos_clan.cpos.drivers.pcie

data class PciDevice(
    val address: PciAddress,
    val vendorId: UShort,
    val deviceId: UShort,
    val classCode: UByte,
    val subClass: UByte,
    val progIf: UByte,
    val revision: UByte,
    val bars: Array<PciBar?>,
    val interrupt: PciInterrupt?,
    val deviceType: PciDeviceType,
) {
    fun printInfo() {
        println(
            "${address}: ${deviceType.displayName} " +
                "[${vendorId.toInt().toString(16).padStart(4, '0')}:" +
                "${deviceId.toInt().toString(16).padStart(4, '0')}] " +
                "(rev: ${revision.toInt().toString(16).padStart(2, '0')})",
        )
    }
}
