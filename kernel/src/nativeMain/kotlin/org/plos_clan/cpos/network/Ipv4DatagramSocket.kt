package org.plos_clan.cpos.network

import org.plos_clan.cpos.fs.sock.SocketAddress
import org.plos_clan.cpos.fs.sock.SocketDeadline
import org.plos_clan.cpos.fs.sock.SocketOptions
import org.plos_clan.cpos.fs.sock.SocketDomain
import org.plos_clan.cpos.fs.sock.SocketReceiveRequest
import org.plos_clan.cpos.fs.sock.SocketReceiveResult
import org.plos_clan.cpos.fs.sock.SocketSendRequest
import org.plos_clan.cpos.fs.sock.SocketShutdownMode
import org.plos_clan.cpos.fs.sock.SocketType
import org.plos_clan.cpos.fs.vfs.Inode
import org.plos_clan.cpos.fs.vfs.IoResult
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsOperationContext
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.tasks.IoWaitQueue
import org.plos_clan.cpos.tasks.PollSubscription
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.utils.LittleEndianBuffer
import org.plos_clan.cpos.utils.PollEvents

internal abstract class Ipv4DatagramSocket(
    network: NetworkStack,
    private val transport: IpProtocol,
    private val ports: PortTable<Ipv4DatagramSocket>,
    private val owner: Int?,
    private val headerSize: Int,
    private val minimumSize: Int = 0,
) : NetworkSocket(network, SocketDomain.IPV4, SocketType.DATAGRAM, transport.number.toInt()) {
    private data class Datagram(
        val payload: PacketSlice,
        val source: Ipv4SocketAddress,
        val receivedAtNanos: ULong?,
    )

    protected data class Transmission(
        val source: Ipv4SocketAddress,
        val destination: Ipv4SocketAddress,
        val path: NetworkPath,
        val ttl: UByte,
        val typeOfService: UByte,
    )

    private var binding: Ipv4SocketAddress? = null
    private var connection: Transmission? = null
    private val messages = ArrayDeque<Datagram>()
    private var queuedBytes = 0
    private var readOpen = true
    private var writeOpen = true
    private var ttl = DEFAULT_TTL
    private var typeOfService = 0.toUByte()
    private val readWaiters = IoWaitQueue()

    protected abstract fun normalizePeer(address: Ipv4SocketAddress): Ipv4SocketAddress?
    protected abstract fun writeHeader(packet: Ipv4OutputPacket, tx: Transmission): VfsError?

    override fun bindSocket(process: Process, address: SocketAddress): VfsResult<Unit> {
        val requested = address as? Ipv4SocketAddress
            ?: return VfsResult.Err(VfsError.ADDRESS_FAMILY_NOT_SUPPORTED)
        if (!requested.address.isAny && !network.isLocalAddress(requested.address)) {
            return VfsResult.Err(VfsError.ADDRESS_NOT_AVAILABLE)
        }
        return lock.withLock {
            if (closed) return@withLock VfsResult.Err(VfsError.BAD_DESCRIPTOR)
            if (binding != null) return@withLock VfsResult.Err(VfsError.INVALID_ARGUMENT)
            val interfaceIndex = optionsLocked().boundInterfaceIndex
            if (!requested.address.isAny && interfaceIndex != null &&
                network.interfaceAddresses(interfaceIndex).none {
                    it.address == requested.address
                }
            ) return@withLock VfsResult.Err(VfsError.ADDRESS_NOT_AVAILABLE)
            val result = bindLocked(requested)
            if (result is VfsResult.Err) result else VfsResult.Ok(Unit)
        }
    }

    override fun connectSocket(
        process: Process,
        address: SocketAddress?,
        nonBlocking: Boolean,
    ): VfsResult<Unit> {
        val endpoint = address as? Ipv4SocketAddress
        if (address != null && endpoint == null) {
            return VfsResult.Err(VfsError.ADDRESS_FAMILY_NOT_SUPPORTED)
        }
        return lock.withLock {
            if (closed) return@withLock VfsResult.Err(VfsError.BAD_DESCRIPTOR)
            if (endpoint == null) connection = null else {
                val route = resolveLocked(endpoint)
                if (route is VfsResult.Err) return@withLock route
                connection = (route as VfsResult.Ok).value
            }
            optionsChangedLocked(optionsLocked())
            VfsResult.Ok(Unit)
        }
    }

    override fun localAddress(): Ipv4SocketAddress = lock.withLock {
        connection?.source ?: binding ?: ANY
    }

    override fun peerAddress(): VfsResult<Ipv4SocketAddress> = lock.withLock {
        val peer = connection?.destination ?: return@withLock VfsResult.Err(VfsError.NOT_CONNECTED)
        VfsResult.Ok(peer)
    }

    override fun optionsChangedLocked(options: SocketOptions) {
        val port = binding?.port ?: return
        val tx = connection
        val source = tx?.source?.address ?: Ipv4Address.ANY
        ports.configure(port, this, options, owner, tx?.destination, source)
    }

    override fun shutdownSocket(mode: SocketShutdownMode): VfsResult<Unit> = lock.withLock {
        if (connection == null) return@withLock VfsResult.Err(VfsError.NOT_CONNECTED)
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
        val maximum = Ipv4Codec.MAX_PACKET_SIZE - Ipv4Codec.MIN_HEADER_SIZE - headerSize
        if (request.count !in minimumSize..maximum) {
            return IoResult.failure(VfsError.MESSAGE_TOO_LONG)
        }
        val prepared = lock.withLock {
            if (closed) return@withLock VfsResult.Err(VfsError.BAD_DESCRIPTOR)
            if (!writeOpen) return@withLock VfsResult.Err(VfsError.BROKEN_PIPE)
            val endpoint = explicit ?: connection?.destination
                ?: return@withLock VfsResult.Err(VfsError.DESTINATION_ADDRESS_REQUIRED)
            resolveLocked(endpoint, allowBroadcast = optionsLocked().broadcast)
        }
        val transmission = when (prepared) {
            is VfsResult.Ok -> prepared.value
            is VfsResult.Err -> return IoResult.failure(prepared.error)
        }
        val packet = Ipv4OutputPacket(headerSize + request.count)
        val offset = Ipv4OutputPacket.PAYLOAD_OFFSET + headerSize
        val copied = request.source.copyTo(request.offset, packet.bytes, offset, request.count)
        if (copied != request.count) return IoResult.failure(VfsError.FAULT)
        writeHeader(packet, transmission)?.let { return IoResult.failure(it) }
        val sent = network.sendIpv4(
            transmission.path, transport, packet,
            ttl = transmission.ttl, typeOfService = transmission.typeOfService,
        )
        return when (sent) {
            is VfsResult.Ok -> IoResult.success(request.count)
            is VfsResult.Err -> IoResult.failure(sent.error)
        }
    }

    override fun receiveSocket(request: SocketReceiveRequest): VfsResult<SocketReceiveResult> {
        val timeout = request.deadline ?: if (request.nonBlocking) null
        else SocketDeadline.after(socketOptions().receiveTimeoutNanos)
        while (true) {
            var waiter: IoWaitQueue.Waiter? = null
            val result = lock.withLock {
                val datagram = messages.firstOrNull()
                if (datagram != null) return@withLock readDatagram(request, datagram)
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
            val waitError = when {
                timeout != null -> timeout.await(readWaiters.events, ::receiveReady)
                readWaiters.await(lock, checkNotNull(waiter)) -> null
                else -> VfsError.INTERRUPTED
            }
            if (waitError != null) return VfsResult.Err(waitError)
        }
    }

    override fun setProtocolOption(
        process: Process,
        level: Int,
        name: Int,
        value: ByteArray,
    ): VfsResult<Unit> {
        if (level != SOL_IP || value.size < Int.SIZE_BYTES) {
            return VfsResult.Err(VfsError.PROTOCOL_OPTION_NOT_AVAILABLE)
        }
        val requested = LittleEndianBuffer(value).readU32(0).toInt()
        return when (name) {
            IP_TOS -> if (requested in 0..UByte.MAX_VALUE.toInt()) {
                lock.withLock { typeOfService = requested.toUByte() }
                VfsResult.Ok(Unit)
            } else {
                VfsResult.Err(VfsError.INVALID_ARGUMENT)
            }
            IP_TTL -> if (requested in 1..UByte.MAX_VALUE.toInt()) {
                lock.withLock { ttl = requested }
                VfsResult.Ok(Unit)
            } else {
                VfsResult.Err(VfsError.INVALID_ARGUMENT)
            }

            IP_RECVERR,
            IP_MTU_DISCOVER,
            IP_RECVTTL,
            -> VfsResult.Ok(Unit)
            else -> VfsResult.Err(VfsError.PROTOCOL_OPTION_NOT_AVAILABLE)
        }
    }

    override fun getProtocolOption(level: Int, name: Int): VfsResult<ByteArray> {
        if (level != SOL_IP) return VfsResult.Err(VfsError.PROTOCOL_OPTION_NOT_AVAILABLE)
        val value = when (name) {
            IP_TOS -> lock.withLock { typeOfService.toInt() }
            IP_TTL -> lock.withLock { ttl }
            IP_MTU -> {
                val tx = lock.withLock { connection }
                    ?: return VfsResult.Err(VfsError.NOT_CONNECTED)
                when (val path = network.path(tx.source.address, tx.destination.address)) {
                    is VfsResult.Ok -> path.value.mtu
                    is VfsResult.Err -> return path
                }
            }
            else -> return VfsResult.Err(VfsError.PROTOCOL_OPTION_NOT_AVAILABLE)
        }
        val bytes = ByteArray(Int.SIZE_BYTES)
        LittleEndianBuffer(bytes).writeU32(0, value.toUInt())
        return VfsResult.Ok(bytes)
    }

    private fun readDatagram(
        request: SocketReceiveRequest,
        datagram: Datagram,
    ): VfsResult<SocketReceiveResult> {
        val payload = datagram.payload
        val size = minOf(request.count, payload.size)
        val copied = payload.copyTo(request.destination, request.offset, size)
        if (copied != size) return VfsResult.Err(VfsError.FAULT)
        if (!request.peek) {
            messages.removeFirst()
            queuedBytes -= payload.bytes.size
        }
        val length = if (request.returnFullLength) payload.size else copied
        val result = SocketReceiveResult(
            length, copied, datagram.source, truncated = copied < payload.size,
            endOfRecord = true, receivedAtNanos = datagram.receivedAtNanos,
        )
        return VfsResult.Ok(result)
    }

    fun enqueue(packet: IpPacketContext, source: Ipv4SocketAddress, offset: Int, size: Int) {
        lock.withLock {
            if (closed || !readOpen) return
            val charge = packet.bytes.size
            if (queuedBytes > optionsLocked().receiveBufferSize - charge) return
            val length = packet.payloadOffset + packet.payloadLength - packet.packetOffset
            val captured = filterPacketLocked(packet.bytes, packet.packetOffset, length)
            if (captured == 0) return
            val headers = offset - packet.packetOffset
            val payload = PacketSlice(packet.bytes, offset, (captured - headers).coerceIn(0, size))
            val datagram = Datagram(payload, source, captureReceiveTimestampLocked())
            messages.addLast(datagram)
            queuedBytes += charge
            readWaiters.wakeReady(1)
        }
    }

    fun reportError(error: VfsError) = lock.withLock {
        storeErrorLocked(error)
        readWaiters.wakeAll()
    }

    override fun readableBytes(): Int = lock.withLock { messages.firstOrNull()?.payload?.size ?: 0 }

    override fun subscribe(
        caller: VfsOperationContext,
        inode: Inode,
        subscription: PollSubscription,
    ) {
        subscription.watch(readWaiters.events, PollEvents.NORMAL_INPUT or PollEvents.POLLRDHUP)
    }

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
        binding?.let { ports.remove(it.port, this) }
        return null
    }

    private fun bindLocked(endpoint: Ipv4SocketAddress): VfsResult<Ipv4SocketAddress> {
        val result = ports.bind(this, endpoint, optionsLocked(), owner)
        if (result is VfsResult.Ok) binding = result.value
        return result
    }

    private fun resolveLocked(
        endpoint: Ipv4SocketAddress, allowBroadcast: Boolean = true,
    ): VfsResult<Transmission> {
        val destination = normalizePeer(endpoint) ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        if (!allowBroadcast && network.isBroadcast(destination.address)) {
            return VfsResult.Err(VfsError.PERMISSION_DENIED)
        }
        val bound = binding ?: when (val result = bindLocked(ANY)) {
            is VfsResult.Err -> return result
            is VfsResult.Ok -> result.value
        }
        val device = optionsLocked().boundInterfaceIndex
        val path = network.path(bound.address, destination.address, device)
        if (path is VfsResult.Err) return path
        val route = (path as VfsResult.Ok).value
        val previous = connection
        val hopLimit = ttl.toUByte()
        if (previous?.path === route && previous.destination == destination &&
            previous.ttl == hopLimit && previous.typeOfService == typeOfService
        ) return VfsResult.Ok(previous)
        val source = Ipv4SocketAddress(route.source, bound.port)
        val tx = Transmission(source, destination, route, hopLimit, typeOfService)
        if (previous?.destination == destination) {
            connection = tx
            if (previous.source != source) optionsChangedLocked(optionsLocked())
        }
        return VfsResult.Ok(tx)
    }

    private fun receiveReady(): Boolean = lock.withLock {
        messages.isNotEmpty() || !readOpen || closed || hasPendingError()
    }

    private companion object {
        val ANY = Ipv4SocketAddress(Ipv4Address.ANY, 0u)
        private const val DEFAULT_TTL = 64
        private const val SOL_IP = 0
        private const val IP_TOS = 1
        private const val IP_TTL = 2
        private const val IP_MTU_DISCOVER = 10
        private const val IP_RECVERR = 11
        private const val IP_RECVTTL = 12
        private const val IP_MTU = 14
    }
}
