@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.fs.sock

import bridge.wait_for_interrupt
import org.plos_clan.cpos.drivers.TscClock
import org.plos_clan.cpos.fs.vfs.AccessMode
import org.plos_clan.cpos.fs.vfs.AnonymousFileFactory
import org.plos_clan.cpos.fs.vfs.FileMode
import org.plos_clan.cpos.fs.vfs.FileSystemContext
import org.plos_clan.cpos.fs.vfs.Inode
import org.plos_clan.cpos.fs.vfs.InodeBackend
import org.plos_clan.cpos.fs.vfs.InodeMetadata
import org.plos_clan.cpos.fs.vfs.InodeType
import org.plos_clan.cpos.fs.vfs.IoMode
import org.plos_clan.cpos.fs.vfs.IoResult
import org.plos_clan.cpos.fs.vfs.ModeAwareOpenFileBackend
import org.plos_clan.cpos.fs.vfs.MutableInodeBackend
import org.plos_clan.cpos.fs.vfs.NodeCreation
import org.plos_clan.cpos.fs.vfs.NodeKind
import org.plos_clan.cpos.fs.vfs.OpenFileBackend
import org.plos_clan.cpos.fs.vfs.OpenFileDescription
import org.plos_clan.cpos.fs.vfs.OpenOptions
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsNodeOperations
import org.plos_clan.cpos.fs.vfs.VfsPathResolver
import org.plos_clan.cpos.fs.vfs.VfsPathname
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.mem.PreparedBufferSource
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.tasks.Scheduler
import org.plos_clan.cpos.tasks.Signal
import org.plos_clan.cpos.tasks.SignalInfo
import org.plos_clan.cpos.tasks.SignalRouter
import org.plos_clan.cpos.utils.IrqSpinLock
import org.plos_clan.cpos.utils.LittleEndianBuffer

private const val DEFAULT_SOCKET_BUFFER_SIZE = 212_992

internal enum class UnixSocketType(val abiValue: Int, val connectionOriented: Boolean) {
    STREAM(1, true),
    DATAGRAM(2, false),
    SEQUENCED_PACKET(5, true),
    ;

    companion object {
        fun fromAbi(value: Int): UnixSocketType? = entries.firstOrNull { it.abiValue == value }
    }
}

internal class UnixSocketName private constructor(private val bytes: ByteArray) {
    private val hash = bytes.contentHashCode()

    fun copyBytes(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean =
        this === other || other is UnixSocketName && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = hash

    companion object {
        fun fromBytes(bytes: ByteArray): UnixSocketName = UnixSocketName(bytes.copyOf())

        fun fromHex(value: UInt, width: Int): UnixSocketName {
            require(width in 1..UInt.SIZE_BYTES * 2)
            val bytes = ByteArray(width)
            var remaining = value
            for (index in bytes.lastIndex downTo 0) {
                val digit = (remaining and 0xFu).toInt()
                bytes[index] = (if (digit < 10) '0'.code + digit
                else 'a'.code + digit - 10).toByte()
                remaining = remaining shr 4
            }
            return UnixSocketName(bytes)
        }
    }
}

internal sealed interface UnixSocketAddress {
    data object Unnamed : UnixSocketAddress
    data class Pathname(val pathname: VfsPathname) : UnixSocketAddress
    data class Abstract(val name: UnixSocketName) : UnixSocketAddress
}

internal data class UnixCredentials(
    val processId: Int,
    val userId: UInt,
    val groupId: UInt,
)

internal data class FileSystemIdentity(
    val userId: UInt,
    val groupId: UInt,
    val privileged: Boolean,
) {
    fun mayWrite(metadata: InodeMetadata): Boolean {
        if (privileged) return true
        val shift = when {
            userId == metadata.uid -> 6
            groupId == metadata.gid -> 3
            else -> 0
        }
        return metadata.mode.bits shr shift and 0x2u != 0u
    }
}

internal class UnixSocketDeadline private constructor(
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
        fun after(timeoutNanos: ULong?): UnixSocketDeadline? {
            if (timeoutNanos == null) return null
            val now = TscClock.nanoTime()
            val expiration = if (timeoutNanos > ULong.MAX_VALUE - now) ULong.MAX_VALUE
            else now + timeoutNanos
            return UnixSocketDeadline(expiration)
        }

        fun earliest(
            first: UnixSocketDeadline?,
            second: UnixSocketDeadline?,
        ): UnixSocketDeadline? = when {
            first == null -> second
            second == null -> first
            first.expirationNanos <= second.expirationNanos -> first
            else -> second
        }
    }
}

internal enum class UnixShutdownMode(val reads: Boolean, val writes: Boolean) {
    READ(reads = true, writes = false),
    WRITE(reads = false, writes = true),
    BOTH(reads = true, writes = true),
}

internal class UnixAncillaryData(
    private val files: MutableList<OpenFileDescription> = mutableListOf(),
    credentials: UnixCredentials? = null,
) {
    var credentials = credentials
        private set

    val fileCount: Int
        get() = files.size

    val isEmpty: Boolean
        get() = files.isEmpty() && credentials == null

    fun attachCredentials(credentials: UnixCredentials) {
        if (this.credentials == null) this.credentials = credentials
    }

    fun duplicate(): UnixAncillaryData? {
        val retained = ArrayList<OpenFileDescription>(files.size)
        for (file in files) {
            if (!file.retain()) {
                retained.forEach(OpenFileDescription::release)
                return null
            }
            retained += file
        }
        return UnixAncillaryData(retained, credentials)
    }

    fun takeFiles(count: Int): List<OpenFileDescription> {
        val taken = minOf(count.coerceAtLeast(0), files.size)
        if (taken == 0) return emptyList()
        val selected = files.subList(0, taken)
        return selected.toList().also { selected.clear() }
    }

    fun release() {
        files.forEach(OpenFileDescription::release)
        files.clear()
    }
}

internal sealed interface UnixSocketDestination {
    val address: UnixSocketAddress

    data class Address(
        override val address: UnixSocketAddress,
    ) : UnixSocketDestination

    data class Resolved(
        val socket: UnixSocket,
        override val address: UnixSocketAddress,
    ) : UnixSocketDestination
}

internal data class UnixSendRequest(
    val source: PreparedBufferSource,
    val offset: Int,
    val count: Int,
    val credentials: UnixCredentials,
    val ancillary: UnixAncillaryData = UnixAncillaryData(),
    val destination: UnixSocketDestination? = null,
    val nonBlocking: Boolean = false,
    val noSignal: Boolean = false,
)

internal data class UnixReceiveRequest(
    val destination: PreparedBufferDestination,
    val offset: Int,
    val count: Int,
    val nonBlocking: Boolean = false,
    val peek: Boolean = false,
    val waitAll: Boolean = false,
    val returnFullLength: Boolean = false,
    val deadline: UnixSocketDeadline? = null,
)

internal data class UnixReceiveResult(
    val bytes: Int,
    val copiedBytes: Int,
    val source: UnixSocketAddress,
    val ancillary: UnixAncillaryData? = null,
    val senderCredentials: UnixCredentials? = null,
    val truncated: Boolean = false,
    val endOfRecord: Boolean = false,
)

internal data class UnixSocketOptions(
    val sendBufferSize: Int = DEFAULT_SOCKET_BUFFER_SIZE,
    val receiveBufferSize: Int = DEFAULT_SOCKET_BUFFER_SIZE,
    val passCredentials: Boolean = false,
    val receiveLowWatermark: Int = 1,
    val sendTimeoutNanos: ULong? = null,
    val receiveTimeoutNanos: ULong? = null,
)

internal sealed interface UnixSocketBinding {
    val address: UnixSocketAddress

    data class Abstract(
        override val address: UnixSocketAddress.Abstract,
    ) : UnixSocketBinding

    data class Pathname(
        override val address: UnixSocketAddress.Pathname,
        val inode: Inode,
    ) : UnixSocketBinding
}

private sealed interface UnixSocketBindingState {
    data object Unbound : UnixSocketBindingState
    data object Reserved : UnixSocketBindingState
    data class Bound(val binding: UnixSocketBinding) : UnixSocketBindingState
}

internal abstract class UnixSocket(
    protected val subsystem: UnixSocketSubsystem,
    val socketType: UnixSocketType,
) : InodeBackend, ModeAwareOpenFileBackend {
    protected val lock = IrqSpinLock()
    protected var closed = false
        private set
    private var bindingState: UnixSocketBindingState = UnixSocketBindingState.Unbound
    private var options = UnixSocketOptions()

    final override val type: InodeType
        get() = InodeType.SOCKET

    final override fun open(inode: Inode, options: OpenOptions): VfsResult<OpenFileBackend> =
        if (options.access == AccessMode.READ_WRITE && lock.withLock { !closed }) {
            VfsResult.Ok(this)
        } else {
            VfsResult.Err(VfsError.BAD_DESCRIPTOR)
        }

    final override fun read(
        inode: Inode,
        destination: PreparedBufferDestination,
        destinationOffset: Int,
        count: Int,
        mode: IoMode,
    ): IoResult = when (val result = receive(
        UnixReceiveRequest(
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
        inode: Inode,
        source: PreparedBufferSource,
        sourceOffset: Int,
        count: Int,
        mode: IoMode,
    ): IoResult {
        val process = ProcessManager.currentProcess()
            ?: return IoResult.failure(VfsError.NOT_FOUND)
        return send(
            UnixSendRequest(
                source,
                sourceOffset,
                count,
                UnixCredentials(process.id, process.euid.toUInt(), process.egid.toUInt()),
                nonBlocking = mode == IoMode.NON_BLOCKING,
            ),
        )
    }

    final override fun ioctl(inode: Inode, command: Int, args: UserMemory): Long {
        if (command != FIONREAD) return -VfsError.NOT_SUPPORTED.errno.toLong()
        val bytes = ByteArray(Int.SIZE_BYTES)
        LittleEndianBuffer(bytes).writeU32(0, readableBytes().coerceAtLeast(0).toUInt())
        return if (args.copyToUser(bytes)) 0L else -VfsError.FAULT.errno.toLong()
    }

    final override fun poll(inode: Inode, events: Int): Long = pollSocket(events).toLong()

    final override fun release() {
        var cleanup: (() -> Unit)? = null
        val ownedBinding = lock.withLock {
            if (closed) return
            closed = true
            cleanup = closeLocked()
            (bindingState as? UnixSocketBindingState.Bound)?.binding.also {
                bindingState = UnixSocketBindingState.Unbound
            }
        }
        if (ownedBinding != null) subsystem.unbind(this, ownedBinding)
        cleanup?.invoke()
    }

    fun reserveBinding(): VfsResult<Unit> = lock.withLock {
        if (closed) return@withLock VfsResult.Err(VfsError.BAD_DESCRIPTOR)
        if (bindingState != UnixSocketBindingState.Unbound) {
            return@withLock VfsResult.Err(VfsError.INVALID_ARGUMENT)
        }
        if (!canBindLocked()) return@withLock VfsResult.Err(VfsError.INVALID_ARGUMENT)
        bindingState = UnixSocketBindingState.Reserved
        VfsResult.Ok(Unit)
    }

    fun commitBinding(binding: UnixSocketBinding): Boolean = lock.withLock {
        if (closed || bindingState != UnixSocketBindingState.Reserved) return@withLock false
        bindingState = UnixSocketBindingState.Bound(binding)
        true
    }

    fun cancelBinding() = lock.withLock {
        if (bindingState == UnixSocketBindingState.Reserved) {
            bindingState = UnixSocketBindingState.Unbound
        }
    }

    fun canBind(): Boolean = lock.withLock {
        !closed && bindingState == UnixSocketBindingState.Unbound && canBindLocked()
    }

    protected open fun canBindLocked(): Boolean = true

    protected fun boundAddressLocked(): UnixSocketAddress =
        (bindingState as? UnixSocketBindingState.Bound)?.binding?.address
            ?: UnixSocketAddress.Unnamed

    fun localAddress(): UnixSocketAddress = lock.withLock { localAddressLocked() }

    protected open fun localAddressLocked(): UnixSocketAddress = boundAddressLocked()

    fun socketOptions(): UnixSocketOptions = lock.withLock { options }

    fun setSendBufferSize(size: Int): VfsResult<Unit> = lock.withLock {
        if (closed) return@withLock VfsResult.Err(VfsError.BAD_DESCRIPTOR)
        val normalized = normalizedBufferSize(size)
        options = options.copy(sendBufferSize = normalized)
        sendBufferSizeChangedLocked(normalized)
        optionsChangedLocked()
        VfsResult.Ok(Unit)
    }

    fun setReceiveBufferSize(size: Int): VfsResult<Unit> = lock.withLock {
        if (closed) return@withLock VfsResult.Err(VfsError.BAD_DESCRIPTOR)
        val normalized = normalizedBufferSize(size)
        options = options.copy(
            receiveBufferSize = normalized,
            receiveLowWatermark = minOf(
                options.receiveLowWatermark,
                normalized,
            ),
        )
        receiveBufferSizeChangedLocked(normalized)
        optionsChangedLocked()
        VfsResult.Ok(Unit)
    }

    fun setPassCredentials(enabled: Boolean): VfsResult<Unit> = lock.withLock {
        if (closed) return@withLock VfsResult.Err(VfsError.BAD_DESCRIPTOR)
        options = options.copy(passCredentials = enabled)
        optionsChangedLocked()
        VfsResult.Ok(Unit)
    }

    fun setReceiveLowWatermark(value: Int): VfsResult<Unit> = lock.withLock {
        if (closed) return@withLock VfsResult.Err(VfsError.BAD_DESCRIPTOR)
        options = options.copy(
            receiveLowWatermark = value.coerceIn(1, options.receiveBufferSize),
        )
        optionsChangedLocked()
        VfsResult.Ok(Unit)
    }

    fun setSendTimeout(timeoutNanos: ULong?): VfsResult<Unit> = lock.withLock {
        if (closed) return@withLock VfsResult.Err(VfsError.BAD_DESCRIPTOR)
        options = options.copy(sendTimeoutNanos = timeoutNanos)
        optionsChangedLocked()
        VfsResult.Ok(Unit)
    }

    fun setReceiveTimeout(timeoutNanos: ULong?): VfsResult<Unit> = lock.withLock {
        if (closed) return@withLock VfsResult.Err(VfsError.BAD_DESCRIPTOR)
        options = options.copy(receiveTimeoutNanos = timeoutNanos)
        optionsChangedLocked()
        VfsResult.Ok(Unit)
    }

    protected fun optionsLocked(): UnixSocketOptions = options

    protected fun inheritOptions(options: UnixSocketOptions) {
        lock.withLock { this.options = options }
    }

    protected open fun sendBufferSizeChangedLocked(size: Int) {}

    protected open fun receiveBufferSizeChangedLocked(size: Int) {}

    protected open fun optionsChangedLocked() {}

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

    open fun listen(backlog: Int, credentials: UnixCredentials): VfsResult<Unit> =
        VfsResult.Err(VfsError.NOT_SUPPORTED)

    open fun connect(
        peer: UnixSocket?,
        address: UnixSocketAddress,
        credentials: UnixCredentials,
        nonBlocking: Boolean,
    ): VfsResult<Unit> = VfsResult.Err(VfsError.NOT_SUPPORTED)

    open fun accept(nonBlocking: Boolean): VfsResult<UnixSocket> =
        VfsResult.Err(VfsError.NOT_SUPPORTED)

    open fun shutdown(mode: UnixShutdownMode): VfsResult<Unit> =
        VfsResult.Err(VfsError.NOT_CONNECTED)

    open fun peerAddress(): VfsResult<UnixSocketAddress> =
        VfsResult.Err(VfsError.NOT_CONNECTED)

    open fun peerCredentials(): VfsResult<UnixCredentials> =
        VfsResult.Err(VfsError.NOT_CONNECTED)

    open fun isListening(): Boolean = false

    abstract fun pairWith(peer: UnixSocket, credentials: UnixCredentials): VfsResult<Unit>

    abstract fun send(request: UnixSendRequest): IoResult

    abstract fun receive(request: UnixReceiveRequest): VfsResult<UnixReceiveResult>

    protected abstract fun readableBytes(): Int

    protected abstract fun pollSocket(events: Int): Int

    protected abstract fun closeLocked(): (() -> Unit)?

    companion object {
        private const val FIONREAD = 0x541B
        private const val MIN_SOCKET_BUFFER_SIZE = 2_048
        private const val MAX_SOCKET_BUFFER_SIZE = 4 * 1024 * 1024

        private fun normalizedBufferSize(requested: Int): Int {
            val doubled = if (requested > Int.MAX_VALUE / 2) Int.MAX_VALUE else requested * 2
            return doubled.coerceIn(MIN_SOCKET_BUFFER_SIZE, MAX_SOCKET_BUFFER_SIZE)
        }
    }
}

internal data object SocketNodeBackend : MutableInodeBackend {
    override val type: InodeType = InodeType.SOCKET

    override fun open(inode: Inode, options: OpenOptions): VfsResult<OpenFileBackend> =
        VfsResult.Err(VfsError.NO_SUCH_DEVICE_OR_ADDRESS)
}

internal class UnixSocketSubsystem(
    private val paths: VfsPathResolver,
    private val nodes: VfsNodeOperations,
    private val anonymousFiles: AnonymousFileFactory,
) {
    private val lock = IrqSpinLock()
    private val abstractBindings = mutableMapOf<UnixSocketName, UnixSocket>()
    private val pathnameBindings = mutableMapOf<Inode, UnixSocket>()
    private var nextAutomaticName = 0u

    fun create(
        context: FileSystemContext,
        type: UnixSocketType,
        nonBlocking: Boolean,
        credentials: UnixCredentials,
    ): VfsResult<OpenFileDescription> = open(
        context,
        newSocket(type, credentials),
        nonBlocking,
    )

    fun open(
        context: FileSystemContext,
        socket: UnixSocket,
        nonBlocking: Boolean,
    ): VfsResult<OpenFileDescription> = anonymousFiles.open(
        context,
        socket,
        OpenOptions(access = AccessMode.READ_WRITE, nonBlocking = nonBlocking),
    )

    fun newSocket(type: UnixSocketType, credentials: UnixCredentials): UnixSocket = when (type) {
        UnixSocketType.DATAGRAM -> UnixDatagramSocket(this, credentials)
        UnixSocketType.STREAM,
        UnixSocketType.SEQUENCED_PACKET,
        -> UnixConnectionSocket(this, type)
    }

    fun bind(
        context: FileSystemContext,
        socket: UnixSocket,
        requested: UnixSocketAddress,
        mode: FileMode,
        uid: UInt,
        gid: UInt,
    ): VfsResult<UnixSocketAddress> {
        val reserved = socket.reserveBinding()
        if (reserved is VfsResult.Err) return reserved
        val result = when (requested) {
            UnixSocketAddress.Unnamed -> bindAutomatic(socket)
            is UnixSocketAddress.Abstract -> bindAbstract(socket, requested)
            is UnixSocketAddress.Pathname -> bindPathname(
                context,
                socket,
                requested,
                mode,
                uid,
                gid,
            )
        }
        if (result is VfsResult.Err) socket.cancelBinding()
        return result
    }

    fun resolve(
        context: FileSystemContext,
        address: UnixSocketAddress,
        identity: FileSystemIdentity,
    ): VfsResult<UnixSocket> = when (address) {
        UnixSocketAddress.Unnamed -> VfsResult.Err(VfsError.ADDRESS_NOT_AVAILABLE)
        is UnixSocketAddress.Abstract -> lock.withLock {
            abstractBindings[address.name]?.let { VfsResult.Ok(it) }
                ?: VfsResult.Err(VfsError.CONNECTION_REFUSED)
        }
        is UnixSocketAddress.Pathname -> {
            val path = when (val result = paths.resolve(context, address.pathname)) {
                is VfsResult.Ok -> result.value
                is VfsResult.Err -> return result
            }
            val inode = path.inode ?: return VfsResult.Err(VfsError.NOT_FOUND)
            if (inode.type != InodeType.SOCKET) {
                return VfsResult.Err(VfsError.CONNECTION_REFUSED)
            }
            if (!identity.mayWrite(inode.metadata())) {
                return VfsResult.Err(VfsError.PERMISSION_DENIED)
            }
            lock.withLock {
                pathnameBindings[inode]?.let { VfsResult.Ok(it) }
                    ?: VfsResult.Err(VfsError.CONNECTION_REFUSED)
            }
        }
    }

    fun pair(
        context: FileSystemContext,
        type: UnixSocketType,
        credentials: UnixCredentials,
        nonBlocking: Boolean,
    ): VfsResult<Pair<OpenFileDescription, OpenFileDescription>> {
        val first = newSocket(type, credentials)
        val second = newSocket(type, credentials)
        val paired = first.pairWith(second, credentials)
        if (paired is VfsResult.Err) {
            first.release()
            second.release()
            return paired
        }
        val firstFile = when (val result = open(context, first, nonBlocking)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> {
                first.release()
                second.release()
                return result
            }
        }
        val secondFile = when (val result = open(context, second, nonBlocking)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> {
                firstFile.release()
                second.release()
                return result
            }
        }
        return VfsResult.Ok(firstFile to secondFile)
    }

    fun unbind(socket: UnixSocket, binding: UnixSocketBinding) {
        lock.withLock {
            when (binding) {
                is UnixSocketBinding.Abstract -> if (
                    abstractBindings[binding.address.name] === socket
                ) {
                    abstractBindings.remove(binding.address.name)
                }
                is UnixSocketBinding.Pathname -> if (pathnameBindings[binding.inode] === socket) {
                    pathnameBindings.remove(binding.inode)
                }
            }
        }
    }

    private fun bindAutomatic(socket: UnixSocket): VfsResult<UnixSocketAddress> = lock.withLock {
        repeat(AUTOMATIC_NAME_SPACE) {
            val value = nextAutomaticName++ and AUTOMATIC_NAME_MASK
            val address = UnixSocketAddress.Abstract(
                UnixSocketName.fromHex(value, AUTOMATIC_NAME_LENGTH),
            )
            if (abstractBindings[address.name] == null) {
                return@withLock commitAbstractBinding(socket, address)
            }
        }
        VfsResult.Err(VfsError.ADDRESS_IN_USE)
    }

    private fun bindAbstract(
        socket: UnixSocket,
        address: UnixSocketAddress.Abstract,
    ): VfsResult<UnixSocketAddress> = lock.withLock {
        commitAbstractBinding(socket, address)
    }

    private fun commitAbstractBinding(
        socket: UnixSocket,
        address: UnixSocketAddress.Abstract,
    ): VfsResult<UnixSocketAddress> {
        if (abstractBindings[address.name] != null) {
            return VfsResult.Err(VfsError.ADDRESS_IN_USE)
        }
        val binding = UnixSocketBinding.Abstract(address)
        if (!socket.commitBinding(binding)) return VfsResult.Err(VfsError.BAD_DESCRIPTOR)
        abstractBindings[address.name] = socket
        return VfsResult.Ok(address)
    }

    private fun bindPathname(
        context: FileSystemContext,
        socket: UnixSocket,
        address: UnixSocketAddress.Pathname,
        mode: FileMode,
        uid: UInt,
        gid: UInt,
    ): VfsResult<UnixSocketAddress> {
        val path = when (val result = nodes.createNode(
            context,
            context.workingDirectory,
            address.pathname,
            NodeCreation(NodeKind.Socket, mode, uid, gid),
        )) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return VfsResult.Err(
                if (result.error == VfsError.ALREADY_EXISTS) VfsError.ADDRESS_IN_USE
                else result.error,
            )
        }
        val inode = checkNotNull(path.inode)
        return lock.withLock {
            if (!socket.commitBinding(UnixSocketBinding.Pathname(address, inode))) {
                return@withLock VfsResult.Err(VfsError.BAD_DESCRIPTOR)
            }
            pathnameBindings[inode] = socket
            VfsResult.Ok(address)
        }
    }

    private companion object {
        const val AUTOMATIC_NAME_LENGTH = 5
        const val AUTOMATIC_NAME_SPACE = 1 shl (AUTOMATIC_NAME_LENGTH * 4)
        const val AUTOMATIC_NAME_MASK = 0xF_FFFFu
    }
}
