package org.plos_clan.cpos.network

import kotlinx.coroutines.delay
import org.plos_clan.cpos.coroutines.KernelCoroutines
import org.plos_clan.cpos.drivers.TscClock
import org.plos_clan.cpos.fs.sock.AbstractSocket
import org.plos_clan.cpos.fs.sock.AcceptedSocket
import org.plos_clan.cpos.fs.sock.IoWaitQueue
import org.plos_clan.cpos.fs.sock.SocketAddress
import org.plos_clan.cpos.fs.sock.SocketDomain
import org.plos_clan.cpos.fs.sock.SocketOptions
import org.plos_clan.cpos.fs.sock.SocketReceiveRequest
import org.plos_clan.cpos.fs.sock.SocketReceiveResult
import org.plos_clan.cpos.fs.sock.SocketSendRequest
import org.plos_clan.cpos.fs.sock.SocketShutdownMode
import org.plos_clan.cpos.fs.sock.SocketType
import org.plos_clan.cpos.fs.vfs.ByteCircularBuffer
import org.plos_clan.cpos.fs.vfs.IoResult
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.mem.ByteArrayBuffer
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.utils.IrqSpinLock
import org.plos_clan.cpos.utils.KernelRandom
import org.plos_clan.cpos.utils.LittleEndianBuffer
import org.plos_clan.cpos.utils.PollEvents
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

private object TcpSequence {
    fun before(first: UInt, second: UInt): Boolean = (first - second).toInt() < 0

    fun after(first: UInt, second: UInt): Boolean = before(second, first)

    fun between(value: UInt, first: UInt, last: UInt): Boolean =
        !before(value, first) && !after(value, last)

    fun distance(first: UInt, second: UInt): UInt = second - first
}

internal data class TcpTransmission(
    val source: Ipv4SocketAddress,
    val destination: Ipv4SocketAddress,
    val sequenceNumber: UInt,
    val acknowledgmentNumber: UInt,
    val flags: Int,
    val window: UShort,
    val options: ByteArray = ByteArray(0),
    val payload: ByteArray = ByteArray(0),
    val ttl: UByte = 64u,
)

@OptIn(ExperimentalAtomicApi::class)
internal object TcpProtocol : IpProtocolHandler {
    private data class Binding(
        val socket: TcpSocket,
        val address: Ipv4SocketAddress,
        val reuseAddress: Boolean,
    )

    private data class ConnectionKey(
        val local: Ipv4SocketAddress,
        val remote: Ipv4SocketAddress,
    )

    override val protocol = IpProtocol.TCP
    private val initialized = AtomicBoolean(false)
    private val lock = IrqSpinLock()
    private val bindings = mutableMapOf<UShort, MutableList<Binding>>()
    private val listeners = mutableMapOf<UShort, MutableList<TcpSocket>>()
    private val connections = mutableMapOf<ConnectionKey, TcpSocket>()
    private val nextEphemeralPort = AtomicInt(EPHEMERAL_PORT_FIRST)

    fun initialize() {
        if (initialized.compareAndSet(false, true)) {
            NetworkStack.registerHandler(this)
            KernelCoroutines.launch("tcp-timers") {
                while (true) {
                    delay(TIMER_INTERVAL_MILLIS)
                    val sockets = lock.withLock { connections.values.distinct() }
                    sockets.forEach(TcpSocket::tick)
                }
            }
        }
    }

    fun createSocket(): TcpSocket {
        initialize()
        return TcpSocket(this)
    }

    fun bind(
        socket: TcpSocket,
        requested: Ipv4SocketAddress,
        reuseAddress: Boolean,
    ): VfsResult<Ipv4SocketAddress> = lock.withLock {
        if (requested.port != 0.toUShort()) return@withLock bindPort(
            socket,
            requested,
            reuseAddress,
        )
        repeat(EPHEMERAL_PORT_COUNT) {
            val value = nextEphemeralPort.fetchAndAdd(1)
            val normalized = EPHEMERAL_PORT_FIRST +
                (value.toUInt() % EPHEMERAL_PORT_COUNT.toUInt()).toInt()
            val result = bindPort(
                socket,
                requested.copy(port = normalized.toUShort()),
                reuseAddress,
            )
            if (result is VfsResult.Ok) return@withLock result
        }
        VfsResult.Err(VfsError.ADDRESS_IN_USE)
    }

    fun listen(socket: TcpSocket, address: Ipv4SocketAddress): VfsResult<Unit> = lock.withLock {
        val entries = listeners.getOrPut(address.port) { mutableListOf() }
        if (socket !in entries) entries += socket
        VfsResult.Ok(Unit)
    }

    fun registerConnection(
        socket: TcpSocket,
        local: Ipv4SocketAddress,
        remote: Ipv4SocketAddress,
    ): VfsResult<Unit> = lock.withLock {
        val key = ConnectionKey(local, remote)
        val current = connections[key]
        if (current != null && current !== socket) return@withLock VfsResult.Err(
            VfsError.ADDRESS_IN_USE,
        )
        connections[key] = socket
        VfsResult.Ok(Unit)
    }

    fun unregister(socket: TcpSocket) = lock.withLock {
        connections.entries.removeAll { it.value === socket }
        listeners.entries.removeAll { (_, entries) ->
            entries.removeAll { it === socket }
            entries.isEmpty()
        }
        bindings.entries.removeAll { (_, entries) ->
            entries.removeAll { it.socket === socket }
            entries.isEmpty()
        }
    }

    override fun receive(packet: IpPacketContext) {
        val segment = TcpCodec.decode(
            packet.bytes,
            packet.payloadOffset,
            packet.payloadLength,
            packet.source,
            packet.destination,
        ) ?: return
        val local = Ipv4SocketAddress(packet.destination, segment.destinationPort)
        val remote = Ipv4SocketAddress(packet.source, segment.sourcePort)
        val connection = lock.withLock { connections[ConnectionKey(local, remote)] }
        if (connection != null) {
            connection.receiveSegment(packet, segment)
            return
        }
        val listenerCandidates = lock.withLock { listeners[local.port]?.toList().orEmpty() }
        val listener = listenerCandidates.firstOrNull {
            val bound = it.boundAddress()
            bound.address.isAny || bound.address == local.address
        }
        if (listener != null && segment.flags and TcpFlags.SYN != 0 &&
            segment.flags and TcpFlags.ACK == 0
        ) {
            listener.receiveSyn(packet, segment, local, remote)
            return
        }
        sendReset(local, remote, segment)
    }

    override fun receiveError(packet: IpPacketContext, error: IpTransportError) {
        if (packet.payloadLength < TcpCodec.MIN_HEADER_SIZE) return
        val input = NetworkOrderBuffer(packet.bytes)
        val local = Ipv4SocketAddress(packet.source, input.readU16(packet.payloadOffset))
        val remote = Ipv4SocketAddress(
            packet.destination,
            input.readU16(packet.payloadOffset + 2),
        )
        val socket = lock.withLock { connections[ConnectionKey(local, remote)] } ?: return
        socket.reportError(
            when (error) {
                IpTransportError.NETWORK_UNREACHABLE -> VfsError.NETWORK_UNREACHABLE
                IpTransportError.HOST_UNREACHABLE -> VfsError.HOST_UNREACHABLE
                IpTransportError.PORT_UNREACHABLE -> VfsError.CONNECTION_REFUSED
                IpTransportError.FRAGMENTATION_NEEDED -> VfsError.MESSAGE_TOO_LONG
                IpTransportError.PROTOCOL_UNREACHABLE -> VfsError.PROTOCOL_NOT_SUPPORTED
                IpTransportError.TIME_EXCEEDED -> VfsError.TIMED_OUT
            },
        )
    }

    fun transmit(transmission: TcpTransmission): VfsResult<Unit> {
        val segment = ByteArray(
            TcpCodec.MIN_HEADER_SIZE + transmission.options.size + transmission.payload.size,
        )
        transmission.payload.copyInto(
            segment,
            TcpCodec.MIN_HEADER_SIZE + transmission.options.size,
        )
        TcpCodec.write(
            segment,
            0,
            transmission.payload.size,
            transmission.source,
            transmission.destination,
            transmission.sequenceNumber,
            transmission.acknowledgmentNumber,
            transmission.flags,
            transmission.window,
            transmission.options,
        )
        return when (val result = NetworkStack.sendIpv4(
            transmission.source.address,
            transmission.destination.address,
            IpProtocol.TCP,
            segment,
            dontFragment = true,
            ttl = transmission.ttl,
        )) {
            is VfsResult.Ok -> VfsResult.Ok(Unit)
            is VfsResult.Err -> result
        }
    }

    private fun bindPort(
        socket: TcpSocket,
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

    private fun sendReset(
        local: Ipv4SocketAddress,
        remote: Ipv4SocketAddress,
        segment: TcpSegment,
    ) {
        if (segment.flags and TcpFlags.RST != 0) return
        val acknowledges = segment.flags and TcpFlags.ACK == 0
        val consumed = segment.payloadLength +
            (if (segment.flags and TcpFlags.SYN != 0) 1 else 0) +
            (if (segment.flags and TcpFlags.FIN != 0) 1 else 0)
        transmit(
            TcpTransmission(
                local,
                remote,
                if (acknowledges) 0u else segment.acknowledgmentNumber,
                if (acknowledges) segment.sequenceNumber + consumed.toUInt() else 0u,
                TcpFlags.RST or if (acknowledges) TcpFlags.ACK else 0,
                0u,
            ),
        )
    }

    private const val EPHEMERAL_PORT_FIRST = 32_768
    private const val EPHEMERAL_PORT_LAST = 60_999
    private const val EPHEMERAL_PORT_COUNT = EPHEMERAL_PORT_LAST - EPHEMERAL_PORT_FIRST + 1
    private const val TIMER_INTERVAL_MILLIS = 100L
}

internal class TcpSocket internal constructor(
    private val subsystem: TcpProtocol,
) : AbstractSocket(SocketDomain.IPV4, SocketType.STREAM, IpProtocol.TCP.number.toInt()) {
    private enum class State {
        IDLE,
        BOUND,
        LISTEN,
        SYN_SENT,
        SYN_RECEIVED,
        ESTABLISHED,
        FIN_WAIT_1,
        FIN_WAIT_2,
        CLOSING,
        CLOSE_WAIT,
        LAST_ACK,
        TIME_WAIT,
        RESET,
    }

    private class Outstanding(
        var sequence: UInt,
        var flags: Int,
        var payload: ByteArray,
        val options: ByteArray,
        var sentAt: ULong,
    ) {
        var retransmissions = 0

        val endSequence: UInt
            get() = sequence + sequenceLength.toUInt()

        val sequenceLength: Int
            get() = payload.size +
                (if (flags and TcpFlags.SYN != 0) 1 else 0) +
                (if (flags and TcpFlags.FIN != 0) 1 else 0)

        fun trim(acknowledgment: UInt) {
            var consumed = TcpSequence.distance(sequence, acknowledgment).toInt()
            if (consumed <= 0) return
            if (flags and TcpFlags.SYN != 0) {
                flags = flags and TcpFlags.SYN.inv()
                sequence++
                consumed--
            }
            if (consumed > 0 && payload.isNotEmpty()) {
                val bytes = minOf(consumed, payload.size)
                payload = payload.copyOfRange(bytes, payload.size)
                sequence += bytes.toUInt()
                consumed -= bytes
            }
            if (consumed > 0 && flags and TcpFlags.FIN != 0) {
                flags = flags and TcpFlags.FIN.inv()
                sequence++
            }
        }

        fun transmission(
            source: Ipv4SocketAddress,
            destination: Ipv4SocketAddress,
            acknowledgment: UInt,
            window: UShort,
            ttl: UByte,
        ) = TcpTransmission(
            source,
            destination,
            sequence,
            acknowledgment,
            flags,
            window,
            options,
            payload,
            ttl,
        )
    }

    private data class OutOfOrder(
        val sequence: UInt,
        val payload: ByteArray,
        val fin: Boolean,
    )

    private data class SegmentActions(
        val transmissions: List<TcpTransmission> = emptyList(),
        val unregister: Boolean = false,
        val accepted: Boolean = false,
    )

    private var state = State.IDLE
    private var binding: Ipv4SocketAddress? = null
    private var local = Ipv4SocketAddress(Ipv4Address.ANY, 0u)
    private var remote: Ipv4SocketAddress? = null
    private var parent: TcpSocket? = null
    private var backlog = 0
    private var children = mutableSetOf<TcpSocket>()
    private var accepted = ArrayDeque<TcpSocket>()
    private val receiveBuffer = ByteCircularBuffer(DEFAULT_BUFFER_SIZE)
    private val sendBuffer = ByteCircularBuffer(DEFAULT_BUFFER_SIZE)
    private val outstanding = ArrayDeque<Outstanding>()
    private val outOfOrder = mutableListOf<OutOfOrder>()
    private val readWaiters = IoWaitQueue()
    private val writeWaiters = IoWaitQueue()
    private val connectWaiters = IoWaitQueue()
    private val acceptWaiters = IoWaitQueue()
    private var iss = 0u
    private var sndUna = 0u
    private var sndNxt = 0u
    private var sndWnd = UShort.MAX_VALUE.toUInt()
    private var sndWl1 = 0u
    private var sndWl2 = 0u
    private var rcvNxt = 0u
    private var peerMss = DEFAULT_MSS
    private var localMss = DEFAULT_MSS
    private var peerWindowScale = 0
    private var localWindowScale = 0
    private var congestionWindow = (INITIAL_CONGESTION_SEGMENTS * DEFAULT_MSS).toUInt()
    private var slowStartThreshold = UInt.MAX_VALUE
    private var readOpen = true
    private var writeOpen = true
    private var finQueued = false
    private var noDelay = false
    private var requestedMss: Int? = null
    private var ttl = DEFAULT_TTL
    private var timeWaitUntil = 0uL
    private var finWaitUntil = 0uL
    private var corkUntil = 0uL

    override fun bindSocket(process: Process, address: SocketAddress): VfsResult<Unit> {
        val requested = address as? Ipv4SocketAddress
            ?: return VfsResult.Err(VfsError.ADDRESS_FAMILY_NOT_SUPPORTED)
        if (!requested.address.isAny && !NetworkStack.isLocalAddress(requested.address)) {
            return VfsResult.Err(VfsError.ADDRESS_NOT_AVAILABLE)
        }
        return lock.withLock {
            if (closed) return@withLock VfsResult.Err(VfsError.BAD_DESCRIPTOR)
            if (state != State.IDLE) return@withLock VfsResult.Err(VfsError.INVALID_ARGUMENT)
            when (val result = subsystem.bind(this, requested, optionsLocked().reuseAddress)) {
                is VfsResult.Ok -> {
                    binding = result.value
                    local = result.value
                    state = State.BOUND
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
        val destination = address as? Ipv4SocketAddress
            ?: return VfsResult.Err(
                if (address == null) VfsError.ADDRESS_FAMILY_NOT_SUPPORTED
                else VfsError.ADDRESS_FAMILY_NOT_SUPPORTED,
            )
        if (destination.address.isAny || destination.address.isLimitedBroadcast ||
            destination.address.isMulticast || destination.port == 0.toUShort()
        ) return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        val started = lock.withLock {
            when (state) {
                State.SYN_SENT,
                State.SYN_RECEIVED,
                -> return@withLock VfsResult.Err(VfsError.ALREADY_IN_PROGRESS)
                State.ESTABLISHED,
                State.FIN_WAIT_1,
                State.FIN_WAIT_2,
                State.CLOSE_WAIT,
                -> return@withLock VfsResult.Err(VfsError.ALREADY_CONNECTED)
                State.IDLE,
                State.BOUND,
                -> Unit
                else -> return@withLock VfsResult.Err(VfsError.INVALID_ARGUMENT)
            }
            val bound = ensureBoundLocked()
            if (bound is VfsResult.Err) return@withLock bound
            val path = NetworkStack.path((bound as VfsResult.Ok).value.address, destination.address)
            if (path is VfsResult.Err) return@withLock path
            val selectedPath = (path as VfsResult.Ok).value
            local = Ipv4SocketAddress(selectedPath.source, bound.value.port)
            remote = destination
            localMss = requestedMss?.coerceAtMost(selectedPath.mtu - IPV4_TCP_HEADER_SIZE)
                ?: (selectedPath.mtu - IPV4_TCP_HEADER_SIZE)
            localMss = localMss.coerceIn(MINIMUM_MSS, UShort.MAX_VALUE.toInt())
            localWindowScale = windowScale(optionsLocked().receiveBufferSize)
            peerMss = DEFAULT_MSS
            peerWindowScale = 0
            iss = randomSequence()
            sndUna = iss
            sndNxt = iss + 1u
            sndWl1 = 0u
            sndWl2 = 0u
            rcvNxt = 0u
            congestionWindow = (INITIAL_CONGESTION_SEGMENTS * localMss).toUInt()
            state = State.SYN_SENT
            val registered = subsystem.registerConnection(this, local, destination)
            if (registered is VfsResult.Err) {
                state = State.BOUND
                return@withLock registered
            }
            val options = TcpCodec.synOptions(localMss.toUShort(), localWindowScale.toUByte())
            val segment = Outstanding(
                iss,
                TcpFlags.SYN,
                ByteArray(0),
                options,
                TscClock.nanoTime(),
            )
            outstanding += segment
            VfsResult.Ok(
                segment.transmission(local, destination, 0u, advertisedWindowLocked(), ttl.toUByte()),
            )
        }
        val transmission = when (started) {
            is VfsResult.Ok -> started.value
            is VfsResult.Err -> return started
        }
        when (val sent = subsystem.transmit(transmission)) {
            is VfsResult.Ok -> Unit
            is VfsResult.Err -> {
                abort(sent.error)
                return sent
            }
        }
        if (nonBlocking) return VfsResult.Err(VfsError.IN_PROGRESS)
        while (true) {
            var waiter: IoWaitQueue.Waiter? = null
            val result = lock.withLock {
                when (state) {
                    State.ESTABLISHED -> VfsResult.Ok(Unit)
                    State.RESET -> VfsResult.Err(takeErrorLocked() ?: VfsError.CONNECTION_REFUSED)
                    State.SYN_SENT,
                    State.SYN_RECEIVED,
                    -> {
                        val thread = ProcessManager.currentThread()
                            ?: return@withLock VfsResult.Err(VfsError.NOT_FOUND)
                        waiter = connectWaiters.add(thread)
                        null
                    }
                    else -> VfsResult.Err(VfsError.NOT_CONNECTED)
                }
            }
            if (result != null) return result
            if (!connectWaiters.await(lock, checkNotNull(waiter))) {
                return VfsResult.Err(VfsError.INTERRUPTED)
            }
        }
    }

    override fun listenSocket(process: Process, backlog: Int): VfsResult<Unit> {
        val registered = lock.withLock {
            if (state != State.IDLE && state != State.BOUND && state != State.LISTEN) {
                return@withLock VfsResult.Err(VfsError.INVALID_ARGUMENT)
            }
            val bound = ensureBoundLocked()
            if (bound is VfsResult.Err) return@withLock bound
            this.backlog = backlog.coerceIn(1, MAX_BACKLOG)
            state = State.LISTEN
            VfsResult.Ok((bound as VfsResult.Ok).value)
        }
        return when (registered) {
            is VfsResult.Ok -> subsystem.listen(this, registered.value)
            is VfsResult.Err -> registered
        }
    }

    override fun acceptSocket(process: Process, nonBlocking: Boolean): VfsResult<AcceptedSocket> {
        while (true) {
            var waiter: IoWaitQueue.Waiter? = null
            val result = lock.withLock {
                if (state != State.LISTEN) return@withLock VfsResult.Err(VfsError.INVALID_ARGUMENT)
                val child = accepted.removeFirstOrNull()
                if (child != null) {
                    children.remove(child)
                    child.parent = null
                    return@withLock VfsResult.Ok(
                        AcceptedSocket(child, checkNotNull(child.remote)),
                    )
                }
                if (nonBlocking) return@withLock VfsResult.Err(VfsError.WOULD_BLOCK)
                val thread = ProcessManager.currentThread()
                    ?: return@withLock VfsResult.Err(VfsError.NOT_FOUND)
                waiter = acceptWaiters.add(thread)
                null
            }
            if (result != null) return result
            if (!acceptWaiters.await(lock, checkNotNull(waiter))) {
                return VfsResult.Err(VfsError.INTERRUPTED)
            }
        }
    }

    override fun localAddress(): Ipv4SocketAddress = lock.withLock { local }

    override fun peerAddress(): VfsResult<Ipv4SocketAddress> = lock.withLock {
        remote?.let { VfsResult.Ok(it) } ?: VfsResult.Err(VfsError.NOT_CONNECTED)
    }

    override fun isListening(): Boolean = lock.withLock { state == State.LISTEN }

    override fun shutdownSocket(mode: SocketShutdownMode): VfsResult<Unit> {
        var transmissions = emptyList<TcpTransmission>()
        val result = lock.withLock {
            if (state !in CONNECTED_STATES) return@withLock VfsResult.Err(VfsError.NOT_CONNECTED)
            if (mode.reads) {
                readOpen = false
                receiveBuffer.clear()
                outOfOrder.clear()
                readWaiters.wakeAll()
            }
            if (mode.writes && writeOpen) {
                writeOpen = false
                transmissions = flushLocked() + queueFinLocked()
                writeWaiters.wakeAll()
            }
            VfsResult.Ok(Unit)
        }
        transmissions.forEach(subsystem::transmit)
        return result
    }

    override fun sendSocket(request: SocketSendRequest): IoResult {
        request.ancillary.release()
        if (request.destination != null) return IoResult.failure(VfsError.ALREADY_CONNECTED)
        var transferred = 0
        while (transferred < request.count) {
            var waiter: IoWaitQueue.Waiter? = null
            var transmissions = emptyList<TcpTransmission>()
            val result = lock.withLock {
                if (state !in SEND_STATES || !writeOpen) {
                    return@withLock IoResult.failure(
                        if (state == State.RESET) takeErrorLocked() ?: VfsError.CONNECTION_RESET
                        else VfsError.BROKEN_PIPE,
                    )
                }
                val capacity = optionsLocked().sendBufferSize - queuedSendBytesLocked()
                if (capacity > 0) {
                    val count = minOf(request.count - transferred, capacity)
                    val written = sendBuffer.write(request.source, request.offset + transferred, count)
                    if (written == 0 && count != 0) return@withLock IoResult.failure(VfsError.FAULT)
                    transferred += written
                    corkUntil = if (request.more) {
                        TscClock.nanoTime() + CORK_TIMEOUT_NANOS
                    } else {
                        0uL
                    }
                    transmissions = flushLocked()
                    return@withLock IoResult.success(transferred)
                }
                if (request.nonBlocking) return@withLock if (transferred == 0) {
                    IoResult.failure(VfsError.WOULD_BLOCK)
                } else {
                    IoResult.success(transferred)
                }
                val thread = ProcessManager.currentThread()
                    ?: return@withLock IoResult.failure(VfsError.NOT_FOUND)
                waiter = writeWaiters.add(thread)
                null
            }
            transmissions.forEach(subsystem::transmit)
            if (result != null && (transferred != 0 || !result.isSuccess)) return result
            if (transferred == request.count) return IoResult.success(transferred)
            if (waiter != null && !writeWaiters.await(lock, checkNotNull(waiter))) {
                return if (transferred == 0) IoResult.failure(VfsError.INTERRUPTED)
                else IoResult.success(transferred)
            }
        }
        return IoResult.success(transferred)
    }

    override fun receiveSocket(request: SocketReceiveRequest): VfsResult<SocketReceiveResult> {
        val deadline = request.deadline ?: if (request.nonBlocking) null
        else org.plos_clan.cpos.fs.sock.SocketDeadline.after(socketOptions().receiveTimeoutNanos)
        while (true) {
            var waiter: IoWaitQueue.Waiter? = null
            var windowUpdate: TcpTransmission? = null
            val result = lock.withLock {
                val available = receiveBuffer.size
                val shouldWait = request.waitAll && available < request.count && readOpen &&
                    state != State.RESET
                if (available != 0 && !shouldWait || available >= request.count) {
                    val copied = receiveBuffer.read(
                        request.destination,
                        request.offset,
                        request.count,
                        request.peek,
                    )
                    if (!request.peek && copied != 0 && remote != null) {
                        windowUpdate = acknowledgmentLocked()
                    }
                    return@withLock VfsResult.Ok(
                        SocketReceiveResult(
                            copied,
                            copied,
                            remote ?: Ipv4SocketAddress(Ipv4Address.ANY, 0u),
                        ),
                    )
                }
                if (!readOpen) return@withLock VfsResult.Ok(
                    SocketReceiveResult(
                        0,
                        0,
                        remote ?: Ipv4SocketAddress(Ipv4Address.ANY, 0u),
                    ),
                )
                takeErrorLocked()?.let { return@withLock VfsResult.Err(it) }
                if (state !in RECEIVE_STATES) return@withLock VfsResult.Err(VfsError.NOT_CONNECTED)
                if (request.nonBlocking) return@withLock VfsResult.Err(VfsError.WOULD_BLOCK)
                if (deadline == null) {
                    val thread = ProcessManager.currentThread()
                        ?: return@withLock VfsResult.Err(VfsError.NOT_FOUND)
                    waiter = readWaiters.add(thread, if (request.waitAll) request.count else 1)
                }
                null
            }
            windowUpdate?.let(subsystem::transmit)
            if (result != null) return result
            val waitError = if (deadline != null) deadline.await {
                lock.withLock {
                    receiveBuffer.size >= (if (request.waitAll) request.count else 1) ||
                        !readOpen || state == State.RESET
                }
            } else if (readWaiters.await(lock, checkNotNull(waiter))) null else VfsError.INTERRUPTED
            if (waitError != null) return VfsResult.Err(waitError)
        }
    }

    override fun setProtocolOption(level: Int, name: Int, value: ByteArray): VfsResult<Unit> {
        if (value.size < Int.SIZE_BYTES) return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        val requested = LittleEndianBuffer(value).readU32(0).toInt()
        return when {
            level == IPPROTO_TCP && name == TCP_NODELAY -> {
                val transmissions = lock.withLock {
                    noDelay = requested != 0
                    flushLocked()
                }
                transmissions.forEach(subsystem::transmit)
                VfsResult.Ok(Unit)
            }
            level == IPPROTO_TCP && name == TCP_MAXSEG -> {
                if (requested !in MINIMUM_MSS..UShort.MAX_VALUE.toInt()) {
                    VfsResult.Err(VfsError.INVALID_ARGUMENT)
                } else {
                    lock.withLock {
                        if (state !in setOf(State.IDLE, State.BOUND, State.LISTEN)) {
                            return@withLock VfsResult.Err(VfsError.INVALID_ARGUMENT)
                        }
                        requestedMss = requested
                        VfsResult.Ok(Unit)
                    }
                }
            }
            level == SOL_IP && name == IP_TTL -> {
                if (requested !in 1..UByte.MAX_VALUE.toInt()) {
                    VfsResult.Err(VfsError.INVALID_ARGUMENT)
                } else {
                    lock.withLock { ttl = requested }
                    VfsResult.Ok(Unit)
                }
            }
            else -> VfsResult.Err(VfsError.PROTOCOL_OPTION_NOT_AVAILABLE)
        }
    }

    override fun getProtocolOption(level: Int, name: Int): VfsResult<ByteArray> {
        val value = when {
            level == IPPROTO_TCP && name == TCP_NODELAY -> lock.withLock { if (noDelay) 1 else 0 }
            level == IPPROTO_TCP && name == TCP_MAXSEG -> lock.withLock { peerMss }
            level == SOL_IP && name == IP_TTL -> lock.withLock { ttl }
            level == SOL_IP && name == IP_MTU -> lock.withLock {
                val destination = remote ?: return@withLock null
                when (val path = NetworkStack.path(local.address, destination.address)) {
                    is VfsResult.Ok -> path.value.mtu
                    is VfsResult.Err -> null
                }
            } ?: return VfsResult.Err(VfsError.NOT_CONNECTED)
            else -> return VfsResult.Err(VfsError.PROTOCOL_OPTION_NOT_AVAILABLE)
        }
        return VfsResult.Ok(ByteArray(Int.SIZE_BYTES).also {
            LittleEndianBuffer(it).writeU32(0, value.toUInt())
        })
    }

    internal fun boundAddress(): Ipv4SocketAddress = lock.withLock {
        binding ?: Ipv4SocketAddress(Ipv4Address.ANY, 0u)
    }

    internal fun receiveSyn(
        packet: IpPacketContext,
        segment: TcpSegment,
        local: Ipv4SocketAddress,
        remote: Ipv4SocketAddress,
    ) {
        val reserved = lock.withLock {
            if (state != State.LISTEN || children.size >= backlog) return@withLock false
            true
        }
        if (!reserved) return
        val child = TcpSocket(subsystem)
        child.inheritOptions(socketOptions())
        val transmission = child.initializePassive(
            this,
            packet.interface_.mtu,
            segment,
            local,
            remote,
        ) ?: return
        val accepted = lock.withLock {
            if (state != State.LISTEN || children.size >= backlog) return@withLock false
            children += child
            true
        }
        if (!accepted || subsystem.registerConnection(child, local, remote) is VfsResult.Err) {
            child.abort(VfsError.CONNECTION_ABORTED)
            return
        }
        subsystem.transmit(transmission)
    }

    internal fun receiveSegment(packet: IpPacketContext, segment: TcpSegment) {
        val actions = lock.withLock { processSegmentLocked(packet, segment) }
        actions.transmissions.forEach(subsystem::transmit)
        if (actions.accepted) parent?.established(this)
        if (actions.unregister) {
            subsystem.unregister(this)
            parent?.removeChild(this)
        }
    }

    internal fun reportError(error: VfsError) {
        setError(error)
        lock.withLock {
            connectWaiters.wakeAll()
            readWaiters.wakeAll()
            writeWaiters.wakeAll()
        }
    }

    internal fun tick() {
        var unregister = false
        val transmissions = lock.withLock {
            val now = TscClock.nanoTime()
            if (state == State.TIME_WAIT) {
                if (now >= timeWaitUntil) {
                    state = State.RESET
                    unregister = true
                }
                return@withLock emptyList()
            }
            if (state == State.FIN_WAIT_2 && now >= finWaitUntil) {
                state = State.RESET
                unregister = true
                return@withLock emptyList()
            }
            val first = outstanding.firstOrNull() ?: return@withLock flushLocked()
            val timeout = INITIAL_RETRANSMISSION_NANOS shl minOf(first.retransmissions, 5)
            if (now - first.sentAt < timeout) return@withLock flushLocked()
            if (first.retransmissions >= MAX_RETRANSMISSIONS) {
                state = State.RESET
                storeErrorLocked(VfsError.TIMED_OUT)
                wakeAllLocked()
                unregister = true
                return@withLock emptyList()
            }
            first.retransmissions++
            first.sentAt = now
            slowStartThreshold = maxOf(congestionWindow / 2u, (2 * peerMss).toUInt())
            congestionWindow = peerMss.toUInt()
            listOf(
                first.transmission(
                    local,
                    checkNotNull(remote),
                    rcvNxt,
                    advertisedWindowLocked(),
                    ttl.toUByte(),
                ),
            )
        }
        transmissions.forEach(subsystem::transmit)
        if (unregister) subsystem.unregister(this)
    }

    override fun readableBytes(): Int = lock.withLock { receiveBuffer.size }

    override fun pollSocket(events: Int): Int = lock.withLock {
        var available = 0
        if (state == State.LISTEN) {
            if (accepted.isNotEmpty()) available = available or PollEvents.NORMAL_INPUT
        } else {
            if (receiveBuffer.size != 0 || !readOpen) available = available or PollEvents.NORMAL_INPUT
            if (state in SEND_STATES && writeOpen &&
                queuedSendBytesLocked() < optionsLocked().sendBufferSize
            ) available = available or PollEvents.NORMAL_OUTPUT
        }
        if (hasPendingError()) available = available or PollEvents.POLLERR
        if (state == State.RESET || closed) available = available or PollEvents.POLLHUP
        available and (events or PollEvents.UNCONDITIONALLY_REPORTED)
    }

    override fun sendBufferSizeChangedLocked(size: Int) {
        sendBuffer.ensureCapacity(size)
        writeWaiters.wakeAll()
    }

    override fun receiveBufferSizeChangedLocked(size: Int) {
        receiveBuffer.ensureCapacity(size)
    }

    override fun optionsChangedLocked(options: SocketOptions) {
        if (state in setOf(State.IDLE, State.BOUND, State.LISTEN)) {
            localWindowScale = windowScale(options.receiveBufferSize)
        }
    }

    override fun closeSocketLocked(): (() -> Unit)? {
        readOpen = false
        writeOpen = false
        readWaiters.wakeAll()
        writeWaiters.wakeAll()
        connectWaiters.wakeAll()
        acceptWaiters.wakeAll()
        if (state == State.LISTEN) {
            val abandoned = children.toList()
            children.clear()
            accepted.clear()
            state = State.RESET
            return {
                subsystem.unregister(this)
                abandoned.forEach { it.abort(VfsError.CONNECTION_ABORTED) }
            }
        }
        val abortive = optionsLocked().linger.let { it.enabled && it.seconds == 0 } ||
            receiveBuffer.size != 0
        if (abortive && remote != null && state in CONNECTED_STATES) {
            val reset = TcpTransmission(
                local,
                checkNotNull(remote),
                sndNxt,
                rcvNxt,
                TcpFlags.RST or TcpFlags.ACK,
                0u,
                ttl = ttl.toUByte(),
            )
            receiveBuffer.clear()
            sendBuffer.clear()
            outstanding.clear()
            outOfOrder.clear()
            state = State.RESET
            return {
                subsystem.transmit(reset)
                subsystem.unregister(this)
            }
        }
        if (state == State.IDLE || state == State.BOUND || state == State.RESET) {
            state = State.RESET
            return if (binding == null) null else ({ subsystem.unregister(this) })
        }
        val transmissions = flushLocked() + queueFinLocked()
        return { transmissions.forEach(subsystem::transmit) }
    }

    private fun initializePassive(
        parent: TcpSocket,
        interfaceMtu: Int,
        segment: TcpSegment,
        local: Ipv4SocketAddress,
        remote: Ipv4SocketAddress,
    ): TcpTransmission? = lock.withLock {
        this.parent = parent
        this.binding = local
        this.local = local
        this.remote = remote
        localMss = (requestedMss ?: interfaceMtu - IPV4_TCP_HEADER_SIZE)
            .coerceIn(MINIMUM_MSS, UShort.MAX_VALUE.toInt())
        peerMss = (segment.options.maximumSegmentSize?.toInt() ?: DEFAULT_MSS)
            .coerceAtLeast(MINIMUM_MSS)
        peerWindowScale = segment.options.windowScale?.toInt() ?: 0
        localWindowScale = windowScale(optionsLocked().receiveBufferSize)
        sndWnd = segment.window.toUInt()
        sndWl1 = segment.sequenceNumber
        sndWl2 = segment.acknowledgmentNumber
        rcvNxt = segment.sequenceNumber + 1u
        iss = randomSequence()
        sndUna = iss
        sndNxt = iss + 1u
        congestionWindow = (INITIAL_CONGESTION_SEGMENTS * minOf(localMss, peerMss)).toUInt()
        state = State.SYN_RECEIVED
        val options = TcpCodec.synOptions(localMss.toUShort(), localWindowScale.toUByte())
        val response = Outstanding(
            iss,
            TcpFlags.SYN or TcpFlags.ACK,
            ByteArray(0),
            options,
            TscClock.nanoTime(),
        )
        outstanding += response
        response.transmission(
            local,
            remote,
            rcvNxt,
            advertisedWindowLocked(),
            ttl.toUByte(),
        )
    }

    private fun processSegmentLocked(
        packet: IpPacketContext,
        segment: TcpSegment,
    ): SegmentActions {
        if (state == State.SYN_SENT) return processSynSentLocked(packet, segment)
        if (state !in setOf(State.IDLE, State.BOUND, State.LISTEN, State.RESET) &&
            !segmentAcceptableLocked(segment)
        ) {
            return SegmentActions(
                if (segment.flags and TcpFlags.RST == 0) listOf(acknowledgmentLocked())
                else emptyList(),
            )
        }
        if (segment.flags and TcpFlags.RST != 0) {
            if (state == State.TIME_WAIT) return SegmentActions()
            if (segment.sequenceNumber != rcvNxt) {
                return SegmentActions(listOf(acknowledgmentLocked()))
            }
            val connecting = state == State.SYN_RECEIVED
            state = State.RESET
            readOpen = false
            writeOpen = false
            storeErrorLocked(
                if (connecting) VfsError.CONNECTION_REFUSED else VfsError.CONNECTION_RESET,
            )
            wakeAllLocked()
            return SegmentActions(unregister = true)
        }
        if (state == State.SYN_RECEIVED && segment.flags and TcpFlags.SYN != 0 &&
            segment.sequenceNumber + 1u == rcvNxt
        ) {
            val synAck = outstanding.firstOrNull()
            return SegmentActions(
                synAck?.let {
                    listOf(
                        it.transmission(
                            local,
                            checkNotNull(remote),
                            rcvNxt,
                            advertisedWindowLocked(),
                            ttl.toUByte(),
                        ),
                    )
                }.orEmpty(),
            )
        }
        if (segment.flags and TcpFlags.SYN != 0) {
            return SegmentActions(listOf(acknowledgmentLocked()))
        }
        if (state == State.TIME_WAIT) {
            if (segment.flags and TcpFlags.FIN != 0) timeWaitUntil = TscClock.nanoTime() + TIME_WAIT_NANOS
            return SegmentActions(listOf(acknowledgmentLocked()))
        }
        if (state == State.RESET || state == State.IDLE || state == State.BOUND ||
            state == State.LISTEN
        ) return SegmentActions()
        if (segment.flags and TcpFlags.ACK == 0) return SegmentActions()
        if (!TcpSequence.between(segment.acknowledgmentNumber, sndUna, sndNxt)) {
            return SegmentActions(listOf(acknowledgmentLocked()))
        }
        val acknowledgments = acknowledgeLocked(segment)
        if (state == State.SYN_RECEIVED) {
            if (segment.acknowledgmentNumber != sndNxt) {
                state = State.RESET
                storeErrorLocked(VfsError.CONNECTION_RESET)
                wakeAllLocked()
                return SegmentActions(listOf(resetForLocked(segment)), unregister = true)
            }
            state = State.ESTABLISHED
            connectWaiters.wakeAll()
            return SegmentActions(
                transmissions = acknowledgments + flushLocked(),
                accepted = parent != null,
            )
        }
        var sendAck = false
        if (segment.payloadLength != 0 || segment.flags and TcpFlags.FIN != 0) {
            sendAck = receiveDataLocked(packet, segment)
        }
        val stateActions = advanceClosingStateLocked()
        val transmissions = ArrayList<TcpTransmission>()
        transmissions += acknowledgments
        if (sendAck) transmissions += acknowledgmentLocked()
        transmissions += flushLocked()
        transmissions += queueFinLocked()
        return SegmentActions(transmissions, unregister = stateActions)
    }

    private fun processSynSentLocked(
        packet: IpPacketContext,
        segment: TcpSegment,
    ): SegmentActions {
        if (segment.flags and TcpFlags.ACK != 0 &&
            segment.acknowledgmentNumber != sndNxt
        ) return if (segment.flags and TcpFlags.RST != 0) SegmentActions()
        else SegmentActions(listOf(resetForLocked(segment)))
        if (segment.flags and TcpFlags.RST != 0) {
            state = State.RESET
            readOpen = false
            writeOpen = false
            storeErrorLocked(VfsError.CONNECTION_REFUSED)
            wakeAllLocked()
            return SegmentActions(unregister = true)
        }
        if (segment.flags and TcpFlags.SYN == 0) return SegmentActions()
        rcvNxt = segment.sequenceNumber + 1u
        peerMss = (segment.options.maximumSegmentSize?.toInt() ?: DEFAULT_MSS)
            .coerceAtLeast(MINIMUM_MSS)
        peerWindowScale = segment.options.windowScale?.toInt() ?: 0
        sndWnd = segment.window.toUInt()
        sndWl1 = segment.sequenceNumber
        sndWl2 = segment.acknowledgmentNumber
        return if (segment.flags and TcpFlags.ACK != 0) {
            acknowledgeLocked(segment)
            state = State.ESTABLISHED
            connectWaiters.wakeAll()
            SegmentActions(
                transmissions = listOf(acknowledgmentLocked()) + flushLocked(),
            )
        } else {
            state = State.SYN_RECEIVED
            outstanding.firstOrNull()?.flags = TcpFlags.SYN or TcpFlags.ACK
            SegmentActions(
                outstanding.firstOrNull()?.let {
                    listOf(
                        it.transmission(
                            local,
                            checkNotNull(remote),
                            rcvNxt,
                            advertisedWindowLocked(),
                            ttl.toUByte(),
                        ),
                    )
                }.orEmpty(),
            )
        }
    }

    private fun acknowledgeLocked(segment: TcpSegment): List<TcpTransmission> {
        val acknowledgment = segment.acknowledgmentNumber
        val previous = sndUna
        if (TcpSequence.after(acknowledgment, sndUna)) {
            sndUna = acknowledgment
            while (outstanding.isNotEmpty()) {
                val first = outstanding.first()
                if (TcpSequence.after(first.endSequence, acknowledgment)) {
                    if (TcpSequence.after(acknowledgment, first.sequence)) first.trim(acknowledgment)
                    break
                }
                outstanding.removeFirst()
            }
            val acknowledged = TcpSequence.distance(previous, acknowledgment)
            if (congestionWindow < slowStartThreshold) {
                congestionWindow += minOf(acknowledged, peerMss.toUInt())
            } else if (acknowledged != 0u) {
                congestionWindow += maxOf(
                    1u,
                    peerMss.toUInt() * peerMss.toUInt() / congestionWindow,
                )
            }
            writeWaiters.wakeAll()
        }
        if (TcpSequence.after(segment.sequenceNumber, sndWl1) ||
            segment.sequenceNumber == sndWl1 &&
            !TcpSequence.before(segment.acknowledgmentNumber, sndWl2)
        ) {
            sndWnd = segment.window.toUInt() shl peerWindowScale
            sndWl1 = segment.sequenceNumber
            sndWl2 = segment.acknowledgmentNumber
        }
        return emptyList()
    }

    private fun segmentAcceptableLocked(segment: TcpSegment): Boolean {
        val window = receiveWindowLocked()
        val length = segment.payloadLength +
            (if (segment.flags and TcpFlags.SYN != 0) 1 else 0) +
            (if (segment.flags and TcpFlags.FIN != 0) 1 else 0)
        if (length == 0) {
            return if (window == 0) segment.sequenceNumber == rcvNxt
            else !TcpSequence.before(segment.sequenceNumber, rcvNxt) &&
                TcpSequence.before(segment.sequenceNumber, rcvNxt + window.toUInt())
        }
        if (window == 0) return false
        val last = segment.sequenceNumber + length.toUInt() - 1u
        val end = rcvNxt + window.toUInt()
        return (!TcpSequence.before(segment.sequenceNumber, rcvNxt) &&
            TcpSequence.before(segment.sequenceNumber, end)) ||
            (!TcpSequence.before(last, rcvNxt) && TcpSequence.before(last, end))
    }

    private fun receiveDataLocked(packet: IpPacketContext, segment: TcpSegment): Boolean {
        var sequence = segment.sequenceNumber
        var payloadOffset = segment.payloadOffset
        var payloadLength = segment.payloadLength
        var fin = segment.flags and TcpFlags.FIN != 0
        if (TcpSequence.before(sequence, rcvNxt)) {
            val duplicate = TcpSequence.distance(sequence, rcvNxt).toInt()
            if (duplicate >= payloadLength + if (fin) 1 else 0) return true
            val discarded = minOf(duplicate, payloadLength)
            sequence += discarded.toUInt()
            payloadOffset += discarded
            payloadLength -= discarded
            if (duplicate > discarded) fin = false
        }
        val payload = if (payloadLength == 0) ByteArray(0) else packet.bytes.copyOfRange(
            payloadOffset,
            payloadOffset + payloadLength,
        )
        if (sequence != rcvNxt) {
            val receiveWindow = receiveWindowLocked()
            if (TcpSequence.after(sequence, rcvNxt + receiveWindow.toUInt()) ||
                payload.size > receiveWindow ||
                outOfOrder.any { existing ->
                    val end = sequence + payload.size.toUInt() + if (fin) 1u else 0u
                    val existingEnd = existing.sequence + existing.payload.size.toUInt() +
                        if (existing.fin) 1u else 0u
                    TcpSequence.before(sequence, existingEnd) && TcpSequence.before(existing.sequence, end)
                }
            ) return true
            outOfOrder += OutOfOrder(sequence, payload, fin)
            outOfOrder.sortWith { first, second ->
                when {
                    first.sequence == second.sequence -> 0
                    TcpSequence.before(first.sequence, second.sequence) -> -1
                    else -> 1
                }
            }
            return true
        }
        if (!appendReceivedLocked(payload, fin)) return true
        while (true) {
            val next = outOfOrder.firstOrNull { it.sequence == rcvNxt } ?: break
            outOfOrder.remove(next)
            if (!appendReceivedLocked(next.payload, next.fin)) break
        }
        return true
    }

    private fun appendReceivedLocked(payload: ByteArray, fin: Boolean): Boolean {
        if (payload.size > optionsLocked().receiveBufferSize - receiveBuffer.size) return false
        if (payload.isNotEmpty() && readOpen) {
            val source = ByteArrayBuffer(payload).prepareRead(0, payload.size) ?: return false
            if (receiveBuffer.write(source, 0, payload.size) != payload.size) return false
            rcvNxt += payload.size.toUInt()
            readWaiters.wakeReady(receiveBuffer.size)
        } else if (payload.isNotEmpty()) {
            rcvNxt += payload.size.toUInt()
        }
        if (fin) {
            rcvNxt++
            readOpen = false
            readWaiters.wakeAll()
            state = when (state) {
                State.ESTABLISHED -> State.CLOSE_WAIT
                State.FIN_WAIT_1 -> if (sndUna == sndNxt) enterTimeWaitLocked() else State.CLOSING
                State.FIN_WAIT_2 -> enterTimeWaitLocked()
                else -> state
            }
        }
        return true
    }

    private fun flushLocked(): List<TcpTransmission> {
        val destination = remote ?: return emptyList()
        if (state !in SEND_STATES && state != State.SYN_RECEIVED || sendBuffer.size == 0) {
            return emptyList()
        }
        val flight = TcpSequence.distance(sndUna, sndNxt).toInt()
        var usable = minOf(sndWnd, congestionWindow).coerceAtMost(Int.MAX_VALUE.toUInt()).toInt() -
            flight
        if (usable <= 0) return emptyList()
        val transmissions = mutableListOf<TcpTransmission>()
        val maximumSegment = minOf(peerMss, localMss)
        while (sendBuffer.size != 0 && usable > 0) {
            if (sendBuffer.size < maximumSegment && TscClock.nanoTime() < corkUntil) break
            val length = minOf(sendBuffer.size, maximumSegment, usable)
            if (!noDelay && outstanding.any { it.payload.isNotEmpty() } &&
                length < maximumSegment
            ) break
            val payload = ByteArray(length)
            val target = ByteArrayBuffer(payload).prepareWrite(0, length) ?: break
            if (sendBuffer.read(target, 0, length) != length) break
            val flags = TcpFlags.ACK or if (sendBuffer.size == 0) TcpFlags.PSH else 0
            val segment = Outstanding(
                sndNxt,
                flags,
                payload,
                ByteArray(0),
                TscClock.nanoTime(),
            )
            sndNxt += length.toUInt()
            outstanding += segment
            transmissions += segment.transmission(
                local,
                destination,
                rcvNxt,
                advertisedWindowLocked(),
                ttl.toUByte(),
            )
            usable -= length
        }
        return transmissions
    }

    private fun queueFinLocked(): List<TcpTransmission> {
        val destination = remote ?: return emptyList()
        if (writeOpen || finQueued || sendBuffer.size != 0 || state !in SEND_STATES) {
            return emptyList()
        }
        val segment = Outstanding(
            sndNxt,
            TcpFlags.FIN or TcpFlags.ACK,
            ByteArray(0),
            ByteArray(0),
            TscClock.nanoTime(),
        )
        sndNxt++
        finQueued = true
        outstanding += segment
        state = if (state == State.CLOSE_WAIT) State.LAST_ACK else State.FIN_WAIT_1
        return listOf(
            segment.transmission(
                local,
                destination,
                rcvNxt,
                advertisedWindowLocked(),
                ttl.toUByte(),
            ),
        )
    }

    private fun acknowledgmentLocked(): TcpTransmission = TcpTransmission(
        local,
        checkNotNull(remote),
        sndNxt,
        rcvNxt,
        TcpFlags.ACK,
        advertisedWindowLocked(),
        ttl = ttl.toUByte(),
    )

    private fun resetForLocked(segment: TcpSegment): TcpTransmission = TcpTransmission(
        local,
        checkNotNull(remote),
        segment.acknowledgmentNumber,
        0u,
        TcpFlags.RST,
        0u,
        ttl = ttl.toUByte(),
    )

    private fun advanceClosingStateLocked(): Boolean {
        if (sndUna != sndNxt) return false
        state = when (state) {
            State.FIN_WAIT_1 -> State.FIN_WAIT_2.also {
                finWaitUntil = TscClock.nanoTime() + FIN_WAIT_2_NANOS
            }
            State.CLOSING -> enterTimeWaitLocked()
            State.LAST_ACK -> State.RESET
            else -> state
        }
        return state == State.RESET
    }

    private fun enterTimeWaitLocked(): State {
        timeWaitUntil = TscClock.nanoTime() + TIME_WAIT_NANOS
        return State.TIME_WAIT
    }

    private fun established(child: TcpSocket) {
        val queued = lock.withLock {
            if (state != State.LISTEN || child !in children) return@withLock false
            accepted += child
            acceptWaiters.wakeOne()
            true
        }
        if (!queued) child.abort(VfsError.CONNECTION_ABORTED)
    }

    private fun ensureBoundLocked(): VfsResult<Ipv4SocketAddress> {
        binding?.let { return VfsResult.Ok(it) }
        return when (val result = subsystem.bind(
            this,
            Ipv4SocketAddress(Ipv4Address.ANY, 0u),
            optionsLocked().reuseAddress,
        )) {
            is VfsResult.Ok -> result.also {
                binding = it.value
                local = it.value
                state = State.BOUND
            }
            is VfsResult.Err -> result
        }
    }

    private fun abort(error: VfsError) {
        lock.withLock {
            state = State.RESET
            readOpen = false
            writeOpen = false
            storeErrorLocked(error)
            wakeAllLocked()
        }
        subsystem.unregister(this)
        parent?.removeChild(this)
    }

    private fun removeChild(child: TcpSocket) = lock.withLock {
        children.remove(child)
        accepted.remove(child)
    }

    private fun wakeAllLocked() {
        readWaiters.wakeAll()
        writeWaiters.wakeAll()
        connectWaiters.wakeAll()
        acceptWaiters.wakeAll()
    }

    private fun queuedSendBytesLocked(): Int = sendBuffer.size + outstanding.sumOf { it.payload.size }

    private fun receiveWindowLocked(): Int = (
        optionsLocked().receiveBufferSize - receiveBuffer.size -
            outOfOrder.sumOf { it.payload.size + if (it.fin) 1 else 0 }
        ).coerceAtLeast(0)

    private fun advertisedWindowLocked(): UShort =
        (receiveWindowLocked() shr localWindowScale)
            .coerceAtMost(UShort.MAX_VALUE.toInt()).toUShort()

    companion object {
        private const val DEFAULT_BUFFER_SIZE = 212_992
        private const val DEFAULT_TTL = 64
        private const val DEFAULT_MSS = 536
        private const val MINIMUM_MSS = 88
        private const val IPV4_TCP_HEADER_SIZE = 40
        private const val INITIAL_CONGESTION_SEGMENTS = 10
        private const val MAX_BACKLOG = 4096
        private const val MAX_RETRANSMISSIONS = 8
        private const val INITIAL_RETRANSMISSION_NANOS = 1_000_000_000uL
        private const val TIME_WAIT_NANOS = 60_000_000_000uL
        private const val FIN_WAIT_2_NANOS = 60_000_000_000uL
        private const val CORK_TIMEOUT_NANOS = 200_000_000uL
        private const val SOL_IP = 0
        private const val IP_TTL = 2
        private const val IP_MTU = 14
        private const val IPPROTO_TCP = 6
        private const val TCP_NODELAY = 1
        private const val TCP_MAXSEG = 2

        private val CONNECTED_STATES = setOf(
            State.ESTABLISHED,
            State.FIN_WAIT_1,
            State.FIN_WAIT_2,
            State.CLOSING,
            State.CLOSE_WAIT,
        )
        private val SEND_STATES = setOf(State.ESTABLISHED, State.CLOSE_WAIT)
        private val RECEIVE_STATES = setOf(
            State.ESTABLISHED,
            State.FIN_WAIT_1,
            State.FIN_WAIT_2,
            State.CLOSING,
            State.CLOSE_WAIT,
        )

        private fun randomSequence(): UInt =
            NetworkOrderBuffer(KernelRandom.bytes(UInt.SIZE_BYTES)).readU32(0)

        private fun windowScale(bufferSize: Int): Int {
            var scale = 0
            var window = bufferSize
            while (window > UShort.MAX_VALUE.toInt() && scale < 14) {
                window = window ushr 1
                scale++
            }
            return scale
        }
    }
}
