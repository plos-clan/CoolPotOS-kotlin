package org.plos_clan.cpos.network

import org.plos_clan.cpos.drivers.net.MacAddress

internal enum class NetworkInterfaceKind(
    val hardwareType: UShort,
    val broadcastAddress: MacAddress,
) {
    ETHERNET(1u, MacAddress.BROADCAST),
    LOOPBACK(772u, MacAddress.ZERO),
}

internal enum class NetworkInterfaceState(val abiValue: UByte, val sysfsName: String) {
    UNKNOWN(0u, "unknown"),
    DOWN(2u, "down"),
    UP(6u, "up"),
}

internal interface NetworkInterfaceView {
    val index: Int
    val name: String
    val kind: NetworkInterfaceKind
    val hardwareAddress: MacAddress
    val mtu: Int
    val configurationFlags: UInt
    val operationalState: NetworkInterfaceState
    val carrier: Boolean
    val linkSpeedBitsPerSecond: ULong
}
