package org.plos_clan.cpos.network

import org.plos_clan.cpos.fs.sock.AbstractSocket
import org.plos_clan.cpos.fs.sock.SocketAddress
import org.plos_clan.cpos.fs.sock.SocketDeadline
import org.plos_clan.cpos.fs.sock.SocketDomain
import org.plos_clan.cpos.fs.sock.SocketReceiveRequest
import org.plos_clan.cpos.fs.sock.SocketReceiveResult
import org.plos_clan.cpos.fs.sock.SocketSendRequest
import org.plos_clan.cpos.fs.sock.SocketType
import org.plos_clan.cpos.fs.vfs.IoResult
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.tasks.CapEnum
import org.plos_clan.cpos.tasks.CapManager
import org.plos_clan.cpos.tasks.IoWaitQueue
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.utils.IrqSpinLock
import org.plos_clan.cpos.utils.LittleEndianBuffer
import org.plos_clan.cpos.utils.PollEvents

internal enum class NetlinkProtocolKind(val number: Int) {
    ROUTE(0),
    USERSOCK(2),
    GENERIC(16),
    ;

    companion object {
        fun fromNumber(number: Int): NetlinkProtocolKind? = entries.firstOrNull {
            it.number == number
        }
    }
}

internal data class NetlinkSocketAddress(
    val portId: UInt,
    val groups: UInt,
) : SocketAddress {
    override val domain = SocketDomain.NETLINK
}

internal data class NetlinkReply(
    val type: Int,
    val payload: ByteArray = ByteArray(0),
    val flags: Int = 0,
)

internal sealed interface NetlinkResult {
    data class Success(
        val replies: List<NetlinkReply> = emptyList(),
        val multipart: Boolean = false,
    ) : NetlinkResult

    data class Failure(
        val error: VfsError,
        val message: String? = null,
        val offset: Int? = null,
    ) : NetlinkResult
}

internal data class NetlinkRequest(
    val process: Process,
    val message: NetlinkMessage,
    val replyPortId: UInt,
    val strict: Boolean,
)

internal abstract class NetlinkProtocol(
    val kind: NetlinkProtocolKind,
    private val nonRootUserSend: Boolean = false,
    private val nonRootGroupReceive: Boolean = true,
    private val nonRootGroupSend: Boolean = false,
) {
    private class Endpoint(val socket: NetlinkSocket) {
        var portId: UInt? = null
        val memberships = mutableSetOf<Int>()
    }

    private val lock = IrqSpinLock()
    private val endpoints = mutableMapOf<NetlinkSocket, Endpoint>()
    private val ports = mutableMapOf<UInt, Endpoint>()
    private val subscribers = mutableMapOf<Int, MutableSet<Endpoint>>()
    private var nextPortId = UInt.MAX_VALUE

    protected abstract val multicastGroupCount: Int

    protected open fun acceptsMulticastGroup(group: Int): Boolean =
        group in 1..multicastGroupCount

    fun createSocket(type: SocketType): NetlinkSocket {
        require(type == SocketType.RAW || type == SocketType.DATAGRAM)
        val socket = NetlinkSocket(this, type)
        lock.withLock { endpoints[socket] = Endpoint(socket) }
        return socket
    }

    fun bind(
        socket: NetlinkSocket,
        requested: NetlinkSocketAddress,
        process: Process,
    ): VfsResult<Unit> {
        val memberships = groups(requested.groups)
        if (memberships.any { !acceptsMulticastGroup(it) }) {
            return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        }
        if (memberships.isNotEmpty() && !nonRootGroupReceive && !hasNetworkAdmin(process)) {
            return VfsResult.Err(VfsError.NOT_PERMITTED)
        }
        return lock.withLock {
            val endpoint = endpoints[socket]
                ?: return@withLock VfsResult.Err(VfsError.BAD_DESCRIPTOR)
            if (endpoint.portId != null) return@withLock VfsResult.Err(VfsError.INVALID_ARGUMENT)
            val portId = if (requested.portId == 0u) allocatePortLocked(process.id.toUInt())
            else requested.portId
            if (ports[portId] != null) return@withLock VfsResult.Err(VfsError.ADDRESS_IN_USE)
            endpoint.portId = portId
            ports[portId] = endpoint
            replaceMembershipsLocked(endpoint, memberships)
            VfsResult.Ok(Unit)
        }
    }

    fun connect(
        socket: NetlinkSocket,
        destination: NetlinkSocketAddress,
        process: Process,
    ): VfsResult<Unit> {
        val validation = validateDestination(destination, process)
        if (validation is VfsResult.Err) return validation
        return when (val bound = autobind(socket, process)) {
            is VfsResult.Ok -> VfsResult.Ok(Unit)
            is VfsResult.Err -> bound
        }
    }

    fun address(socket: NetlinkSocket): NetlinkSocketAddress = lock.withLock {
        val endpoint = endpoints[socket]
            ?: return@withLock KERNEL_ADDRESS
        NetlinkSocketAddress(endpoint.portId ?: 0u, membershipMask(endpoint.memberships))
    }

    fun setMembership(
        socket: NetlinkSocket,
        group: Int,
        add: Boolean,
        process: Process,
    ): VfsResult<Unit> {
        if (!acceptsMulticastGroup(group)) return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        if (!nonRootGroupReceive && !hasNetworkAdmin(process)) {
            return VfsResult.Err(VfsError.NOT_PERMITTED)
        }
        return lock.withLock {
            val endpoint = endpoints[socket]
                ?: return@withLock VfsResult.Err(VfsError.BAD_DESCRIPTOR)
            if (add) {
                if (endpoint.memberships.add(group)) {
                    subscribers.getOrPut(group, ::mutableSetOf).add(endpoint)
                }
            } else if (endpoint.memberships.remove(group)) {
                subscribers[group]?.let { members ->
                    members.remove(endpoint)
                    if (members.isEmpty()) subscribers.remove(group)
                }
            }
            VfsResult.Ok(Unit)
        }
    }

    fun membershipBytes(socket: NetlinkSocket): ByteArray = lock.withLock {
        val endpoint = endpoints[socket] ?: return@withLock ByteArray(0)
        val words = (multicastGroupCount + UInt.SIZE_BITS - 1) / UInt.SIZE_BITS
        ByteArray(words * UInt.SIZE_BYTES).also { bytes ->
            val output = LittleEndianBuffer(bytes)
            for (group in endpoint.memberships) {
                val word = (group - 1) / UInt.SIZE_BITS
                val bit = (group - 1) % UInt.SIZE_BITS
                val offset = word * UInt.SIZE_BYTES
                output.writeU32(offset, output.readU32(offset) or (1u shl bit))
            }
        }
    }

    fun send(
        socket: NetlinkSocket,
        destination: NetlinkSocketAddress,
        request: SocketSendRequest,
        bytes: ByteArray,
    ): VfsResult<Unit> {
        val validation = validateDestination(destination, request.process)
        if (validation is VfsResult.Err) return validation
        val sourcePort = when (val bound = autobind(socket, request.process)) {
            is VfsResult.Ok -> bound.value
            is VfsResult.Err -> return bound
        }
        if (destination.groups != 0u) {
            val group = destination.groups.countTrailingZeroBits() + 1
            val recipients = lock.withLock {
                subscribers[group].orEmpty().mapNotNull { endpoint ->
                    endpoint.socket.takeUnless { endpoint.portId == destination.portId }
                }
            }
            val source = NetlinkSocketAddress(sourcePort, 1u shl (group - 1))
            val failed = recipients.count { !it.enqueue(bytes, source) }
            return if (failed != 0 && socket.netlinkOptions().broadcastErrors) {
                VfsResult.Err(VfsError.NO_BUFFER_SPACE)
            } else {
                VfsResult.Ok(Unit)
            }
        }
        if (destination.portId == 0u) {
            return if (receiveFromUser(socket, request.process, sourcePort, bytes)) {
                VfsResult.Ok(Unit)
            } else {
                VfsResult.Err(VfsError.CONNECTION_REFUSED)
            }
        }
        val recipient = lock.withLock { ports[destination.portId]?.socket }
            ?: return VfsResult.Err(VfsError.CONNECTION_REFUSED)
        return if (recipient.enqueue(bytes, NetlinkSocketAddress(sourcePort, 0u))) {
            VfsResult.Ok(Unit)
        } else {
            VfsResult.Err(if (request.nonBlocking) VfsError.WOULD_BLOCK else VfsError.NO_BUFFER_SPACE)
        }
    }

    fun close(socket: NetlinkSocket) = lock.withLock {
        val endpoint = endpoints.remove(socket) ?: return@withLock
        endpoint.portId?.let { ports.remove(it) }
        replaceMembershipsLocked(endpoint, emptyList())
    }

    protected open fun receiveFromUser(
        socket: NetlinkSocket,
        process: Process,
        sourcePort: UInt,
        bytes: ByteArray,
    ): Boolean = false

    protected fun multicastFromKernel(group: Int, createReply: () -> NetlinkReply?) {
        if (!acceptsMulticastGroup(group)) return
        val recipients = lock.withLock { subscribers[group].orEmpty().map(Endpoint::socket) }
        if (recipients.isEmpty()) return
        val reply = createReply() ?: return
        val bytes = NetlinkCodec.encode(reply.type, reply.flags, 0u, payload = reply.payload)
        val groups = if (group <= UInt.SIZE_BITS) 1u shl (group - 1) else 0u
        val source = NetlinkSocketAddress(0u, groups)
        recipients.forEach { it.enqueue(bytes, source) }
    }

    protected fun hasNetworkAdmin(process: Process): Boolean =
        process.vfsOperationContext.privileged || ProcessManager.currentThread()?.let { thread ->
            thread.process === process && CapManager.hasAllCapability(thread, CapEnum.NET_ADMIN)
        } == true

    private fun validateDestination(
        destination: NetlinkSocketAddress,
        process: Process,
    ): VfsResult<Unit> {
        val privileged = hasNetworkAdmin(process)
        if (destination.groups != 0u) {
            val group = destination.groups.countTrailingZeroBits() + 1
            if (!acceptsMulticastGroup(group)) return VfsResult.Err(VfsError.INVALID_ARGUMENT)
            if (!nonRootGroupSend && !privileged) return VfsResult.Err(VfsError.NOT_PERMITTED)
        }
        if (destination.groups == 0u && destination.portId != 0u && !nonRootUserSend && !privileged) {
            return VfsResult.Err(VfsError.NOT_PERMITTED)
        }
        return VfsResult.Ok(Unit)
    }

    private fun autobind(socket: NetlinkSocket, process: Process): VfsResult<UInt> = lock.withLock {
        val endpoint = endpoints[socket]
            ?: return@withLock VfsResult.Err(VfsError.BAD_DESCRIPTOR)
        endpoint.portId?.let { return@withLock VfsResult.Ok(it) }
        val portId = allocatePortLocked(process.id.toUInt())
        endpoint.portId = portId
        ports[portId] = endpoint
        VfsResult.Ok(portId)
    }

    private fun allocatePortLocked(processPort: UInt): UInt {
        if (processPort != 0u && ports[processPort] == null) return processPort
        while (true) {
            val candidate = nextPortId--
            if (candidate != 0u && ports[candidate] == null) return candidate
        }
    }

    private fun replaceMembershipsLocked(endpoint: Endpoint, groups: Collection<Int>) {
        for (group in endpoint.memberships) {
            subscribers[group]?.let { members ->
                members.remove(endpoint)
                if (members.isEmpty()) subscribers.remove(group)
            }
        }
        endpoint.memberships.clear()
        for (group in groups) {
            endpoint.memberships += group
            subscribers.getOrPut(group, ::mutableSetOf).add(endpoint)
        }
    }

    private fun groups(mask: UInt): List<Int> = buildList {
        repeat(UInt.SIZE_BITS) { bit ->
            if (mask and (1u shl bit) != 0u) add(bit + 1)
        }
    }

    private fun membershipMask(groups: Set<Int>): UInt = groups.fold(0u) { mask, group ->
        if (group <= UInt.SIZE_BITS) mask or (1u shl (group - 1)) else mask
    }

    companion object {
        val KERNEL_ADDRESS = NetlinkSocketAddress(0u, 0u)
    }
}

internal abstract class NetlinkKernelProtocol(
    kind: NetlinkProtocolKind,
    nonRootGroupReceive: Boolean = true,
) : NetlinkProtocol(kind, nonRootGroupReceive = nonRootGroupReceive) {
    final override fun receiveFromUser(
        socket: NetlinkSocket,
        process: Process,
        sourcePort: UInt,
        bytes: ByteArray,
    ): Boolean {
        val messages = NetlinkCodec.decode(bytes) ?: return true
        val options = socket.netlinkOptions()
        for (message in messages) {
            if (message.flags.toInt() and NetlinkAbi.NLM_F_REQUEST == 0) continue
            val request = NetlinkRequest(process, message, sourcePort, options.strictCheck)
            val result = if (message.type.toInt() == NetlinkAbi.NLMSG_NOOP) {
                NetlinkResult.Success()
            } else {
                handle(request)
            }
            when (result) {
                is NetlinkResult.Success -> {
                    for (reply in result.replies) {
                        val flags = reply.flags or if (result.multipart) NetlinkAbi.NLM_F_MULTI else 0
                        socket.enqueue(
                            NetlinkCodec.encode(
                                reply.type,
                                flags,
                                message.sequence,
                                sourcePort,
                                reply.payload,
                            ),
                            KERNEL_ADDRESS,
                        )
                    }
                    if (result.multipart) {
                        socket.enqueue(done(message.sequence, sourcePort), KERNEL_ADDRESS)
                    } else if (message.flags.toInt() and NetlinkAbi.NLM_F_ACK != 0) {
                        socket.enqueue(
                            acknowledgment(request, options, null),
                            KERNEL_ADDRESS,
                        )
                    }
                }
                is NetlinkResult.Failure -> socket.enqueue(
                    acknowledgment(request, options, result),
                    KERNEL_ADDRESS,
                )
            }
        }
        return true
    }

    protected abstract fun handle(request: NetlinkRequest): NetlinkResult

    protected fun notify(group: Int, createReply: () -> NetlinkReply?) =
        multicastFromKernel(group, createReply)

    private fun done(sequence: UInt, portId: UInt): ByteArray = NetlinkCodec.encode(
        NetlinkAbi.NLMSG_DONE,
        NetlinkAbi.NLM_F_MULTI,
        sequence,
        portId,
        ByteArray(Int.SIZE_BYTES),
    )

    private fun acknowledgment(
        request: NetlinkRequest,
        options: NetlinkSocketOptions,
        failure: NetlinkResult.Failure?,
    ): ByteArray {
        val includeRequest = failure != null && !options.capAck
        val originalSize = if (includeRequest) NetlinkCodec.align(request.message.raw.size)
        else NetlinkCodec.HEADER_SIZE
        val fixed = ByteArray(Int.SIZE_BYTES + originalSize)
        LittleEndianBuffer(fixed).writeU32(
            0,
            (failure?.error?.errno?.unaryMinus() ?: 0).toUInt(),
        )
        val copied = if (includeRequest) request.message.raw.size else NetlinkCodec.HEADER_SIZE
        request.message.raw.bytes.copyInto(
            fixed,
            Int.SIZE_BYTES,
            request.message.raw.offset,
            request.message.raw.offset + copied,
        )
        val attributes = if (options.extendedAck && failure != null) buildList {
            failure.message?.let { add(NetlinkAttribute.string(NLMSGERR_ATTR_MSG, it)) }
            failure.offset?.let { add(NetlinkAttribute.u32(NLMSGERR_ATTR_OFFS, it.toUInt())) }
        } else {
            emptyList()
        }
        var flags = if (includeRequest) 0 else NetlinkAbi.NLM_F_CAPPED
        if (attributes.isNotEmpty()) flags = flags or NetlinkAbi.NLM_F_ACK_TLVS
        return NetlinkCodec.encode(
            NetlinkAbi.NLMSG_ERROR,
            flags,
            request.message.sequence,
            request.replyPortId,
            NetlinkCodec.payload(fixed, attributes),
        )
    }

    companion object {
        private const val NLMSGERR_ATTR_MSG = 1
        private const val NLMSGERR_ATTR_OFFS = 2
    }
}

private object UserspaceNetlinkProtocol : NetlinkProtocol(
    NetlinkProtocolKind.USERSOCK,
    nonRootUserSend = true,
    nonRootGroupReceive = false,
) {
    override val multicastGroupCount = UInt.SIZE_BITS
}

internal object NetlinkProtocols {
    fun createSocket(protocol: Int, type: SocketType): NetlinkSocket? = when (
        NetlinkProtocolKind.fromNumber(protocol)
    ) {
        NetlinkProtocolKind.ROUTE -> RouteNetlinkProtocol.createSocket(type)
        NetlinkProtocolKind.USERSOCK -> UserspaceNetlinkProtocol.createSocket(type)
        NetlinkProtocolKind.GENERIC -> GenericNetlinkProtocol.createSocket(type)
        null -> null
    }
}

internal data class NetlinkSocketOptions(
    val broadcastErrors: Boolean = false,
    val noEnobufs: Boolean = false,
    val capAck: Boolean = false,
    val extendedAck: Boolean = false,
    val strictCheck: Boolean = false,
)

internal class NetlinkSocket internal constructor(
    private val subsystem: NetlinkProtocol,
    type: SocketType,
) : AbstractSocket(SocketDomain.NETLINK, type, subsystem.kind.number) {
    private data class Datagram(val bytes: ByteArray, val source: NetlinkSocketAddress)

    private var peer = NetlinkProtocol.KERNEL_ADDRESS
    private val messages = ArrayDeque<Datagram>()
    private var queuedBytes = 0
    private val readWaiters = IoWaitQueue()
    private var netlinkOptions = NetlinkSocketOptions()

    override fun bindSocket(process: Process, address: SocketAddress): VfsResult<Unit> =
        subsystem.bind(
            this,
            address as? NetlinkSocketAddress
                ?: return VfsResult.Err(VfsError.ADDRESS_FAMILY_NOT_SUPPORTED),
            process,
        )

    override fun connectSocket(
        process: Process,
        address: SocketAddress?,
        nonBlocking: Boolean,
    ): VfsResult<Unit> {
        val destination = when (address) {
            null -> NetlinkProtocol.KERNEL_ADDRESS
            is NetlinkSocketAddress -> address
            else -> return VfsResult.Err(VfsError.ADDRESS_FAMILY_NOT_SUPPORTED)
        }
        val result = subsystem.connect(this, destination, process)
        if (result is VfsResult.Ok) lock.withLock { peer = destination }
        return result
    }

    override fun localAddress(): NetlinkSocketAddress = subsystem.address(this)

    override fun peerAddress(): VfsResult<NetlinkSocketAddress> = VfsResult.Ok(
        lock.withLock { peer },
    )

    override fun sendSocket(request: SocketSendRequest): IoResult {
        request.ancillary.release()
        val explicit = request.destination?.let {
            it as? NetlinkSocketAddress
                ?: return IoResult.failure(VfsError.ADDRESS_FAMILY_NOT_SUPPORTED)
        }
        val prepared = lock.withLock {
            when {
                closed -> VfsResult.Err(VfsError.BAD_DESCRIPTOR)
                request.count > optionsLocked().sendBufferSize ->
                    VfsResult.Err(VfsError.MESSAGE_TOO_LONG)
                else -> VfsResult.Ok(explicit ?: peer)
            }
        }
        val destination = when (prepared) {
            is VfsResult.Ok -> prepared.value
            is VfsResult.Err -> return IoResult.failure(prepared.error)
        }
        val bytes = ByteArray(request.count)
        if (request.source.copyTo(request.offset, bytes, 0, request.count) != request.count) {
            return IoResult.failure(VfsError.FAULT)
        }
        return when (val result = subsystem.send(this, destination, request, bytes)) {
            is VfsResult.Ok -> IoResult.success(request.count)
            is VfsResult.Err -> IoResult.failure(result.error)
        }
    }

    override fun receiveSocket(request: SocketReceiveRequest): VfsResult<SocketReceiveResult> {
        val deadline = request.deadline ?: if (request.nonBlocking) null
        else SocketDeadline.after(socketOptions().receiveTimeoutNanos)
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
                takeErrorLocked()?.let { return@withLock VfsResult.Err(it) }
                if (closed) return@withLock VfsResult.Err(VfsError.NOT_CONNECTED)
                if (request.nonBlocking) return@withLock VfsResult.Err(VfsError.WOULD_BLOCK)
                if (deadline == null) {
                    val thread = ProcessManager.currentThread()
                        ?: return@withLock VfsResult.Err(VfsError.NOT_FOUND)
                    waiter = readWaiters.add(thread)
                }
                null
            }
            if (result != null) return result
            val waitError = if (deadline != null) deadline.await {
                lock.withLock { messages.isNotEmpty() || hasPendingError() || closed }
            } else if (readWaiters.await(lock, checkNotNull(waiter))) null else VfsError.INTERRUPTED
            if (waitError != null) return VfsResult.Err(waitError)
        }
    }

    override fun setProtocolOption(
        process: Process,
        level: Int,
        name: Int,
        value: ByteArray,
    ): VfsResult<Unit> {
        if (level != NetlinkAbi.SOL_NETLINK) {
            return VfsResult.Err(VfsError.PROTOCOL_OPTION_NOT_AVAILABLE)
        }
        if (name == NetlinkAbi.NETLINK_ADD_MEMBERSHIP ||
            name == NetlinkAbi.NETLINK_DROP_MEMBERSHIP
        ) {
            if (value.size < UInt.SIZE_BYTES) return VfsResult.Err(VfsError.INVALID_ARGUMENT)
            val rawGroup = LittleEndianBuffer(value).readU32(0)
            if (rawGroup > Int.MAX_VALUE.toUInt()) return VfsResult.Err(VfsError.INVALID_ARGUMENT)
            return subsystem.setMembership(
                this,
                rawGroup.toInt(),
                name == NetlinkAbi.NETLINK_ADD_MEMBERSHIP,
                process,
            )
        }
        if (value.size < UInt.SIZE_BYTES) return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        val enabled = LittleEndianBuffer(value).readU32(0) != 0u
        return lock.withLock {
            netlinkOptions = when (name) {
                NetlinkAbi.NETLINK_BROADCAST_ERROR -> netlinkOptions.copy(broadcastErrors = enabled)
                NetlinkAbi.NETLINK_NO_ENOBUFS -> netlinkOptions.copy(noEnobufs = enabled)
                NetlinkAbi.NETLINK_CAP_ACK -> netlinkOptions.copy(capAck = enabled)
                NetlinkAbi.NETLINK_EXT_ACK -> netlinkOptions.copy(extendedAck = enabled)
                NetlinkAbi.NETLINK_GET_STRICT_CHK -> netlinkOptions.copy(strictCheck = enabled)
                else -> return@withLock VfsResult.Err(VfsError.PROTOCOL_OPTION_NOT_AVAILABLE)
            }
            VfsResult.Ok(Unit)
        }
    }

    override fun getProtocolOption(level: Int, name: Int): VfsResult<ByteArray> {
        if (level != NetlinkAbi.SOL_NETLINK) {
            return VfsResult.Err(VfsError.PROTOCOL_OPTION_NOT_AVAILABLE)
        }
        if (name == NetlinkAbi.NETLINK_LIST_MEMBERSHIPS) {
            return VfsResult.Ok(subsystem.membershipBytes(this))
        }
        val enabled = lock.withLock {
            when (name) {
                NetlinkAbi.NETLINK_BROADCAST_ERROR -> netlinkOptions.broadcastErrors
                NetlinkAbi.NETLINK_NO_ENOBUFS -> netlinkOptions.noEnobufs
                NetlinkAbi.NETLINK_CAP_ACK -> netlinkOptions.capAck
                NetlinkAbi.NETLINK_EXT_ACK -> netlinkOptions.extendedAck
                NetlinkAbi.NETLINK_GET_STRICT_CHK -> netlinkOptions.strictCheck
                else -> return@withLock null
            }
        } ?: return VfsResult.Err(VfsError.PROTOCOL_OPTION_NOT_AVAILABLE)
        return VfsResult.Ok(ByteArray(UInt.SIZE_BYTES).also {
            LittleEndianBuffer(it).writeU32(0, if (enabled) 1u else 0u)
        })
    }

    internal fun netlinkOptions(): NetlinkSocketOptions = lock.withLock { netlinkOptions }

    internal fun enqueue(bytes: ByteArray, source: NetlinkSocketAddress): Boolean = lock.withLock {
        if (closed) return@withLock false
        if (bytes.size > optionsLocked().receiveBufferSize - queuedBytes) {
            if (!netlinkOptions.noEnobufs) {
                storeErrorLocked(VfsError.NO_BUFFER_SPACE)
                readWaiters.wakeOne()
            }
            return@withLock false
        }
        messages += Datagram(bytes, source)
        queuedBytes += bytes.size
        readWaiters.wakeOne()
        true
    }

    override fun readableBytes(): Int = lock.withLock { messages.firstOrNull()?.bytes?.size ?: 0 }

    override fun pollSocket(events: Int): Int = lock.withLock {
        var available = PollEvents.NORMAL_OUTPUT
        if (messages.isNotEmpty()) available = available or PollEvents.NORMAL_INPUT
        if (hasPendingError()) available = available or PollEvents.POLLERR
        if (closed) available = available or PollEvents.POLLHUP
        available and (events or PollEvents.UNCONDITIONALLY_REPORTED)
    }

    override fun closeSocketLocked(): (() -> Unit) {
        messages.clear()
        queuedBytes = 0
        readWaiters.wakeAll()
        return { subsystem.close(this) }
    }
}
