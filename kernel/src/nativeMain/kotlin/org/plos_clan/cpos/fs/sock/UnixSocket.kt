@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.fs.sock

import org.plos_clan.cpos.fs.vfs.AccessPermissions
import org.plos_clan.cpos.fs.vfs.AccessMode
import org.plos_clan.cpos.fs.vfs.AnonymousFileFactory
import org.plos_clan.cpos.fs.vfs.FileMode
import org.plos_clan.cpos.fs.vfs.FileSystemContext
import org.plos_clan.cpos.fs.vfs.Inode
import org.plos_clan.cpos.fs.vfs.InodeType
import org.plos_clan.cpos.fs.vfs.IoResult
import org.plos_clan.cpos.fs.vfs.MutableInodeBackend
import org.plos_clan.cpos.fs.vfs.NodeCreation
import org.plos_clan.cpos.fs.vfs.NodeKind
import org.plos_clan.cpos.fs.vfs.OpenFileBackend
import org.plos_clan.cpos.fs.vfs.OpenFileDescription
import org.plos_clan.cpos.fs.vfs.OpenOptions
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsNodeOperations
import org.plos_clan.cpos.fs.vfs.VfsOperationContext
import org.plos_clan.cpos.fs.vfs.VfsPathResolver
import org.plos_clan.cpos.fs.vfs.VfsPathname
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.mem.PreparedBufferSource
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.utils.IrqSpinLock

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

internal sealed interface UnixSocketAddress : SocketAddress {
    override val domain: SocketDomain
        get() = SocketDomain.UNIX

    data object Unnamed : UnixSocketAddress
    data class Pathname(val pathname: VfsPathname) : UnixSocketAddress
    data class Abstract(val name: UnixSocketName) : UnixSocketAddress
}

internal data class UnixCredentials(
    val processId: Int,
    val userId: UInt,
    val groupId: UInt,
)

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
    val deadline: SocketDeadline? = null,
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
    socketType: SocketType,
) : AbstractSocket(SocketDomain.UNIX, socketType, 0) {
    private var bindingState: UnixSocketBindingState = UnixSocketBindingState.Unbound

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

    final override fun localAddress(): UnixSocketAddress = lock.withLock { localAddressLocked() }

    protected open fun localAddressLocked(): UnixSocketAddress = boundAddressLocked()

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

    open fun shutdown(mode: SocketShutdownMode): VfsResult<Unit> =
        VfsResult.Err(VfsError.NOT_CONNECTED)

    override fun peerAddress(): VfsResult<UnixSocketAddress> =
        VfsResult.Err(VfsError.NOT_CONNECTED)

    override fun peerCredentials(): VfsResult<UnixCredentials> =
        VfsResult.Err(VfsError.NOT_CONNECTED)

    override fun isListening(): Boolean = false

    abstract fun pairWith(peer: UnixSocket, credentials: UnixCredentials): VfsResult<Unit>

    abstract fun send(request: UnixSendRequest): IoResult

    abstract fun receive(request: UnixReceiveRequest): VfsResult<UnixReceiveResult>

    final override fun bindSocket(process: Process, address: SocketAddress): VfsResult<Unit> {
        val unixAddress = address as? UnixSocketAddress
            ?: return VfsResult.Err(VfsError.ADDRESS_FAMILY_NOT_SUPPORTED)
        val context = process.context ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val caller = process.vfsOperationContext
        return when (val result = subsystem.bind(
            caller,
            context,
            this,
            unixAddress,
            FileMode(0x1FFu and caller.fileCreationMask.inv()),
            caller.uid,
            caller.gid,
        )) {
            is VfsResult.Ok -> VfsResult.Ok(Unit)
            is VfsResult.Err -> result
        }
    }

    final override fun connectSocket(
        process: Process,
        address: SocketAddress?,
        nonBlocking: Boolean,
    ): VfsResult<Unit> {
        if (address == null || address == UnspecifiedSocketAddress) {
            return connect(null, UnixSocketAddress.Unnamed, credentials(process), nonBlocking)
        }
        val unixAddress = address as? UnixSocketAddress
            ?: return VfsResult.Err(VfsError.ADDRESS_FAMILY_NOT_SUPPORTED)
        val context = process.context ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val peer = when (val result = subsystem.resolve(
            process.vfsOperationContext,
            context,
            unixAddress,
        )) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        return connect(peer, unixAddress, credentials(process), nonBlocking)
    }

    final override fun listenSocket(process: Process, backlog: Int): VfsResult<Unit> =
        listen(backlog, credentials(process))

    final override fun acceptSocket(
        process: Process,
        nonBlocking: Boolean,
    ): VfsResult<AcceptedSocket> = when (val result = accept(nonBlocking)) {
        is VfsResult.Ok -> VfsResult.Ok(
            AcceptedSocket(
                result.value,
                when (val peer = result.value.peerAddress()) {
                    is VfsResult.Ok -> peer.value
                    is VfsResult.Err -> UnixSocketAddress.Unnamed
                },
            ),
        )
        is VfsResult.Err -> result
    }

    final override fun shutdownSocket(mode: SocketShutdownMode): VfsResult<Unit> = shutdown(mode)

    final override fun sendSocket(request: SocketSendRequest): IoResult {
        val destination = when (val result = resolveDestination(request.process, request.destination)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> {
                request.ancillary.release()
                return IoResult.failure(result.error)
            }
        }
        return send(
            UnixSendRequest(
                request.source,
                request.offset,
                request.count,
                credentials(request.process),
                request.ancillary,
                destination,
                request.nonBlocking,
                request.noSignal,
            ),
        )
    }

    final override fun receiveSocket(
        request: SocketReceiveRequest,
    ): VfsResult<SocketReceiveResult> = when (val result = receive(
        UnixReceiveRequest(
            request.destination,
            request.offset,
            request.count,
            request.nonBlocking,
            request.peek,
            request.waitAll,
            request.returnFullLength,
            request.deadline,
        ),
    )) {
        is VfsResult.Ok -> result.value.let { received ->
            VfsResult.Ok(
                SocketReceiveResult(
                    received.bytes,
                    received.copiedBytes,
                    received.source,
                    received.ancillary,
                    received.senderCredentials,
                    received.truncated,
                    received.endOfRecord,
                ),
            )
        }
        is VfsResult.Err -> result
    }

    final override fun setPassCredentials(
        process: Process,
        enabled: Boolean,
    ): VfsResult<Unit> {
        if (enabled && canBind()) {
            val bound = bindSocket(process, UnixSocketAddress.Unnamed)
            if (bound is VfsResult.Err) return bound
        }
        return super.setPassCredentials(process, enabled)
    }

    final override fun closeSocketLocked(): (() -> Unit)? {
        val cleanup = closeUnixLocked()
        val binding = (bindingState as? UnixSocketBindingState.Bound)?.binding
        bindingState = UnixSocketBindingState.Unbound
        return when {
            binding == null -> cleanup
            cleanup == null -> ({ subsystem.unbind(this, binding) })
            else -> ({
                subsystem.unbind(this, binding)
                cleanup()
            })
        }
    }

    protected abstract fun closeUnixLocked(): (() -> Unit)?

    private fun resolveDestination(
        process: Process,
        address: SocketAddress?,
    ): VfsResult<UnixSocketDestination?> {
        if (address == null) return VfsResult.Ok(null)
        val unixAddress = address as? UnixSocketAddress
            ?: return VfsResult.Err(VfsError.ADDRESS_FAMILY_NOT_SUPPORTED)
        if (socketType.connectionOriented) {
            return VfsResult.Ok(UnixSocketDestination.Address(unixAddress))
        }
        val context = process.context ?: return VfsResult.Err(VfsError.NOT_FOUND)
        return when (val result = subsystem.resolve(
            process.vfsOperationContext,
            context,
            unixAddress,
        )) {
            is VfsResult.Ok -> VfsResult.Ok(
                UnixSocketDestination.Resolved(result.value, unixAddress),
            )
            is VfsResult.Err -> result
        }
    }

    private fun credentials(process: Process): UnixCredentials = UnixCredentials(
        process.id,
        process.euid.toUInt(),
        process.egid.toUInt(),
    )
}

internal data object SocketNodeBackend : MutableInodeBackend {
    override val type: InodeType = InodeType.SOCKET

    override fun open(
        caller: VfsOperationContext,
        inode: Inode,
        options: OpenOptions,
    ): VfsResult<OpenFileBackend> =
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
        caller: VfsOperationContext,
        context: FileSystemContext,
        type: SocketType,
        nonBlocking: Boolean,
        credentials: UnixCredentials,
    ): VfsResult<OpenFileDescription> = open(
        caller,
        context,
        newSocket(type, credentials),
        nonBlocking,
    )

    fun open(
        caller: VfsOperationContext,
        context: FileSystemContext,
        socket: UnixSocket,
        nonBlocking: Boolean,
    ): VfsResult<OpenFileDescription> = anonymousFiles.open(
        caller,
        context,
        socket,
        OpenOptions(access = AccessMode.READ_WRITE, nonBlocking = nonBlocking),
    )

    fun newSocket(type: SocketType, credentials: UnixCredentials): UnixSocket = when (type) {
        SocketType.DATAGRAM -> UnixDatagramSocket(this, credentials)
        SocketType.STREAM,
        SocketType.SEQUENCED_PACKET,
        -> UnixConnectionSocket(this, type)
        SocketType.RAW -> error("Raw Unix sockets are unsupported")
    }

    fun bind(
        caller: VfsOperationContext,
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
                caller,
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
        caller: VfsOperationContext,
        context: FileSystemContext,
        address: UnixSocketAddress,
    ): VfsResult<UnixSocket> = when (address) {
        UnixSocketAddress.Unnamed -> VfsResult.Err(VfsError.ADDRESS_NOT_AVAILABLE)
        is UnixSocketAddress.Abstract -> lock.withLock {
            abstractBindings[address.name]?.let { VfsResult.Ok(it) }
                ?: VfsResult.Err(VfsError.CONNECTION_REFUSED)
        }
        is UnixSocketAddress.Pathname -> {
            val path = when (val result = paths.resolve(caller, context, address.pathname)) {
                is VfsResult.Ok -> result.value
                is VfsResult.Err -> return result
            }
            val inode = path.inode ?: return VfsResult.Err(VfsError.NOT_FOUND)
            if (inode.type != InodeType.SOCKET) {
                return VfsResult.Err(VfsError.CONNECTION_REFUSED)
            }
            when (val access = inode.backend.checkAccess(caller, inode, AccessPermissions.WRITE)) {
                is VfsResult.Ok -> Unit
                is VfsResult.Err -> return access
            }
            lock.withLock {
                pathnameBindings[inode]?.let { VfsResult.Ok(it) }
                    ?: VfsResult.Err(VfsError.CONNECTION_REFUSED)
            }
        }
    }

    fun pair(
        caller: VfsOperationContext,
        context: FileSystemContext,
        type: SocketType,
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
        val firstFile = when (val result = open(caller, context, first, nonBlocking)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> {
                first.release()
                second.release()
                return result
            }
        }
        val secondFile = when (val result = open(caller, context, second, nonBlocking)) {
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
        caller: VfsOperationContext,
        context: FileSystemContext,
        socket: UnixSocket,
        address: UnixSocketAddress.Pathname,
        mode: FileMode,
        uid: UInt,
        gid: UInt,
    ): VfsResult<UnixSocketAddress> {
        val path = when (val result = nodes.createNode(
            caller,
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
