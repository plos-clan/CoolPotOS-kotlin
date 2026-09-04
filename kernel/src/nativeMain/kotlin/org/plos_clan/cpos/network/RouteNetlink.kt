package org.plos_clan.cpos.network

import org.plos_clan.cpos.drivers.net.MacAddress
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.utils.LittleEndianBuffer

internal object RouteNetlinkProtocol :
    NetlinkKernelProtocol(NetlinkProtocolKind.ROUTE),
    NetworkConfigurationListener {
    override val multicastGroupCount = RTNLGRP_MAX

    init {
        NetworkStack.addListener(this)
    }

    override fun handle(request: NetlinkRequest): NetlinkResult = when (request.message.type.toInt()) {
        RTM_GETLINK -> getLink(request)
        RTM_NEWLINK, RTM_SETLINK -> mutate(request) { echo -> setLink(request.message, echo) }
        RTM_GETADDR -> dump(request, IFADDR_SIZE, AF_INET) {
            NetworkStack.snapshotInterfaces().flatMap { interface_ ->
                NetworkStack.interfaceAddresses(interface_.index).map {
                    addressReply(interface_, it, removed = false)
                }
            }
        }
        RTM_NEWADDR -> mutate(request) { echo ->
            changeAddress(request.message, removed = false, echo)
        }
        RTM_DELADDR -> mutate(request) { echo ->
            changeAddress(request.message, removed = true, echo)
        }
        RTM_GETROUTE -> dump(request, RTMSG_SIZE, AF_INET) {
            NetworkStack.snapshotRoutes().map { routeReply(it, removed = false) }
        }
        RTM_NEWROUTE -> mutate(request) { echo ->
            changeRoute(request.message, removed = false, echo)
        }
        RTM_DELROUTE -> mutate(request) { echo ->
            changeRoute(request.message, removed = true, echo)
        }
        RTM_GETNEIGH -> dump(request, NDMSG_SIZE, AF_INET) {
            NetworkStack.snapshotNeighbors().mapNotNull { neighborReply(it, removed = false) }
        }
        RTM_NEWNEIGH -> mutate(request) { echo ->
            changeNeighbor(request.message, removed = false, echo)
        }
        RTM_DELNEIGH -> mutate(request) { echo ->
            changeNeighbor(request.message, removed = true, echo)
        }
        else -> NetlinkResult.Failure(VfsError.NOT_SUPPORTED)
    }

    override fun linkChanged(interface_: NetworkInterface, removed: Boolean) {
        notify(RTNLGRP_LINK) { linkReply(interface_, removed) }
    }

    override fun addressChanged(
        interface_: NetworkInterface,
        address: NetworkInterfaceAddress,
        removed: Boolean,
    ) {
        notify(RTNLGRP_IPV4_IFADDR) { addressReply(interface_, address, removed) }
    }

    override fun routeChanged(route: NetworkRoute, removed: Boolean) {
        notify(RTNLGRP_IPV4_ROUTE) { routeReply(route, removed) }
    }

    override fun neighborChanged(neighbor: NetworkNeighbor, removed: Boolean) {
        notify(RTNLGRP_NEIGH) { neighborReply(neighbor, removed) }
    }

    private fun getLink(request: NetlinkRequest): NetlinkResult {
        if (request.message.flags.toInt() and NetlinkAbi.NLM_F_DUMP == NetlinkAbi.NLM_F_DUMP) {
            return dump(request, IFINFO_SIZE, AF_PACKET) {
                NetworkStack.snapshotInterfaces().map { linkReply(it, removed = false) }
            }
        }
        val decoded = when (val result = decodeLinkRequest(request.message)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return NetlinkResult.Failure(result.error)
        }
        val interface_ = when (val result = resolveInterface(decoded)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return NetlinkResult.Failure(result.error)
        }
        return NetlinkResult.Success(listOf(linkReply(interface_, removed = false)))
    }

    private fun dump(
        request: NetlinkRequest,
        fixedSize: Int,
        family: Int,
        replies: () -> List<NetlinkReply>,
    ): NetlinkResult {
        if (request.message.flags.toInt() and NetlinkAbi.NLM_F_DUMP != NetlinkAbi.NLM_F_DUMP ||
            request.message.payload.size < RTGENMSG_SIZE
        ) {
            return NetlinkResult.Failure(VfsError.INVALID_ARGUMENT)
        }
        if (request.strict && request.message.payload.size != RTGENMSG_SIZE &&
            (request.message.payload.size < fixedSize || request.message.attributes(fixedSize) == null)
        ) {
            return NetlinkResult.Failure(VfsError.INVALID_ARGUMENT)
        }
        val requestedFamily = request.message.payload.readU8(0).toInt()
        if (requestedFamily != AF_UNSPEC && requestedFamily != family) {
            return NetlinkResult.Failure(VfsError.ADDRESS_FAMILY_NOT_SUPPORTED)
        }
        return NetlinkResult.Success(replies(), multipart = true)
    }

    private inline fun mutate(
        request: NetlinkRequest,
        operation: (echo: Boolean) -> VfsResult<NetlinkReply?>,
    ): NetlinkResult {
        if (!hasNetworkAdmin(request.process)) {
            return NetlinkResult.Failure(VfsError.NOT_PERMITTED)
        }
        val echo = request.message.flags.toInt() and NetlinkAbi.NLM_F_ECHO != 0
        return when (val result = operation(echo)) {
            is VfsResult.Ok -> NetlinkResult.Success(
                if (echo) listOfNotNull(result.value) else emptyList(),
            )
            is VfsResult.Err -> NetlinkResult.Failure(result.error)
        }
    }

    private data class LinkRequest(
        val index: Int,
        val flags: UInt,
        val change: UInt,
        val attributes: NetlinkAttributes,
    )

    private fun setLink(message: NetlinkMessage, echo: Boolean): VfsResult<NetlinkReply?> {
        val request = when (val result = decodeLinkRequest(message)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        val mtu = request.attributes[IFLA_MTU]?.let {
            val value = it.u32() ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
            if (value > Int.MAX_VALUE.toUInt()) return VfsResult.Err(VfsError.INVALID_ARGUMENT)
            value.toInt()
        }
        val interface_ = when (val result = resolveInterface(request)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        val up = if (request.change and NetworkInterface.UP_FLAG != 0u) {
            request.flags and NetworkInterface.UP_FLAG != 0u
        } else {
            interface_.administrativeUp
        }
        return when (val result = NetworkStack.setLink(interface_.index, up, mtu)) {
            is VfsResult.Ok -> VfsResult.Ok(
                if (echo) NetworkStack.interfaceByIndex(interface_.index)?.let {
                    linkReply(it, removed = false)
                } else {
                    null
                },
            )
            is VfsResult.Err -> result
        }
    }

    private fun decodeLinkRequest(message: NetlinkMessage): VfsResult<LinkRequest> {
        if (message.payload.size < IFINFO_SIZE) return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        val attributes = message.attributes(IFINFO_SIZE)
            ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        return VfsResult.Ok(
            LinkRequest(
                message.payload.readU32(4).toInt(),
                message.payload.readU32(8),
                message.payload.readU32(12),
                attributes,
            ),
        )
    }

    private fun resolveInterface(request: LinkRequest): VfsResult<NetworkInterface> {
        val name = request.attributes[IFLA_IFNAME]?.string(IF_NAMESIZE)
            ?: if (request.attributes[IFLA_IFNAME] != null) {
                return VfsResult.Err(VfsError.INVALID_ARGUMENT)
            } else {
                null
            }
        val byIndex = request.index.takeIf { it > 0 }?.let(NetworkStack::interfaceByIndex)
        val byName = name?.let(NetworkStack::interfaceByName)
        if (request.index != 0 && byIndex == null || name != null && byName == null) {
            return VfsResult.Err(VfsError.NO_DEVICE)
        }
        if (byIndex != null && byName != null && byIndex.index != byName.index) {
            return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        }
        return VfsResult.Ok(byIndex ?: byName ?: return VfsResult.Err(VfsError.NO_DEVICE))
    }

    private fun changeAddress(
        message: NetlinkMessage,
        removed: Boolean,
        echo: Boolean,
    ): VfsResult<NetlinkReply?> {
        if (message.payload.size < IFADDR_SIZE) return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        if (message.payload.readU8(0).toInt() != AF_INET) {
            return VfsResult.Err(VfsError.ADDRESS_FAMILY_NOT_SUPPORTED)
        }
        val prefixLength = message.payload.readU8(1).toInt()
        if (prefixLength !in 0..Ipv4Address.SIZE_BYTES * Byte.SIZE_BITS) {
            return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        }
        val index = message.payload.readU32(4).toInt()
        if (index <= 0) return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        val attributes = message.attributes(IFADDR_SIZE)
            ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        val addressAttribute = attributes[IFA_LOCAL] ?: attributes[IFA_ADDRESS]
            ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        if (addressAttribute.payload.size != Ipv4Address.SIZE_BYTES) {
            return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        }
        val address = Ipv4Address.from(addressAttribute.payload.copy())
            ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        val interface_ = NetworkStack.interfaceByIndex(index)
            ?: return VfsResult.Err(VfsError.NO_DEVICE)
        val flagsAttribute = attributes[IFA_FLAGS]
        val flags = flagsAttribute?.u32()
            ?: if (flagsAttribute == null) message.payload.readU8(2).toUInt()
            else return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        val configured = NetworkInterfaceAddress(
            address,
            prefixLength,
            automaticPrefixRoute = flags and IFA_F_NOPREFIXROUTE == 0u,
        )
        val result = if (removed) NetworkStack.removeAddress(index, address, prefixLength)
        else NetworkStack.addAddress(index, configured)
        return when (result) {
            is VfsResult.Ok -> VfsResult.Ok(
                if (echo) addressReply(interface_, configured, removed) else null,
            )
            is VfsResult.Err -> result
        }
    }

    private fun changeRoute(
        message: NetlinkMessage,
        removed: Boolean,
        echo: Boolean,
    ): VfsResult<NetlinkReply?> {
        if (message.payload.size < RTMSG_SIZE) return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        if (message.payload.readU8(0).toInt() != AF_INET) {
            return VfsResult.Err(VfsError.ADDRESS_FAMILY_NOT_SUPPORTED)
        }
        val prefixLength = message.payload.readU8(1).toInt()
        if (prefixLength !in 0..Ipv4Address.SIZE_BYTES * Byte.SIZE_BITS) {
            return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        }
        val table = message.payload.readU8(4).toInt()
        if (table != RT_TABLE_UNSPEC && table != RT_TABLE_MAIN) {
            return VfsResult.Err(VfsError.NOT_SUPPORTED)
        }
        val type = message.payload.readU8(7).toInt()
        if (type != RTN_UNICAST) return VfsResult.Err(VfsError.NOT_SUPPORTED)
        val attributes = message.attributes(RTMSG_SIZE)
            ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        val destination = attributes[RTA_DST]?.let {
            if (it.payload.size != Ipv4Address.SIZE_BYTES) {
                return VfsResult.Err(VfsError.INVALID_ARGUMENT)
            }
            Ipv4Address.from(it.payload.copy())
        } ?: if (prefixLength == 0) {
            Ipv4Address.ANY
        } else {
            return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        }
        val gateway = attributes[RTA_GATEWAY]?.let {
            if (it.payload.size != Ipv4Address.SIZE_BYTES) {
                return VfsResult.Err(VfsError.INVALID_ARGUMENT)
            }
            Ipv4Address.from(it.payload.copy())
        }?.takeUnless(Ipv4Address::isAny)
        val interfaceIndex = attributes[RTA_OIF]?.u32()?.toInt()
            ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        if (interfaceIndex <= 0) return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        val preferredSource = attributes[RTA_PREFSRC]?.let {
            if (it.payload.size != Ipv4Address.SIZE_BYTES) {
                return VfsResult.Err(VfsError.INVALID_ARGUMENT)
            }
            Ipv4Address.from(it.payload.copy())
        }
        val metric = attributes[RTA_PRIORITY]?.u32()
            ?: if (attributes[RTA_PRIORITY] != null) {
                return VfsResult.Err(VfsError.INVALID_ARGUMENT)
            } else {
                0u
            }
        val route = NetworkRoute(
            Ipv4Prefix(checkNotNull(destination), prefixLength),
            gateway,
            interfaceIndex,
            preferredSource,
            metric,
            protocol = message.payload.readU8(5),
        )
        val result = when {
            removed -> NetworkStack.removeRoute(route)
            message.flags.toInt() and NetlinkAbi.NLM_F_REPLACE != 0 ->
                NetworkStack.replaceRoute(route)
            else -> NetworkStack.addRoute(route)
        }
        return when (result) {
            is VfsResult.Ok -> VfsResult.Ok(if (echo) routeReply(route, removed) else null)
            is VfsResult.Err -> result
        }
    }

    private fun changeNeighbor(
        message: NetlinkMessage,
        removed: Boolean,
        echo: Boolean,
    ): VfsResult<NetlinkReply?> {
        if (message.payload.size < NDMSG_SIZE) return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        if (message.payload.readU8(0).toInt() != AF_INET) {
            return VfsResult.Err(VfsError.ADDRESS_FAMILY_NOT_SUPPORTED)
        }
        val interfaceIndex = message.payload.readU32(4).toInt()
        if (interfaceIndex <= 0) return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        val attributes = message.attributes(NDMSG_SIZE)
            ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        val addressAttribute = attributes[NDA_DST]
            ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        if (addressAttribute.payload.size != Ipv4Address.SIZE_BYTES) {
            return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        }
        val address = Ipv4Address.from(addressAttribute.payload.copy())
            ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        val previous = if (removed && echo) NetworkStack.snapshotNeighbors().firstOrNull {
            it.interfaceIndex == interfaceIndex && it.address == address
        } else {
            null
        }
        val result = if (removed) {
            NetworkStack.removeNeighbor(interfaceIndex, address)
        } else {
            val hardwareAttribute = attributes[NDA_LLADDR]
                ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
            if (hardwareAttribute.payload.size != MacAddress.SIZE_BYTES) {
                return VfsResult.Err(VfsError.INVALID_ARGUMENT)
            }
            val hardwareAddress = MacAddress.from(hardwareAttribute.payload.copy())
                ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
            NetworkStack.setNeighbor(interfaceIndex, address, hardwareAddress)
        }
        return when (result) {
            is VfsResult.Ok -> {
                val neighbor = previous ?: if (echo) {
                    NetworkStack.snapshotNeighbors().firstOrNull {
                        it.interfaceIndex == interfaceIndex && it.address == address
                    }
                } else {
                    null
                }
                VfsResult.Ok(neighbor?.let { neighborReply(it, removed) })
            }
            is VfsResult.Err -> result
        }
    }

    private fun linkReply(interface_: NetworkInterface, removed: Boolean): NetlinkReply {
        val fixed = ByteArray(IFINFO_SIZE)
        LittleEndianBuffer(fixed).apply {
            writeU8(0, AF_UNSPEC.toUByte())
            writeU16(2, interface_.kind.hardwareType)
            writeU32(4, interface_.index.toUInt())
            writeU32(8, interface_.flags)
            writeU32(12, 0u)
        }
        val address = ByteArray(MacAddress.SIZE_BYTES).also(interface_.hardwareAddress::copyTo)
        return NetlinkReply(
            if (removed) RTM_DELLINK else RTM_NEWLINK,
            NetlinkCodec.payload(
                fixed,
                listOf(
                    NetlinkAttribute.string(IFLA_IFNAME, interface_.name),
                    NetlinkAttribute.binary(IFLA_ADDRESS, address),
                    NetlinkAttribute.binary(
                        IFLA_BROADCAST,
                        ByteArray(MacAddress.SIZE_BYTES).also(
                            interface_.kind.broadcastAddress::copyTo,
                        ),
                    ),
                    NetlinkAttribute.u32(IFLA_MTU, interface_.mtu.toUInt()),
                    NetlinkAttribute.u8(
                        IFLA_OPERSTATE,
                        interface_.operationalState.abiValue,
                    ),
                ),
            ),
        )
    }

    private fun addressReply(
        interface_: NetworkInterface,
        address: NetworkInterfaceAddress,
        removed: Boolean,
    ): NetlinkReply {
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
        val nativeAddress = ByteArray(Ipv4Address.SIZE_BYTES).also(address.address::writeTo)
        val attributes = mutableListOf(
            NetlinkAttribute.binary(IFA_ADDRESS, nativeAddress),
            NetlinkAttribute.binary(IFA_LOCAL, nativeAddress),
            NetlinkAttribute.string(IFA_LABEL, interface_.name),
        )
        if (!address.automaticPrefixRoute) {
            attributes += NetlinkAttribute.u32(IFA_FLAGS, IFA_F_NOPREFIXROUTE)
        }
        if (address.prefixLength < 31) {
            attributes += NetlinkAttribute.binary(
                IFA_BROADCAST,
                ByteArray(Ipv4Address.SIZE_BYTES).also(address.prefix.broadcast::writeTo),
            )
        }
        return NetlinkReply(
            if (removed) RTM_DELADDR else RTM_NEWADDR,
            NetlinkCodec.payload(fixed, attributes),
        )
    }

    private fun routeReply(route: NetworkRoute, removed: Boolean): NetlinkReply {
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
        val table = if (route.kind == NetworkRouteKind.UNICAST) RT_TABLE_MAIN else RT_TABLE_LOCAL
        LittleEndianBuffer(fixed).apply {
            writeU8(0, AF_INET.toUByte())
            writeU8(1, route.destination.length.toUByte())
            writeU8(2, 0u)
            writeU8(3, 0u)
            writeU8(4, table.toUByte())
            writeU8(5, route.protocol)
            writeU8(6, scope.toUByte())
            writeU8(7, type.toUByte())
            writeU32(8, 0u)
        }
        val attributes = mutableListOf<NetlinkAttribute>()
        if (route.destination.length != 0) {
            attributes += NetlinkAttribute.binary(
                RTA_DST,
                ByteArray(Ipv4Address.SIZE_BYTES).also(route.destination.network::writeTo),
            )
        }
        route.gateway?.let {
            attributes += NetlinkAttribute.binary(
                RTA_GATEWAY,
                ByteArray(Ipv4Address.SIZE_BYTES).also(it::writeTo),
            )
        }
        attributes += NetlinkAttribute.u32(RTA_OIF, route.interfaceIndex.toUInt())
        route.preferredSource?.let {
            attributes += NetlinkAttribute.binary(
                RTA_PREFSRC,
                ByteArray(Ipv4Address.SIZE_BYTES).also(it::writeTo),
            )
        }
        if (route.metric != 0u) {
            attributes += NetlinkAttribute.u32(RTA_PRIORITY, route.metric)
        }
        return NetlinkReply(
            if (removed) RTM_DELROUTE else RTM_NEWROUTE,
            NetlinkCodec.payload(fixed, attributes),
        )
    }

    private fun neighborReply(neighbor: NetworkNeighbor, removed: Boolean): NetlinkReply? {
        if (NetworkStack.interfaceByIndex(neighbor.interfaceIndex) == null) return null
        val fixed = ByteArray(NDMSG_SIZE)
        LittleEndianBuffer(fixed).apply {
            writeU8(0, AF_INET.toUByte())
            writeU32(4, neighbor.interfaceIndex.toUInt())
            writeU16(8, (if (neighbor.reachable) NUD_REACHABLE else NUD_FAILED).toUShort())
            writeU8(10, 0u)
            writeU8(11, RTN_UNICAST.toUByte())
        }
        return NetlinkReply(
            if (removed) RTM_DELNEIGH else RTM_NEWNEIGH,
            NetlinkCodec.payload(
                fixed,
                listOf(
                    NetlinkAttribute.binary(
                        NDA_DST,
                        ByteArray(Ipv4Address.SIZE_BYTES).also(neighbor.address::writeTo),
                    ),
                    NetlinkAttribute.binary(
                        NDA_LLADDR,
                        ByteArray(MacAddress.SIZE_BYTES).also(neighbor.hardwareAddress::copyTo),
                    ),
                ),
            ),
        )
    }

    private const val AF_UNSPEC = 0
    private const val AF_INET = 2
    private const val AF_PACKET = 17
    private const val RTM_NEWLINK = 16
    private const val RTM_DELLINK = 17
    private const val RTM_GETLINK = 18
    private const val RTM_SETLINK = 19
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
    private const val RTNLGRP_MAX = 39
    private const val IFINFO_SIZE = 16
    private const val IFADDR_SIZE = 8
    private const val RTMSG_SIZE = 12
    private const val NDMSG_SIZE = 12
    private const val RTGENMSG_SIZE = 1
    private const val IF_NAMESIZE = 16
    private const val IFLA_ADDRESS = 1
    private const val IFLA_BROADCAST = 2
    private const val IFLA_IFNAME = 3
    private const val IFLA_MTU = 4
    private const val IFLA_OPERSTATE = 16
    private const val IFA_ADDRESS = 1
    private const val IFA_LOCAL = 2
    private const val IFA_LABEL = 3
    private const val IFA_BROADCAST = 4
    private const val IFA_FLAGS = 8
    private const val IFA_F_NOPREFIXROUTE = 0x200u
    private const val RTA_DST = 1
    private const val RTA_OIF = 4
    private const val RTA_GATEWAY = 5
    private const val RTA_PRIORITY = 6
    private const val RTA_PREFSRC = 7
    private const val RT_TABLE_UNSPEC = 0
    private const val RT_TABLE_MAIN = 254
    private const val RT_TABLE_LOCAL = 255
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
