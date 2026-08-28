package org.plos_clan.cpos.network

import org.plos_clan.cpos.drivers.net.MacAddress
import org.plos_clan.cpos.fs.sock.AbstractSocket
import org.plos_clan.cpos.fs.sock.SocketAddress
import org.plos_clan.cpos.fs.sock.SocketDomain
import org.plos_clan.cpos.fs.sock.SocketReceiveRequest
import org.plos_clan.cpos.fs.sock.SocketReceiveResult
import org.plos_clan.cpos.fs.sock.SocketSendRequest
import org.plos_clan.cpos.fs.sock.SocketType
import org.plos_clan.cpos.fs.vfs.IoResult
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.tasks.IoWaitQueue
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.utils.IrqSpinLock
import org.plos_clan.cpos.utils.LittleEndianBuffer
import org.plos_clan.cpos.utils.PollEvents
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

internal data class NetlinkSocketAddress(
    val portId: UInt,
    val groups: UInt,
) : SocketAddress {
    override val domain = SocketDomain.NETLINK
}

internal data class NetlinkMessage(
    val type: UShort,
    val flags: UShort,
    val sequence: UInt,
    val portId: UInt,
    val payload: ByteArray,
)

internal object NetlinkCodec {
    const val HEADER_SIZE = 16
    const val ATTRIBUTE_HEADER_SIZE = 4

    fun decode(bytes: ByteArray): List<NetlinkMessage>? {
        val messages = mutableListOf<NetlinkMessage>()
        val input = LittleEndianBuffer(bytes)
        var offset = 0
        while (offset < bytes.size) {
            if (offset > bytes.size - HEADER_SIZE) return null
            val length = input.readU32(offset).toLong()
            if (length < HEADER_SIZE || length > bytes.size - offset) return null
            val size = length.toInt()
            messages += NetlinkMessage(
                input.readU16(offset + 4),
                input.readU16(offset + 6),
                input.readU32(offset + 8),
                input.readU32(offset + 12),
                bytes.copyOfRange(offset + HEADER_SIZE, offset + size),
            )
            val aligned = align(size)
            if (aligned > bytes.size - offset) {
                if (offset + size != bytes.size) return null
                offset = bytes.size
            } else {
                offset += aligned
            }
        }
        return messages
    }

    fun encode(
        type: Int,
        flags: Int,
        sequence: UInt,
        payload: ByteArray = ByteArray(0),
    ): ByteArray = ByteArray(HEADER_SIZE + payload.size).also { bytes ->
        LittleEndianBuffer(bytes).apply {
            writeU32(0, bytes.size.toUInt())
            writeU16(4, type.toUShort())
            writeU16(6, flags.toUShort())
            writeU32(8, sequence)
            writeU32(12, 0u)
        }
        payload.copyInto(bytes, HEADER_SIZE)
    }

    fun withPortId(bytes: ByteArray, portId: UInt): ByteArray {
        require(bytes.size >= HEADER_SIZE)
        return bytes.copyOf().also { LittleEndianBuffer(it).writeU32(12, portId) }
    }

    fun attributes(payload: ByteArray, offset: Int): Map<Int, ByteArray>? {
        if (offset < 0 || offset > payload.size) return null
        val result = mutableMapOf<Int, ByteArray>()
        val input = LittleEndianBuffer(payload)
        var cursor = offset
        while (cursor < payload.size) {
            if (cursor > payload.size - ATTRIBUTE_HEADER_SIZE) return null
            val length = input.readU16(cursor).toInt()
            if (length < ATTRIBUTE_HEADER_SIZE || length > payload.size - cursor) return null
            val type = input.readU16(cursor + 2).toInt() and NLA_TYPE_MASK
            result[type] = payload.copyOfRange(cursor + ATTRIBUTE_HEADER_SIZE, cursor + length)
            val aligned = align(length)
            if (aligned > payload.size - cursor) {
                if (cursor + length != payload.size) return null
                cursor = payload.size
            } else {
                cursor += aligned
            }
        }
        return result
    }

    fun attribute(type: Int, payload: ByteArray): ByteArray {
        val length = ATTRIBUTE_HEADER_SIZE + payload.size
        return ByteArray(align(length)).also { bytes ->
            LittleEndianBuffer(bytes).apply {
                writeU16(0, length.toUShort())
                writeU16(2, type.toUShort())
            }
            payload.copyInto(bytes, ATTRIBUTE_HEADER_SIZE)
        }
    }

    fun payload(fixed: ByteArray, attributes: List<ByteArray>): ByteArray {
        val result = ByteArray(fixed.size + attributes.sumOf(ByteArray::size))
        fixed.copyInto(result)
        var offset = fixed.size
        for (attribute in attributes) {
            attribute.copyInto(result, offset)
            offset += attribute.size
        }
        return result
    }

    fun intAttribute(type: Int, value: UInt): ByteArray = attribute(
        type,
        ByteArray(UInt.SIZE_BYTES).also { LittleEndianBuffer(it).writeU32(0, value) },
    )

    fun stringAttribute(type: Int, value: String): ByteArray =
        attribute(type, value.encodeToByteArray() + byteArrayOf(0))

    private fun align(length: Int): Int = (length + 3) and 3.inv()

    private const val NLA_TYPE_MASK = 0x3FFF
}

@OptIn(ExperimentalAtomicApi::class)
internal object RouteNetlink : NetworkConfigurationListener {
    private val initialized = AtomicBoolean(false)
    private val lock = IrqSpinLock()
    private val sockets = mutableMapOf<UInt, NetlinkSocket>()
    private val nextPortId = AtomicInt(0x4000_0000)

    fun createSocket(type: SocketType): NetlinkSocket {
        check(type == SocketType.RAW || type == SocketType.DATAGRAM)
        if (initialized.compareAndSet(false, true)) NetworkStack.addListener(this)
        return NetlinkSocket(this, type)
    }

    fun bind(socket: NetlinkSocket, requested: NetlinkSocketAddress, process: Process): VfsResult<NetlinkSocketAddress> =
        lock.withLock {
            if (sockets.values.any { it === socket }) {
                return@withLock VfsResult.Err(VfsError.INVALID_ARGUMENT)
            }
            var portId = requested.portId
            if (portId == 0u) {
                val processPort = process.id.toUInt()
                portId = if (sockets[processPort] == null) processPort else allocatePortLocked()
            }
            if (sockets[portId] != null) return@withLock VfsResult.Err(VfsError.ADDRESS_IN_USE)
            sockets[portId] = socket
            VfsResult.Ok(NetlinkSocketAddress(portId, requested.groups))
        }

    fun updateGroups(socket: NetlinkSocket, groups: UInt) = lock.withLock {
        if (sockets.values.any { it === socket }) socket.updateGroups(groups)
    }

    fun unbind(socket: NetlinkSocket) = lock.withLock {
        sockets.entries.removeAll { it.value === socket }
    }

    fun process(socket: NetlinkSocket, process: Process, bytes: ByteArray): VfsResult<Unit> {
        val requests = NetlinkCodec.decode(bytes)
            ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        val recipientPortId = socket.localAddress().portId
        fun reply(message: ByteArray) {
            socket.enqueue(NetlinkCodec.withPortId(message, recipientPortId), KERNEL_ADDRESS)
        }
        for (request in requests) {
            if (request.flags.toInt() and NLM_F_REQUEST == 0) {
                reply(error(request, VfsError.INVALID_ARGUMENT))
                continue
            }
            when (val result = dispatch(process, request)) {
                is VfsResult.Ok -> {
                    result.value.forEach(::reply)
                    if (request.flags.toInt() and NLM_F_ACK != 0 && result.value.isEmpty()) {
                        reply(acknowledgment(request))
                    }
                }
                is VfsResult.Err -> reply(error(request, result.error))
            }
        }
        return VfsResult.Ok(Unit)
    }

    override fun linkChanged(interface_: NetworkInterface, removed: Boolean) {
        broadcast(
            RTNLGRP_LINK,
            linkMessage(interface_, sequence = 0u, multi = false, removed = removed),
        )
    }

    override fun addressChanged(
        interface_: NetworkInterface,
        address: NetworkInterfaceAddress,
        removed: Boolean,
    ) {
        broadcast(
            RTNLGRP_IPV4_IFADDR,
            addressMessage(interface_, address, 0u, multi = false, removed = removed),
        )
    }

    override fun routeChanged(route: NetworkRoute, removed: Boolean) {
        broadcast(
            RTNLGRP_IPV4_ROUTE,
            routeMessage(route, 0u, multi = false, removed = removed),
        )
    }

    override fun neighborChanged(neighbor: NetworkNeighbor, removed: Boolean) {
        broadcast(
            RTNLGRP_NEIGH,
            neighborMessage(neighbor, 0u, multi = false, removed = removed),
        )
    }

    private fun dispatch(
        process: Process,
        request: NetlinkMessage,
    ): VfsResult<List<ByteArray>> = when (request.type.toInt()) {
        RTM_GETLINK -> getLink(request)
        RTM_NEWLINK -> mutate(process) { setLink(request) }
        RTM_GETADDR -> dump(request) {
            NetworkStack.snapshotInterfaces().flatMap { interface_ ->
                NetworkStack.interfaceAddresses(interface_.index).map {
                    addressMessage(interface_, it, request.sequence, true, false)
                }
            }
        }
        RTM_NEWADDR -> mutate(process) { changeAddress(request, removed = false) }
        RTM_DELADDR -> mutate(process) { changeAddress(request, removed = true) }
        RTM_GETROUTE -> dump(request) {
            NetworkStack.snapshotRoutes().map { routeMessage(it, request.sequence, true, false) }
        }
        RTM_NEWROUTE -> mutate(process) { changeRoute(request, removed = false) }
        RTM_DELROUTE -> mutate(process) { changeRoute(request, removed = true) }
        RTM_GETNEIGH -> dump(request) {
            NetworkStack.snapshotNeighbors().map { neighborMessage(it, request.sequence, true, false) }
        }
        RTM_NEWNEIGH -> mutate(process) { changeNeighbor(request, removed = false) }
        RTM_DELNEIGH -> mutate(process) { changeNeighbor(request, removed = true) }
        else -> VfsResult.Err(VfsError.NOT_SUPPORTED)
    }

    private fun dump(
        request: NetlinkMessage,
        messages: () -> List<ByteArray>,
    ): VfsResult<List<ByteArray>> {
        if (request.flags.toInt() and NLM_F_DUMP != NLM_F_DUMP) {
            return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        }
        return VfsResult.Ok(
            messages() + NetlinkCodec.encode(
                NLMSG_DONE,
                NLM_F_MULTI,
                request.sequence,
                ByteArray(Int.SIZE_BYTES),
            ),
        )
    }

    private inline fun mutate(
        process: Process,
        operation: () -> VfsResult<Unit>,
    ): VfsResult<List<ByteArray>> {
        if (!process.vfsOperationContext.privileged) {
            return VfsResult.Err(VfsError.NOT_PERMITTED)
        }
        return when (val result = operation()) {
            is VfsResult.Ok -> VfsResult.Ok(emptyList())
            is VfsResult.Err -> result
        }
    }

    private data class LinkRequest(
        val index: Int,
        val flags: UInt,
        val change: UInt,
        val attributes: Map<Int, ByteArray>,
    )

    private fun getLink(request: NetlinkMessage): VfsResult<List<ByteArray>> {
        if (request.flags.toInt() and NLM_F_DUMP == NLM_F_DUMP) {
            return dump(request) {
                NetworkStack.snapshotInterfaces().map {
                    linkMessage(it, request.sequence, multi = true, removed = false)
                }
            }
        }
        val linkRequest = when (val result = decodeLinkRequest(request)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        val interface_ = when (val result = resolveInterface(linkRequest)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        return VfsResult.Ok(
            listOf(linkMessage(interface_, request.sequence, multi = false, removed = false)),
        )
    }

    private fun setLink(request: NetlinkMessage): VfsResult<Unit> {
        val linkRequest = when (val result = decodeLinkRequest(request)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        val mtu = linkRequest.attributes[IFLA_MTU]?.let {
            if (it.size < UInt.SIZE_BYTES) return VfsResult.Err(VfsError.INVALID_ARGUMENT)
            LittleEndianBuffer(it).readU32(0).toInt()
        }
        val interface_ = when (val result = resolveInterface(linkRequest)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        val up = if (linkRequest.change and IFF_UP != 0u) linkRequest.flags and IFF_UP != 0u
        else interface_.administrativeUp
        return NetworkStack.setLink(interface_.index, up, mtu)
    }

    private fun decodeLinkRequest(request: NetlinkMessage): VfsResult<LinkRequest> {
        if (request.payload.size < IFINFO_SIZE) return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        val input = LittleEndianBuffer(request.payload)
        val attributes = NetlinkCodec.attributes(request.payload, IFINFO_SIZE)
            ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        return VfsResult.Ok(
            LinkRequest(
                input.readU32(4).toInt(),
                input.readU32(8),
                input.readU32(12),
                attributes,
            ),
        )
    }

    private fun resolveInterface(request: LinkRequest): VfsResult<NetworkInterface> {
        val name = request.attributes[IFLA_IFNAME]?.let { bytes ->
            val terminator = bytes.indexOfFirst { it == 0.toByte() }
            if (terminator <= 0 || terminator != bytes.lastIndex) {
                return VfsResult.Err(VfsError.INVALID_ARGUMENT)
            }
            bytes.copyOfRange(0, terminator).decodeToString()
        }
        val byIndex = request.index.takeIf { it != 0 }?.let(NetworkStack::interfaceByIndex)
        val byName = name?.let(NetworkStack::interfaceByName)
        if (request.index != 0 && byIndex == null || name != null && byName == null) {
            return VfsResult.Err(VfsError.NO_DEVICE)
        }
        if (byIndex != null && byName != null && byIndex.index != byName.index) {
            return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        }
        return VfsResult.Ok(byIndex ?: byName ?: return VfsResult.Err(VfsError.NO_DEVICE))
    }

    private fun changeAddress(request: NetlinkMessage, removed: Boolean): VfsResult<Unit> {
        if (request.payload.size < IFADDR_SIZE) return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        val input = LittleEndianBuffer(request.payload)
        if (input.readU8(0).toInt() != AF_INET) {
            return VfsResult.Err(VfsError.ADDRESS_FAMILY_NOT_SUPPORTED)
        }
        val prefixLength = input.readU8(1).toInt()
        val index = input.readU32(4).toInt()
        val attributes = NetlinkCodec.attributes(request.payload, IFADDR_SIZE)
            ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        val bytes = attributes[IFA_LOCAL] ?: attributes[IFA_ADDRESS]
            ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        val address = Ipv4Address.from(bytes) ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        return if (removed) NetworkStack.removeAddress(index, address, prefixLength)
        else NetworkStack.addAddress(index, NetworkInterfaceAddress(address, prefixLength))
    }

    private fun changeRoute(request: NetlinkMessage, removed: Boolean): VfsResult<Unit> {
        if (request.payload.size < RTMSG_SIZE) return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        val input = LittleEndianBuffer(request.payload)
        if (input.readU8(0).toInt() != AF_INET) {
            return VfsResult.Err(VfsError.ADDRESS_FAMILY_NOT_SUPPORTED)
        }
        val prefixLength = input.readU8(1).toInt()
        val protocol = input.readU8(5)
        val type = input.readU8(7).toInt()
        if (type != RTN_UNICAST) return VfsResult.Err(VfsError.NOT_SUPPORTED)
        val attributes = NetlinkCodec.attributes(request.payload, RTMSG_SIZE)
            ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        val destination = attributes[RTA_DST]?.let { Ipv4Address.from(it) } ?: Ipv4Address.ANY
        val gateway = attributes[RTA_GATEWAY]?.let { Ipv4Address.from(it) }
        val interfaceBytes = attributes[RTA_OIF]
            ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        if (interfaceBytes.size < UInt.SIZE_BYTES) return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        val interfaceIndex = LittleEndianBuffer(interfaceBytes).readU32(0).toInt()
        val preferredSource = attributes[RTA_PREFSRC]?.let { Ipv4Address.from(it) }
        val metric = attributes[RTA_PRIORITY]?.let {
            if (it.size < UInt.SIZE_BYTES) return VfsResult.Err(VfsError.INVALID_ARGUMENT)
            LittleEndianBuffer(it).readU32(0)
        } ?: 0u
        val route = NetworkRoute(
            Ipv4Prefix(destination, prefixLength),
            gateway,
            interfaceIndex,
            preferredSource,
            metric,
            protocol = protocol,
        )
        return when {
            removed -> NetworkStack.removeRoute(route)
            request.flags.toInt() and NLM_F_REPLACE != 0 -> NetworkStack.replaceRoute(route)
            else -> NetworkStack.addRoute(route)
        }
    }

    private fun changeNeighbor(request: NetlinkMessage, removed: Boolean): VfsResult<Unit> {
        if (request.payload.size < NDMSG_SIZE) return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        val input = LittleEndianBuffer(request.payload)
        if (input.readU8(0).toInt() != AF_INET) {
            return VfsResult.Err(VfsError.ADDRESS_FAMILY_NOT_SUPPORTED)
        }
        val interfaceIndex = input.readU32(4).toInt()
        val attributes = NetlinkCodec.attributes(request.payload, NDMSG_SIZE)
            ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        val address = attributes[NDA_DST]?.let { Ipv4Address.from(it) }
            ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        if (removed) return NetworkStack.removeNeighbor(interfaceIndex, address)
        val hardwareAddress = attributes[NDA_LLADDR]?.let { MacAddress.from(it) }
            ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        return NetworkStack.setNeighbor(interfaceIndex, address, hardwareAddress)
    }

    private fun linkMessage(
        interface_: NetworkInterface,
        sequence: UInt,
        multi: Boolean,
        removed: Boolean,
    ): ByteArray {
        val fixed = ByteArray(IFINFO_SIZE)
        val flags = (if (interface_.administrativeUp) IFF_UP else 0u) or
            (if (interface_.kind == NetworkInterfaceKind.LOOPBACK) IFF_LOOPBACK else IFF_BROADCAST) or
            IFF_MULTICAST or if (interface_.running) IFF_RUNNING or IFF_LOWER_UP else 0u
        LittleEndianBuffer(fixed).apply {
            writeU8(0, AF_UNSPEC.toUByte())
            writeU16(2, interface_.kind.hardwareType)
            writeU32(4, interface_.index.toUInt())
            writeU32(8, flags)
            writeU32(12, UInt.MAX_VALUE)
        }
        val address = ByteArray(MacAddress.SIZE_BYTES).also {
            interface_.hardwareAddress.copyTo(it)
        }
        val attributes = listOf(
            NetlinkCodec.stringAttribute(IFLA_IFNAME, interface_.name),
            NetlinkCodec.attribute(IFLA_ADDRESS, address),
            NetlinkCodec.intAttribute(IFLA_MTU, interface_.mtu.toUInt()),
            NetlinkCodec.attribute(
                IFLA_OPERSTATE,
                byteArrayOf((if (interface_.running) IF_OPER_UP else IF_OPER_DOWN).toByte()),
            ),
        )
        return NetlinkCodec.encode(
            if (removed) RTM_DELLINK else RTM_NEWLINK,
            if (multi) NLM_F_MULTI else 0,
            sequence,
            NetlinkCodec.payload(fixed, attributes),
        )
    }

    private fun addressMessage(
        interface_: NetworkInterface,
        address: NetworkInterfaceAddress,
        sequence: UInt,
        multi: Boolean,
        removed: Boolean,
    ): ByteArray {
        val fixed = ByteArray(IFADDR_SIZE)
        LittleEndianBuffer(fixed).apply {
            writeU8(0, AF_INET.toUByte())
            writeU8(1, address.prefixLength.toUByte())
            writeU8(2, 0u)
            writeU8(
                3,
                (if (interface_.kind == NetworkInterfaceKind.LOOPBACK) RT_SCOPE_HOST
                else RT_SCOPE_UNIVERSE).toUByte(),
            )
            writeU32(4, interface_.index.toUInt())
        }
        val nativeAddress = ByteArray(Ipv4Address.SIZE_BYTES).also { address.address.writeTo(it) }
        val attributes = mutableListOf(
            NetlinkCodec.attribute(IFA_ADDRESS, nativeAddress),
            NetlinkCodec.attribute(IFA_LOCAL, nativeAddress),
            NetlinkCodec.stringAttribute(IFA_LABEL, interface_.name),
        )
        if (address.prefixLength < 31) {
            attributes += NetlinkCodec.attribute(
                IFA_BROADCAST,
                ByteArray(Ipv4Address.SIZE_BYTES).also { address.prefix.broadcast.writeTo(it) },
            )
        }
        return NetlinkCodec.encode(
            if (removed) RTM_DELADDR else RTM_NEWADDR,
            if (multi) NLM_F_MULTI else 0,
            sequence,
            NetlinkCodec.payload(fixed, attributes),
        )
    }

    private fun routeMessage(
        route: NetworkRoute,
        sequence: UInt,
        multi: Boolean,
        removed: Boolean,
    ): ByteArray {
        val fixed = ByteArray(RTMSG_SIZE)
        val scope = when (route.kind) {
            NetworkRouteKind.LOCAL -> RT_SCOPE_HOST
            NetworkRouteKind.BROADCAST -> RT_SCOPE_LINK
            NetworkRouteKind.UNICAST -> if (route.gateway == null) RT_SCOPE_LINK else RT_SCOPE_UNIVERSE
        }
        val type = when (route.kind) {
            NetworkRouteKind.UNICAST -> RTN_UNICAST
            NetworkRouteKind.LOCAL -> RTN_LOCAL
            NetworkRouteKind.BROADCAST -> RTN_BROADCAST
        }
        LittleEndianBuffer(fixed).apply {
            writeU8(0, AF_INET.toUByte())
            writeU8(1, route.destination.length.toUByte())
            writeU8(2, 0u)
            writeU8(3, 0u)
            writeU8(4, RT_TABLE_MAIN.toUByte())
            writeU8(5, route.protocol)
            writeU8(6, scope.toUByte())
            writeU8(7, type.toUByte())
            writeU32(8, 0u)
        }
        val attributes = mutableListOf<ByteArray>()
        if (route.destination.length != 0) {
            attributes += NetlinkCodec.attribute(
                RTA_DST,
                ByteArray(Ipv4Address.SIZE_BYTES).also { route.destination.network.writeTo(it) },
            )
        }
        route.gateway?.let {
            attributes += NetlinkCodec.attribute(
                RTA_GATEWAY,
                ByteArray(Ipv4Address.SIZE_BYTES).also { bytes -> it.writeTo(bytes) },
            )
        }
        attributes += NetlinkCodec.intAttribute(RTA_OIF, route.interfaceIndex.toUInt())
        route.preferredSource?.let {
            attributes += NetlinkCodec.attribute(
                RTA_PREFSRC,
                ByteArray(Ipv4Address.SIZE_BYTES).also { bytes -> it.writeTo(bytes) },
            )
        }
        if (route.metric != 0u) {
            attributes += NetlinkCodec.intAttribute(RTA_PRIORITY, route.metric)
        }
        return NetlinkCodec.encode(
            if (removed) RTM_DELROUTE else RTM_NEWROUTE,
            if (multi) NLM_F_MULTI else 0,
            sequence,
            NetlinkCodec.payload(fixed, attributes),
        )
    }

    private fun neighborMessage(
        neighbor: NetworkNeighbor,
        sequence: UInt,
        multi: Boolean,
        removed: Boolean,
    ): ByteArray {
        val interface_ = NetworkStack.interfaceByIndex(neighbor.interfaceIndex)
            ?: return ByteArray(0)
        val fixed = ByteArray(NDMSG_SIZE)
        LittleEndianBuffer(fixed).apply {
            writeU8(0, AF_INET.toUByte())
            writeU32(4, neighbor.interfaceIndex.toUInt())
            writeU16(8, (if (neighbor.reachable) NUD_REACHABLE else NUD_FAILED).toUShort())
            writeU8(10, 0u)
            writeU8(11, RTN_UNICAST.toUByte())
        }
        val attributes = listOf(
            NetlinkCodec.attribute(
                NDA_DST,
                ByteArray(Ipv4Address.SIZE_BYTES).also { neighbor.address.writeTo(it) },
            ),
            NetlinkCodec.attribute(
                NDA_LLADDR,
                ByteArray(MacAddress.SIZE_BYTES).also { neighbor.hardwareAddress.copyTo(it) },
            ),
        )
        return NetlinkCodec.encode(
            if (removed) RTM_DELNEIGH else RTM_NEWNEIGH,
            if (multi) NLM_F_MULTI else 0,
            sequence,
            NetlinkCodec.payload(fixed, attributes),
        )
    }

    private fun acknowledgment(request: NetlinkMessage): ByteArray =
        NetlinkCodec.encode(NLMSG_ERROR, 0, request.sequence, errorPayload(request, 0))

    private fun error(request: NetlinkMessage, error: VfsError): ByteArray =
        NetlinkCodec.encode(
            NLMSG_ERROR,
            0,
            request.sequence,
            errorPayload(request, -error.errno),
        )

    private fun errorPayload(request: NetlinkMessage, error: Int): ByteArray =
        ByteArray(Int.SIZE_BYTES + NetlinkCodec.HEADER_SIZE).also { bytes ->
            LittleEndianBuffer(bytes).apply {
                writeU32(0, error.toUInt())
                writeU32(4, (NetlinkCodec.HEADER_SIZE + request.payload.size).toUInt())
                writeU16(8, request.type)
                writeU16(10, request.flags)
                writeU32(12, request.sequence)
                writeU32(16, request.portId)
            }
        }

    private fun broadcast(group: Int, message: ByteArray) {
        if (message.isEmpty()) return
        val mask = 1u shl (group - 1)
        val recipients = lock.withLock { sockets.values.filter { it.groups() and mask != 0u } }
        recipients.forEach { it.enqueue(message, KERNEL_ADDRESS) }
    }

    private fun allocatePortLocked(): UInt {
        while (true) {
            val candidate = nextPortId.fetchAndAdd(1).toUInt()
            if (candidate != 0u && sockets[candidate] == null) return candidate
        }
    }

    private val KERNEL_ADDRESS = NetlinkSocketAddress(0u, 0u)
    private const val AF_UNSPEC = 0
    private const val AF_INET = 2
    private const val NLMSG_ERROR = 2
    private const val NLMSG_DONE = 3
    private const val NLM_F_REQUEST = 0x0001
    private const val NLM_F_MULTI = 0x0002
    private const val NLM_F_ACK = 0x0004
    private const val NLM_F_DUMP = 0x0300
    private const val NLM_F_REPLACE = 0x0100
    private const val RTM_NEWLINK = 16
    private const val RTM_DELLINK = 17
    private const val RTM_GETLINK = 18
    private const val RTM_NEWADDR = 20
    private const val RTM_DELADDR = 21
    private const val RTM_GETADDR = 22
    private const val RTM_NEWROUTE = 24
    private const val RTM_DELROUTE = 25
    private const val RTM_GETROUTE = 26
    private const val RTM_NEWNEIGH = 28
    private const val RTM_DELNEIGH = 29
    private const val RTM_GETNEIGH = 30
    private const val RTNLGRP_LINK = 1
    private const val RTNLGRP_NEIGH = 3
    private const val RTNLGRP_IPV4_IFADDR = 5
    private const val RTNLGRP_IPV4_ROUTE = 7
    private const val IFINFO_SIZE = 16
    private const val IFADDR_SIZE = 8
    private const val RTMSG_SIZE = 12
    private const val NDMSG_SIZE = 12
    private const val IFF_UP = 0x0001u
    private const val IFF_BROADCAST = 0x0002u
    private const val IFF_LOOPBACK = 0x0008u
    private const val IFF_RUNNING = 0x0040u
    private const val IFF_MULTICAST = 0x1000u
    private const val IFF_LOWER_UP = 0x1_0000u
    private const val IFLA_ADDRESS = 1
    private const val IFLA_IFNAME = 3
    private const val IFLA_MTU = 4
    private const val IFLA_OPERSTATE = 16
    private const val IF_OPER_DOWN = 2
    private const val IF_OPER_UP = 6
    private const val IFA_ADDRESS = 1
    private const val IFA_LOCAL = 2
    private const val IFA_LABEL = 3
    private const val IFA_BROADCAST = 4
    private const val RTA_DST = 1
    private const val RTA_OIF = 4
    private const val RTA_GATEWAY = 5
    private const val RTA_PRIORITY = 6
    private const val RTA_PREFSRC = 7
    private const val RT_TABLE_MAIN = 254
    private const val RT_SCOPE_UNIVERSE = 0
    private const val RT_SCOPE_LINK = 253
    private const val RT_SCOPE_HOST = 254
    private const val RTN_UNICAST = 1
    private const val RTN_LOCAL = 2
    private const val RTN_BROADCAST = 3
    private const val NDA_DST = 1
    private const val NDA_LLADDR = 2
    private const val NUD_REACHABLE = 0x02
    private const val NUD_FAILED = 0x20
}

internal class NetlinkSocket internal constructor(
    private val subsystem: RouteNetlink,
    type: SocketType,
) : AbstractSocket(SocketDomain.NETLINK, type, NETLINK_ROUTE) {
    private data class Datagram(val bytes: ByteArray, val source: NetlinkSocketAddress)

    private var local: NetlinkSocketAddress? = null
    private var peer = NetlinkSocketAddress(0u, 0u)
    private var messages = ArrayDeque<Datagram>()
    private var queuedBytes = 0
    private val readWaiters = IoWaitQueue()

    override fun bindSocket(process: Process, address: SocketAddress): VfsResult<Unit> {
        val requested = address as? NetlinkSocketAddress
            ?: return VfsResult.Err(VfsError.ADDRESS_FAMILY_NOT_SUPPORTED)
        val result = subsystem.bind(this, requested, process)
        if (result is VfsResult.Ok) lock.withLock { local = result.value }
        return when (result) {
            is VfsResult.Ok -> VfsResult.Ok(Unit)
            is VfsResult.Err -> result
        }
    }

    override fun connectSocket(
        process: Process,
        address: SocketAddress?,
        nonBlocking: Boolean,
    ): VfsResult<Unit> {
        val destination = address as? NetlinkSocketAddress
            ?: return VfsResult.Err(VfsError.ADDRESS_FAMILY_NOT_SUPPORTED)
        if (destination.portId != 0u || destination.groups != 0u) {
            return VfsResult.Err(VfsError.PERMISSION_DENIED)
        }
        lock.withLock { peer = destination }
        return VfsResult.Ok(Unit)
    }

    override fun localAddress(): NetlinkSocketAddress = lock.withLock {
        local ?: NetlinkSocketAddress(0u, 0u)
    }

    override fun peerAddress(): VfsResult<NetlinkSocketAddress> = VfsResult.Ok(peer)

    override fun sendSocket(request: SocketSendRequest): IoResult {
        request.ancillary.release()
        val destination = request.destination?.let {
            it as? NetlinkSocketAddress
                ?: return IoResult.failure(VfsError.ADDRESS_FAMILY_NOT_SUPPORTED)
        } ?: peer
        if (destination.portId != 0u || destination.groups != 0u) {
            return IoResult.failure(VfsError.PERMISSION_DENIED)
        }
        if (lock.withLock { local == null }) {
            when (val result = subsystem.bind(this, NetlinkSocketAddress(0u, 0u), request.process)) {
                is VfsResult.Ok -> lock.withLock { local = result.value }
                is VfsResult.Err -> return IoResult.failure(result.error)
            }
        }
        val bytes = ByteArray(request.count)
        if (request.source.copyTo(request.offset, bytes, 0, request.count) != request.count) {
            return IoResult.failure(VfsError.FAULT)
        }
        return when (val result = subsystem.process(this, request.process, bytes)) {
            is VfsResult.Ok -> IoResult.success(request.count)
            is VfsResult.Err -> IoResult.failure(result.error)
        }
    }

    override fun receiveSocket(request: SocketReceiveRequest): VfsResult<SocketReceiveResult> {
        while (true) {
            var waiter: IoWaitQueue.Waiter? = null
            val result = lock.withLock {
                val datagram = messages.firstOrNull()
                if (datagram != null) {
                    val copied = minOf(request.count, datagram.bytes.size)
                    if (request.destination.copyFrom(
                            request.offset,
                            datagram.bytes,
                            0,
                            copied,
                        ) != copied
                    ) return@withLock VfsResult.Err(VfsError.FAULT)
                    if (!request.peek) {
                        messages.removeFirst()
                        queuedBytes -= datagram.bytes.size
                    }
                    return@withLock VfsResult.Ok(
                        SocketReceiveResult(
                            if (request.returnFullLength) datagram.bytes.size else copied,
                            copied,
                            datagram.source,
                            truncated = copied < datagram.bytes.size,
                            endOfRecord = true,
                        ),
                    )
                }
                if (closed) return@withLock VfsResult.Err(VfsError.NOT_CONNECTED)
                if (request.nonBlocking) return@withLock VfsResult.Err(VfsError.WOULD_BLOCK)
                val thread = ProcessManager.currentThread()
                    ?: return@withLock VfsResult.Err(VfsError.NOT_FOUND)
                waiter = readWaiters.add(thread)
                null
            }
            if (result != null) return result
            if (!readWaiters.await(lock, checkNotNull(waiter))) {
                return VfsResult.Err(VfsError.INTERRUPTED)
            }
        }
    }

    override fun setProtocolOption(level: Int, name: Int, value: ByteArray): VfsResult<Unit> {
        if (level != SOL_NETLINK || value.size < UInt.SIZE_BYTES) {
            return VfsResult.Err(VfsError.PROTOCOL_OPTION_NOT_AVAILABLE)
        }
        val group = LittleEndianBuffer(value).readU32(0).toInt()
        if (group !in 1..UInt.SIZE_BITS) return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        val mask = 1u shl (group - 1)
        val updated = lock.withLock {
            val current = local ?: return@withLock null
            val groups = when (name) {
                NETLINK_ADD_MEMBERSHIP -> current.groups or mask
                NETLINK_DROP_MEMBERSHIP -> current.groups and mask.inv()
                else -> return VfsResult.Err(VfsError.PROTOCOL_OPTION_NOT_AVAILABLE)
            }
            NetlinkSocketAddress(current.portId, groups).also { local = it }
        } ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        subsystem.updateGroups(this, updated.groups)
        return VfsResult.Ok(Unit)
    }

    internal fun updateGroups(groups: UInt) = lock.withLock {
        local = local?.copy(groups = groups)
    }

    internal fun groups(): UInt = lock.withLock { local?.groups ?: 0u }

    internal fun enqueue(bytes: ByteArray, source: NetlinkSocketAddress) = lock.withLock {
        if (closed || queuedBytes > optionsLocked().receiveBufferSize - bytes.size) return@withLock
        messages += Datagram(bytes, source)
        queuedBytes += bytes.size
        readWaiters.wakeOne()
    }

    override fun readableBytes(): Int = lock.withLock { messages.firstOrNull()?.bytes?.size ?: 0 }

    override fun pollSocket(events: Int): Int = lock.withLock {
        var available = PollEvents.NORMAL_OUTPUT
        if (messages.isNotEmpty()) available = available or PollEvents.NORMAL_INPUT
        if (closed) available = available or PollEvents.POLLHUP
        available and (events or PollEvents.UNCONDITIONALLY_REPORTED)
    }

    override fun closeSocketLocked(): (() -> Unit)? {
        messages.clear()
        queuedBytes = 0
        readWaiters.wakeAll()
        return if (local == null) null else ({ subsystem.unbind(this) })
    }

    companion object {
        private const val NETLINK_ROUTE = 0
        private const val SOL_NETLINK = 270
        private const val NETLINK_ADD_MEMBERSHIP = 1
        private const val NETLINK_DROP_MEMBERSHIP = 2
    }
}
