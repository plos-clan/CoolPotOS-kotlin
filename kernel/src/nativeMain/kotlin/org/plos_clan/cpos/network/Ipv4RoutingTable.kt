package org.plos_clan.cpos.network

internal data class NetworkPath(
    val intfc: NetworkInterface,
    val source: Ipv4Address,
    val destination: Ipv4Address,
    val nextHop: Ipv4Address = destination,
) {
    val mtu get() = intfc.mtu
    val local get() = intfc.kind == NetworkInterfaceKind.LOOPBACK
}

internal class Ipv4RoutingTable(
    devices: Map<Int, NetworkInterface>,
    assigned: Map<Int, List<NetworkInterfaceAddress>>,
    configured: List<NetworkRoute>,
) {
    val interfaces = devices.toMap()
    val links = interfaces.values.sortedBy(NetworkInterface::index)
    val addresses = assigned.toMap()
    private val loopback = links.firstOrNull { it.kind == NetworkInterfaceKind.LOOPBACK }
    private val localRoutes = buildMap {
        if (loopback == null) return@buildMap
        for (entry in addresses.values.flatten()) {
            val route = NetworkPath(loopback, entry.address, entry.address)
            put(entry.address, route)
        }
    }
    val localAddresses get() = localRoutes.keys
    private val broadcasts = addresses.mapValues { (_, entries) ->
        entries.filter { it.prefixLength < 31 }.map { it.prefix.broadcast }.toSet()
    }
    private val allBroadcasts = broadcasts.values.flatten().toSet()
    val routes = buildList {
        addAll(configured)
        addresses.forEach { (index, entries) -> entries.forEach { addConnected(index, it) } }
    }.distinct()
    private val unicast = routes.filter { it.kind == NetworkRouteKind.UNICAST }
        .sortedWith(compareByDescending<NetworkRoute> { it.destination.length }
            .thenBy { it.metric })

    fun isBroadcast(address: Ipv4Address, index: Int? = null): Boolean {
        val candidates = if (index == null) allBroadcasts else broadcasts[index].orEmpty()
        return address.isLimitedBroadcast || address in candidates
    }

    fun select(source: Ipv4Address, destination: Ipv4Address, index: Int?): NetworkPath? {
        val local = localRoutes[destination]
        if (local != null) {
            if (!local.intfc.running || index != null && local.intfc.index != index) return null
            if (source.isAny || source == local.source) return local
            return if (source in localAddresses) local.copy(source = source) else null
        }
        if (destination.isLimitedBroadcast) {
            val link = links.firstOrNull {
                it.kind == NetworkInterfaceKind.ETHERNET && it.running &&
                    (index == null || it.index == index) && !addresses[it.index].isNullOrEmpty()
            } ?: return null
            if (!source.isAny && source !in localAddresses) return null
            val assigned = addresses.getValue(link.index)
            val selected = if (source.isAny) assigned.first().address else source
            return NetworkPath(link, selected, destination)
        }
        val route = unicast.firstOrNull {
            (index == null || index == it.interfaceIndex) && it.destination.contains(destination) &&
                interfaces[it.interfaceIndex]?.running == true
        } ?: return null
        val entries = addresses[route.interfaceIndex] ?: return null
        val preferred = entries.firstOrNull { it.address == route.preferredSource }
        val selected = when {
            !source.isAny -> entries.firstOrNull { it.address == source }
            preferred != null -> preferred
            else -> entries.firstOrNull { it.prefix.contains(destination) } ?: entries.firstOrNull()
        } ?: return null
        val link = interfaces.getValue(route.interfaceIndex)
        return NetworkPath(link, selected.address, destination, route.gateway ?: destination)
    }

    private fun MutableList<NetworkRoute>.addConnected(index: Int, entry: NetworkInterfaceAddress) {
        fun addRoute(prefix: Ipv4Prefix, link: Int, kind: NetworkRouteKind) {
            val route = NetworkRoute(
                prefix, interfaceIndex = link, preferredSource = entry.address,
                kind = kind, protocol = 2u,
            )
            add(route)
        }
        if (entry.automaticPrefixRoute) addRoute(entry.prefix, index, NetworkRouteKind.UNICAST)
        val host = Ipv4Prefix(entry.address, 32)
        if (loopback != null) addRoute(host, loopback.index, NetworkRouteKind.LOCAL)
        if (entry.prefixLength < 31) {
            val broadcast = Ipv4Prefix(entry.prefix.broadcast, 32)
            addRoute(broadcast, index, NetworkRouteKind.BROADCAST)
        }
    }
}
