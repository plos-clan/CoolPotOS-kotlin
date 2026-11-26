package org.plos_clan.cpos.network

import kotlin.concurrent.Volatile
import kotlinx.collections.immutable.persistentHashMapOf
import org.plos_clan.cpos.fs.sock.SocketOptions
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.utils.IrqSpinLock

internal class PortTable<S : Any>(private val ephemeral: IntRange = 32768..60999) {
    private data class Receiver(
        val local: Ipv4Address,
        val device: Int?,
        val peer: Ipv4SocketAddress? = null,
        val listening: Boolean = false,
    ) {
        fun matches(address: Ipv4Address, remote: Ipv4SocketAddress, index: Int): Boolean =
            (local.isAny || local == address) && (peer == null || peer == remote) &&
                (device == null || device == index)
    }

    private data class Binding<S>(
        val socket: S,
        val address: Ipv4Address,
        val reuseAddress: Boolean,
        val reuseOwner: Int?,
        val receiver: Receiver,
    ) {
        fun overlaps(other: Ipv4Address) = address.isAny || other.isAny || address == other

        fun conflicts(other: Binding<S>, listen: Boolean): Boolean {
            if (!overlaps(other.address)) return false
            if (reuseOwner != null && reuseOwner == other.reuseOwner) return false
            if (listen) return other.receiver.listening
            return other.receiver.listening || !reuseAddress || !other.reuseAddress ||
                reuseOwner != null && other.reuseOwner != null
        }
    }

    private class Group<S>(val entries: List<Binding<S>>) {
        val receiver get() = entries.first().receiver
        private val unicast = entries.run {
            val last = last()
            val owner = last.reuseOwner
            when {
                size == 1 -> this
                owner == null -> listOf(last)
                all { it.reuseOwner == owner } -> this
                else -> filter { it.reuseOwner == owner }
            }
        }

        fun select(local: Ipv4SocketAddress, remote: Ipv4SocketAddress): S {
            if (unicast.size == 1) return unicast[0].socket
            val flow = (local.hashCode() * 31 + remote.hashCode()).toUInt()
            val mixed = (flow xor (flow shr 16)) * 0x85eb_ca6bu
            val hash = (mixed xor (mixed shr 13)) * 0xc2b2_ae35u
            val index = (hash xor (hash shr 16)) % unicast.size.toUInt()
            return unicast[index.toInt()].socket
        }

        fun deliver(
            local: Ipv4Address, remote: Ipv4SocketAddress, index: Int, deliver: (S) -> Unit,
        ) {
            if (!receiver.matches(local, remote, index)) return
            for (entry in entries) deliver(entry.socket)
        }
    }

    private class Port<S>(val entries: List<Binding<S>>) {
        private val only: Group<S>?
        private val groups: Map<Receiver, Group<S>>

        init {
            val receiver = entries.first().receiver
            if (entries.all { it.receiver == receiver }) {
                only = Group(entries)
                groups = emptyMap()
            } else {
                only = null
                groups = entries.groupBy { it.receiver }.mapValues { Group(it.value) }
            }
        }

        fun select(
            local: Ipv4SocketAddress, remote: Ipv4SocketAddress, index: Int, listening: Boolean,
        ): S? {
            val group = only
            if (group != null) {
                val receiver = group.receiver
                if (receiver.listening != listening) return null
                if (!receiver.matches(local.address, remote, index)) return null
                return group.select(local, remote)
            }
            fun match(peer: Ipv4SocketAddress?, device: Int?): Group<S>? {
                val key = Receiver(local.address, device, peer, listening)
                return groups[key] ?: groups[key.copy(local = Ipv4Address.ANY)]
            }
            val best = match(remote, index) ?: match(remote, null)
                ?: match(null, index) ?: match(null, null)
            return best?.select(local, remote)
        }

        fun multicast(
            local: Ipv4Address, remote: Ipv4SocketAddress, index: Int, deliver: (S) -> Unit,
        ) {
            val single = only
            if (single != null) single.deliver(local, remote, index, deliver)
            else groups.values.forEach { it.deliver(local, remote, index, deliver) }
        }
    }

    private val lock = IrqSpinLock()
    @Volatile private var ports = persistentHashMapOf<UShort, Port<S>>()
    private var next = ephemeral.first

    fun bind(
        socket: S, address: Ipv4SocketAddress, options: SocketOptions, owner: Int?,
    ): VfsResult<Ipv4SocketAddress> = lock.withLock {
        val receiver = Receiver(address.address, options.boundInterfaceIndex)
        val shared = owner != null && options.reuseAddress
        val reuseOwner = owner.takeIf { options.reusePort }
        val entry = Binding(socket, address.address, shared, reuseOwner, receiver)
        val port = if (address.port != 0.toUShort()) address.port else allocate(entry)
            ?: return@withLock VfsResult.Err(VfsError.ADDRESS_IN_USE)
        val entries = ports[port]?.entries.orEmpty()
        if (entries.any { entry.conflicts(it, listen = false) }) {
            return@withLock VfsResult.Err(VfsError.ADDRESS_IN_USE)
        }
        publish(port, entries + entry)
        val local = if (port == address.port) address else address.copy(port = port)
        VfsResult.Ok(local)
    }

    private fun allocate(entry: Binding<S>): UShort? {
        repeat(ephemeral.last - ephemeral.first + 1) {
            val port = next.toUShort()
            next = if (next == ephemeral.last) ephemeral.first else next + 1
            val entries = ports[port]?.entries ?: return port
            if (entries.none { it.overlaps(entry.address) }) return port
        }
        return null
    }

    fun listen(port: UShort, socket: S): VfsResult<Unit> = change(port, socket) { entry, group ->
        if (entry.receiver.listening) return VfsResult.Ok(Unit)
        if (group.any { entry.conflicts(it, listen = true) }) {
            return VfsResult.Err(VfsError.ADDRESS_IN_USE)
        }
        val listener = entry.receiver.copy(listening = true)
        entry.copy(receiver = listener)
    }

    fun configure(
        port: UShort, socket: S, options: SocketOptions, owner: Int?,
        peer: Ipv4SocketAddress? = null, source: Ipv4Address = Ipv4Address.ANY,
    ) {
        change(port, socket) { entry, _ ->
            val address = if (peer == null) entry.address else source
            val device = options.boundInterfaceIndex
            val receiver = Receiver(address, device, peer, entry.receiver.listening)
            val shared = owner != null && options.reuseAddress
            val reuseOwner = owner.takeIf { options.reusePort }
            entry.copy(reuseAddress = shared, reuseOwner = reuseOwner, receiver = receiver)
        }
    }

    fun remove(port: UShort, socket: S, replacement: S? = null) {
        change(port, socket) { entry, _ -> replacement?.let { entry.copy(socket = it) } }
    }

    private inline fun change(
        port: UShort, socket: S, edit: (Binding<S>, List<Binding<S>>) -> Binding<S>?,
    ): VfsResult<Unit> = lock.withLock {
        val group = ports[port]?.entries.orEmpty()
        val entry = group.firstOrNull { it.socket === socket }
            ?: return@withLock VfsResult.Err(VfsError.INVALID_ARGUMENT)
        val updated = edit(entry, group)
        if (updated != entry) {
            val entries = group.mapNotNull { if (it === entry) updated else it }
            publish(port, entries)
        }
        VfsResult.Ok(Unit)
    }

    private fun publish(port: UShort, entries: List<Binding<S>>) {
        if (entries.isEmpty()) ports = ports.removing(port) else {
            val snapshot = Port(entries)
            ports = ports.putting(port, snapshot)
        }
    }

    fun select(
        local: Ipv4SocketAddress, remote: Ipv4SocketAddress, index: Int, listening: Boolean = false,
    ): S? = ports[local.port]?.select(local, remote, index, listening)

    fun multicast(
        local: Ipv4SocketAddress, remote: Ipv4SocketAddress, index: Int, deliver: (S) -> Unit,
    ) {
        ports[local.port]?.multicast(local.address, remote, index, deliver)
    }
}
