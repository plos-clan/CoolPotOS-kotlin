package org.plos_clan.cpos.fs.sock

import org.plos_clan.cpos.fs.vfs.IoResult
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.utils.PollEvents

internal class UnixDatagramSocket(
    subsystem: UnixSocketSubsystem,
    private val identity: UnixCredentials,
) : UnixSocket(subsystem, UnixSocketType.DATAGRAM) {
    private data class Peer(
        val socket: UnixDatagramSocket,
        val address: UnixSocketAddress,
        val credentials: UnixCredentials,
    )

    private data class Datagram(
        val bytes: ByteArray,
        val sourceSocket: UnixDatagramSocket,
        val sourceAddress: UnixSocketAddress,
        val credentials: UnixCredentials,
        val ancillary: UnixAncillaryData?,
    ) {
        val accountedSize: Int
            get() = maxOf(1, bytes.size)
    }

    private var messages = ArrayDeque<Datagram>()
    private val readWaiters = IoWaitQueue()
    private val writeWaiters = IoWaitQueue()
    private var queuedBytes = 0
    private var peer: Peer? = null
    private var readOpen = true
    private var writeOpen = true

    override fun connect(
        peer: UnixSocket?,
        address: UnixSocketAddress,
        credentials: UnixCredentials,
        nonBlocking: Boolean,
    ): VfsResult<Unit> = lock.withLock {
        if (closed) return@withLock VfsResult.Err(VfsError.BAD_DESCRIPTOR)
        if (peer == null) {
            this.peer = null
            readOpen = true
            writeOpen = true
            return@withLock VfsResult.Ok(Unit)
        }
        val datagram = peer as? UnixDatagramSocket
            ?: return@withLock VfsResult.Err(VfsError.WRONG_PROTOCOL_TYPE)
        this.peer = Peer(datagram, address, datagram.identity)
        readOpen = true
        writeOpen = true
        VfsResult.Ok(Unit)
    }

    override fun shutdown(mode: UnixShutdownMode): VfsResult<Unit> {
        val replacement = if (mode.reads) ArrayDeque<Datagram>() else null
        var discarded: ArrayDeque<Datagram>? = null
        var sendTarget: UnixDatagramSocket? = null
        val result = lock.withLock {
            if (peer == null) return@withLock VfsResult.Err(VfsError.NOT_CONNECTED)
            if (mode.reads && readOpen) {
                readOpen = false
                discarded = messages
                messages = checkNotNull(replacement)
                queuedBytes = 0
                readWaiters.wakeAll()
                writeWaiters.wakeAll()
            }
            if (mode.writes && writeOpen) {
                writeOpen = false
                sendTarget = peer?.socket
            }
            VfsResult.Ok(Unit)
        }
        discarded?.forEach { it.ancillary?.release() }
        sendTarget?.wakeBlockedSenders()
        return result
    }

    override fun peerAddress(): VfsResult<UnixSocketAddress> = lock.withLock {
        peer?.address?.let { VfsResult.Ok(it) } ?: VfsResult.Err(VfsError.NOT_CONNECTED)
    }

    override fun peerCredentials(): VfsResult<UnixCredentials> = lock.withLock {
        peer?.credentials?.let { VfsResult.Ok(it) } ?: VfsResult.Err(VfsError.NOT_CONNECTED)
    }

    override fun send(request: UnixSendRequest): IoResult {
        val selected = if (request.destination != null) {
            val destination = request.destination as? UnixSocketDestination.Resolved
            val target = destination?.socket as? UnixDatagramSocket
            if (target == null) {
                request.ancillary.release()
                return IoResult.failure(VfsError.WRONG_PROTOCOL_TYPE)
            }
            Peer(target, destination.address, target.identity)
        } else {
            lock.withLock { peer }
        }
        if (selected == null) {
            request.ancillary.release()
            return IoResult.failure(VfsError.DESTINATION_ADDRESS_REQUIRED)
        }
        if (!lock.withLock { writeOpen && !closed }) {
            request.ancillary.release()
            signalBrokenPipe(request.noSignal)
            return IoResult.failure(VfsError.BROKEN_PIPE)
        }
        val options = socketOptions()
        val maximumMessageSize = minOf(
            options.sendBufferSize,
            selected.socket.maximumMessageSize(),
        )
        if (maxOf(1, request.count) > maximumMessageSize) {
            request.ancillary.release()
            return IoResult.failure(VfsError.MESSAGE_TOO_LONG)
        }

        val data = ByteArray(request.count)
        if (request.count != 0 &&
            request.source.copyTo(request.offset, data, 0, request.count) != request.count
        ) {
            request.ancillary.release()
            return IoResult.failure(VfsError.FAULT)
        }
        val message = Datagram(
            data,
            this,
            localAddress(),
            request.credentials,
            request.ancillary.takeUnless { it.isEmpty },
        )
        val deadline = if (request.nonBlocking) null else {
            UnixSocketDeadline.after(options.sendTimeoutNanos)
        }
        val result = selected.socket.enqueue(message, request.nonBlocking, deadline)
        if (result is VfsResult.Err) {
            request.ancillary.release()
            if (result.error == VfsError.BROKEN_PIPE) signalBrokenPipe(request.noSignal)
            return IoResult.failure(result.error)
        }
        return IoResult.success(request.count)
    }

    override fun receive(request: UnixReceiveRequest): VfsResult<UnixReceiveResult> {
        val thread = ProcessManager.currentThread()
        val options = socketOptions()
        val deadline = if (request.nonBlocking) null else UnixSocketDeadline.earliest(
            request.deadline,
            UnixSocketDeadline.after(options.receiveTimeoutNanos),
        )
        while (true) {
            var waiter: IoWaitQueue.Waiter? = null
            val received = lock.withLock {
                val message = messages.firstOrNull()
                if (message != null) {
                    val copied = minOf(request.count, message.bytes.size)
                    if (copied != 0 && request.destination.copyFrom(
                            request.offset,
                            message.bytes,
                            0,
                            copied,
                        ) != copied
                    ) {
                        return@withLock VfsResult.Err(VfsError.FAULT)
                    }
                    val ancillary = if (request.peek) {
                        message.ancillary?.duplicate() ?: message.ancillary?.let {
                            return@withLock VfsResult.Err(VfsError.NO_MEMORY)
                        }
                    } else {
                        check(messages.removeFirst() === message)
                        queuedBytes -= message.accountedSize
                        writeWaiters.wakeReady(
                            optionsLocked().receiveBufferSize - queuedBytes,
                        )
                        if (messages.isNotEmpty()) readWaiters.wakeOne()
                        message.ancillary
                    }
                    return@withLock VfsResult.Ok(
                        UnixReceiveResult(
                            bytes = if (request.returnFullLength) message.bytes.size else copied,
                            copiedBytes = copied,
                            source = message.sourceAddress,
                            ancillary = ancillary,
                            senderCredentials = message.credentials,
                            truncated = copied < message.bytes.size,
                            endOfRecord = true,
                        ),
                    )
                }
                if (!readOpen || closed) {
                    return@withLock VfsResult.Ok(
                        UnixReceiveResult(0, 0, UnixSocketAddress.Unnamed),
                    )
                }
                if (request.nonBlocking) return@withLock VfsResult.Err(VfsError.WOULD_BLOCK)
                if (deadline == null) waiter = readWaiters.add(checkNotNull(thread))
                null
            }
            if (received != null) return received
            if (deadline != null) {
                val waitError = deadline.await(::receiveReady)
                if (waitError != null) return VfsResult.Err(waitError)
                continue
            }
            if (!readWaiters.await(lock, checkNotNull(waiter))) {
                return VfsResult.Err(VfsError.INTERRUPTED)
            }
        }
    }

    override fun readableBytes(): Int = lock.withLock { messages.firstOrNull()?.bytes?.size ?: 0 }

    override fun pollSocket(events: Int): Int {
        var peerSocket: UnixDatagramSocket? = null
        var checkWritable = false
        var available = lock.withLock {
            var result = if (messages.isNotEmpty() || !readOpen || closed) {
                PollEvents.NORMAL_INPUT
            } else {
                0
            }
            checkWritable = writeOpen && !closed
            if (checkWritable) {
                peerSocket = peer?.socket
            } else {
                result = result or PollEvents.POLLERR
            }
            if (closed) result = result or PollEvents.POLLHUP
            result
        }
        if (checkWritable && peerSocket?.isWritable() != false) {
            available = available or PollEvents.NORMAL_OUTPUT
        }
        return available and (events or PollEvents.UNCONDITIONALLY_REPORTED)
    }

    override fun receiveBufferSizeChangedLocked(size: Int) {
        writeWaiters.wakeAll()
    }

    override fun closeLocked(): (() -> Unit)? {
        val discarded = messages
        val sendTarget = peer?.socket
        messages = ArrayDeque()
        readOpen = false
        writeOpen = false
        peer = null
        queuedBytes = 0
        readWaiters.wakeAll()
        writeWaiters.wakeAll()
        if (discarded.isEmpty() && sendTarget == null) return null
        return {
            discarded.forEach { it.ancillary?.release() }
            sendTarget?.wakeBlockedSenders()
        }
    }

    override fun pairWith(peer: UnixSocket, credentials: UnixCredentials): VfsResult<Unit> {
        val datagramPeer = peer as? UnixDatagramSocket
            ?: return VfsResult.Err(VfsError.WRONG_PROTOCOL_TYPE)
        lock.withLock {
            if (this.peer != null) return VfsResult.Err(VfsError.ALREADY_CONNECTED)
            this.peer = Peer(datagramPeer, UnixSocketAddress.Unnamed, credentials)
        }
        datagramPeer.lock.withLock {
            check(datagramPeer.peer == null)
            datagramPeer.peer = Peer(this, UnixSocketAddress.Unnamed, credentials)
        }
        return VfsResult.Ok(Unit)
    }

    private fun enqueue(
        message: Datagram,
        nonBlocking: Boolean,
        deadline: UnixSocketDeadline?,
    ): VfsResult<Unit> {
        val thread = ProcessManager.currentThread()
        while (true) {
            if (!message.sourceSocket.isSendOpen()) {
                return VfsResult.Err(VfsError.BROKEN_PIPE)
            }
            var waiter: IoWaitQueue.Waiter? = null
            val result = lock.withLock {
                if (closed || !readOpen) return@withLock VfsResult.Err(VfsError.CONNECTION_REFUSED)
                val connectedPeer = peer?.socket
                if (connectedPeer != null && connectedPeer !== message.sourceSocket) {
                    return@withLock VfsResult.Err(VfsError.CONNECTION_REFUSED)
                }
                val capacity = optionsLocked().receiveBufferSize
                if (message.accountedSize > capacity) {
                    return@withLock VfsResult.Err(VfsError.MESSAGE_TOO_LONG)
                }
                if (message.accountedSize <= capacity - queuedBytes) {
                    messages += message
                    queuedBytes += message.accountedSize
                    readWaiters.wakeOne()
                    return@withLock VfsResult.Ok(Unit)
                }
                if (nonBlocking) return@withLock VfsResult.Err(VfsError.WOULD_BLOCK)
                if (deadline == null) {
                    waiter = writeWaiters.add(checkNotNull(thread), message.accountedSize)
                }
                null
            }
            if (result != null) return result
            if (deadline != null) {
                val waitError = deadline.await {
                    !message.sourceSocket.isSendOpen() || enqueueReady(message)
                }
                if (waitError != null) return VfsResult.Err(waitError)
                continue
            }
            if (!writeWaiters.await(lock, checkNotNull(waiter))) {
                return VfsResult.Err(VfsError.INTERRUPTED)
            }
        }
    }

    private fun isWritable(): Boolean = lock.withLock {
        !closed && readOpen && queuedBytes < optionsLocked().receiveBufferSize
    }

    private fun receiveReady(): Boolean = lock.withLock {
        messages.isNotEmpty() || !readOpen || closed
    }

    private fun isSendOpen(): Boolean = lock.withLock { !closed && writeOpen }

    private fun wakeBlockedSenders() = lock.withLock { writeWaiters.wakeAll() }

    private fun maximumMessageSize(): Int = lock.withLock { optionsLocked().receiveBufferSize }

    private fun enqueueReady(message: Datagram): Boolean = lock.withLock {
        val capacity = optionsLocked().receiveBufferSize
        closed || !readOpen || peer?.socket?.let { it !== message.sourceSocket } == true ||
            message.accountedSize > capacity || message.accountedSize <= capacity - queuedBytes
    }

}