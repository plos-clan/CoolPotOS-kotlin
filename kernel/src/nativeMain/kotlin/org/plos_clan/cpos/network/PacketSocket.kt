@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package org.plos_clan.cpos.network

import kotlin.concurrent.atomics.AtomicReference
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
import org.plos_clan.cpos.tasks.IoWaitQueue
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.utils.PollEvents

internal object PacketSocketProtocol {
    private val sockets = AtomicReference<List<PacketSocket>>(emptyList())

    fun createSocket(protocol: Int): PacketSocket = PacketSocket(
        this,
        protocol,
        ((protocol and 0xFF) shl 8 or (protocol ushr 8 and 0xFF)).toUShort(),
    ).also(::register)

    fun receive(
        interface_: NetworkInterface,
        frame: ByteArray,
        ethernet: EthernetHeader,
    ) {
        sockets.load().forEach { it.enqueue(interface_, frame, ethernet) }
    }

    fun unregister(socket: PacketSocket) {
        while (true) {
            val current = sockets.load()
            val updated = current.filterNot { it === socket }
            if (current.size == updated.size || sockets.compareAndSet(current, updated)) return
        }
    }

    private fun register(socket: PacketSocket) {
        while (true) {
            val current = sockets.load()
            if (sockets.compareAndSet(current, current + socket)) return
        }
    }
}

internal class PacketSocket internal constructor(
    private val subsystem: PacketSocketProtocol,
    protocol: Int,
    private val socketProtocol: UShort,
) : AbstractSocket(SocketDomain.PACKET, SocketType.DATAGRAM, protocol) {
    private data class Datagram(
        val bytes: ByteArray,
        val source: PacketSocketAddress,
    )

    private var binding: PacketSocketAddress? = null
    private val messages = ArrayDeque<Datagram>()
    private var queuedBytes = 0
    private val readWaiters = IoWaitQueue()

    override val supportsSocketFilter = true

    override fun bindSocket(process: Process, address: SocketAddress): VfsResult<Unit> {
        val requested = address as? PacketSocketAddress
            ?: return VfsResult.Err(VfsError.ADDRESS_FAMILY_NOT_SUPPORTED)
        val interface_ = requested.interfaceIndex.takeIf { it != 0 }?.let {
            NetworkStack.interfaceByIndex(it) ?: return VfsResult.Err(VfsError.NO_DEVICE)
        }
        if (interface_ != null && interface_.kind != NetworkInterfaceKind.ETHERNET) {
            return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        }
        return lock.withLock {
            if (closed) return@withLock VfsResult.Err(VfsError.BAD_DESCRIPTOR)
            binding = PacketSocketAddress(
                requested.interfaceIndex,
                requested.protocol,
                interface_?.kind?.hardwareType ?: 0u,
                hardwareAddress = interface_?.hardwareAddress,
            )
            VfsResult.Ok(Unit)
        }
    }

    override fun localAddress(): PacketSocketAddress = lock.withLock {
        binding ?: PacketSocketAddress(protocol = socketProtocol)
    }

    override fun sendSocket(request: SocketSendRequest): IoResult {
        request.ancillary.release()
        val explicit = request.destination as? PacketSocketAddress
            ?: return IoResult.failure(
                if (request.destination == null) VfsError.DESTINATION_ADDRESS_REQUIRED
                else VfsError.ADDRESS_FAMILY_NOT_SUPPORTED,
            )
        val destination = lock.withLock {
            if (closed) return@withLock null
            val bound = binding
            PacketSocketAddress(
                explicit.interfaceIndex.takeIf { it != 0 } ?: bound?.interfaceIndex ?: 0,
                explicit.protocol.takeIf { it != 0.toUShort() }
                    ?: bound?.protocol?.takeIf { it != 0.toUShort() }
                    ?: socketProtocol,
                hardwareAddress = explicit.hardwareAddress,
            )
        } ?: return IoResult.failure(VfsError.BAD_DESCRIPTOR)
        if (destination.interfaceIndex == 0 || destination.protocol == 0.toUShort() ||
            destination.hardwareAddress == null
        ) return IoResult.failure(VfsError.INVALID_ARGUMENT)
        val interface_ = NetworkStack.interfaceByIndex(destination.interfaceIndex)
            ?: return IoResult.failure(VfsError.NO_DEVICE)
        if (interface_.kind != NetworkInterfaceKind.ETHERNET) {
            return IoResult.failure(VfsError.INVALID_ARGUMENT)
        }
        if (!interface_.running) return IoResult.failure(VfsError.NETWORK_UNREACHABLE)
        if (request.count > interface_.mtu) return IoResult.failure(VfsError.MESSAGE_TOO_LONG)

        val frame = ByteArray(EthernetHeader.SIZE + request.count)
        if (request.source.copyTo(
                request.offset,
                frame,
                EthernetHeader.SIZE,
                request.count,
            ) != request.count
        ) return IoResult.failure(VfsError.FAULT)
        EthernetHeader(
            destination.hardwareAddress,
            interface_.hardwareAddress,
            destination.protocol,
        ).writeTo(frame)
        return if (interface_.transmit(frame)) IoResult.success(request.count)
        else IoResult.failure(VfsError.NO_BUFFER_SPACE)
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

    override fun setProtocolOption(
        process: Process,
        level: Int,
        name: Int,
        value: ByteArray,
    ): VfsResult<Unit> = if (level == SOL_PACKET && name == PACKET_AUXDATA &&
        value.size >= Int.SIZE_BYTES
    ) {
        VfsResult.Ok(Unit)
    } else {
        VfsResult.Err(VfsError.PROTOCOL_OPTION_NOT_AVAILABLE)
    }

    internal fun enqueue(
        interface_: NetworkInterface,
        frame: ByteArray,
        ethernet: EthernetHeader,
    ) = lock.withLock {
        val bound = binding
        val protocol = bound?.protocol ?: socketProtocol
        if (closed || protocol == 0.toUShort() || protocol != ethernet.type ||
            bound != null && bound.interfaceIndex != 0 && bound.interfaceIndex != interface_.index
        ) return@withLock
        val packetLength = frame.size - EthernetHeader.SIZE
        val capturedLength = filterPacketLocked(frame, EthernetHeader.SIZE, packetLength)
        if (capturedLength == 0 ||
            queuedBytes > optionsLocked().receiveBufferSize - capturedLength
        ) return@withLock
        val packetType = when {
            ethernet.destination == MacAddress.BROADCAST -> PACKET_BROADCAST
            ethernet.destination[0].toUInt() and 1u != 0u -> PACKET_MULTICAST
            else -> PACKET_HOST
        }
        messages += Datagram(
            frame.copyOfRange(EthernetHeader.SIZE, EthernetHeader.SIZE + capturedLength),
            PacketSocketAddress(
                interface_.index,
                ethernet.type,
                interface_.kind.hardwareType,
                packetType,
                ethernet.source,
            ),
        )
        queuedBytes += capturedLength
        readWaiters.wakeReady(1)
    }

    override fun readableBytes(): Int = lock.withLock { messages.firstOrNull()?.bytes?.size ?: 0 }

    override fun pollSocket(events: Int): Int = lock.withLock {
        var available = if (closed) 0 else PollEvents.NORMAL_OUTPUT
        if (messages.isNotEmpty()) available = available or PollEvents.NORMAL_INPUT
        if (hasPendingError()) available = available or PollEvents.POLLERR
        if (closed) available = available or PollEvents.POLLHUP
        available and (events or PollEvents.UNCONDITIONALLY_REPORTED)
    }

    override fun receiveBufferSizeChangedLocked(size: Int) {
        readWaiters.wakeAll()
    }

    override fun closeSocketLocked(): () -> Unit {
        messages.clear()
        queuedBytes = 0
        readWaiters.wakeAll()
        return { subsystem.unregister(this) }
    }

    private fun receiveReady(): Boolean = lock.withLock {
        messages.isNotEmpty() || closed || hasPendingError()
    }

    companion object {
        private const val SOL_PACKET = 263
        private const val PACKET_AUXDATA = 8
        private const val PACKET_HOST: UByte = 0u
        private const val PACKET_BROADCAST: UByte = 1u
        private const val PACKET_MULTICAST: UByte = 2u
    }
}
