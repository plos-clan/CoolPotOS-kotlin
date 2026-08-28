@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
)

package org.plos_clan.cpos.network

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.readBytes
import kotlinx.coroutines.delay
import org.plos_clan.cpos.coroutines.KernelCoroutines
import org.plos_clan.cpos.coroutines.KernelEvent
import org.plos_clan.cpos.drivers.TscClock
import org.plos_clan.cpos.drivers.net.EthernetDevice
import org.plos_clan.cpos.drivers.net.EthernetDevices
import org.plos_clan.cpos.drivers.net.EthernetProtocol
import org.plos_clan.cpos.drivers.net.MacAddress
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.utils.IrqSpinLock
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt

internal enum class NetworkInterfaceKind(val hardwareType: UShort) {
    ETHERNET(1u),
    LOOPBACK(772u),
}

internal data class NetworkInterfaceAddress(
    val address: Ipv4Address,
    val prefixLength: Int,
) {
    val prefix = Ipv4Prefix(address, prefixLength)
}

internal enum class NetworkRouteKind {
    UNICAST,
    LOCAL,
    BROADCAST,
}

internal data class NetworkRoute(
    val destination: Ipv4Prefix,
    val gateway: Ipv4Address? = null,
    val interfaceIndex: Int,
    val preferredSource: Ipv4Address? = null,
    val metric: UInt = 0u,
    val kind: NetworkRouteKind = NetworkRouteKind.UNICAST,
    val protocol: UByte = 4u,
)

internal data class NetworkNeighbor(
    val interfaceIndex: Int,
    val address: Ipv4Address,
    val hardwareAddress: MacAddress,
    val reachable: Boolean,
)

internal data class NetworkPath(
    val source: Ipv4Address,
    val mtu: Int,
)

internal interface NetworkConfigurationListener {
    fun linkChanged(interface_: NetworkInterface, removed: Boolean) {}

    fun addressChanged(
        interface_: NetworkInterface,
        address: NetworkInterfaceAddress,
        removed: Boolean,
    ) {}

    fun routeChanged(route: NetworkRoute, removed: Boolean) {}

    fun neighborChanged(neighbor: NetworkNeighbor, removed: Boolean) {}
}

internal data class IpPacketContext(
    val interface_: NetworkInterface,
    val bytes: ByteArray,
    val source: Ipv4Address,
    val destination: Ipv4Address,
    val protocol: UByte,
    val payloadOffset: Int,
    val payloadLength: Int,
)

internal enum class IpTransportError {
    NETWORK_UNREACHABLE,
    HOST_UNREACHABLE,
    PROTOCOL_UNREACHABLE,
    PORT_UNREACHABLE,
    FRAGMENTATION_NEEDED,
    TIME_EXCEEDED,
}

internal interface IpProtocolHandler {
    val protocol: IpProtocol

    fun receive(packet: IpPacketContext)

    fun receiveError(packet: IpPacketContext, error: IpTransportError) {}
}

internal class NetworkInterface internal constructor(
    val index: Int,
    val name: String,
    val kind: NetworkInterfaceKind,
    private val device: EthernetDevice?,
) {
    private val administrativeState = AtomicBoolean(kind == NetworkInterfaceKind.LOOPBACK)
    private val configuredMtu = AtomicInt(
        if (device == null) LOOPBACK_MTU
        else minOf(DEFAULT_ETHERNET_MTU, device.maximumFrameSize.toInt() - EthernetHeader.SIZE),
    )
    private val attached = AtomicBoolean(true)
    private val transmitLock = IrqSpinLock()
    private val transmitQueue = ArrayDeque<ByteArray>()
    private var queuedBytes = 0
    private val transmitEvent: KernelEvent? = device?.let { KernelCoroutines.dispatcher.createEvent() }

    val hardwareAddress: MacAddress
        get() = device?.macAddress ?: MacAddress.ZERO

    val mtu: Int
        get() = configuredMtu.load()

    val hardwareMaximumMtu: Int
        get() = if (device == null) LOOPBACK_MTU
        else device.maximumFrameSize.toInt() - EthernetHeader.SIZE

    val administrativeUp: Boolean
        get() = administrativeState.load()

    val running: Boolean
        get() = administrativeUp && attached.load() && (device?.linkUp ?: true)

    val linkSpeedBitsPerSecond: ULong
        get() = device?.linkSpeedBitsPerSecond ?: 0uL

    init {
        if (device != null) {
            KernelCoroutines.launch("net-tx-$name") {
                transmitLoop(device, checkNotNull(transmitEvent))
            }
        }
    }

    internal fun setAdministrativeUp(up: Boolean): Boolean =
        administrativeState.exchange(up) != up

    internal fun setMtu(mtu: Int): Boolean {
        if (mtu !in MIN_IPV4_MTU..hardwareMaximumMtu) return false
        configuredMtu.store(mtu)
        return true
    }

    internal fun detach() {
        attached.store(false)
        administrativeState.store(false)
        transmitLock.withLock {
            transmitQueue.clear()
            queuedBytes = 0
        }
        transmitEvent?.signal()
    }

    internal fun transmit(frame: ByteArray): Boolean {
        if (device == null || !running || frame.size > device.maximumFrameSize.toInt()) return false
        val accepted = transmitLock.withLock {
            if (queuedBytes > MAX_QUEUED_BYTES - frame.size) return@withLock false
            transmitQueue.addLast(frame)
            queuedBytes += frame.size
            true
        }
        if (accepted) transmitEvent?.signal()
        return accepted
    }

    private suspend fun transmitLoop(device: EthernetDevice, event: KernelEvent) {
        while (attached.load()) {
            val frame = transmitLock.withLock {
                transmitQueue.removeFirstOrNull()?.also { queuedBytes -= it.size }
            }
            if (frame == null) {
                event.await()
            } else if (running) {
                device.transmit(frame)
            }
        }
    }

    companion object {
        private const val DEFAULT_ETHERNET_MTU = 1500
        private const val LOOPBACK_MTU = 65_535
        private const val MIN_IPV4_MTU = 68
        private const val MAX_QUEUED_BYTES = 4 * 1024 * 1024
    }
}

internal object NetworkStack : EthernetProtocol {
    private data class NeighborKey(val interfaceIndex: Int, val address: Ipv4Address)

    private data class NeighborEntry(
        val hardwareAddress: MacAddress,
        val expiresAt: ULong,
    )

    private class PendingNeighbor(
        val source: Ipv4Address,
        var lastRequest: ULong,
    ) {
        val frames = ArrayDeque<ByteArray>()
    }

    private data class SelectedRoute(
        val interface_: NetworkInterface,
        val source: Ipv4Address,
        val nextHop: Ipv4Address,
    )

    private data class FragmentKey(
        val interfaceIndex: Int,
        val source: Ipv4Address,
        val destination: Ipv4Address,
        val protocol: UByte,
        val identification: UShort,
    )

    private data class FragmentPiece(val offset: Int, val bytes: ByteArray)

    private class FragmentAssembly(val lastUpdated: ULong) {
        var updatedAt = lastUpdated
        var totalLength: Int? = null
        val pieces = mutableListOf<FragmentPiece>()
        var storedBytes = 0

        fun add(offset: Int, more: Boolean, payload: ByteArray, now: ULong): ByteArray? {
            if (invalid) return null
            val end = offset + payload.size
            if (end > MAX_REASSEMBLED_PAYLOAD || pieces.size >= MAX_FRAGMENTS ||
                pieces.any { offset < it.offset + it.bytes.size && it.offset < end }
            ) {
                pieces.clear()
                storedBytes = -1
                return null
            }
            if (!more) {
                val known = totalLength
                if (known != null && known != end) {
                    pieces.clear()
                    storedBytes = -1
                    return null
                }
                totalLength = end
            }
            pieces += FragmentPiece(offset, payload)
            pieces.sortBy(FragmentPiece::offset)
            storedBytes += payload.size
            updatedAt = now
            val expected = totalLength ?: return null
            if (storedBytes != expected) return null
            var cursor = 0
            for (piece in pieces) {
                if (piece.offset != cursor) return null
                cursor += piece.bytes.size
            }
            if (cursor != expected) return null
            return ByteArray(expected).also { result ->
                pieces.forEach { it.bytes.copyInto(result, it.offset) }
            }
        }

        val invalid: Boolean
            get() = storedBytes < 0

        companion object {
            private const val MAX_FRAGMENTS = 128
            private const val MAX_REASSEMBLED_PAYLOAD = 65_515
        }
    }

    private val lock = IrqSpinLock()
    private val listenerLock = IrqSpinLock()
    private val fragmentLock = IrqSpinLock()
    private val interfaces = mutableMapOf<Int, NetworkInterface>()
    private val deviceInterfaces = mutableMapOf<EthernetDevice, NetworkInterface>()
    private val addresses = mutableMapOf<Int, MutableList<NetworkInterfaceAddress>>()
    private val routes = mutableListOf<NetworkRoute>()
    private val neighbors = mutableMapOf<NeighborKey, NeighborEntry>()
    private val pendingNeighbors = mutableMapOf<NeighborKey, PendingNeighbor>()
    private val handlers = mutableMapOf<UByte, IpProtocolHandler>()
    private val fragments = mutableMapOf<FragmentKey, FragmentAssembly>()
    private val listeners = mutableSetOf<NetworkConfigurationListener>()
    private val nextInterfaceIndex = AtomicInt(2)
    private val nextIdentification = AtomicInt(0)
    private var initialized = false

    fun initialize() {
        if (initialized) return
        initialized = true
        val loopback = NetworkInterface(LOOPBACK_INDEX, "lo", NetworkInterfaceKind.LOOPBACK, null)
        lock.withLock {
            interfaces[loopback.index] = loopback
            addresses[loopback.index] = mutableListOf(
                NetworkInterfaceAddress(Ipv4Address.fromBits(0x7F00_0001u), 8),
            )
        }
        EthernetDevices.installProtocol(this)
        UdpProtocol.initialize()
        TcpProtocol.initialize()
        KernelCoroutines.launch("network-maintenance") {
            while (true) {
                delay(MAINTENANCE_INTERVAL_MILLIS)
                expireState()
            }
        }
    }

    fun registerHandler(handler: IpProtocolHandler) {
        lock.withLock {
            check(handlers.put(handler.protocol.number, handler) == null) {
                "IP protocol ${handler.protocol} is already registered"
            }
        }
    }

    fun addListener(listener: NetworkConfigurationListener) {
        listenerLock.withLock { listeners.add(listener) }
    }

    fun snapshotInterfaces(): List<NetworkInterface> =
        lock.withLock { interfaces.values.sortedBy(NetworkInterface::index) }

    fun interfaceByIndex(index: Int): NetworkInterface? = lock.withLock { interfaces[index] }

    fun interfaceByName(name: String): NetworkInterface? = lock.withLock {
        interfaces.values.firstOrNull { it.name == name }
    }

    fun interfaceAddresses(index: Int): List<NetworkInterfaceAddress> =
        lock.withLock { addresses[index]?.toList().orEmpty() }

    fun snapshotRoutes(): List<NetworkRoute> = lock.withLock { allRoutesLocked() }

    fun snapshotNeighbors(): List<NetworkNeighbor> {
        val now = TscClock.nanoTime()
        return lock.withLock {
            neighbors.map { (key, entry) ->
                NetworkNeighbor(
                    key.interfaceIndex,
                    key.address,
                    entry.hardwareAddress,
                    entry.expiresAt > now,
                )
            }
        }
    }

    fun setNeighbor(
        interfaceIndex: Int,
        address: Ipv4Address,
        hardwareAddress: MacAddress,
    ): VfsResult<Unit> {
        if (!hardwareAddress.isUnicast || !address.isUnicast) {
            return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        }
        val neighbor = lock.withLock {
            if (interfaces[interfaceIndex] == null) return@withLock null
            neighbors[NeighborKey(interfaceIndex, address)] = NeighborEntry(
                hardwareAddress,
                ULong.MAX_VALUE,
            )
            NetworkNeighbor(interfaceIndex, address, hardwareAddress, reachable = true)
        } ?: return VfsResult.Err(VfsError.NO_DEVICE)
        notifyListeners { it.neighborChanged(neighbor, removed = false) }
        return VfsResult.Ok(Unit)
    }

    fun removeNeighbor(interfaceIndex: Int, address: Ipv4Address): VfsResult<Unit> {
        val entry = lock.withLock { neighbors.remove(NeighborKey(interfaceIndex, address)) }
            ?: return VfsResult.Err(VfsError.NOT_FOUND)
        notifyListeners {
            it.neighborChanged(
                NetworkNeighbor(interfaceIndex, address, entry.hardwareAddress, reachable = false),
                removed = true,
            )
        }
        return VfsResult.Ok(Unit)
    }

    fun setLink(index: Int, up: Boolean, mtu: Int? = null): VfsResult<Unit> {
        val interface_ = lock.withLock { interfaces[index] }
            ?: return VfsResult.Err(VfsError.NO_DEVICE)
        if (mtu != null && !interface_.setMtu(mtu)) {
            return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        }
        val changed = interface_.setAdministrativeUp(up) || mtu != null
        if (changed) notifyListeners { it.linkChanged(interface_, removed = false) }
        return VfsResult.Ok(Unit)
    }

    fun addAddress(index: Int, address: NetworkInterfaceAddress): VfsResult<Unit> {
        if (address.address.isAny || address.address.isLimitedBroadcast ||
            address.address.isMulticast
        ) return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        val interface_ = lock.withLock {
            val selected = interfaces[index] ?: return@withLock null
            val assigned = addresses.getOrPut(index) { mutableListOf() }
            if (assigned.any { it.address == address.address &&
                    it.prefixLength == address.prefixLength
                }
            ) return VfsResult.Err(VfsError.ALREADY_EXISTS)
            assigned += address
            selected
        } ?: return VfsResult.Err(VfsError.NO_DEVICE)
        notifyListeners { it.addressChanged(interface_, address, removed = false) }
        if (interface_.kind == NetworkInterfaceKind.ETHERNET && interface_.running) {
            sendArpAnnouncement(interface_, address.address)
        }
        return VfsResult.Ok(Unit)
    }

    fun removeAddress(index: Int, address: Ipv4Address, prefixLength: Int? = null): VfsResult<Unit> {
        var removed: NetworkInterfaceAddress? = null
        val interface_ = lock.withLock {
            val selected = interfaces[index] ?: return@withLock null
            val assigned = addresses[index] ?: return VfsResult.Err(VfsError.ADDRESS_NOT_AVAILABLE)
            val position = assigned.indexOfFirst {
                it.address == address && (prefixLength == null || it.prefixLength == prefixLength)
            }
            if (position < 0) return VfsResult.Err(VfsError.ADDRESS_NOT_AVAILABLE)
            removed = assigned.removeAt(position)
            selected
        } ?: return VfsResult.Err(VfsError.NO_DEVICE)
        notifyListeners { it.addressChanged(interface_, checkNotNull(removed), removed = true) }
        return VfsResult.Ok(Unit)
    }

    fun addRoute(route: NetworkRoute): VfsResult<Unit> {
        val normalized = route.copy(
            destination = Ipv4Prefix(route.destination.network, route.destination.length),
        )
        val added = lock.withLock {
            if (interfaces[normalized.interfaceIndex] == null) return@withLock false
            if (routes.contains(normalized)) return VfsResult.Err(VfsError.ALREADY_EXISTS)
            routes += normalized
            true
        }
        if (!added) return VfsResult.Err(VfsError.NO_DEVICE)
        notifyListeners { it.routeChanged(normalized, removed = false) }
        return VfsResult.Ok(Unit)
    }

    fun removeRoute(route: NetworkRoute): VfsResult<Unit> {
        val removed = lock.withLock {
            val index = routes.indexOfFirst { existing ->
                existing.destination.network == route.destination.network &&
                    existing.destination.length == route.destination.length &&
                    existing.gateway == route.gateway &&
                    existing.interfaceIndex == route.interfaceIndex &&
                    (route.preferredSource == null ||
                        existing.preferredSource == route.preferredSource) &&
                    (route.metric == 0u || existing.metric == route.metric) &&
                    (route.protocol == 0.toUByte() || existing.protocol == route.protocol)
            }
            if (index < 0) null else routes.removeAt(index)
        } ?: return VfsResult.Err(VfsError.NOT_FOUND)
        notifyListeners { it.routeChanged(removed, removed = true) }
        return VfsResult.Ok(Unit)
    }

    fun replaceRoute(route: NetworkRoute): VfsResult<Unit> {
        val normalized = route.copy(
            destination = Ipv4Prefix(route.destination.network, route.destination.length),
        )
        var replaced: NetworkRoute? = null
        val added = lock.withLock {
            if (interfaces[normalized.interfaceIndex] == null) return@withLock false
            val index = routes.indexOfFirst {
                it.destination.network == normalized.destination.network &&
                    it.destination.length == normalized.destination.length
            }
            if (index >= 0) replaced = routes.removeAt(index)
            routes += normalized
            true
        }
        if (!added) return VfsResult.Err(VfsError.NO_DEVICE)
        replaced?.let { previous -> notifyListeners { it.routeChanged(previous, removed = true) } }
        notifyListeners { it.routeChanged(normalized, removed = false) }
        return VfsResult.Ok(Unit)
    }

    fun isLocalAddress(address: Ipv4Address): Boolean = lock.withLock {
        addresses.values.any { assigned -> assigned.any { it.address == address } }
    }

    fun path(source: Ipv4Address, destination: Ipv4Address): VfsResult<NetworkPath> =
        lock.withLock { selectRouteLocked(source, destination) }?.let {
            VfsResult.Ok(NetworkPath(it.source, it.interface_.mtu))
        } ?: VfsResult.Err(
            if (source.isAny) VfsError.NETWORK_UNREACHABLE else VfsError.ADDRESS_NOT_AVAILABLE,
        )

    fun isBroadcast(address: Ipv4Address): Boolean = lock.withLock {
        address.isLimitedBroadcast || addresses.values.any { assigned ->
            assigned.any { it.prefixLength < 31 && it.prefix.broadcast == address }
        }
    }

    fun sendIpv4(
        source: Ipv4Address,
        destination: Ipv4Address,
        protocol: IpProtocol,
        payload: ByteArray,
        payloadOffset: Int = 0,
        payloadLength: Int = payload.size - payloadOffset,
        dontFragment: Boolean = false,
        ttl: UByte = 64u,
    ): VfsResult<Ipv4Address> {
        if (payloadOffset < 0 || payloadLength < 0 ||
            payloadOffset > payload.size - payloadLength ||
            payloadLength > Ipv4Codec.MAX_PACKET_SIZE - Ipv4Codec.MIN_HEADER_SIZE
        ) return VfsResult.Err(VfsError.MESSAGE_TOO_LONG)
        val route = lock.withLock { selectRouteLocked(source, destination) }
            ?: return VfsResult.Err(
                if (source.isAny) VfsError.NETWORK_UNREACHABLE else VfsError.ADDRESS_NOT_AVAILABLE,
            )
        val identification = nextIdentification.fetchAndAdd(1).toUShort()
        if (route.interface_.kind == NetworkInterfaceKind.LOOPBACK) {
            val packet = ByteArray(Ipv4Codec.MIN_HEADER_SIZE + payloadLength)
            payload.copyInto(
                packet,
                Ipv4Codec.MIN_HEADER_SIZE,
                payloadOffset,
                payloadOffset + payloadLength,
            )
            Ipv4Codec.writeHeader(
                packet,
                0,
                payloadLength,
                route.source,
                destination,
                protocol.number,
                identification,
                ttl,
            )
            dispatchIpv4(
                IpPacketContext(
                    route.interface_,
                    packet,
                    route.source,
                    destination,
                    protocol.number,
                    Ipv4Codec.MIN_HEADER_SIZE,
                    payloadLength,
                ),
                protocol.number,
            )
            return VfsResult.Ok(route.source)
        }
        if (!route.interface_.running) return VfsResult.Err(VfsError.NETWORK_UNREACHABLE)
        val maximumPayload = route.interface_.mtu - Ipv4Codec.MIN_HEADER_SIZE
        if (maximumPayload <= 0 || dontFragment && payloadLength > maximumPayload) {
            return VfsResult.Err(VfsError.MESSAGE_TOO_LONG)
        }
        val fragmentPayload = maximumPayload and 7.inv()
        if (payloadLength > maximumPayload && fragmentPayload == 0) {
            return VfsResult.Err(VfsError.MESSAGE_TOO_LONG)
        }
        var position = 0
        var firstFragment = true
        while (firstFragment || position < payloadLength) {
            firstFragment = false
            val remaining = payloadLength - position
            val currentLength = if (remaining > maximumPayload) fragmentPayload else remaining
            val moreFragments = position + currentLength < payloadLength
            val frame = ByteArray(EthernetHeader.SIZE + Ipv4Codec.MIN_HEADER_SIZE + currentLength)
            payload.copyInto(
                frame,
                EthernetHeader.SIZE + Ipv4Codec.MIN_HEADER_SIZE,
                payloadOffset + position,
                payloadOffset + position + currentLength,
            )
            Ipv4Codec.writeHeader(
                frame,
                EthernetHeader.SIZE,
                currentLength,
                route.source,
                destination,
                protocol.number,
                identification,
                ttl,
                position,
                moreFragments,
                dontFragment,
            )
            if (!routeFrame(route, destination, frame)) {
                return VfsResult.Err(VfsError.NO_MEMORY)
            }
            position += currentLength
        }
        return VfsResult.Ok(route.source)
    }

    fun sendPortUnreachable(packet: IpPacketContext) {
        sendIcmpError(packet, ICMP_DESTINATION_UNREACHABLE, ICMP_PORT_UNREACHABLE)
    }

    override fun attach(device: EthernetDevice) {
        val interface_ = NetworkInterface(
            nextInterfaceIndex.fetchAndAdd(1),
            "eth${nextInterfaceIndex.load() - 3}",
            NetworkInterfaceKind.ETHERNET,
            device,
        )
        lock.withLock {
            interfaces[interface_.index] = interface_
            deviceInterfaces[device] = interface_
            addresses[interface_.index] = mutableListOf()
        }
        notifyListeners { it.linkChanged(interface_, removed = false) }
    }

    override fun detach(device: EthernetDevice) {
        val removedRoutes = mutableListOf<NetworkRoute>()
        val interface_ = lock.withLock {
            val selected = deviceInterfaces.remove(device) ?: return
            interfaces.remove(selected.index)
            addresses.remove(selected.index)
            routes.removeAll { route ->
                (route.interfaceIndex == selected.index).also { if (it) removedRoutes += route }
            }
            neighbors.keys.removeAll { it.interfaceIndex == selected.index }
            pendingNeighbors.keys.removeAll { it.interfaceIndex == selected.index }
            selected
        }
        interface_.detach()
        removedRoutes.forEach { route -> notifyListeners { it.routeChanged(route, true) } }
        notifyListeners { it.linkChanged(interface_, removed = true) }
    }

    override fun receive(device: EthernetDevice, frame: CPointer<UByteVar>, length: UInt) {
        if (length > Int.MAX_VALUE.toUInt()) return
        val interface_ = lock.withLock { deviceInterfaces[device] } ?: return
        val size = length.toInt()
        if (!interface_.running || size < EthernetHeader.SIZE ||
            size > device.maximumFrameSize.toInt()
        ) return
        receiveFrame(interface_, frame.readBytes(size))
    }

    private fun receiveFrame(interface_: NetworkInterface, frame: ByteArray) {
        val ethernet = EthernetHeader.decode(frame) ?: return
        if (ethernet.destination != interface_.hardwareAddress &&
            ethernet.destination != MacAddress.BROADCAST &&
            ethernet.destination[0].toUInt() and 1u != 1u
        ) return
        when (ethernet.type) {
            EthernetType.ARP.value -> receiveArp(interface_, frame)
            EthernetType.IPV4.value -> receiveIpv4(interface_, frame)
        }
    }

    private fun receiveArp(interface_: NetworkInterface, frame: ByteArray) {
        val packet = ArpPacket.decode(
            frame,
            EthernetHeader.SIZE,
            frame.size - EthernetHeader.SIZE,
        ) ?: return
        if (!packet.senderHardwareAddress.isUnicast ||
            packet.senderProtocolAddress.isLimitedBroadcast ||
            packet.senderProtocolAddress.isMulticast
        ) return
        if (!packet.senderProtocolAddress.isAny) {
            learnNeighbor(interface_, packet.senderProtocolAddress, packet.senderHardwareAddress)
        }
        if (packet.operation != ArpOperation.REQUEST) return
        val ownsTarget = lock.withLock {
            addresses[interface_.index]?.any { it.address == packet.targetProtocolAddress } == true
        }
        if (!ownsTarget) return
        sendArp(
            interface_,
            packet.senderHardwareAddress,
            ArpPacket(
                ArpOperation.REPLY,
                interface_.hardwareAddress,
                packet.targetProtocolAddress,
                packet.senderHardwareAddress,
                packet.senderProtocolAddress,
            ),
        )
    }

    private fun learnNeighbor(
        interface_: NetworkInterface,
        address: Ipv4Address,
        hardwareAddress: MacAddress,
    ) {
        val key = NeighborKey(interface_.index, address)
        val neighbor = NetworkNeighbor(interface_.index, address, hardwareAddress, reachable = true)
        val pending = lock.withLock {
            neighbors[key] = NeighborEntry(
                hardwareAddress,
                TscClock.nanoTime() + NEIGHBOR_REACHABLE_NANOS,
            )
            pendingNeighbors.remove(key)
        }
        pending?.frames?.forEach { frame ->
            EthernetHeader(
                hardwareAddress,
                interface_.hardwareAddress,
                EthernetType.IPV4.value,
            ).writeTo(frame)
            interface_.transmit(frame)
        }
        notifyListeners { it.neighborChanged(neighbor, removed = false) }
    }

    private fun receiveIpv4(interface_: NetworkInterface, frame: ByteArray) {
        val packet = Ipv4Codec.decode(
            frame,
            EthernetHeader.SIZE,
            frame.size - EthernetHeader.SIZE,
        ) ?: return
        if (packet.ttl == 0.toUByte() || !acceptsDestination(interface_, packet.destination)) return
        if (packet.fragmentOffset == 0 && !packet.moreFragments) {
            dispatchIpv4(
                IpPacketContext(
                    interface_,
                    frame,
                    packet.source,
                    packet.destination,
                    packet.protocol,
                    packet.payloadOffset,
                    packet.payloadLength,
                ),
                packet.protocol,
            )
            return
        }
        val payload = frame.copyOfRange(
            packet.payloadOffset,
            packet.payloadOffset + packet.payloadLength,
        )
        val key = FragmentKey(
            interface_.index,
            packet.source,
            packet.destination,
            packet.protocol,
            packet.identification,
        )
        val reassembled = fragmentLock.withLock {
            val now = TscClock.nanoTime()
            if (fragments[key] == null && fragments.size >= MAX_FRAGMENT_ASSEMBLIES) {
                fragments.minByOrNull { it.value.updatedAt }?.key?.let(fragments::remove)
            }
            val assembly = fragments.getOrPut(key) { FragmentAssembly(now) }
            val result = assembly.add(packet.fragmentOffset, packet.moreFragments, payload, now)
            if (result != null) fragments.remove(key)
            result
        } ?: return
        dispatchIpv4(
            IpPacketContext(
                interface_,
                reassembled,
                packet.source,
                packet.destination,
                packet.protocol,
                0,
                reassembled.size,
            ),
            packet.protocol,
        )
    }

    private fun dispatchIpv4(packet: IpPacketContext, protocol: UByte) {
        if (protocol == IpProtocol.ICMP.number) {
            receiveIcmp(packet)
            return
        }
        val handler = lock.withLock { handlers[protocol] }
        if (handler == null) {
            sendIcmpError(packet, ICMP_DESTINATION_UNREACHABLE, ICMP_PROTOCOL_UNREACHABLE)
        } else {
            handler.receive(packet)
        }
    }

    private fun receiveIcmp(packet: IpPacketContext) {
        if (packet.payloadLength < ICMP_HEADER_SIZE || !InternetChecksum.valid(
                packet.bytes,
                packet.payloadOffset,
                packet.payloadLength,
            )
        ) return
        val input = NetworkOrderBuffer(packet.bytes)
        val type = input.readU8(packet.payloadOffset).toInt()
        val code = input.readU8(packet.payloadOffset + 1).toInt()
        IcmpProtocol.receive(packet, type, code)
        if (type == ICMP_ECHO_REQUEST && code == 0 &&
            !packet.destination.isLimitedBroadcast && !packet.destination.isMulticast
        ) {
            val reply = packet.bytes.copyOfRange(
                packet.payloadOffset,
                packet.payloadOffset + packet.payloadLength,
            )
            val output = NetworkOrderBuffer(reply)
            output.writeU8(0, ICMP_ECHO_REPLY.toUByte())
            output.writeU16(2, 0u)
            output.writeU16(2, InternetChecksum.compute(reply))
            sendIpv4(packet.destination, packet.source, IpProtocol.ICMP, reply)
            return
        }
        val error = when {
            type == ICMP_DESTINATION_UNREACHABLE && code == 0 ->
                IpTransportError.NETWORK_UNREACHABLE
            type == ICMP_DESTINATION_UNREACHABLE && code == 1 ->
                IpTransportError.HOST_UNREACHABLE
            type == ICMP_DESTINATION_UNREACHABLE && code == ICMP_PROTOCOL_UNREACHABLE ->
                IpTransportError.PROTOCOL_UNREACHABLE
            type == ICMP_DESTINATION_UNREACHABLE && code == ICMP_PORT_UNREACHABLE ->
                IpTransportError.PORT_UNREACHABLE
            type == ICMP_DESTINATION_UNREACHABLE && code == 4 ->
                IpTransportError.FRAGMENTATION_NEEDED
            type == ICMP_TIME_EXCEEDED -> IpTransportError.TIME_EXCEEDED
            else -> return
        }
        val quotedOffset = packet.payloadOffset + ICMP_HEADER_SIZE
        val quotedLength = packet.payloadLength - ICMP_HEADER_SIZE
        val quoted = Ipv4Codec.decode(packet.bytes, quotedOffset, quotedLength) ?: return
        val handler = lock.withLock { handlers[quoted.protocol] } ?: return
        handler.receiveError(
            IpPacketContext(
                packet.interface_,
                packet.bytes,
                quoted.source,
                quoted.destination,
                quoted.protocol,
                quoted.payloadOffset,
                minOf(quoted.payloadLength, quotedLength - (quoted.payloadOffset - quotedOffset)),
            ),
            error,
        )
    }

    private fun sendIcmpError(packet: IpPacketContext, type: Int, code: Int) {
        if (packet.source.isAny || packet.source.isLimitedBroadcast || packet.source.isMulticast ||
            packet.destination.isLimitedBroadcast || packet.destination.isMulticast
        ) return
        val quotedPayloadLength = minOf(packet.payloadLength, 8)
        val quotedLength = Ipv4Codec.MIN_HEADER_SIZE + quotedPayloadLength
        val message = ByteArray(ICMP_HEADER_SIZE + quotedLength)
        packet.bytes.copyInto(
            message,
            ICMP_HEADER_SIZE + Ipv4Codec.MIN_HEADER_SIZE,
            packet.payloadOffset,
            packet.payloadOffset + quotedPayloadLength,
        )
        Ipv4Codec.writeHeader(
            message,
            ICMP_HEADER_SIZE,
            quotedPayloadLength,
            packet.source,
            packet.destination,
            packet.protocol,
            0u,
        )
        val output = NetworkOrderBuffer(message)
        output.writeU8(0, type.toUByte())
        output.writeU8(1, code.toUByte())
        output.writeU16(2, 0u)
        output.writeU32(4, 0u)
        output.writeU16(2, InternetChecksum.compute(message))
        sendIpv4(packet.destination, packet.source, IpProtocol.ICMP, message)
    }

    private fun routeFrame(
        route: SelectedRoute,
        destination: Ipv4Address,
        frame: ByteArray,
    ): Boolean {
        val directHardware = when {
            destination.isLimitedBroadcast || isDirectedBroadcast(route.interface_, destination) ->
                MacAddress.BROADCAST
            destination.isMulticast -> multicastHardwareAddress(destination)
            else -> null
        }
        if (directHardware != null) {
            EthernetHeader(
                directHardware,
                route.interface_.hardwareAddress,
                EthernetType.IPV4.value,
            ).writeTo(frame)
            return route.interface_.transmit(frame)
        }
        val key = NeighborKey(route.interface_.index, route.nextHop)
        var resolved: MacAddress? = null
        var request = false
        var queued = false
        lock.withLock {
            val now = TscClock.nanoTime()
            val neighbor = neighbors[key]
            if (neighbor != null && neighbor.expiresAt > now) {
                resolved = neighbor.hardwareAddress
                return@withLock
            }
            if (neighbor != null) neighbors.remove(key)
            val pending = pendingNeighbors.getOrPut(key) { PendingNeighbor(route.source, 0uL) }
            if (pending.frames.size >= MAX_PENDING_NEIGHBOR_FRAMES) return@withLock
            pending.frames.addLast(frame)
            queued = true
            if (pending.lastRequest == 0uL ||
                now - pending.lastRequest >= ARP_REQUEST_INTERVAL_NANOS
            ) {
                pending.lastRequest = now
                request = true
            }
        }
        val hardware = resolved
        if (hardware != null) {
            EthernetHeader(
                hardware,
                route.interface_.hardwareAddress,
                EthernetType.IPV4.value,
            ).writeTo(frame)
            return route.interface_.transmit(frame)
        }
        if (request) sendArpRequest(route.interface_, route.source, route.nextHop)
        return queued
    }

    private fun sendArpRequest(
        interface_: NetworkInterface,
        source: Ipv4Address,
        target: Ipv4Address,
    ) = sendArp(
        interface_,
        MacAddress.BROADCAST,
        ArpPacket(
            ArpOperation.REQUEST,
            interface_.hardwareAddress,
            source,
            MacAddress.ZERO,
            target,
        ),
    )

    private fun sendArpAnnouncement(interface_: NetworkInterface, address: Ipv4Address) = sendArp(
        interface_,
        MacAddress.BROADCAST,
        ArpPacket(
            ArpOperation.REQUEST,
            interface_.hardwareAddress,
            address,
            MacAddress.ZERO,
            address,
        ),
    )

    private fun sendArp(
        interface_: NetworkInterface,
        destination: MacAddress,
        packet: ArpPacket,
    ) {
        val frame = ByteArray(EthernetHeader.SIZE + ArpPacket.SIZE)
        EthernetHeader(destination, interface_.hardwareAddress, EthernetType.ARP.value).writeTo(frame)
        packet.writeTo(frame, EthernetHeader.SIZE)
        interface_.transmit(frame)
    }

    private fun selectRouteLocked(
        requestedSource: Ipv4Address,
        destination: Ipv4Address,
    ): SelectedRoute? {
        val local = addresses.entries.firstNotNullOfOrNull { (index, assigned) ->
            assigned.firstOrNull { it.address == destination }?.let { index to it }
        }
        if (local != null) {
            val loopback = interfaces[LOOPBACK_INDEX] ?: return null
            val source = if (requestedSource.isAny) local.second.address else requestedSource
            if (!hasAddressLocked(source)) return null
            return SelectedRoute(loopback, source, destination)
        }
        if (destination.isLimitedBroadcast) {
            val interface_ = interfaces.values.firstOrNull {
                it.kind == NetworkInterfaceKind.ETHERNET && it.running &&
                    !addresses[it.index].isNullOrEmpty()
            } ?: return null
            val source = selectSourceLocked(interface_, requestedSource, destination) ?: return null
            return SelectedRoute(interface_, source, destination)
        }
        val route = allRoutesLocked()
            .asSequence()
            .filter { it.kind == NetworkRouteKind.UNICAST && it.destination.contains(destination) }
            .mapNotNull { candidate ->
                val interface_ = interfaces[candidate.interfaceIndex]
                    ?.takeIf(NetworkInterface::running) ?: return@mapNotNull null
                Triple(candidate, interface_, candidate.destination.length)
            }
            .sortedWith(
                compareByDescending<Triple<NetworkRoute, NetworkInterface, Int>> { it.third }
                    .thenBy { it.first.metric },
            )
            .firstOrNull() ?: return null
        val source = if (!requestedSource.isAny) {
            requestedSource.takeIf(::hasAddressLocked)
        } else {
            route.first.preferredSource?.takeIf(::hasAddressLocked)
                ?: selectSourceLocked(route.second, Ipv4Address.ANY, destination)
        } ?: return null
        return SelectedRoute(
            route.second,
            source,
            route.first.gateway ?: destination,
        )
    }

    private fun selectSourceLocked(
        interface_: NetworkInterface,
        requested: Ipv4Address,
        destination: Ipv4Address,
    ): Ipv4Address? {
        if (!requested.isAny) return requested.takeIf(::hasAddressLocked)
        val assigned = addresses[interface_.index].orEmpty()
        return assigned.firstOrNull { it.prefix.contains(destination) }?.address
            ?: assigned.firstOrNull()?.address
    }

    private fun allRoutesLocked(): List<NetworkRoute> {
        val result = ArrayList<NetworkRoute>(routes.size + addresses.values.sumOf(List<*>::size) * 3)
        result += routes
        for ((index, assigned) in addresses) {
            for (address in assigned) {
                result += NetworkRoute(
                    address.prefix,
                    interfaceIndex = index,
                    preferredSource = address.address,
                    kind = NetworkRouteKind.UNICAST,
                    protocol = ROUTE_PROTOCOL_KERNEL,
                )
                result += NetworkRoute(
                    Ipv4Prefix(address.address, 32),
                    interfaceIndex = LOOPBACK_INDEX,
                    preferredSource = address.address,
                    kind = NetworkRouteKind.LOCAL,
                    protocol = ROUTE_PROTOCOL_KERNEL,
                )
                if (address.prefixLength < 31) {
                    result += NetworkRoute(
                        Ipv4Prefix(address.prefix.broadcast, 32),
                        interfaceIndex = index,
                        preferredSource = address.address,
                        kind = NetworkRouteKind.BROADCAST,
                        protocol = ROUTE_PROTOCOL_KERNEL,
                    )
                }
            }
        }
        return result.distinct()
    }

    private fun hasAddressLocked(address: Ipv4Address): Boolean =
        addresses.values.any { assigned -> assigned.any { it.address == address } }

    private fun acceptsDestination(
        interface_: NetworkInterface,
        destination: Ipv4Address,
    ): Boolean = lock.withLock {
        destination.isLimitedBroadcast || destination.isMulticast ||
            hasAddressLocked(destination) ||
            addresses[interface_.index].orEmpty().any { address ->
                address.prefixLength < 31 && address.prefix.broadcast == destination
            }
    }

    private fun isDirectedBroadcast(
        interface_: NetworkInterface,
        destination: Ipv4Address,
    ): Boolean = lock.withLock {
        addresses[interface_.index].orEmpty().any {
            it.prefixLength < 31 && it.prefix.broadcast == destination
        }
    }

    private fun multicastHardwareAddress(address: Ipv4Address): MacAddress =
        checkNotNull(
            MacAddress.fromBits(0x0100_5E00_0000uL or (address.value and 0x7F_FFFFu).toULong()),
        )

    private fun expireState() {
        val now = TscClock.nanoTime()
        val expiredNeighbors = mutableListOf<NetworkNeighbor>()
        lock.withLock {
            neighbors.entries.removeAll { (key, entry) ->
                (entry.expiresAt <= now).also { expired ->
                    if (expired) {
                        expiredNeighbors += NetworkNeighbor(
                            key.interfaceIndex,
                            key.address,
                            entry.hardwareAddress,
                            reachable = false,
                        )
                    }
                }
            }
            pendingNeighbors.entries.removeAll { (_, pending) ->
                now - pending.lastRequest >= PENDING_NEIGHBOR_TIMEOUT_NANOS
            }
        }
        fragmentLock.withLock {
            fragments.entries.removeAll { now - it.value.updatedAt >= FRAGMENT_TIMEOUT_NANOS }
        }
        expiredNeighbors.forEach { neighbor ->
            notifyListeners { it.neighborChanged(neighbor, removed = true) }
        }
    }

    private inline fun notifyListeners(notification: (NetworkConfigurationListener) -> Unit) {
        listenerLock.withLock { listeners.toList() }.forEach(notification)
    }

    private const val LOOPBACK_INDEX = 1
    private const val MAINTENANCE_INTERVAL_MILLIS = 1_000L
    private const val NEIGHBOR_REACHABLE_NANOS = 60_000_000_000uL
    private const val ARP_REQUEST_INTERVAL_NANOS = 1_000_000_000uL
    private const val PENDING_NEIGHBOR_TIMEOUT_NANOS = 5_000_000_000uL
    private const val FRAGMENT_TIMEOUT_NANOS = 30_000_000_000uL
    private const val MAX_PENDING_NEIGHBOR_FRAMES = 64
    private const val MAX_FRAGMENT_ASSEMBLIES = 256
    private const val ICMP_HEADER_SIZE = 8
    private const val ICMP_ECHO_REPLY = 0
    private const val ICMP_DESTINATION_UNREACHABLE = 3
    private const val ICMP_ECHO_REQUEST = 8
    private const val ICMP_TIME_EXCEEDED = 11
    private const val ICMP_PROTOCOL_UNREACHABLE = 2
    private const val ICMP_PORT_UNREACHABLE = 3
    private val ROUTE_PROTOCOL_KERNEL = 2u.toUByte()
}
