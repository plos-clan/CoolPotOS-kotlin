package org.plos_clan.cpos.network

import org.plos_clan.cpos.fs.sock.AbstractSocket
import org.plos_clan.cpos.fs.sock.IoWaitQueue
import org.plos_clan.cpos.fs.sock.SocketAddress
import org.plos_clan.cpos.fs.sock.SocketDomain
import org.plos_clan.cpos.fs.sock.SocketReceiveRequest
import org.plos_clan.cpos.fs.sock.SocketReceiveResult
import org.plos_clan.cpos.fs.sock.SocketSendRequest
import org.plos_clan.cpos.fs.sock.SocketShutdownMode
import org.plos_clan.cpos.fs.sock.SocketType
import org.plos_clan.cpos.fs.vfs.IoResult
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.utils.IrqSpinLock
import org.plos_clan.cpos.utils.LittleEndianBuffer
import org.plos_clan.cpos.utils.PollEvents
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
internal object UdpProtocol : IpProtocolHandler {
    private data class Binding(
        val socket: UdpSocket,
        val address: Ipv4SocketAddress,
        val reuseAddress: Boolean,
    )

    override val protocol = IpProtocol.UDP
    private val initialized = AtomicBoolean(false)
    private val lock = IrqSpinLock()
    private val bindings = mutableMapOf<UShort, MutableList<Binding>>()
    private val nextEphemeralPort = AtomicInt(EPHEMERAL_PORT_FIRST)

    fun initialize() {
        if (initialized.compareAndSet(false, true)) NetworkStack.registerHandler(this)
    }

    fun createSocket(): UdpSocket {
        initialize()
        return UdpSocket(this)
    }

    fun bind(
        socket: UdpSocket,
        requested: Ipv4SocketAddress,
        reuseAddress: Boolean,
    ): VfsResult<Ipv4SocketAddress> = lock.withLock {
        if (requested.port != 0.toUShort()) {
            return@withLock bindPort(socket, requested, reuseAddress)
        }
        repeat(EPHEMERAL_PORT_COUNT) {
            val value = nextEphemeralPort.fetchAndAdd(1)
            val normalized = EPHEMERAL_PORT_FIRST +
                (value.toUInt() % EPHEMERAL_PORT_COUNT.toUInt()).toInt()
            val candidate = requested.copy(port = normalized.toUShort())
            val result = bindPort(socket, candidate, reuseAddress)
            if (result is VfsResult.Ok) return@withLock result
        }
        VfsResult.Err(VfsError.ADDRESS_IN_USE)
    }

    fun unbind(socket: UdpSocket) = lock.withLock {
        bindings.entries.removeAll { (_, entries) ->
            entries.removeAll { it.socket === socket }
            entries.isEmpty()
        }
    }

    override fun receive(packet: IpPacketContext) {
        val segment = UdpCodec.decode(
            packet.bytes,
            packet.payloadOffset,
            packet.payloadLength,
            packet.source,
            packet.destination,
        ) ?: return
        val source = Ipv4SocketAddress(packet.source, segment.sourcePort)
        val destination = Ipv4SocketAddress(packet.destination, segment.destinationPort)
        val candidates = lock.withLock { bindings[segment.destinationPort]?.toList().orEmpty() }
            .filter { binding ->
                (binding.address.address.isAny || binding.address.address == packet.destination) &&
                    binding.socket.accepts(source)
            }
        val recipients =
            if (NetworkStack.isBroadcast(packet.destination) || packet.destination.isMulticast) {
                candidates.map(Binding::socket).distinct()
            } else {
                candidates.maxByOrNull { if (it.address.address.isAny) 0 else 1 }
                    ?.let { listOf(it.socket) }.orEmpty()
            }
        if (recipients.isEmpty()) {
            if (!NetworkStack.isBroadcast(packet.destination) && !packet.destination.isMulticast) {
                NetworkStack.sendPortUnreachable(packet)
            }
            return
        }
        val payload = packet.bytes.copyOfRange(
            segment.payloadOffset,
            segment.payloadOffset + segment.payloadLength,
        )
        recipients.forEach { it.enqueue(payload, source, destination) }
    }

    override fun receiveError(packet: IpPacketContext, error: IpTransportError) {
        if (packet.payloadLength < UdpCodec.HEADER_SIZE) return
        val input = NetworkOrderBuffer(packet.bytes)
        val sourcePort = input.readU16(packet.payloadOffset)
        val destinationPort = input.readU16(packet.payloadOffset + 2)
        val remote = Ipv4SocketAddress(packet.destination, destinationPort)
        val recipient = lock.withLock { bindings[sourcePort]?.toList().orEmpty() }
            .firstOrNull { binding ->
                (binding.address.address.isAny || binding.address.address == packet.source) &&
                    binding.socket.accepts(remote)
            }?.socket ?: return
        recipient.reportError(
            when (error) {
                IpTransportError.NETWORK_UNREACHABLE -> VfsError.NETWORK_UNREACHABLE
                IpTransportError.HOST_UNREACHABLE -> VfsError.HOST_UNREACHABLE
                IpTransportError.PORT_UNREACHABLE -> VfsError.CONNECTION_REFUSED
                IpTransportError.FRAGMENTATION_NEEDED -> VfsError.MESSAGE_TOO_LONG
                IpTransportError.PROTOCOL_UNREACHABLE -> VfsError.PROTOCOL_NOT_SUPPORTED
                IpTransportError.TIME_EXCEEDED -> VfsError.HOST_UNREACHABLE
            },
        )
    }

    private fun bindPort(
        socket: UdpSocket,
        address: Ipv4SocketAddress,
        reuseAddress: Boolean,
    ): VfsResult<Ipv4SocketAddress> {
        val entries = bindings.getOrPut(address.port) { mutableListOf() }
        val conflict = entries.any { existing ->
            val overlaps = existing.address.address.isAny || address.address.isAny ||
                existing.address.address == address.address
            overlaps && (!existing.reuseAddress || !reuseAddress)
        }
        if (conflict) return VfsResult.Err(VfsError.ADDRESS_IN_USE)
        entries += Binding(socket, address, reuseAddress)
        return VfsResult.Ok(address)
    }

    private const val EPHEMERAL_PORT_FIRST = 32_768
    private const val EPHEMERAL_PORT_LAST = 60_999
    private const val EPHEMERAL_PORT_COUNT = EPHEMERAL_PORT_LAST - EPHEMERAL_PORT_FIRST + 1
}

internal class UdpSocket internal constructor(
    private val subsystem: UdpProtocol,
) : AbstractSocket(SocketDomain.IPV4, SocketType.DATAGRAM, IpProtocol.UDP.number.toInt()) {
    private data class Datagram(
        val bytes: ByteArray,
        val source: Ipv4SocketAddress,
        val destination: Ipv4SocketAddress,
    )

    private var binding: Ipv4SocketAddress? = null
    private var selectedSource = Ipv4Address.ANY
    private var peer: Ipv4SocketAddress? = null
    private var messages = ArrayDeque<Datagram>()
    private var queuedBytes = 0
    private var readOpen = true
    private var writeOpen = true
    private var ttl = DEFAULT_TTL
    private val readWaiters = IoWaitQueue()

    override fun bindSocket(process: Process, address: SocketAddress): VfsResult<Unit> {
        val requested = address as? Ipv4SocketAddress
            ?: return VfsResult.Err(VfsError.ADDRESS_FAMILY_NOT_SUPPORTED)
        if (!requested.address.isAny && !NetworkStack.isLocalAddress(requested.address)) {
            return VfsResult.Err(VfsError.ADDRESS_NOT_AVAILABLE)
        }
        return lock.withLock {
            if (closed) return@withLock VfsResult.Err(VfsError.BAD_DESCRIPTOR)
            if (binding != null) return@withLock VfsResult.Err(VfsError.INVALID_ARGUMENT)
            when (val result = subsystem.bind(this, requested, optionsLocked().reuseAddress)) {
                is VfsResult.Ok -> {
                    binding = result.value
                    selectedSource = result.value.address
                    VfsResult.Ok(Unit)
                }
                is VfsResult.Err -> result
            }
        }
    }

    override fun connectSocket(
        process: Process,
        address: SocketAddress?,
        nonBlocking: Boolean,
    ): VfsResult<Unit> {
        if (address == null) {
            lock.withLock {
                peer = null
                selectedSource = binding?.address ?: Ipv4Address.ANY
            }
            return VfsResult.Ok(Unit)
        }
        val destination = address as? Ipv4SocketAddress
            ?: return VfsResult.Err(VfsError.ADDRESS_FAMILY_NOT_SUPPORTED)
        if (destination.address.isAny || destination.port == 0.toUShort()) {
            return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        }
        return lock.withLock {
            if (closed) return@withLock VfsResult.Err(VfsError.BAD_DESCRIPTOR)
            val local = ensureBoundLocked()
            if (local is VfsResult.Err) return@withLock local
            val path = NetworkStack.path(checkNotNull((local as VfsResult.Ok).value).address, destination.address)
            when (path) {
                is VfsResult.Ok -> {
                    selectedSource = path.value.source
                    peer = destination
                    VfsResult.Ok(Unit)
                }
                is VfsResult.Err -> path
            }
        }
    }

    override fun localAddress(): Ipv4SocketAddress = lock.withLock {
        val local = binding ?: return@withLock Ipv4SocketAddress(Ipv4Address.ANY, 0u)
        if (selectedSource.isAny) local else local.copy(address = selectedSource)
    }

    override fun peerAddress(): VfsResult<Ipv4SocketAddress> = lock.withLock {
        peer?.let { VfsResult.Ok(it) } ?: VfsResult.Err(VfsError.NOT_CONNECTED)
    }

    override fun shutdownSocket(mode: SocketShutdownMode): VfsResult<Unit> = lock.withLock {
        if (peer == null) return@withLock VfsResult.Err(VfsError.NOT_CONNECTED)
        if (mode.reads) {
            readOpen = false
            messages.clear()
            queuedBytes = 0
            readWaiters.wakeAll()
        }
        if (mode.writes) writeOpen = false
        VfsResult.Ok(Unit)
    }

    override fun sendSocket(request: SocketSendRequest): IoResult {
        request.ancillary.release()
        val requestedDestination = request.destination
        val explicit = if (requestedDestination == null) null else
            requestedDestination as? Ipv4SocketAddress
                ?: return IoResult.failure(VfsError.ADDRESS_FAMILY_NOT_SUPPORTED)
        if (request.count > MAX_PAYLOAD_SIZE) return IoResult.failure(VfsError.MESSAGE_TOO_LONG)
        val prepared = lock.withLock {
            if (closed) return@withLock VfsResult.Err(VfsError.BAD_DESCRIPTOR)
            if (!writeOpen) return@withLock VfsResult.Err(VfsError.BROKEN_PIPE)
            val destination = explicit ?: peer
                ?: return@withLock VfsResult.Err(VfsError.DESTINATION_ADDRESS_REQUIRED)
            if (destination.port == 0.toUShort() || destination.address.isAny) {
                return@withLock VfsResult.Err(VfsError.INVALID_ARGUMENT)
            }
            if (NetworkStack.isBroadcast(destination.address) && !optionsLocked().broadcast) {
                return@withLock VfsResult.Err(VfsError.PERMISSION_DENIED)
            }
            val local = ensureBoundLocked()
            if (local is VfsResult.Err) return@withLock local
            val bound = (local as VfsResult.Ok).value
            val path = NetworkStack.path(bound.address, destination.address)
            if (path is VfsResult.Err) return@withLock path
            val selected = (path as VfsResult.Ok).value.source
            if (peer != null) selectedSource = selected
            VfsResult.Ok(
                Pair(
                    Ipv4SocketAddress(selected, bound.port),
                    destination,
                ),
            )
        }
        val endpoints = when (prepared) {
            is VfsResult.Ok -> prepared.value
            is VfsResult.Err -> return IoResult.failure(prepared.error)
        }
        val payload = ByteArray(UdpCodec.HEADER_SIZE + request.count)
        if (request.source.copyTo(
                request.offset,
                payload,
                UdpCodec.HEADER_SIZE,
                request.count,
            ) != request.count
        ) return IoResult.failure(VfsError.FAULT)
        UdpCodec.write(payload, 0, request.count, endpoints.first, endpoints.second)
        return when (val sent = NetworkStack.sendIpv4(
            endpoints.first.address,
            endpoints.second.address,
            IpProtocol.UDP,
            payload,
            ttl = ttl.toUByte(),
        )) {
            is VfsResult.Ok -> IoResult.success(request.count)
            is VfsResult.Err -> IoResult.failure(sent.error)
        }
    }

    override fun receiveSocket(request: SocketReceiveRequest): VfsResult<SocketReceiveResult> {
        val timeout = request.deadline ?: if (request.nonBlocking) null
        else org.plos_clan.cpos.fs.sock.SocketDeadline.after(socketOptions().receiveTimeoutNanos)
        while (true) {
            var waiter: IoWaitQueue.Waiter? = null
            val result = lock.withLock {
                val datagram = messages.firstOrNull()
                if (datagram != null) {
                    val copied = minOf(request.count, datagram.bytes.size)
                    val transferred = request.destination.copyFrom(
                        request.offset,
                        datagram.bytes,
                        0,
                        copied,
                    )
                    if (transferred != copied) return@withLock VfsResult.Err(VfsError.FAULT)
                    if (!request.peek) {
                        messages.removeFirst()
                        queuedBytes -= datagram.bytes.size
                    }
                    val returned = if (request.returnFullLength) datagram.bytes.size else copied
                    return@withLock VfsResult.Ok(
                        SocketReceiveResult(
                            returned,
                            copied,
                            datagram.source,
                            truncated = copied < datagram.bytes.size,
                            endOfRecord = true,
                        ),
                    )
                }
                takeErrorLocked()?.let { return@withLock VfsResult.Err(it) }
                if (!readOpen || closed) return@withLock VfsResult.Err(VfsError.NOT_CONNECTED)
                if (request.nonBlocking) return@withLock VfsResult.Err(VfsError.WOULD_BLOCK)
                if (timeout == null) {
                    val thread = ProcessManager.currentThread()
                        ?: return@withLock VfsResult.Err(VfsError.NOT_FOUND)
                    waiter = readWaiters.add(thread)
                }
                null
            }
            if (result != null) return result
            val waitError = if (timeout != null) timeout.await(::receiveReady)
            else if (readWaiters.await(lock, checkNotNull(waiter))) null else VfsError.INTERRUPTED
            if (waitError != null) return VfsResult.Err(waitError)
        }
    }

    override fun setProtocolOption(level: Int, name: Int, value: ByteArray): VfsResult<Unit> {
        if (level != SOL_IP || name != IP_TTL || value.size < Int.SIZE_BYTES) {
            return VfsResult.Err(VfsError.PROTOCOL_OPTION_NOT_AVAILABLE)
        }
        val requested = LittleEndianBuffer(value).readU32(0).toInt()
        if (requested !in 1..UByte.MAX_VALUE.toInt()) {
            return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        }
        lock.withLock { ttl = requested }
        return VfsResult.Ok(Unit)
    }

    override fun getProtocolOption(level: Int, name: Int): VfsResult<ByteArray> {
        if (level != SOL_IP) return VfsResult.Err(VfsError.PROTOCOL_OPTION_NOT_AVAILABLE)
        val value = when (name) {
            IP_TTL -> lock.withLock { ttl }
            IP_MTU -> {
                val endpoints = lock.withLock { selectedSource to peer }
                val remote = endpoints.second
                    ?: return VfsResult.Err(VfsError.NOT_CONNECTED)
                when (val path = NetworkStack.path(endpoints.first, remote.address)) {
                    is VfsResult.Ok -> path.value.mtu
                    is VfsResult.Err -> return path
                }
            }
            else -> return VfsResult.Err(VfsError.PROTOCOL_OPTION_NOT_AVAILABLE)
        }
        return VfsResult.Ok(ByteArray(Int.SIZE_BYTES).also {
            LittleEndianBuffer(it).writeU32(0, value.toUInt())
        })
    }

    internal fun accepts(source: Ipv4SocketAddress): Boolean = lock.withLock {
        peer == null || peer == source
    }

    internal fun enqueue(
        bytes: ByteArray,
        source: Ipv4SocketAddress,
        destination: Ipv4SocketAddress,
    ) = lock.withLock {
        if (closed || !readOpen || queuedBytes > optionsLocked().receiveBufferSize - bytes.size) {
            return@withLock
        }
        messages.addLast(Datagram(bytes, source, destination))
        queuedBytes += bytes.size
        readWaiters.wakeReady(1)
    }

    internal fun reportError(error: VfsError) {
        setError(error)
        lock.withLock { readWaiters.wakeAll() }
    }

    override fun readableBytes(): Int = lock.withLock { messages.firstOrNull()?.bytes?.size ?: 0 }

    override fun pollSocket(events: Int): Int = lock.withLock {
        var available = 0
        if (messages.isNotEmpty() || !readOpen) available = available or PollEvents.NORMAL_INPUT
        if (writeOpen && !closed) available = available or PollEvents.NORMAL_OUTPUT
        if (hasPendingError()) available = available or PollEvents.POLLERR
        if (closed) available = available or PollEvents.POLLHUP
        available and (events or PollEvents.UNCONDITIONALLY_REPORTED)
    }

    override fun receiveBufferSizeChangedLocked(size: Int) {
        readWaiters.wakeAll()
    }

    override fun closeSocketLocked(): (() -> Unit)? {
        messages.clear()
        queuedBytes = 0
        readOpen = false
        writeOpen = false
        readWaiters.wakeAll()
        return if (binding == null) null else ({ subsystem.unbind(this) })
    }

    private fun ensureBoundLocked(): VfsResult<Ipv4SocketAddress> {
        binding?.let { return VfsResult.Ok(it) }
        return when (val result = subsystem.bind(
            this,
            Ipv4SocketAddress(Ipv4Address.ANY, 0u),
            optionsLocked().reuseAddress,
        )) {
            is VfsResult.Ok -> result.also { binding = it.value }
            is VfsResult.Err -> result
        }
    }

    private fun receiveReady(): Boolean = lock.withLock {
        messages.isNotEmpty() || !readOpen || closed || hasPendingError()
    }

    companion object {
        private const val DEFAULT_TTL = 64
        private const val MAX_PAYLOAD_SIZE = 65_507
        private const val SOL_IP = 0
        private const val IP_TTL = 2
        private const val IP_MTU = 14

    }
}
