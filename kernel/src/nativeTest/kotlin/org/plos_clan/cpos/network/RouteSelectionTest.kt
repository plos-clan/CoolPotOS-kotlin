package org.plos_clan.cpos.network

import org.plos_clan.cpos.drivers.net.EthernetDevice
import org.plos_clan.cpos.drivers.net.MacAddress
import org.plos_clan.cpos.fs.vfs.VfsResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RouteSelectionTest {
    private class Device : EthernetDevice() {
        override val macAddress = MacAddress.ZERO
        override val maximumFrameSize = 1514u
        override val linkSpeedBitsPerSecond = 100_000_000uL
        override val linkUp = true
        override suspend fun transmit(frame: ByteArray) = true
    }

    @Test
    fun routeSelectionTracksConfigurationAndLinkState() {
        val network = NetworkStack()
        val first = Device()
        val second = Device()
        network.attach(first)
        network.attach(second)
        val firstIndex = checkNotNull(network.interfaceByName("eth0")).index
        val secondIndex = checkNotNull(network.interfaceByName("eth1")).index
        network.setLink(firstIndex, true)
        network.setLink(secondIndex, true)
        val firstAddress = Ipv4Address.fromBits(0x0a000001u)
        val secondAddress = Ipv4Address.fromBits(0x0b000001u)
        val firstAssignment = NetworkInterfaceAddress(firstAddress, 24)
        val secondAssignment = NetworkInterfaceAddress(secondAddress, 24)
        network.addAddress(firstIndex, firstAssignment)
        network.addAddress(secondIndex, secondAssignment)
        val destination = Ipv4Address.fromBits(0x0c000002u)
        val prefix = Ipv4Prefix(destination, 24)
        val fallback = NetworkRoute(Ipv4Prefix(Ipv4Address.ANY, 0), interfaceIndex = firstIndex)
        val preferred = NetworkRoute(prefix, interfaceIndex = secondIndex, metric = 100u)
        val equal = NetworkRoute(prefix, interfaceIndex = firstIndex, metric = 100u)
        try {
            network.addRoute(fallback)
            assertEquals(firstAddress, network.pathSource(destination))
            network.addRoute(preferred)
            network.addRoute(equal)
            assertEquals(secondAddress, network.pathSource(destination))
            val cheaper = equal.copy(metric = 10u)
            network.addRoute(cheaper)
            assertEquals(firstAddress, network.pathSource(destination))
            network.removeRoute(cheaper)
            assertEquals(secondAddress, network.pathSource(destination))
            network.setLink(secondIndex, false)
            assertEquals(firstAddress, network.pathSource(destination))
            network.setLink(secondIndex, true)
            assertEquals(secondAddress, network.pathSource(destination))
            val replacement = preferred.copy(metric = 200u)
            network.replaceRoute(replacement)
            assertEquals(firstAddress, network.pathSource(destination))
            network.removeRoute(equal)
            assertEquals(secondAddress, network.pathSource(destination))
            assertEquals(firstAddress, network.pathSource(destination, firstIndex))
            network.removeAddress(secondIndex, secondAddress)
            assertIs<VfsResult.Err>(network.path(Ipv4Address.ANY, destination))
            network.addAddress(secondIndex, secondAssignment)
            assertEquals(secondAddress, network.pathSource(destination))
            network.detach(second)
            assertEquals(firstAddress, network.pathSource(destination))
        } finally {
            network.detach(first)
            network.detach(second)
        }
    }

    @Test
    fun connectedRoutesAreRebuiltAfterAddressChanges() {
        val network = NetworkStack()
        val device = Device()
        network.attach(device)
        val index = checkNotNull(network.interfaceByName("eth0")).index
        network.setLink(index, true)
        val source = Ipv4Address.fromBits(0x0a000001u)
        val destination = Ipv4Address.fromBits(0x0a000002u)
        val assignment = NetworkInterfaceAddress(source, 24)
        try {
            assertIs<VfsResult.Err>(network.path(Ipv4Address.ANY, destination))
            network.addAddress(index, assignment)
            assertEquals(source, network.pathSource(destination))
            network.removeAddress(index, source)
            assertIs<VfsResult.Err>(network.path(Ipv4Address.ANY, destination))
            network.addAddress(index, assignment)
            assertEquals(source, network.pathSource(destination))
            network.detach(device)
            assertIs<VfsResult.Err>(network.path(Ipv4Address.ANY, destination))
        } finally {
            network.detach(device)
        }
    }

    private fun NetworkStack.pathSource(destination: Ipv4Address, index: Int? = null): Ipv4Address {
        val result = path(Ipv4Address.ANY, destination, index)
        return assertIs<VfsResult.Ok<NetworkPath>>(result).value.source
    }
}
