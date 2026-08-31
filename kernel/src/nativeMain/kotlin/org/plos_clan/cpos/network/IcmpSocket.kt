package org.plos_clan.cpos.network

import org.plos_clan.cpos.fs.sock.AbstractSocket
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
import org.plos_clan.cpos.tasks.IoWaitQueue
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.utils.IrqSpinLock
import org.plos_clan.cpos.utils.LittleEndianBuffer
import org.plos_clan.cpos.utils.PollEvents
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
internal object IcmpProtocol {
    private data class Binding(
        val socket: IcmpSocket,
        val address: Ipv4SocketAddress,
    )

    private val lock = IrqSpinLock()
    private val bindings = mutableMapOf<UShort, MutableList<Binding>>()
    private val nextIdentifier = AtomicInt(1)

    fun createSocket(): IcmpSocket = IcmpSocket(this)

    fun bind(socket: IcmpSocket, requested: Ipv4SocketAddress): VfsResult<Ipv4SocketAddress> =
        lock.withLock {
            if (requested.port != 0.toUShort()) return@withLock bindIdentifier(socket, requested)
            repeat(UShort.MAX_VALUE.toInt()) {
                val identifier = ((nextIdentifier.fetchAndAdd(1).toUInt() - 1u) %
                    UShort.MAX_VALUE.toUInt() + 1u).toUShort()
                val result = bindIdentifier(socket, requested.copy(port = identifier))
                if (result is VfsResult.Ok) return@withLock result
            }
            VfsResult.Err(VfsError.ADDRESS_IN_USE)
        }

    fun unbind(socket: IcmpSocket) = lock.withLock {
        bindings.entries.removeAll { (_, entries) ->
            entries.removeAll { it.socket === socket }
            entries.isEmpty()
        }
    }

    fun receive(packet: IpPacketContext, type: Int, code: Int) {
        if (type != ICMP_ECHO_REPLY || code != 0 || packet.payloadLength < ICMP_HEADER_SIZE) return
        val identifier = NetworkOrderBuffer(packet.bytes).readU16(packet.payloadOffset + 4)
        val source = Ipv4SocketAddress(packet.source, 0u)
        val recipients = lock.withLock { bindings[identifier]?.toList().orEmpty() }
            .filter { binding ->
                (binding.address.address.isAny || binding.address.address == packet.destination) &&
                    binding.socket.accepts(source)
            }
            .map(Binding::socket)
        if (recipients.isEmpty()) return
        val message = packet.bytes.copyOfRange(
            packet.payloadOffset,
            packet.payloadOffset + packet.payloadLength,
        )
        recipients.forEach { it.enqueue(message, source) }
    }

    private fun bindIdentifier(
        socket: IcmpSocket,
        address: Ipv4SocketAddress,
    ): VfsResult<Ipv4SocketAddress> {
        val entries = bindings.getOrPut(address.port) { mutableListOf() }
        if (entries.any {
                it.address.address.isAny || address.address.isAny ||
                    it.address.address == address.address
            }
        ) return VfsResult.Err(VfsError.ADDRESS_IN_USE)
        entries += Binding(socket, address)
        return VfsResult.Ok(address)
    }

    private const val ICMP_HEADER_SIZE = 8
    private const val ICMP_ECHO_REPLY = 0
}

internal class IcmpSocket internal constructor(
    private val subsystem: IcmpProtocol,
) : AbstractSocket(SocketDomain.IPV4, SocketType.DATAGRAM, IpProtocol.ICMP.number.toInt()) {
    private data class Datagram(val bytes: ByteArray, val source: Ipv4SocketAddress)

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
            when (val result = subsystem.bind(this, requested)) {
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
        if (destination.address.isAny || destination.address.isLimitedBroadcast ||
            destination.address.isMulticast
        ) return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        return lock.withLock {
            val local = ensureBoundLocked()
            if (local is VfsResult.Err) return@withLock local
            when (val path = NetworkStack.path(
                (local as VfsResult.Ok).value.address,
                destination.address,
            )) {
                is VfsResult.Ok -> {
                    selectedSource = path.value.source
                    peer = destination.copy(port = 0u)
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
        if (request.count < ICMP_HEADER_SIZE || request.count > MAX_MESSAGE_SIZE) {
            return IoResult.failure(VfsError.MESSAGE_TOO_LONG)
        }
        val explicit = request.destination?.let {
            it as? Ipv4SocketAddress
                ?: return IoResult.failure(VfsError.ADDRESS_FAMILY_NOT_SUPPORTED)
        }
        val prepared = lock.withLock {
            if (closed) return@withLock VfsResult.Err(VfsError.BAD_DESCRIPTOR)
            if (!writeOpen) return@withLock VfsResult.Err(VfsError.BROKEN_PIPE)
            val destination = explicit ?: peer
                ?: return@withLock VfsResult.Err(VfsError.DESTINATION_ADDRESS_REQUIRED)
            if (destination.address.isAny || destination.address.isLimitedBroadcast ||
                destination.address.isMulticast
            ) return@withLock VfsResult.Err(VfsError.INVALID_ARGUMENT)
            val local = ensureBoundLocked()
            if (local is VfsResult.Err) return@withLock local
            val bound = (local as VfsResult.Ok).value
            when (val path = NetworkStack.path(bound.address, destination.address)) {
                is VfsResult.Ok -> VfsResult.Ok(
                    Pair(Ipv4SocketAddress(path.value.source, bound.port), destination),
                )
                is VfsResult.Err -> path
            }
        }
        val endpoints = when (prepared) {
            is VfsResult.Ok -> prepared.value
            is VfsResult.Err -> return IoResult.failure(prepared.error)
        }
        val message = ByteArray(request.count)
        if (request.source.copyTo(request.offset, message, 0, request.count) != request.count) {
            return IoResult.failure(VfsError.FAULT)
        }
        val output = NetworkOrderBuffer(message)
        if (output.readU8(0).toInt() != ICMP_ECHO_REQUEST || output.readU8(1) != 0.toUByte()) {
            return IoResult.failure(VfsError.NOT_PERMITTED)
        }
        output.writeU16(2, 0u)
        output.writeU16(4, endpoints.first.port)
        output.writeU16(2, InternetChecksum.compute(message))
        return when (val result = NetworkStack.sendIpv4(
            endpoints.first.address,
            endpoints.second.address,
            IpProtocol.ICMP,
            message,
            ttl = ttl.toUByte(),
        )) {
            is VfsResult.Ok -> IoResult.success(request.count)
            is VfsResult.Err -> IoResult.failure(result.error)
        }
    }

    override fun receiveSocket(request: SocketReceiveRequest): VfsResult<SocketReceiveResult> {
        val deadline = request.deadline ?: if (request.nonBlocking) null
        else org.plos_clan.cpos.fs.sock.SocketDeadline.after(socketOptions().receiveTimeoutNanos)
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
                if (!readOpen || closed) return@withLock VfsResult.Err(VfsError.NOT_CONNECTED)
                if (request.nonBlocking) return@withLock VfsResult.Err(VfsError.WOULD_BLOCK)
                if (deadline == null) {
                    val thread = ProcessManager.currentThread()
                        ?: return@withLock VfsResult.Err(VfsError.NOT_FOUND)
                    waiter = readWaiters.add(thread)
                }
                null
            }
            if (result != null) return result
            val waitError = if (deadline != null) deadline.await(::receiveReady)
            else if (readWaiters.await(lock, checkNotNull(waiter))) null else VfsError.INTERRUPTED
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
            IP_TTL -> lock.withLock { ttl }
            IP_MTU -> {
                val endpoints = lock.withLock { selectedSource to peer }
                val remote = endpoints.second ?: return VfsResult.Err(VfsError.NOT_CONNECTED)
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
        peer == null || peer?.address == source.address
    }

    internal fun enqueue(bytes: ByteArray, source: Ipv4SocketAddress) = lock.withLock {
        if (closed || !readOpen || queuedBytes > optionsLocked().receiveBufferSize - bytes.size) {
            return@withLock
        }
        messages += Datagram(bytes, source)
        queuedBytes += bytes.size
        readWaiters.wakeOne()
    }

    override fun readableBytes(): Int = lock.withLock { messages.firstOrNull()?.bytes?.size ?: 0 }

    override fun pollSocket(events: Int): Int = lock.withLock {
        var available = 0
        if (messages.isNotEmpty() || !readOpen) available = available or PollEvents.NORMAL_INPUT
        if (writeOpen && !closed) available = available or PollEvents.NORMAL_OUTPUT
        if (closed) available = available or PollEvents.POLLHUP
        available and (events or PollEvents.UNCONDITIONALLY_REPORTED)
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
        )) {
            is VfsResult.Ok -> result.also { binding = it.value }
            is VfsResult.Err -> result
        }
    }

    private fun receiveReady(): Boolean = lock.withLock {
        messages.isNotEmpty() || !readOpen || closed
    }

    companion object {
        private const val ICMP_HEADER_SIZE = 8
        private const val ICMP_ECHO_REQUEST = 8
        private const val MAX_MESSAGE_SIZE = 65_515
        private const val DEFAULT_TTL = 64
        private const val SOL_IP = 0
        private const val IP_TTL = 2
        private const val IP_MTU_DISCOVER = 10
        private const val IP_RECVERR = 11
        private const val IP_RECVTTL = 12
        private const val IP_MTU = 14
    }
}
