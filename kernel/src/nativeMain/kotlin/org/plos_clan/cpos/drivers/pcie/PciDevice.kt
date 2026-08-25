package org.plos_clan.cpos.drivers.pcie

internal data class PciFunctionInfo(
    val address: PciAddress,
    val vendorId: UShort,
    val deviceId: UShort,
    val classCode: UByte,
    val subClass: UByte,
    val progIf: UByte,
    val revision: UByte,
    val interruptLine: UByte,
) {
    val classValue: UInt
        get() = (classCode.toUInt() shl 16) or
            (subClass.toUInt() shl 8) or
            progIf.toUInt()
}

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
    internal constructor(
        function: PciFunctionInfo,
        bars: Array<PciBar?>,
        interrupt: PciInterrupt?,
    ) : this(
        address = function.address,
        vendorId = function.vendorId,
        deviceId = function.deviceId,
        classCode = function.classCode,
        subClass = function.subClass,
        progIf = function.progIf,
        revision = function.revision,
        bars = bars,
        interrupt = interrupt,
        deviceType = PciDeviceType.parse(function.classCode, function.subClass),
    )

    fun printInfo() {
        println(
            "${address}: ${deviceType.displayName} " +
                "[${vendorId.toInt().toString(16).padStart(4, '0')}:" +
                "${deviceId.toInt().toString(16).padStart(4, '0')}] " +
                "(rev: ${revision.toInt().toString(16).padStart(2, '0')})",
        )
    }
}
