@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.fs.sock

import bridge.wait_for_interrupt
import org.plos_clan.cpos.drivers.TscClock
import org.plos_clan.cpos.fs.vfs.AccessMode
import org.plos_clan.cpos.fs.vfs.Inode
import org.plos_clan.cpos.fs.vfs.InodeBackend
import org.plos_clan.cpos.fs.vfs.InodeType
import org.plos_clan.cpos.fs.vfs.IoMode
import org.plos_clan.cpos.fs.vfs.IoResult
import org.plos_clan.cpos.fs.vfs.ModeAwareOpenFileBackend
import org.plos_clan.cpos.fs.vfs.OpenFileBackend
import org.plos_clan.cpos.fs.vfs.OpenOptions
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsOperationContext
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.mem.PreparedBufferSource
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.tasks.Scheduler
import org.plos_clan.cpos.tasks.Signal
import org.plos_clan.cpos.tasks.SignalInfo
import org.plos_clan.cpos.tasks.SignalRouter
import org.plos_clan.cpos.utils.IrqSpinLock
import org.plos_clan.cpos.utils.LittleEndianBuffer

private const val DEFAULT_SOCKET_BUFFER_SIZE = 212_992

internal enum class SocketDomain(val abiValue: Int) {
    UNIX(1),
    IPV4(2),
    NETLINK(16),
    ;

    companion object {
        fun fromAbi(value: Int): SocketDomain? = entries.firstOrNull { it.abiValue == value }
    }
}

internal enum class SocketType(val abiValue: Int, val connectionOriented: Boolean) {
    STREAM(1, true),
    DATAGRAM(2, false),
    RAW(3, false),
    SEQUENCED_PACKET(5, true),
    ;

    companion object {
        fun fromAbi(value: Int): SocketType? = entries.firstOrNull { it.abiValue == value }
    }
}

internal interface SocketAddress {
    val domain: SocketDomain
}

internal data object UnspecifiedSocketAddress : SocketAddress {
    override val domain = SocketDomain.UNIX
}

internal enum class SocketShutdownMode(val reads: Boolean, val writes: Boolean) {
    READ(reads = true, writes = false),
    WRITE(reads = false, writes = true),
    BOTH(reads = true, writes = true),
}

internal data class SocketLinger(val enabled: Boolean = false, val seconds: Int = 0)

internal data class SocketOptions(
    val sendBufferSize: Int = DEFAULT_SOCKET_BUFFER_SIZE,
    val receiveBufferSize: Int = DEFAULT_SOCKET_BUFFER_SIZE,
    val passCredentials: Boolean = false,
    val receiveLowWatermark: Int = 1,
    val sendTimeoutNanos: ULong? = null,
    val receiveTimeoutNanos: ULong? = null,
    val reuseAddress: Boolean = false,
    val broadcast: Boolean = false,
    val keepAlive: Boolean = false,
    val linger: SocketLinger = SocketLinger(),
)

internal data class SocketSendRequest(
    val process: Process,
    val source: PreparedBufferSource,
    val offset: Int,
    val count: Int,
    val ancillary: UnixAncillaryData = UnixAncillaryData(),
    val destination: SocketAddress? = null,
    val nonBlocking: Boolean = false,
    val noSignal: Boolean = false,
    val more: Boolean = false,
)

internal data class SocketReceiveRequest(
    val destination: PreparedBufferDestination,
    val offset: Int,
    val count: Int,
    val nonBlocking: Boolean = false,
    val peek: Boolean = false,
    val waitAll: Boolean = false,
    val returnFullLength: Boolean = false,
    val deadline: SocketDeadline? = null,
)

internal data class SocketReceiveResult(
    val bytes: Int,
    val copiedBytes: Int,
    val source: SocketAddress,
    val ancillary: UnixAncillaryData? = null,
    val senderCredentials: UnixCredentials? = null,
    val truncated: Boolean = false,
    val endOfRecord: Boolean = false,
)

internal data class AcceptedSocket(
    val socket: AbstractSocket,
    val peerAddress: SocketAddress,
)

internal class SocketDeadline private constructor(
    private val expirationNanos: ULong,
) {
    fun expired(): Boolean = !TscClock.isReady || TscClock.nanoTime() >= expirationNanos

    fun remainingNanos(): ULong {
        if (!TscClock.isReady) return 0uL
        val now = TscClock.nanoTime()
        return if (now >= expirationNanos) 0uL else expirationNanos - now
    }

    fun await(ready: () -> Boolean): VfsError? {
        val thread = ProcessManager.currentThread() ?: return VfsError.NOT_FOUND
        while (!ready()) {
            if (thread.hasPendingSignal()) return VfsError.INTERRUPTED
            if (expired()) return VfsError.WOULD_BLOCK
            Scheduler.yieldCurrent()
            wait_for_interrupt()
        }
        return null
    }

    companion object {
        fun after(timeoutNanos: ULong?): SocketDeadline? {
            if (timeoutNanos == null) return null
            val now = TscClock.nanoTime()
            val expiration = if (timeoutNanos > ULong.MAX_VALUE - now) ULong.MAX_VALUE
            else now + timeoutNanos
            return SocketDeadline(expiration)
        }

        fun earliest(first: SocketDeadline?, second: SocketDeadline?): SocketDeadline? = when {
            first == null -> second
            second == null -> first
            first.expirationNanos <= second.expirationNanos -> first
            else -> second
        }
    }
}

internal abstract class AbstractSocket(
    val domain: SocketDomain,
    val socketType: SocketType,
    val protocol: Int,
) : InodeBackend, ModeAwareOpenFileBackend {
    protected val lock = IrqSpinLock()
    protected var closed = false
        private set
    private var options = SocketOptions()
    private var pendingError: VfsError? = null

    final override val type = InodeType.SOCKET

    final override fun open(
        caller: VfsOperationContext,
        inode: Inode,
        options: OpenOptions,
    ): VfsResult<OpenFileBackend> =
        if (options.access == AccessMode.READ_WRITE && lock.withLock { !closed }) {
            VfsResult.Ok(this)
        } else {
            VfsResult.Err(VfsError.BAD_DESCRIPTOR)
        }

    final override fun read(
        caller: VfsOperationContext,
        inode: Inode,
        destination: PreparedBufferDestination,
        destinationOffset: Int,
        count: Int,
        mode: IoMode,
    ): IoResult = when (val result = receiveSocket(
        SocketReceiveRequest(
            destination,
            destinationOffset,
            count,
            nonBlocking = mode == IoMode.NON_BLOCKING,
        ),
    )) {
        is VfsResult.Ok -> {
            result.value.ancillary?.release()
            IoResult.success(result.value.copiedBytes)
        }
        is VfsResult.Err -> IoResult.failure(result.error)
    }

    final override fun write(
        caller: VfsOperationContext,
        inode: Inode,
        source: PreparedBufferSource,
        sourceOffset: Int,
        count: Int,
        mode: IoMode,
    ): IoResult {
        val process = ProcessManager.currentProcess()
            ?: return IoResult.failure(VfsError.NOT_FOUND)
        return sendSocket(
            SocketSendRequest(
                process,
                source,
                sourceOffset,
                count,
                nonBlocking = mode == IoMode.NON_BLOCKING,
            ),
        )
    }

    final override fun ioctl(
        caller: VfsOperationContext,
        inode: Inode,
        command: Int,
        args: UserMemory,
    ): Long {
        if (command != FIONREAD) return -VfsError.NOT_SUPPORTED.errno.toLong()
        val bytes = ByteArray(Int.SIZE_BYTES)
        LittleEndianBuffer(bytes).writeU32(0, readableBytes().coerceAtLeast(0).toUInt())
        return if (args.copyToUser(bytes)) 0L else -VfsError.FAULT.errno.toLong()
    }

    final override fun poll(
        caller: VfsOperationContext,
        inode: Inode,
        events: Int,
    ): Long = pollSocket(events).toLong()

    final override fun release() {
        val cleanup = lock.withLock {
            if (closed) return
            closed = true
            closeSocketLocked()
        }
        cleanup?.invoke()
    }

    open fun bindSocket(process: Process, address: SocketAddress): VfsResult<Unit> =
        VfsResult.Err(VfsError.NOT_SUPPORTED)

    open fun connectSocket(
        process: Process,
        address: SocketAddress?,
        nonBlocking: Boolean,
    ): VfsResult<Unit> = VfsResult.Err(VfsError.NOT_SUPPORTED)

    open fun listenSocket(process: Process, backlog: Int): VfsResult<Unit> =
        VfsResult.Err(VfsError.NOT_SUPPORTED)

    open fun acceptSocket(process: Process, nonBlocking: Boolean): VfsResult<AcceptedSocket> =
        VfsResult.Err(VfsError.NOT_SUPPORTED)

    open fun shutdownSocket(mode: SocketShutdownMode): VfsResult<Unit> =
        VfsResult.Err(VfsError.NOT_CONNECTED)

    abstract fun localAddress(): SocketAddress

    open fun peerAddress(): VfsResult<SocketAddress> = VfsResult.Err(VfsError.NOT_CONNECTED)

    open fun peerCredentials(): VfsResult<UnixCredentials> =
        VfsResult.Err(VfsError.NOT_CONNECTED)

    open fun isListening(): Boolean = false

    abstract fun sendSocket(request: SocketSendRequest): IoResult

    abstract fun receiveSocket(request: SocketReceiveRequest): VfsResult<SocketReceiveResult>

    fun socketOptions(): SocketOptions = lock.withLock { options }

    fun takeError(): VfsError? = lock.withLock { pendingError.also { pendingError = null } }

    protected fun takeErrorLocked(): VfsError? = pendingError.also { pendingError = null }

    protected fun setError(error: VfsError?) = lock.withLock { pendingError = error }

    protected fun storeErrorLocked(error: VfsError?) {
        pendingError = error
    }

    protected fun hasPendingError(): Boolean = pendingError != null

    fun setSendBufferSize(size: Int): VfsResult<Unit> = updateOptions {
        val normalized = normalizeBufferSize(size) ?: return@updateOptions null
        copy(sendBufferSize = normalized)
    }

    fun setReceiveBufferSize(size: Int): VfsResult<Unit> = updateOptions {
        val normalized = normalizeBufferSize(size) ?: return@updateOptions null
        copy(
            receiveBufferSize = normalized,
            receiveLowWatermark = minOf(receiveLowWatermark, normalized),
        )
    }

    fun setPassCredentials(enabled: Boolean): VfsResult<Unit> =
        updateOptions { copy(passCredentials = enabled) }

    fun setReceiveLowWatermark(value: Int): VfsResult<Unit> = updateOptions {
        if (value <= 0) return@updateOptions null
        copy(receiveLowWatermark = value.coerceAtMost(receiveBufferSize))
    }

    fun setSendTimeout(timeoutNanos: ULong?): VfsResult<Unit> =
        updateOptions { copy(sendTimeoutNanos = timeoutNanos) }

    fun setReceiveTimeout(timeoutNanos: ULong?): VfsResult<Unit> =
        updateOptions { copy(receiveTimeoutNanos = timeoutNanos) }

    fun setReuseAddress(enabled: Boolean): VfsResult<Unit> =
        updateOptions { copy(reuseAddress = enabled) }

    fun setBroadcast(enabled: Boolean): VfsResult<Unit> =
        updateOptions { copy(broadcast = enabled) }

    fun setKeepAlive(enabled: Boolean): VfsResult<Unit> =
        updateOptions { copy(keepAlive = enabled) }

    fun setLinger(linger: SocketLinger): VfsResult<Unit> = updateOptions {
        if (linger.seconds < 0) return@updateOptions null
        copy(linger = linger)
    }

    open fun setProtocolOption(
        process: Process,
        level: Int,
        name: Int,
        value: ByteArray,
    ): VfsResult<Unit> =
        VfsResult.Err(VfsError.PROTOCOL_OPTION_NOT_AVAILABLE)

    open fun getProtocolOption(level: Int, name: Int): VfsResult<ByteArray> =
        VfsResult.Err(VfsError.PROTOCOL_OPTION_NOT_AVAILABLE)

    protected fun optionsLocked(): SocketOptions = options

    protected fun inheritOptions(options: SocketOptions) {
        lock.withLock {
            val previous = this.options
            this.options = options
            if (previous.sendBufferSize != options.sendBufferSize) {
                sendBufferSizeChangedLocked(options.sendBufferSize)
            }
            if (previous.receiveBufferSize != options.receiveBufferSize) {
                receiveBufferSizeChangedLocked(options.receiveBufferSize)
            }
            optionsChangedLocked(options)
        }
    }

    protected fun signalBrokenPipe(suppressed: Boolean) {
        if (suppressed) return
        ProcessManager.currentThread()?.let { thread ->
            SignalRouter.sendThread(
                sender = thread.process,
                target = thread,
                info = SignalInfo.fromSender(Signal.PIPE, thread.process),
            )
        }
    }

    protected open fun sendBufferSizeChangedLocked(size: Int) {}

    protected open fun receiveBufferSizeChangedLocked(size: Int) {}

    protected open fun optionsChangedLocked(options: SocketOptions) {}

    protected abstract fun readableBytes(): Int

    protected abstract fun pollSocket(events: Int): Int

    protected abstract fun closeSocketLocked(): (() -> Unit)?

    private fun updateOptions(transform: SocketOptions.() -> SocketOptions?): VfsResult<Unit> =
        lock.withLock {
            if (closed) return@withLock VfsResult.Err(VfsError.BAD_DESCRIPTOR)
            val updated = options.transform()
                ?: return@withLock VfsResult.Err(VfsError.INVALID_ARGUMENT)
            val previous = options
            options = updated
            if (previous.sendBufferSize != updated.sendBufferSize) {
                sendBufferSizeChangedLocked(updated.sendBufferSize)
            }
            if (previous.receiveBufferSize != updated.receiveBufferSize) {
                receiveBufferSizeChangedLocked(updated.receiveBufferSize)
            }
            optionsChangedLocked(updated)
            VfsResult.Ok(Unit)
        }

    companion object {
        private const val FIONREAD = 0x541B
        private const val MIN_SOCKET_BUFFER_SIZE = 2_048
        private const val MAX_SOCKET_BUFFER_SIZE = 4 * 1024 * 1024

        private fun normalizeBufferSize(requested: Int): Int? {
            if (requested < 0) return null
            val doubled = if (requested > Int.MAX_VALUE / 2) Int.MAX_VALUE else requested * 2
            return doubled.coerceIn(MIN_SOCKET_BUFFER_SIZE, MAX_SOCKET_BUFFER_SIZE)
        }
    }
}
