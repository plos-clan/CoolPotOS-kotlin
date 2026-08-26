package org.plos_clan.cpos.fs.sock

import org.plos_clan.cpos.fs.vfs.ByteCircularBuffer
import org.plos_clan.cpos.fs.vfs.IoEvent
import org.plos_clan.cpos.fs.vfs.IoResult
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.mem.PreparedBufferSource
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.utils.IrqSpinLock
import org.plos_clan.cpos.utils.PollEvents

internal class UnixConnectionSocket(
    subsystem: UnixSocketSubsystem,
    type: UnixSocketType,
) : UnixSocket(subsystem, type) {
    private sealed interface State {
        data object Initial : State
        data object Connecting : State
        data class Listening(val listener: UnixSocketListener) : State
        data class Connected(
            val connection: UnixDuplexConnection,
            val side: UnixDuplexConnection.Side,
            val localAddress: UnixSocketAddress,
            val peerAddress: UnixSocketAddress,
            val peerCredentials: UnixCredentials,
        ) : State
        data object Closed : State
    }

    private data class ConnectStart(
        val localAddress: UnixSocketAddress,
        val options: UnixSocketOptions,
    )

    private var state: State = State.Initial

    init {
        require(type.connectionOriented)
    }

    override fun canBindLocked(): Boolean = state == State.Initial

    override fun localAddressLocked(): UnixSocketAddress =
        (state as? State.Connected)?.localAddress ?: boundAddressLocked()

    override fun listen(backlog: Int, credentials: UnixCredentials): VfsResult<Unit> =
        lock.withLock {
            if (closed) return@withLock VfsResult.Err(VfsError.BAD_DESCRIPTOR)
            when (val current = state) {
                State.Initial -> {
                    val address = boundAddressLocked()
                    if (address == UnixSocketAddress.Unnamed) {
                        return@withLock VfsResult.Err(VfsError.INVALID_ARGUMENT)
                    }
                    state = State.Listening(
                        UnixSocketListener(
                            subsystem,
                            socketType,
                            address,
                            credentials,
                            optionsLocked(),
                            backlog,
                        ),
                    )
                    VfsResult.Ok(Unit)
                }
                is State.Listening -> {
                    current.listener.updateBacklog(backlog)
                    VfsResult.Ok(Unit)
                }
                State.Connecting,
                is State.Connected,
                State.Closed,
                -> VfsResult.Err(VfsError.INVALID_ARGUMENT)
            }
        }

    override fun connect(
        peer: UnixSocket?,
        address: UnixSocketAddress,
        credentials: UnixCredentials,
        nonBlocking: Boolean,
    ): VfsResult<Unit> {
        val connectionPeer = peer as? UnixConnectionSocket
            ?: return VfsResult.Err(
                if (peer == null) VfsError.CONNECTION_REFUSED else VfsError.WRONG_PROTOCOL_TYPE,
            )
        if (connectionPeer.socketType != socketType) {
            return VfsResult.Err(VfsError.WRONG_PROTOCOL_TYPE)
        }
        val listener = connectionPeer.listeningEndpoint()
            ?: return VfsResult.Err(VfsError.CONNECTION_REFUSED)

        val reserved = lock.withLock {
            if (closed) return@withLock VfsResult.Err(VfsError.BAD_DESCRIPTOR)
            when (state) {
                State.Initial -> {
                    state = State.Connecting
                    VfsResult.Ok(ConnectStart(boundAddressLocked(), optionsLocked()))
                }
                State.Connecting -> VfsResult.Err(VfsError.ALREADY_IN_PROGRESS)
                is State.Connected -> VfsResult.Err(VfsError.ALREADY_CONNECTED)
                is State.Listening,
                State.Closed,
                -> VfsResult.Err(VfsError.INVALID_ARGUMENT)
            }
        }
        val start = when (reserved) {
            is VfsResult.Ok -> reserved.value
            is VfsResult.Err -> return reserved
        }

        val connected = listener.connect(
            start.localAddress,
            credentials,
            start.options,
            nonBlocking,
            if (nonBlocking) null else UnixSocketDeadline.after(start.options.sendTimeoutNanos),
        )
        return lock.withLock {
            if (connected is VfsResult.Ok) {
                val endpoint = connected.value
                endpoint.connection.updateOptions(
                    UnixDuplexConnection.Side.FIRST,
                    optionsLocked(),
                )
                state = State.Connected(
                    endpoint.connection,
                    UnixDuplexConnection.Side.FIRST,
                    start.localAddress,
                    endpoint.peerAddress,
                    endpoint.peerCredentials,
                )
                VfsResult.Ok(Unit)
            } else {
                state = State.Initial
                VfsResult.Err((connected as VfsResult.Err).error)
            }
        }
    }

    override fun accept(nonBlocking: Boolean): VfsResult<UnixSocket> {
        val listener = listeningEndpoint()
            ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        val deadline = if (nonBlocking) null else {
            UnixSocketDeadline.after(socketOptions().receiveTimeoutNanos)
        }
        return listener.accept(nonBlocking, deadline)
    }

    override fun shutdown(mode: UnixShutdownMode): VfsResult<Unit> {
        val endpoint = connectedEndpoint() ?: return VfsResult.Err(VfsError.NOT_CONNECTED)
        endpoint.connection.shutdown(endpoint.side, mode)?.invoke()
        return VfsResult.Ok(Unit)
    }

    override fun peerAddress(): VfsResult<UnixSocketAddress> = lock.withLock {
        val connected = state as? State.Connected
            ?: return@withLock VfsResult.Err(VfsError.NOT_CONNECTED)
        VfsResult.Ok(connected.peerAddress)
    }

    override fun peerCredentials(): VfsResult<UnixCredentials> = lock.withLock {
        val connected = state as? State.Connected
            ?: return@withLock VfsResult.Err(VfsError.NOT_CONNECTED)
        VfsResult.Ok(connected.peerCredentials)
    }

    override fun isListening(): Boolean = lock.withLock { state is State.Listening }

    override fun send(request: UnixSendRequest): IoResult {
        val endpoint = connectedEndpoint()
        if (endpoint == null) {
            request.ancillary.release()
            return IoResult.failure(VfsError.NOT_CONNECTED)
        }
        if (request.destination != null) {
            request.ancillary.release()
            return IoResult.failure(VfsError.ALREADY_CONNECTED)
        }
        if (socketType == UnixSocketType.SEQUENCED_PACKET) {
            return sendPacket(endpoint, request)
        }
        if (request.count == 0) {
            val error = if (request.ancillary.isEmpty) null else VfsError.INVALID_ARGUMENT
            request.ancillary.release()
            return error?.let(IoResult::failure) ?: IoResult.success(0)
        }

        var transferred = 0
        var ancillary: UnixAncillaryData? = request.ancillary
        val options = socketOptions()
        val deadline = if (request.nonBlocking) null
        else UnixSocketDeadline.after(options.sendTimeoutNanos)
        while (transferred < request.count) {
            val result = endpoint.connection.send(
                endpoint.side,
                request.source,
                request.offset + transferred,
                request.count - transferred,
                ancillary,
                request.credentials,
            )
            if (result.isSuccess && ancillary?.isEmpty == false) ancillary = null
            if (result.isSuccess) {
                val current = result.bytesTransferred
                if (current == 0) break
                transferred += current
                if (transferred == request.count) break
            } else if (result.error != VfsError.WOULD_BLOCK) {
                if (result.error == VfsError.BROKEN_PIPE) {
                    signalBrokenPipe(request.noSignal)
                }
                ancillary?.release()
                return if (transferred == 0) result else IoResult.success(transferred)
            }
            if (request.nonBlocking) break
            val waitError = awaitEndpoint(endpoint, IoEvent.WRITABLE, 1, deadline)
            if (waitError != null) {
                ancillary?.release()
                return if (transferred == 0) IoResult.failure(waitError)
                else IoResult.success(transferred)
            }
        }
        ancillary?.release()
        return IoResult.success(transferred)
    }

    override fun receive(request: UnixReceiveRequest): VfsResult<UnixReceiveResult> {
        val endpoint = connectedEndpoint()
            ?: return VfsResult.Err(VfsError.NOT_CONNECTED)
        if (request.count == 0 && socketType == UnixSocketType.STREAM) {
            return VfsResult.Ok(
                UnixReceiveResult(
                    bytes = 0,
                    copiedBytes = 0,
                    source = endpoint.peerAddress,
                    senderCredentials = endpoint.peerCredentials,
                ),
            )
        }

        var copied = 0
        var reported = 0
        var ancillary: UnixAncillaryData? = null
        var truncated = false
        var endOfRecord = false
        val options = socketOptions()
        val deadline = if (request.nonBlocking) null else UnixSocketDeadline.earliest(
            request.deadline,
            UnixSocketDeadline.after(options.receiveTimeoutNanos),
        )
        while (copied < request.count ||
            socketType == UnixSocketType.SEQUENCED_PACKET && !endOfRecord
        ) {
            val minimum = minOf(
                request.count - copied,
                options.receiveLowWatermark,
            )
            if (!request.nonBlocking && copied == 0 &&
                !endpoint.connection.readReady(endpoint.side, minimum)
            ) {
                val waitError = awaitEndpoint(endpoint, IoEvent.READABLE, minimum, deadline)
                if (waitError != null) {
                    return VfsResult.Err(waitError)
                }
            }
            val received = endpoint.connection.receive(
                endpoint.side,
                request.destination,
                request.offset + copied,
                request.count - copied,
                request.peek,
                request.returnFullLength,
            )
            when (received) {
                is VfsResult.Ok -> {
                    val current = received.value
                    copied += current.copiedBytes
                    reported = if (request.returnFullLength) current.bytes else copied
                    ancillary = current.ancillary
                    truncated = current.truncated
                    endOfRecord = current.endOfRecord
                    if (current.copiedBytes == 0 || request.peek || current.ancillary != null ||
                        current.endOfRecord || !request.waitAll || copied == request.count
                    ) {
                        break
                    }
                }
                is VfsResult.Err -> {
                    if (received.error != VfsError.WOULD_BLOCK) {
                        if (copied == 0) return received
                        break
                    }
                    if ((copied != 0 && !request.waitAll) || request.nonBlocking) {
                        if (copied == 0) return received
                        break
                    }
                    val waitError = awaitEndpoint(endpoint, IoEvent.READABLE, 1, deadline)
                    if (waitError != null) {
                        if (copied == 0) return VfsResult.Err(waitError)
                        break
                    }
                }
            }
        }
        return VfsResult.Ok(
            UnixReceiveResult(
                bytes = reported,
                copiedBytes = copied,
                source = endpoint.peerAddress,
                ancillary = ancillary,
                senderCredentials = endpoint.peerCredentials,
                truncated = truncated,
                endOfRecord = endOfRecord,
            ),
        )
    }

    override fun readableBytes(): Int = when (val current = lock.withLock { state }) {
        is State.Connected -> current.connection.readableBytes(current.side)
        is State.Listening -> current.listener.pendingCount()
        else -> 0
    }

    override fun pollSocket(events: Int): Int {
        val receiveLowWatermark = socketOptions().receiveLowWatermark
        return when (val current = lock.withLock { state }) {
            State.Initial,
            State.Connecting,
            -> 0
            is State.Listening -> current.listener.poll(events)
            is State.Connected -> current.connection.poll(
                current.side,
                events,
                receiveLowWatermark,
            )
            State.Closed -> PollEvents.POLLHUP and
                (events or PollEvents.UNCONDITIONALLY_REPORTED)
        }
    }

    override fun closeLocked(): (() -> Unit)? {
        val cleanup = when (val current = state) {
            is State.Listening -> current.listener.close()
            is State.Connected -> current.connection.close(current.side)
            State.Initial,
            State.Connecting,
            State.Closed,
            -> null
        }
        state = State.Closed
        return cleanup
    }

    override fun pairWith(peer: UnixSocket, credentials: UnixCredentials): VfsResult<Unit> {
        val connectionPeer = peer as? UnixConnectionSocket
            ?: return VfsResult.Err(VfsError.WRONG_PROTOCOL_TYPE)
        if (connectionPeer.socketType != socketType) {
            return VfsResult.Err(VfsError.WRONG_PROTOCOL_TYPE)
        }
        val firstOptions = socketOptions()
        val secondOptions = connectionPeer.socketOptions()
        val connection = UnixDuplexConnection(
            socketType,
            firstOptions,
            secondOptions,
        )
        lock.withLock {
            if (state != State.Initial) return VfsResult.Err(VfsError.ALREADY_CONNECTED)
            state = State.Connected(
                connection,
                UnixDuplexConnection.Side.FIRST,
                UnixSocketAddress.Unnamed,
                UnixSocketAddress.Unnamed,
                credentials,
            )
        }
        connectionPeer.lock.withLock {
            check(connectionPeer.state == State.Initial)
            connectionPeer.state = State.Connected(
                connection,
                UnixDuplexConnection.Side.SECOND,
                UnixSocketAddress.Unnamed,
                UnixSocketAddress.Unnamed,
                credentials,
            )
        }
        return VfsResult.Ok(Unit)
    }

    private fun sendPacket(endpoint: State.Connected, request: UnixSendRequest): IoResult {
        val options = socketOptions()
        val deadline = if (request.nonBlocking) null
        else UnixSocketDeadline.after(options.sendTimeoutNanos)
        val ancillary: UnixAncillaryData = request.ancillary
        while (true) {
            val result = endpoint.connection.send(
                endpoint.side,
                request.source,
                request.offset,
                request.count,
                ancillary,
                request.credentials,
            )
            if (result.isSuccess && !ancillary.isEmpty) return result
            if (result.error != VfsError.WOULD_BLOCK || request.nonBlocking) {
                ancillary.release()
                if (result.error == VfsError.BROKEN_PIPE) {
                    signalBrokenPipe(request.noSignal)
                }
                return result
            }
            val waitError = awaitEndpoint(
                endpoint,
                IoEvent.WRITABLE,
                request.count,
                deadline,
            )
            if (waitError != null) {
                ancillary.release()
                return IoResult.failure(waitError)
            }
        }
    }

    private fun connectedEndpoint(): State.Connected? = lock.withLock { state as? State.Connected }

    private fun awaitEndpoint(
        endpoint: State.Connected,
        event: IoEvent,
        count: Int,
        deadline: UnixSocketDeadline?,
    ): VfsError? {
        if (deadline == null) {
            return if (endpoint.connection.await(endpoint.side, event, count)) null
            else VfsError.INTERRUPTED
        }
        return deadline.await {
            when (event) {
                IoEvent.READABLE -> endpoint.connection.readReady(endpoint.side, count)
                IoEvent.WRITABLE -> endpoint.connection.writeReady(endpoint.side, count)
            }
        }
    }

    private fun listeningEndpoint(): UnixSocketListener? = lock.withLock {
        (state as? State.Listening)?.listener
    }

    override fun sendBufferSizeChangedLocked(size: Int) {
        (state as? State.Connected)?.let { it.connection.resizeSendBuffer(it.side, size) }
    }

    override fun receiveBufferSizeChangedLocked(size: Int) {
        (state as? State.Connected)?.let { it.connection.resizeReceiveBuffer(it.side, size) }
    }

    override fun optionsChangedLocked() {
        when (val current = state) {
            is State.Connected -> current.connection.setReceivePassCredentials(
                current.side,
                optionsLocked().passCredentials,
            )
            is State.Listening -> current.listener.updateOptions(optionsLocked())
            else -> Unit
        }
    }

    private constructor(
        subsystem: UnixSocketSubsystem,
        type: UnixSocketType,
        connected: State.Connected,
        options: UnixSocketOptions,
    ) : this(subsystem, type) {
        state = connected
        inheritOptions(options)
    }

    companion object {
        private fun accepted(
            subsystem: UnixSocketSubsystem,
            type: UnixSocketType,
            connection: UnixDuplexConnection,
            localAddress: UnixSocketAddress,
            peerAddress: UnixSocketAddress,
            peerCredentials: UnixCredentials,
            options: UnixSocketOptions,
        ): UnixConnectionSocket = UnixConnectionSocket(
            subsystem,
            type,
            State.Connected(
                connection,
                UnixDuplexConnection.Side.SECOND,
                localAddress,
                peerAddress,
                peerCredentials,
            ),
            options,
        )
    }

    private class UnixSocketListener(
        private val subsystem: UnixSocketSubsystem,
        private val type: UnixSocketType,
        private val localAddress: UnixSocketAddress,
        private val credentials: UnixCredentials,
        private var options: UnixSocketOptions,
        backlog: Int,
    ) {
        data class ClientEndpoint(
            val connection: UnixDuplexConnection,
            val peerAddress: UnixSocketAddress,
            val peerCredentials: UnixCredentials,
        )

        private val lock = IrqSpinLock()
        private var pending = ArrayDeque<UnixConnectionSocket>()
        private val acceptWaiters = IoWaitQueue()
        private val connectWaiters = IoWaitQueue()
        private var capacity = normalizedBacklog(backlog)
        private var reservations = 0
        private var closed = false

        fun connect(
            clientAddress: UnixSocketAddress,
            clientCredentials: UnixCredentials,
            clientOptions: UnixSocketOptions,
            nonBlocking: Boolean,
            deadline: UnixSocketDeadline?,
        ): VfsResult<ClientEndpoint> {
            val thread = ProcessManager.currentThread()
            while (true) {
                var waiter: IoWaitQueue.Waiter? = null
                var serverOptions: UnixSocketOptions? = null
                val error = lock.withLock {
                    if (closed) return@withLock VfsError.CONNECTION_REFUSED
                    if (pending.size + reservations < capacity) {
                        reservations++
                        serverOptions = options
                        return@withLock null
                    }
                    if (nonBlocking) return@withLock VfsError.WOULD_BLOCK
                    if (deadline == null) waiter = connectWaiters.add(checkNotNull(thread))
                    null
                }
                if (error != null) return VfsResult.Err(error)
                val acceptedOptions = serverOptions
                if (acceptedOptions != null) {
                    val connection = UnixDuplexConnection(
                        type,
                        clientOptions,
                        acceptedOptions,
                    )
                    val acceptedSocket = accepted(
                        subsystem,
                        type,
                        connection,
                        localAddress,
                        clientAddress,
                        clientCredentials,
                        acceptedOptions,
                    )
                    val committed = lock.withLock {
                        reservations--
                        if (closed) return@withLock false
                        pending += acceptedSocket
                        acceptWaiters.wakeOne()
                        true
                    }
                    if (!committed) {
                        acceptedSocket.release()
                        return VfsResult.Err(VfsError.CONNECTION_REFUSED)
                    }
                    return VfsResult.Ok(
                        ClientEndpoint(connection, localAddress, credentials),
                    )
                }
                if (deadline != null) {
                    val waitError = deadline.await(::canConnect)
                    if (waitError != null) return VfsResult.Err(waitError)
                    continue
                }
                if (!connectWaiters.await(lock, checkNotNull(waiter))) {
                    return VfsResult.Err(VfsError.INTERRUPTED)
                }
            }
        }

        fun accept(
            nonBlocking: Boolean,
            deadline: UnixSocketDeadline?,
        ): VfsResult<UnixSocket> {
            val thread = ProcessManager.currentThread()
            while (true) {
                var waiter: IoWaitQueue.Waiter? = null
                val accepted = lock.withLock {
                    pending.removeFirstOrNull()?.let { socket ->
                        connectWaiters.wakeReady(capacity - pending.size - reservations)
                        return@withLock VfsResult.Ok<UnixSocket>(socket)
                    }
                    if (closed) return@withLock VfsResult.Err(VfsError.INVALID_ARGUMENT)
                    if (nonBlocking) return@withLock VfsResult.Err(VfsError.WOULD_BLOCK)
                    if (deadline == null) waiter = acceptWaiters.add(checkNotNull(thread))
                    null
                }
                if (accepted != null) return accepted
                if (deadline != null) {
                    val waitError = deadline.await(::canAccept)
                    if (waitError != null) return VfsResult.Err(waitError)
                    continue
                }
                if (!acceptWaiters.await(lock, checkNotNull(waiter))) {
                    return VfsResult.Err(VfsError.INTERRUPTED)
                }
            }
        }

        fun updateBacklog(backlog: Int) {
            lock.withLock {
                capacity = normalizedBacklog(backlog)
                connectWaiters.wakeReady(capacity - pending.size - reservations)
            }
        }

        fun updateOptions(options: UnixSocketOptions) {
            lock.withLock { this.options = options }
        }

        fun pendingCount(): Int = lock.withLock { pending.size }

        private fun canConnect(): Boolean = lock.withLock {
            closed || pending.size + reservations < capacity
        }

        private fun canAccept(): Boolean = lock.withLock { closed || pending.isNotEmpty() }

        fun poll(events: Int): Int = lock.withLock {
            val available = when {
                pending.isNotEmpty() -> PollEvents.NORMAL_INPUT
                closed -> PollEvents.POLLHUP
                else -> 0
            }
            available and (events or PollEvents.UNCONDITIONALLY_REPORTED)
        }

        fun close(): (() -> Unit)? {
            val replacement = ArrayDeque<UnixConnectionSocket>()
            val abandoned = lock.withLock {
                if (closed) return null
                closed = true
                acceptWaiters.wakeAll()
                connectWaiters.wakeAll()
                pending.also { pending = replacement }
            }
            if (abandoned.isEmpty()) return null
            return {
                while (abandoned.isNotEmpty()) abandoned.removeFirst().release()
            }
        }

        companion object {
            private const val MAX_BACKLOG = 4_096

            private fun normalizedBacklog(backlog: Int): Int =
                (backlog.coerceIn(0, MAX_BACKLOG) + 1).coerceAtLeast(1)
        }
    }
}

private data class UnixBufferReceive(
    val bytes: Int,
    val copiedBytes: Int,
    val ancillary: UnixAncillaryData? = null,
    val truncated: Boolean = false,
    val endOfRecord: Boolean = false,
)

private sealed class UnixConnectionBuffer(capacity: Int) {
    var capacity = capacity
        private set

    abstract val readableBytes: Int
    abstract val remaining: Int
    abstract fun isReadable(minimum: Int): Boolean

    abstract fun send(
        source: PreparedBufferSource,
        offset: Int,
        count: Int,
        ancillary: UnixAncillaryData?,
    ): IoResult

    abstract fun receive(
        destination: PreparedBufferDestination,
        offset: Int,
        count: Int,
        peek: Boolean,
        returnFullLength: Boolean,
    ): VfsResult<UnixBufferReceive>

    abstract fun clear(): List<UnixAncillaryData>

    fun resize(capacity: Int) {
        require(capacity > 0)
        this.capacity = capacity
    }
}

private class UnixStreamBuffer(capacity: Int) : UnixConnectionBuffer(capacity) {
    private data class ControlMarker(
        val offset: ULong,
        val ancillary: UnixAncillaryData,
    )

    private val bytes = ByteCircularBuffer(minOf(capacity, INITIAL_CAPACITY))
    private val controls = ArrayDeque<ControlMarker>()
    private var readSequence = 0uL
    private var writeSequence = 0uL

    override val readableBytes: Int
        get() = bytes.size
    override val remaining: Int
        get() = (capacity - bytes.size).coerceAtLeast(0)
    override fun isReadable(minimum: Int): Boolean = bytes.size >= minimum

    override fun send(
        source: PreparedBufferSource,
        offset: Int,
        count: Int,
        ancillary: UnixAncillaryData?,
    ): IoResult {
        if (count == 0) return IoResult.success(0)
        val writable = minOf(count, remaining)
        if (writable != 0) {
            val required = bytes.size + writable
            if (required > bytes.capacity) {
                val doubled = if (bytes.capacity > Int.MAX_VALUE / 2) Int.MAX_VALUE
                else bytes.capacity * 2
                bytes.ensureCapacity(minOf(capacity, maxOf(required, doubled)))
            }
        }
        val transferred = bytes.write(source, offset, writable)
        if (transferred == 0) return IoResult.failure(VfsError.WOULD_BLOCK)
        val consumed = ancillary != null && !ancillary.isEmpty
        if (consumed) controls += ControlMarker(writeSequence, ancillary)
        writeSequence += transferred.toULong()
        return IoResult.success(transferred)
    }

    override fun receive(
        destination: PreparedBufferDestination,
        offset: Int,
        count: Int,
        peek: Boolean,
        returnFullLength: Boolean,
    ): VfsResult<UnixBufferReceive> {
        if (count == 0) return VfsResult.Ok(UnixBufferReceive(0, 0))
        val marker = controls.firstOrNull()?.takeIf { it.offset == readSequence }
        val nextMarker = controls.firstOrNull { it.offset > readSequence }
        val beforeMarker = nextMarker?.let { (it.offset - readSequence).toInt() }
        val requested = beforeMarker?.let { minOf(count, it) } ?: count
        val transferred = bytes.read(destination, offset, requested, peek)
        if (transferred == 0) return VfsResult.Err(VfsError.FAULT)

        val ancillary = if (marker == null) {
            null
        } else if (peek) {
            marker.ancillary.duplicate() ?: return VfsResult.Err(VfsError.NO_MEMORY)
        } else {
            check(controls.removeFirst() === marker)
            marker.ancillary
        }
        if (!peek) readSequence += transferred.toULong()
        return VfsResult.Ok(
            UnixBufferReceive(
                bytes = transferred,
                copiedBytes = transferred,
                ancillary = ancillary,
            ),
        )
    }

    override fun clear(): List<UnixAncillaryData> {
        bytes.clear()
        val discarded = if (controls.isEmpty()) emptyList() else {
            ArrayList<UnixAncillaryData>(controls.size).also { result ->
                controls.forEach { result += it.ancillary }
            }
        }
        controls.clear()
        readSequence = writeSequence
        return discarded
    }

    private companion object {
        const val INITIAL_CAPACITY = 4_096
    }
}

private class UnixPacketBuffer(capacity: Int) : UnixConnectionBuffer(capacity) {
    private data class Packet(
        val bytes: ByteArray,
        val ancillary: UnixAncillaryData?,
    ) {
        val accountedSize: Int
            get() = maxOf(1, bytes.size)

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false

            other as Packet

            if (!bytes.contentEquals(other.bytes)) return false
            if (ancillary != other.ancillary) return false
            if (accountedSize != other.accountedSize) return false

            return true
        }

        override fun hashCode(): Int {
            var result = bytes.contentHashCode()
            result = 31 * result + (ancillary?.hashCode() ?: 0)
            result = 31 * result + accountedSize
            return result
        }
    }

    private val packets = ArrayDeque<Packet>()
    private var used = 0

    override val readableBytes: Int
        get() = packets.firstOrNull()?.bytes?.size ?: 0
    override val remaining: Int
        get() = (capacity - used).coerceAtLeast(0)
    override fun isReadable(minimum: Int): Boolean = packets.isNotEmpty()

    override fun send(
        source: PreparedBufferSource,
        offset: Int,
        count: Int,
        ancillary: UnixAncillaryData?,
    ): IoResult {
        val accountedSize = maxOf(1, count)
        if (accountedSize > capacity) {
            return IoResult.failure(VfsError.MESSAGE_TOO_LONG)
        }
        if (accountedSize > remaining) {
            return IoResult.failure(VfsError.WOULD_BLOCK)
        }
        val data = ByteArray(count)
        if (count != 0 && source.copyTo(offset, data, 0, count) != count) {
            return IoResult.failure(VfsError.FAULT)
        }
        val consumed = ancillary != null && !ancillary.isEmpty
        packets += Packet(data, ancillary.takeIf { consumed })
        used += accountedSize
        return IoResult.success(count)
    }

    override fun receive(
        destination: PreparedBufferDestination,
        offset: Int,
        count: Int,
        peek: Boolean,
        returnFullLength: Boolean,
    ): VfsResult<UnixBufferReceive> {
        val packet = packets.firstOrNull() ?: return VfsResult.Err(VfsError.WOULD_BLOCK)
        val copied = minOf(count, packet.bytes.size)
        if (copied != 0 && destination.copyFrom(offset, packet.bytes, 0, copied) != copied) {
            return VfsResult.Err(VfsError.FAULT)
        }
        val ancillary = if (peek) {
            packet.ancillary?.duplicate() ?: packet.ancillary?.let {
                return VfsResult.Err(VfsError.NO_MEMORY)
            }
        } else {
            check(packets.removeFirst() === packet)
            used -= packet.accountedSize
            packet.ancillary
        }
        return VfsResult.Ok(
            UnixBufferReceive(
                bytes = if (returnFullLength) packet.bytes.size else copied,
                copiedBytes = copied,
                ancillary = ancillary,
                truncated = copied < packet.bytes.size,
                endOfRecord = true,
            ),
        )
    }

    override fun clear(): List<UnixAncillaryData> {
        val discarded = ArrayList<UnixAncillaryData>()
        packets.forEach { packet -> packet.ancillary?.let(discarded::add) }
        packets.clear()
        used = 0
        return discarded
    }
}

private class UnixDuplexConnection(
    type: UnixSocketType,
    firstOptions: UnixSocketOptions,
    secondOptions: UnixSocketOptions,
) {
    enum class Side {
        FIRST,
        SECOND;

        val opposite: Side
            get() = if (this == FIRST) SECOND else FIRST
    }

    private class Direction(
        val buffer: UnixConnectionBuffer,
        var sendBufferSize: Int,
        var receiveBufferSize: Int,
        var passCredentials: Boolean,
    ) {
        var writerOpen = true
        var readerOpen = true
        val readWaiters = IoWaitQueue()
        val writeWaiters = IoWaitQueue()

        fun resize() {
            buffer.resize(minOf(sendBufferSize, receiveBufferSize))
        }
    }

    private val lock = IrqSpinLock()
    private val directions = arrayOf(
        Direction(
            newBuffer(type, minOf(firstOptions.sendBufferSize, secondOptions.receiveBufferSize)),
            firstOptions.sendBufferSize,
            secondOptions.receiveBufferSize,
            secondOptions.passCredentials,
        ),
        Direction(
            newBuffer(type, minOf(secondOptions.sendBufferSize, firstOptions.receiveBufferSize)),
            secondOptions.sendBufferSize,
            firstOptions.receiveBufferSize,
            firstOptions.passCredentials,
        ),
    )

    fun send(
        side: Side,
        source: PreparedBufferSource,
        offset: Int,
        count: Int,
        ancillary: UnixAncillaryData?,
        credentials: UnixCredentials,
    ): IoResult = lock.withLock {
        val direction = outgoing(side)
        if (!direction.writerOpen || !direction.readerOpen) {
            return@withLock IoResult.failure(VfsError.BROKEN_PIPE)
        }
        if (direction.passCredentials) ancillary?.attachCredentials(credentials)
        val result = direction.buffer.send(source, offset, count, ancillary)
        if (result.isSuccess) direction.readWaiters.wakeOne()
        result
    }

    fun receive(
        side: Side,
        destination: PreparedBufferDestination,
        offset: Int,
        count: Int,
        peek: Boolean,
        returnFullLength: Boolean,
    ): VfsResult<UnixBufferReceive> = lock.withLock {
        val direction = incoming(side)
        if (!direction.readerOpen) return@withLock VfsResult.Ok(UnixBufferReceive(0, 0))
        if (!direction.buffer.isReadable(1)) {
            return@withLock if (direction.writerOpen) {
                VfsResult.Err(VfsError.WOULD_BLOCK)
            } else {
                VfsResult.Ok(UnixBufferReceive(0, 0))
            }
        }
        val result = direction.buffer.receive(
            destination,
            offset,
            count,
            peek,
            returnFullLength,
        )
        if (!peek && result is VfsResult.Ok) {
            direction.writeWaiters.wakeOne()
            if (direction.buffer.isReadable(1)) direction.readWaiters.wakeOne()
        }
        result
    }

    fun await(side: Side, event: IoEvent, count: Int): Boolean {
        val thread = checkNotNull(ProcessManager.currentThread())
        var waiter: IoWaitQueue.Waiter? = null
        lateinit var queue: IoWaitQueue
        lock.withLock {
            val direction = if (event == IoEvent.READABLE) incoming(side) else outgoing(side)
            val ready = when (event) {
                IoEvent.READABLE -> direction.buffer.isReadable(maxOf(1, count)) ||
                    !direction.writerOpen ||
                    !direction.readerOpen
                IoEvent.WRITABLE -> !direction.writerOpen || !direction.readerOpen ||
                    direction.buffer.remaining >= minOf(maxOf(1, count), direction.buffer.capacity)
            }
            if (!ready) {
                val minimum = if (event == IoEvent.READABLE) 1
                else minOf(maxOf(1, count), direction.buffer.capacity)
                queue = if (event == IoEvent.READABLE) direction.readWaiters
                else direction.writeWaiters
                waiter = queue.add(thread, minimum)
            }
        }
        return waiter?.let { queue.await(lock, it) } ?: true
    }

    fun readableBytes(side: Side): Int = lock.withLock { incoming(side).buffer.readableBytes }

    fun readReady(side: Side, minimum: Int): Boolean = lock.withLock {
        val direction = incoming(side)
        direction.buffer.isReadable(maxOf(1, minimum)) || !direction.writerOpen ||
            !direction.readerOpen
    }

    fun writeReady(side: Side, minimum: Int): Boolean = lock.withLock {
        val direction = outgoing(side)
        !direction.writerOpen || !direction.readerOpen ||
            direction.buffer.remaining >= minOf(
                maxOf(1, minimum),
                direction.buffer.capacity,
            )
    }

    fun resizeSendBuffer(side: Side, size: Int) = lock.withLock {
        val direction = outgoing(side)
        direction.sendBufferSize = size
        direction.resize()
        direction.writeWaiters.wakeAll()
    }

    fun resizeReceiveBuffer(side: Side, size: Int) = lock.withLock {
        val direction = incoming(side)
        direction.receiveBufferSize = size
        direction.resize()
        direction.writeWaiters.wakeAll()
    }

    fun setReceivePassCredentials(side: Side, enabled: Boolean) = lock.withLock {
        incoming(side).passCredentials = enabled
    }

    fun updateOptions(side: Side, options: UnixSocketOptions) = lock.withLock {
        val outgoing = outgoing(side)
        outgoing.sendBufferSize = options.sendBufferSize
        outgoing.resize()
        outgoing.writeWaiters.wakeAll()

        val incoming = incoming(side)
        incoming.receiveBufferSize = options.receiveBufferSize
        incoming.passCredentials = options.passCredentials
        incoming.resize()
        incoming.writeWaiters.wakeAll()
    }

    fun poll(side: Side, events: Int, receiveLowWatermark: Int): Int = lock.withLock {
        val incoming = incoming(side)
        val outgoing = outgoing(side)
        var available = 0
        if (incoming.buffer.isReadable(receiveLowWatermark) || !incoming.writerOpen ||
            !incoming.readerOpen
        ) {
            available = available or PollEvents.NORMAL_INPUT
        }
        if (!incoming.writerOpen) available = available or PollEvents.POLLRDHUP
        if (outgoing.writerOpen && outgoing.readerOpen && outgoing.buffer.remaining != 0) {
            available = available or PollEvents.NORMAL_OUTPUT
        }
        if (!outgoing.readerOpen) available = available or PollEvents.POLLERR
        if (!incoming.writerOpen && !outgoing.readerOpen) {
            available = available or PollEvents.POLLHUP
        }
        available and (events or PollEvents.UNCONDITIONALLY_REPORTED)
    }

    fun shutdown(side: Side, mode: UnixShutdownMode): (() -> Unit)? {
        val discarded = lock.withLock {
            var ancillary: List<UnixAncillaryData> = emptyList()
            if (mode.reads) {
                val incoming = incoming(side)
                if (incoming.readerOpen) {
                    incoming.readerOpen = false
                    ancillary = incoming.buffer.clear()
                    incoming.writeWaiters.wakeAll()
                    incoming.readWaiters.wakeAll()
                }
            }
            if (mode.writes) {
                val outgoing = outgoing(side)
                if (outgoing.writerOpen) {
                    outgoing.writerOpen = false
                    outgoing.readWaiters.wakeAll()
                    outgoing.writeWaiters.wakeAll()
                }
            }
            ancillary
        }
        if (discarded.isEmpty()) return null
        return { discarded.forEach(UnixAncillaryData::release) }
    }

    fun close(side: Side): (() -> Unit)? = shutdown(side, UnixShutdownMode.BOTH)

    private fun outgoing(side: Side): Direction = directions[side.ordinal]

    private fun incoming(side: Side): Direction = directions[side.opposite.ordinal]

    companion object {
        private fun newBuffer(type: UnixSocketType, capacity: Int): UnixConnectionBuffer =
            when (type) {
                UnixSocketType.STREAM -> UnixStreamBuffer(capacity)
                UnixSocketType.SEQUENCED_PACKET -> UnixPacketBuffer(capacity)
                UnixSocketType.DATAGRAM -> error("Datagram sockets do not use duplex streams")
            }
    }
}
