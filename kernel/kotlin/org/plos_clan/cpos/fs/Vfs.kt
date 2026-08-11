@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package org.plos_clan.cpos.fs

import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.mem.BufferDestination
import org.plos_clan.cpos.mem.BufferSource
import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.mem.PreparedBufferSource
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.tasks.Scheduler
import org.plos_clan.cpos.tasks.Thread
import org.plos_clan.cpos.utils.IrqSpinLock
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import org.plos_clan.cpos.utils.PollEvents
import kotlin.concurrent.atomics.AtomicInt

enum class VfsError(val errno: Int) {
    INTERRUPTED(4),
    IO(5),
    BAD_DESCRIPTOR(9),
    WOULD_BLOCK(11),
    PERMISSION_DENIED(13),
    FAULT(14),
    BUSY(16),
    ALREADY_EXISTS(17),
    CROSS_DEVICE(18),
    NO_DEVICE(19),
    NOT_DIRECTORY(20),
    IS_DIRECTORY(21),
    INVALID_ARGUMENT(22),
    NOT_TTY(25),
    FILE_TOO_LARGE(27),
    NO_SPACE(28),
    ILLEGAL_SEEK(29),
    READ_ONLY(30),
    BROKEN_PIPE(32),
    NOT_EMPTY(39),
    TOO_MANY_SYMLINKS(40),
    NOT_SUPPORTED(95),
    NOT_FOUND(2);

    companion object {
        internal fun fromErrno(errno: Int): VfsError =
            entries.firstOrNull { it.errno == errno } ?: IO
    }
}

sealed interface VfsResult<out T> {
    data class Ok<T>(val value: T) : VfsResult<T>
    data class Err(val error: VfsError) : VfsResult<Nothing>
}

value class IoResult private constructor(val raw: Long) {
    val isSuccess: Boolean
        get() = raw >= 0

    val bytesTransferred: Int
        get() = if (isSuccess) raw.toInt() else 0

    val error: VfsError?
        get() = if (isSuccess) null else VfsError.fromErrno((-raw).toInt())

    companion object {
        fun success(bytesTransferred: Int): IoResult {
            require(bytesTransferred >= 0)
            return IoResult(bytesTransferred.toLong())
        }

        fun failure(error: VfsError): IoResult = IoResult(-error.errno.toLong())
    }
}

class VfsName private constructor(private val bytes: ByteArray) {
    private val hash = bytes.contentHashCode()

    internal val isDot: Boolean
        get() = bytes.size == 1 && bytes[0] == '.'.code.toByte()

    internal val isDotDot: Boolean
        get() = bytes.size == 2 &&
            bytes[0] == '.'.code.toByte() && bytes[1] == '.'.code.toByte()

    val size: Int
        get() = bytes.size

    fun copyBytes(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean =
        this === other || other is VfsName && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = hash

    override fun toString(): String = bytes.decodeToString()

    companion object {
        internal val ROOT = VfsName(ByteArray(0))

        fun fromBytes(bytes: ByteArray): VfsResult<VfsName> {
            if (bytes.isEmpty() || bytes.any { it == 0.toByte() || it == '/'.code.toByte() }) {
                return VfsResult.Err(VfsError.INVALID_ARGUMENT)
            }
            return VfsResult.Ok(VfsName(bytes.copyOf()))
        }

        internal fun fromPath(bytes: ByteArray, start: Int, end: Int): VfsName =
            VfsName(bytes.copyOfRange(start, end))
    }
}

class VfsPathname private constructor(private val bytes: ByteArray) {
    private val hash = bytes.contentHashCode()

    val isAbsolute: Boolean
        get() = bytes.firstOrNull() == '/'.code.toByte()

    val size: Int
        get() = bytes.size

    fun copyBytes(): ByteArray = bytes.copyOf()

    internal fun components(): VfsResult<List<VfsName>> {
        if (bytes.any { it == 0.toByte() }) {
            return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        }

        val components = mutableListOf<VfsName>()
        var cursor = 0
        while (cursor < bytes.size) {
            while (cursor < bytes.size && bytes[cursor] == '/'.code.toByte()) {
                cursor++
            }
            if (cursor == bytes.size) {
                break
            }

            val start = cursor
            while (cursor < bytes.size && bytes[cursor] != '/'.code.toByte()) {
                cursor++
            }
            components += VfsName.fromPath(bytes, start, cursor)
        }
        return VfsResult.Ok(components)
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is VfsPathname && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = hash

    override fun toString(): String = bytes.decodeToString()

    companion object {
        fun fromBytes(bytes: ByteArray): VfsPathname = VfsPathname(bytes.copyOf())

        fun fromString(path: String): VfsPathname = VfsPathname(path.encodeToByteArray())
    }
}

value class InodeId(val value: ULong)

value class FileMode(val bits: UInt)

enum class InodeType {
    REGULAR,
    DIRECTORY,
    SYMLINK,
    CHARACTER_DEVICE,
    BLOCK_DEVICE,
    PIPE,
    SOCKET,
}

data class InodeMetadata(
    val mode: FileMode,
    val size: ULong = 0uL,
    val linkCount: UInt = 1u,
    val deviceNumber: ULong = 0uL,
    val uid: UInt = 0u,
    val gid: UInt = 0u,
)

enum class AccessMode {
    READ,
    WRITE,
    READ_WRITE,
    PATH;

    internal val canRead: Boolean
        get() = this == READ || this == READ_WRITE

    internal val canWrite: Boolean
        get() = this == WRITE || this == READ_WRITE
}

enum class CreateDisposition {
    OPEN_EXISTING,
    OPEN_OR_CREATE,
    CREATE_NEW,
}

data class OpenOptions(
    val access: AccessMode = AccessMode.READ,
    val create: CreateDisposition = CreateDisposition.OPEN_EXISTING,
    val createMode: FileMode = FileMode(0x1A4u),
    val truncate: Boolean = false,
    val append: Boolean = false,
    val directoryOnly: Boolean = false,
    val followFinalSymlink: Boolean = true,
)

value class MountFlags(val bits: UInt) {
    operator fun contains(flag: MountFlags): Boolean = bits and flag.bits == flag.bits

    companion object {
        val NONE = MountFlags(0u)
        val READ_ONLY = MountFlags(1u shl 0)
        val NO_EXEC = MountFlags(1u shl 1)
        val NO_DEVICE = MountFlags(1u shl 2)
        val NO_SUID = MountFlags(1u shl 3)
    }
}

interface FileSystemOptions

data object EmptyFileSystemOptions : FileSystemOptions

data class MountOptions(
    val flags: MountFlags = MountFlags.NONE,
    val fileSystem: FileSystemOptions = EmptyFileSystemOptions,
)

interface FileSystemType {
    val name: String
    val magic: ULong

    fun createSuperBlock(options: FileSystemOptions): VfsResult<SuperBlock>
}

interface SuperBlockBackend {
    fun sync(): VfsResult<Unit> = VfsResult.Ok(Unit)

    fun release() {}
}

class SuperBlock internal constructor(
    val type: FileSystemType,
    val backend: SuperBlockBackend,
    createRoot: (SuperBlock) -> Inode,
) {
    val root: Dentry = Dentry(
        superBlock = this,
        name = VfsName.ROOT,
        parent = null,
        inode = createRoot(this),
    )
}

interface InodeBackend {
    val type: InodeType

    fun open(inode: Inode, options: OpenOptions): VfsResult<OpenFileBackend>

    fun evict(inode: Inode) {}
}

interface TruncatableBackend : InodeBackend {
    fun truncate(inode: Inode, size: ULong): VfsResult<Unit>
}

interface ContentBackedFile {
    fun attachContent(inode: Inode, content: FileContent, offset: Int, size: Int): Boolean
}

interface SymlinkBackend : InodeBackend {
    fun readLink(inode: Inode): VfsResult<VfsPathname>
}

interface DirectoryBackend : InodeBackend {
    val cacheNegativeLookups: Boolean
        get() = true

    fun lookup(directory: Inode, name: VfsName): VfsResult<Inode?>

    fun create(directory: Inode, name: VfsName, mode: FileMode): VfsResult<Inode> =
        VfsResult.Err(VfsError.NOT_SUPPORTED)

    fun mkdir(directory: Inode, name: VfsName, mode: FileMode): VfsResult<Inode> =
        VfsResult.Err(VfsError.NOT_SUPPORTED)

    fun symlink(directory: Inode, name: VfsName, target: VfsPathname): VfsResult<Inode> =
        VfsResult.Err(VfsError.NOT_SUPPORTED)

    fun unlink(directory: Inode, name: VfsName): VfsResult<Unit> =
        VfsResult.Err(VfsError.NOT_SUPPORTED)

    fun rmdir(directory: Inode, name: VfsName): VfsResult<Unit> =
        VfsResult.Err(VfsError.NOT_SUPPORTED)
}

class FilePosition(var value: Long = 0)

data class DirectoryEntry(
    val name: VfsName,
    val inodeId: InodeId,
    val type: InodeType,
)

/** Immutable bytes that can back a file without an eager copy. */
interface FileContent {
    val size: Int

    fun copyInto(
        destination: PreparedBufferDestination,
        destinationOffset: Int,
        sourceOffset: Int,
        count: Int,
    ): Int
}

interface OpenFileBackend {
    /** Stable identity for immutable file pages that may be shared by mappings. */
    val immutablePageSource: Any?
        get() = null

    fun read(
        inode: Inode,
        destination: PreparedBufferDestination,
        destinationOffset: Int,
        count: Int,
        position: FilePosition,
    ): IoResult = IoResult.failure(VfsError.NOT_SUPPORTED)

    fun write(
        inode: Inode,
        source: PreparedBufferSource,
        sourceOffset: Int,
        count: Int,
        position: FilePosition,
        append: Boolean,
    ): IoResult = IoResult.failure(VfsError.NOT_SUPPORTED)

    fun iterate(
        inode: Inode,
        position: FilePosition,
        emit: (entry: DirectoryEntry, nextOffset: Long) -> Boolean,
    ): VfsResult<Unit> = VfsResult.Err(VfsError.NOT_DIRECTORY)

    fun ioctl(inode: Inode, command: Int, args: UserMemory): Long =
        -VfsError.NOT_SUPPORTED.errno.toLong()

    fun poll(inode: Inode, events: Int): Long =
        -VfsError.NOT_SUPPORTED.errno.toLong()

    fun release() {}
}

enum class IoEvent {
    READABLE,
    WRITABLE,
}

enum class IoMode {
    BLOCKING,
    NON_BLOCKING,
}

interface PositionlessOpenFileBackend : OpenFileBackend {
    fun read(
        inode: Inode,
        destination: PreparedBufferDestination,
        destinationOffset: Int,
        count: Int,
    ): IoResult = IoResult.failure(VfsError.NOT_SUPPORTED)

    fun write(
        inode: Inode,
        source: PreparedBufferSource,
        sourceOffset: Int,
        count: Int,
    ): IoResult = IoResult.failure(VfsError.NOT_SUPPORTED)

    override fun read(
        inode: Inode,
        destination: PreparedBufferDestination,
        destinationOffset: Int,
        count: Int,
        position: FilePosition,
    ): IoResult = read(inode, destination, destinationOffset, count)

    override fun write(
        inode: Inode,
        source: PreparedBufferSource,
        sourceOffset: Int,
        count: Int,
        position: FilePosition,
        append: Boolean,
    ): IoResult = write(inode, source, sourceOffset, count)
}

interface DiscardingOpenFileBackend : PositionlessOpenFileBackend {
    fun discard(inode: Inode, count: Int): IoResult

    override fun write(
        inode: Inode,
        source: PreparedBufferSource,
        sourceOffset: Int,
        count: Int,
    ): IoResult = discard(inode, count)
}

interface WaitableOpenFileBackend : PositionlessOpenFileBackend {
    fun write(
        inode: Inode,
        source: PreparedBufferSource,
        sourceOffset: Int,
        count: Int,
        mode: IoMode,
    ): IoResult

    override fun write(
        inode: Inode,
        source: PreparedBufferSource,
        sourceOffset: Int,
        count: Int,
    ): IoResult = write(inode, source, sourceOffset, count, IoMode.BLOCKING)

    fun await(event: IoEvent, count: Int)
}

class Inode internal constructor(
    val id: InodeId,
    val superBlock: SuperBlock,
    internal val backend: InodeBackend,
    metadata: InodeMetadata,
) {
    private val lock = IrqSpinLock()
    private var currentMetadata = metadata
    private var openReferences = 0
    private var evicted = false

    val type: InodeType
        get() = backend.type

    fun metadata(): InodeMetadata = lock.withLock { currentMetadata }

    internal fun updateMetadata(update: (InodeMetadata) -> InodeMetadata) {
        var shouldEvict = false
        lock.withLock {
            if (!evicted) {
                currentMetadata = update(currentMetadata)
                if (currentMetadata.linkCount == 0u && openReferences == 0) {
                    evicted = true
                    shouldEvict = true
                }
            }
        }
        if (shouldEvict) {
            backend.evict(this)
        }
    }

    internal fun acquireOpenReference(): Boolean = lock.withLock {
        if (evicted) {
            false
        } else {
            openReferences++
            true
        }
    }

    internal fun releaseOpenReference() {
        var shouldEvict = false
        lock.withLock {
            check(openReferences > 0)
            openReferences--
            if (currentMetadata.linkCount == 0u && openReferences == 0 && !evicted) {
                evicted = true
                shouldEvict = true
            }
        }
        if (shouldEvict) {
            backend.evict(this)
        }
    }
}

class Dentry internal constructor(
    val superBlock: SuperBlock,
    val name: VfsName,
    val parent: Dentry?,
    inode: Inode?,
) {
    private val lock = IrqSpinLock()
    private var currentInode = inode
    private val children = mutableMapOf<VfsName, Dentry>()

    fun inode(): Inode? = lock.withLock { currentInode }

    internal fun cachedChild(name: VfsName): Dentry? = lock.withLock { children[name] }

    internal fun cacheChild(name: VfsName, inode: Inode?): Dentry = lock.withLock {
        children[name]?.also {
            if (inode != null) {
                it.install(inode)
            }
            return@withLock it
        }
        Dentry(superBlock, name, this, inode).also { children[name] = it }
    }

    internal fun markChildNegative(name: VfsName) {
        lock.withLock { children[name] }?.install(null)
    }

    private fun install(inode: Inode?) {
        lock.withLock { currentInode = inode }
    }
}

class Mount internal constructor(
    val superBlock: SuperBlock,
    val root: Dentry = superBlock.root,
    val flags: MountFlags = MountFlags.NONE,
) {
    init {
        require(root.superBlock === superBlock)
    }

    internal var parent: Mount? = null
        private set
    internal var mountPoint: Dentry? = null
        private set

    internal fun attach(parent: Mount, mountPoint: Dentry) {
        check(this.parent == null && this.mountPoint == null)
        this.parent = parent
        this.mountPoint = mountPoint
    }
}

data class VfsPath(val mount: Mount, val dentry: Dentry) {
    val inode: Inode?
        get() = dentry.inode()
}

private data class MountPoint(val mount: Mount, val dentry: Dentry)

class MountNamespace internal constructor(val root: Mount) {
    private val lock = IrqSpinLock()
    private val mounts = mutableMapOf<MountPoint, Mount>()

    internal fun mountedAt(path: VfsPath): Mount? =
        lock.withLock { mounts[MountPoint(path.mount, path.dentry)] }

    internal fun attach(target: VfsPath, child: Mount): VfsResult<Unit> = lock.withLock {
        val key = MountPoint(target.mount, target.dentry)
        if (mounts.containsKey(key)) {
            return@withLock VfsResult.Err(VfsError.BUSY)
        }
        child.attach(target.mount, target.dentry)
        mounts[key] = child
        VfsResult.Ok(Unit)
    }
}

class FileSystemContext internal constructor(val namespace: MountNamespace) {
    var root: VfsPath = VfsPath(namespace.root, namespace.root.root)
        internal set
    var workingDirectory: VfsPath = root
        internal set

    internal fun fork(): FileSystemContext = FileSystemContext(namespace).also {
        it.root = root
        it.workingDirectory = workingDirectory
    }
}

enum class SeekOrigin {
    START,
    CURRENT,
    END,
}

class OpenFileDescription internal constructor(
    val path: VfsPath,
    val inode: Inode,
    val access: AccessMode,
    append: Boolean,
    private val backend: OpenFileBackend,
) {
    private val references = AtomicInt(1)
    private val positionLock = IrqSpinLock()
    private val position = FilePosition()
    private val positionlessBackend = backend as? PositionlessOpenFileBackend
    private val statusFlags = AtomicInt(if (append) OpenFlags.O_APPEND else 0)

    val offset: Long
        get() = positionLock.withLock { position.value }

    internal val immutablePageSource: Any?
        get() = backend.immutablePageSource

    fun getStatusFlags(): Int = statusFlags.load()

    fun setStatusFlags(flags: Int) {
        statusFlags.store(flags and (OpenFlags.O_APPEND or OpenFlags.O_NONBLOCK))
    }

    fun retain(): Boolean {
        val previous = references.fetchAndAdd(1)
        if (previous in 1 until Int.MAX_VALUE) return true
        references.fetchAndAdd(-1)
        return false
    }

    fun release() {
        val previous = references.fetchAndAdd(-1)
        if (previous <= 0) {
            references.fetchAndAdd(1)
        } else if (previous == 1) {
            backend.release()
            inode.releaseOpenReference()
        }
    }

    fun read(destination: BufferDestination, offset: Int, count: Int): IoResult {
        readError(offset, count)?.let { return IoResult.failure(it) }
        val prepared = destination.prepareWrite(offset, count)
            ?: return IoResult.failure(VfsError.FAULT)
        return readBackend(prepared, offset, count, position)
    }

    internal fun read(
        destination: PreparedBufferDestination,
        offset: Int,
        count: Int,
    ): IoResult {
        readError(offset, count)?.let { return IoResult.failure(it) }
        return readBackend(destination, offset, count, position)
    }

    /** Reads without changing the open file description's shared offset. */
    fun readAt(
        fileOffset: ULong,
        destination: BufferDestination,
        offset: Int,
        count: Int,
    ): IoResult {
        if (fileOffset > Long.MAX_VALUE.toULong()) {
            return IoResult.failure(VfsError.INVALID_ARGUMENT)
        }
        readError(offset, count)?.let { return IoResult.failure(it) }
        val prepared = destination.prepareWrite(offset, count)
            ?: return IoResult.failure(VfsError.FAULT)
        return readBackend(prepared, offset, count, FilePosition(fileOffset.toLong()))
    }

    fun write(source: BufferSource, offset: Int, count: Int): IoResult {
        writeError(offset, count)?.let { return IoResult.failure(it) }
        val discard = positionlessBackend as? DiscardingOpenFileBackend
        if (discard != null) return discard.discard(inode, count)
        val prepared = source.prepareRead(offset, count)
            ?: return IoResult.failure(VfsError.FAULT)
        return writeBackend(prepared, offset, count)
    }

    internal fun write(source: PreparedBufferSource, offset: Int, count: Int): IoResult {
        writeError(offset, count)?.let { return IoResult.failure(it) }
        return writeBackend(source, offset, count)
    }

    internal fun discardWrite(count: Int): IoResult? {
        writeError(0, count)?.let { return IoResult.failure(it) }
        return (positionlessBackend as? DiscardingOpenFileBackend)?.discard(inode, count)
    }

    fun iterate(emit: (entry: DirectoryEntry, nextOffset: Long) -> Boolean): VfsResult<Unit> {
        if (!access.canRead || references.load() == 0) {
            return VfsResult.Err(VfsError.BAD_DESCRIPTOR)
        }
        if (inode.type != InodeType.DIRECTORY) {
            return VfsResult.Err(VfsError.NOT_DIRECTORY)
        }
        return positionLock.withLock {
            if (references.load() == 0) {
                return@withLock VfsResult.Err(VfsError.BAD_DESCRIPTOR)
            }
            backend.iterate(inode, position, emit)
        }
    }

    fun ioctl(command: Int, args: UserMemory): Long = positionLock.withLock {
        if (references.load() == 0) {
            -VfsError.BAD_DESCRIPTOR.errno.toLong()
        } else {
            backend.ioctl(inode, command, args)
        }
    }

    fun poll(events: Int): Long = positionLock.withLock {
        if (references.load() == 0) {
            -VfsError.BAD_DESCRIPTOR.errno.toLong()
        } else if (inode.type == InodeType.REGULAR || inode.type == InodeType.DIRECTORY) {
            (events and PollEvents.DEFAULT_FILE_EVENTS).toLong()
        } else {
            backend.poll(inode, events)
        }
    }

    fun seek(offset: Long, origin: SeekOrigin): VfsResult<Long> {
        if (references.load() == 0) {
            return VfsResult.Err(VfsError.BAD_DESCRIPTOR)
        }
        return positionLock.withLock {
            if (references.load() == 0) {
                return@withLock VfsResult.Err(VfsError.BAD_DESCRIPTOR)
            }
            val base = when (origin) {
                SeekOrigin.START -> 0L
                SeekOrigin.CURRENT -> position.value
                SeekOrigin.END -> inode.metadata().size.takeIf { it <= Long.MAX_VALUE.toULong() }?.toLong()
                    ?: return@withLock VfsResult.Err(VfsError.FILE_TOO_LARGE)
            }
            if ((offset > 0 && base > Long.MAX_VALUE - offset) ||
                (offset < 0 && base < Long.MIN_VALUE - offset)
            ) {
                return@withLock VfsResult.Err(VfsError.INVALID_ARGUMENT)
            }
            val next = base + offset
            if (next < 0) {
                return@withLock VfsResult.Err(VfsError.INVALID_ARGUMENT)
            }
            position.value = next
            VfsResult.Ok(next)
        }
    }

    private fun readBackend(
        destination: PreparedBufferDestination,
        offset: Int,
        count: Int,
        filePosition: FilePosition,
    ): IoResult {
        val positionless = positionlessBackend
        if (positionless == null) {
            if (filePosition !== position) {
                return backend.read(inode, destination, offset, count, filePosition)
            }
            return positionLock.withLock {
                backend.read(inode, destination, offset, count, filePosition)
            }
        }
        val waitable = positionless as? WaitableOpenFileBackend
            ?: return positionless.read(inode, destination, offset, count)
        while (true) {
            val mode = currentIoMode()
            val result = waitable.read(inode, destination, offset, count)
            if (result.error != VfsError.WOULD_BLOCK || mode == IoMode.NON_BLOCKING) return result
            waitable.await(IoEvent.READABLE, count)
        }
    }

    private fun writeBackend(source: PreparedBufferSource, offset: Int, count: Int): IoResult {
        val positionless = positionlessBackend
        if (positionless == null) {
            return positionLock.withLock {
                val append = statusFlags.load() and OpenFlags.O_APPEND != 0
                backend.write(inode, source, offset, count, position, append)
            }
        }
        val waitable = positionless as? WaitableOpenFileBackend
            ?: return positionless.write(inode, source, offset, count)
        var transferred = 0
        while (transferred < count) {
            val remaining = count - transferred
            val mode = currentIoMode()
            val result = waitable.write(inode, source, offset + transferred, remaining, mode)
            if (result.isSuccess) {
                val current = result.bytesTransferred
                if (current == 0) return IoResult.success(transferred)
                transferred += current
                if (transferred == count) return IoResult.success(transferred)
            } else if (result.error != VfsError.WOULD_BLOCK) {
                return if (transferred == 0) result else IoResult.success(transferred)
            }
            if (mode == IoMode.NON_BLOCKING) {
                return if (transferred == 0) result else IoResult.success(transferred)
            }

            waitable.await(IoEvent.WRITABLE, count - transferred)
        }
        return IoResult.success(transferred)
    }

    private fun readError(offset: Int, count: Int): VfsError? = when {
        offset < 0 || count < 0 -> VfsError.INVALID_ARGUMENT
        references.load() == 0 || !access.canRead -> VfsError.BAD_DESCRIPTOR
        inode.type == InodeType.DIRECTORY -> VfsError.IS_DIRECTORY
        else -> null
    }

    private fun writeError(offset: Int, count: Int): VfsError? = when {
        offset < 0 || count < 0 -> VfsError.INVALID_ARGUMENT
        references.load() == 0 || !access.canWrite -> VfsError.BAD_DESCRIPTOR
        MountFlags.READ_ONLY in path.mount.flags -> VfsError.READ_ONLY
        inode.type == InodeType.DIRECTORY -> VfsError.IS_DIRECTORY
        else -> null
    }

    private fun currentIoMode(): IoMode =
        if (statusFlags.load() and OpenFlags.O_NONBLOCK == 0) IoMode.BLOCKING
        else IoMode.NON_BLOCKING
}

class Vfs(
    private val maxSymlinkDepth: Int = 40,
) {
    private val registryLock = IrqSpinLock()
    private val fileSystems = mutableMapOf<String, FileSystemType>()
    private val pipeLock = IrqSpinLock()
    private var nextPipeInode = ULong.MAX_VALUE

    init {
        require(maxSymlinkDepth > 0)
    }

    fun register(fileSystem: FileSystemType): VfsResult<Unit> = registryLock.withLock {
        if (fileSystems.containsKey(fileSystem.name)) {
            return@withLock VfsResult.Err(VfsError.ALREADY_EXISTS)
        }
        fileSystems[fileSystem.name] = fileSystem
        VfsResult.Ok(Unit)
    }

    fun createContext(
        fileSystemName: String,
        options: MountOptions = MountOptions(),
    ): VfsResult<FileSystemContext> {
        val superBlock = when (val result = createSuperBlock(fileSystemName, options.fileSystem)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        val rootMount = Mount(superBlock, flags = options.flags)
        return VfsResult.Ok(FileSystemContext(MountNamespace(rootMount)))
    }

    fun createPipe(context: FileSystemContext): VfsResult<Pair<OpenFileDescription, OpenFileDescription>> {
        val superBlock = context.workingDirectory.mount.superBlock
        val state = PipeState()
        val readInode = pipeInode(superBlock, state, writable = false)
        val writeInode = pipeInode(superBlock, state, writable = true)
        if (!readInode.acquireOpenReference() || !writeInode.acquireOpenReference()) {
            return VfsResult.Err(VfsError.IO)
        }
        return VfsResult.Ok(
            OpenFileDescription(
                path = context.workingDirectory,
                inode = readInode,
                access = AccessMode.READ,
                append = false,
                backend = PipeEndpoint(state, writable = false),
            ) to OpenFileDescription(
                path = context.workingDirectory,
                inode = writeInode,
                access = AccessMode.WRITE,
                append = false,
                backend = PipeEndpoint(state, writable = true),
            ),
        )
    }

    private fun pipeInode(
        superBlock: SuperBlock,
        state: PipeState,
        writable: Boolean,
    ): Inode = Inode(
        id = pipeLock.withLock { InodeId(nextPipeInode--) },
        superBlock = superBlock,
        backend = PipeInode(state, writable),
        metadata = InodeMetadata(FileMode(0x1A4u)),
    )

    fun mount(
        context: FileSystemContext,
        target: VfsPathname,
        fileSystemName: String,
        options: MountOptions = MountOptions(),
    ): VfsResult<Mount> {
        val path = when (val result = resolve(context, target)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        if (path.inode?.type != InodeType.DIRECTORY) {
            return VfsResult.Err(VfsError.NOT_DIRECTORY)
        }

        val superBlock = when (val result = createSuperBlock(fileSystemName, options.fileSystem)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        val mount = Mount(superBlock, flags = options.flags)
        return when (val attached = context.namespace.attach(path, mount)) {
            is VfsResult.Ok -> VfsResult.Ok(mount)
            is VfsResult.Err -> {
                superBlock.backend.release()
                attached
            }
        }
    }

    fun resolve(
        context: FileSystemContext,
        pathname: VfsPathname,
        followFinalSymlink: Boolean = true,
    ): VfsResult<VfsPath> = resolveAt(
        context = context,
        directory = context.workingDirectory,
        pathname = pathname,
        followFinalSymlink = followFinalSymlink,
    )

    fun resolveAt(
        context: FileSystemContext,
        directory: VfsPath,
        pathname: VfsPathname,
        followFinalSymlink: Boolean = true,
    ): VfsResult<VfsPath> {
        if (pathname.size == 0) {
            return VfsResult.Err(VfsError.NOT_FOUND)
        }
        val components = when (val result = pathname.components()) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        val start = if (pathname.isAbsolute) context.root else directory
        return walk(context, start, components, followFinalSymlink)
    }

    fun open(
        context: FileSystemContext,
        pathname: VfsPathname,
        options: OpenOptions = OpenOptions(),
    ): VfsResult<OpenFileDescription> = openAt(
        context = context,
        directory = context.workingDirectory,
        pathname = pathname,
        options = options,
    )

    fun openAt(
        context: FileSystemContext,
        directory: VfsPath,
        pathname: VfsPathname,
        options: OpenOptions = OpenOptions(),
    ): VfsResult<OpenFileDescription> {
        if (options.truncate && !options.access.canWrite) {
            return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        }

        val path = when (options.create) {
            CreateDisposition.OPEN_EXISTING -> when (
                val result = resolveAt(
                    context,
                    directory,
                    pathname,
                    options.followFinalSymlink,
                )
            ) {
                is VfsResult.Ok -> result.value
                is VfsResult.Err -> return result
            }

            CreateDisposition.OPEN_OR_CREATE,
            CreateDisposition.CREATE_NEW,
            -> when (val result = openOrCreate(context, directory, pathname, options)) {
                is VfsResult.Ok -> result.value
                is VfsResult.Err -> return result
            }
        }

        val inode = path.inode ?: return VfsResult.Err(VfsError.NOT_FOUND)
        if (inode.type == InodeType.SYMLINK) {
            return VfsResult.Err(VfsError.TOO_MANY_SYMLINKS)
        }
        if (options.directoryOnly && inode.type != InodeType.DIRECTORY) {
            return VfsResult.Err(VfsError.NOT_DIRECTORY)
        }
        if (options.access.canWrite && inode.type == InodeType.DIRECTORY) {
            return VfsResult.Err(VfsError.IS_DIRECTORY)
        }
        if (options.access.canWrite && MountFlags.READ_ONLY in path.mount.flags) {
            return VfsResult.Err(VfsError.READ_ONLY)
        }
        if (!inode.acquireOpenReference()) {
            return VfsResult.Err(VfsError.NOT_FOUND)
        }

        val backend = if (options.access == AccessMode.PATH) {
            PathOnlyHandle
        } else {
            when (val result = inode.backend.open(inode, options)) {
                is VfsResult.Ok -> result.value
                is VfsResult.Err -> {
                    inode.releaseOpenReference()
                    return result
                }
            }
        }

        if (options.truncate && inode.type == InodeType.REGULAR) {
            val truncatable = inode.backend as? TruncatableBackend
            val result = truncatable?.truncate(inode, 0uL)
                ?: VfsResult.Err(VfsError.NOT_SUPPORTED)
            if (result is VfsResult.Err) {
                backend.release()
                inode.releaseOpenReference()
                return result
            }
        }

        return VfsResult.Ok(
            OpenFileDescription(
                path = path,
                inode = inode,
                access = options.access,
                append = options.append,
                backend = backend,
            )
        )
    }

    internal fun createFile(
        directory: VfsPath,
        name: VfsName,
        mode: FileMode,
        content: FileContent,
        contentOffset: Int,
        contentSize: Int,
    ): VfsResult<VfsPath> {
        val path = when (val result = createChild(directory, name) { backend, parent ->
            backend.create(parent, name, mode)
        }) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        val inode = path.inode ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val backed = inode.backend as? ContentBackedFile
        if (backed?.attachContent(inode, content, contentOffset, contentSize) == true) {
            return VfsResult.Ok(path)
        }

        val parent = directory.inode ?: return VfsResult.Err(VfsError.IO)
        val backend = parent.backend as? DirectoryBackend ?: return VfsResult.Err(VfsError.IO)
        backend.unlink(parent, name)
        directory.dentry.markChildNegative(name)
        return VfsResult.Err(VfsError.IO)
    }

    internal fun mkdirAt(
        directory: VfsPath,
        name: VfsName,
        mode: FileMode,
    ): VfsResult<VfsPath> = createChild(directory, name) { backend, parent ->
        backend.mkdir(parent, name, mode)
    }

    internal fun symlinkAt(
        directory: VfsPath,
        name: VfsName,
        target: VfsPathname,
    ): VfsResult<VfsPath> = createChild(directory, name) { backend, parent ->
        backend.symlink(parent, name, target)
    }

    fun mkdir(
        context: FileSystemContext,
        pathname: VfsPathname,
        mode: FileMode = FileMode(0x1EDu),
    ): VfsResult<VfsPath> {
        val parent = when (val result = resolveParent(context, pathname)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        if (MountFlags.READ_ONLY in parent.path.mount.flags) {
            return VfsResult.Err(VfsError.READ_ONLY)
        }
        val directory = parent.path.inode ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val backend = directory.backend as? DirectoryBackend
            ?: return VfsResult.Err(VfsError.NOT_DIRECTORY)

        when (val existing = lookupChild(context, parent.path, parent.name)) {
            is VfsResult.Ok -> return VfsResult.Err(VfsError.ALREADY_EXISTS)
            is VfsResult.Err -> if (existing.error != VfsError.NOT_FOUND) return existing
        }

        val inode = when (val result = backend.mkdir(directory, parent.name, mode)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        val dentry = parent.path.dentry.cacheChild(parent.name, inode)
        return VfsResult.Ok(VfsPath(parent.path.mount, dentry))
    }

    fun symlink(
        context: FileSystemContext,
        target: VfsPathname,
        linkPath: VfsPathname,
    ): VfsResult<VfsPath> {
        val parent = when (val result = resolveParent(context, linkPath)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        if (MountFlags.READ_ONLY in parent.path.mount.flags) {
            return VfsResult.Err(VfsError.READ_ONLY)
        }
        val directory = parent.path.inode ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val backend = directory.backend as? DirectoryBackend
            ?: return VfsResult.Err(VfsError.NOT_DIRECTORY)

        when (val existing = lookupChild(context, parent.path, parent.name)) {
            is VfsResult.Ok -> return VfsResult.Err(VfsError.ALREADY_EXISTS)
            is VfsResult.Err -> if (existing.error != VfsError.NOT_FOUND) return existing
        }

        val inode = when (val result = backend.symlink(directory, parent.name, target)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        val dentry = parent.path.dentry.cacheChild(parent.name, inode)
        return VfsResult.Ok(VfsPath(parent.path.mount, dentry))
    }

    fun unlink(context: FileSystemContext, pathname: VfsPathname): VfsResult<Unit> =
        remove(context, pathname, directory = false)

    fun rmdir(context: FileSystemContext, pathname: VfsPathname): VfsResult<Unit> =
        remove(context, pathname, directory = true)

    fun chdir(context: FileSystemContext, pathname: VfsPathname): VfsResult<Unit> {
        val path = when (val result = resolve(context, pathname)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        if (path.inode?.type != InodeType.DIRECTORY) {
            return VfsResult.Err(VfsError.NOT_DIRECTORY)
        }
        context.workingDirectory = path
        return VfsResult.Ok(Unit)
    }

    fun absolutePath(
        context: FileSystemContext,
        initial: VfsPath,
    ): VfsResult<ByteArray> {
        if (initial.inode == null) {
            return VfsResult.Err(VfsError.NOT_FOUND)
        }

        val components = mutableListOf<ByteArray>()
        var current = initial
        while (current != context.root) {
            if (current.dentry === current.mount.root) {
                val parentMount = current.mount.parent
                    ?: return VfsResult.Err(VfsError.NOT_FOUND)
                val mountPoint = current.mount.mountPoint
                    ?: return VfsResult.Err(VfsError.NOT_FOUND)
                current = VfsPath(parentMount, mountPoint)
                if (current == context.root) {
                    break
                }
            }

            components += current.dentry.name.copyBytes()
            val parent = current.dentry.parent
                ?: return VfsResult.Err(VfsError.NOT_FOUND)
            current = VfsPath(current.mount, parent)
        }

        if (components.isEmpty()) {
            return VfsResult.Ok(byteArrayOf('/'.code.toByte()))
        }

        val pathSize = components.fold(components.size - 1L) { size, component ->
            size + component.size
        } + 1L
        if (pathSize > Int.MAX_VALUE) {
            return VfsResult.Err(VfsError.FILE_TOO_LARGE)
        }

        val result = ByteArray(pathSize.toInt())
        var offset = 0
        result[offset++] = '/'.code.toByte()
        components.asReversed().forEachIndexed { index, component ->
            if (index != 0) {
                result[offset++] = '/'.code.toByte()
            }
            component.copyInto(result, destinationOffset = offset)
            offset += component.size
        }
        return VfsResult.Ok(result)
    }

    private fun createSuperBlock(name: String, options: FileSystemOptions): VfsResult<SuperBlock> {
        val fileSystem = registryLock.withLock { fileSystems[name] }
            ?: return VfsResult.Err(VfsError.NOT_FOUND)
        return fileSystem.createSuperBlock(options)
    }

    private fun openOrCreate(
        context: FileSystemContext,
        directory: VfsPath,
        pathname: VfsPathname,
        options: OpenOptions,
    ): VfsResult<VfsPath> {
        val parent = when (val result = resolveParent(context, directory, pathname)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }

        when (val existing = lookupChild(context, parent.path, parent.name)) {
            is VfsResult.Ok -> {
                if (options.create == CreateDisposition.CREATE_NEW) {
                    return VfsResult.Err(VfsError.ALREADY_EXISTS)
                }
                if (options.followFinalSymlink && existing.value.inode?.type == InodeType.SYMLINK) {
                    return resolve(context, pathname, followFinalSymlink = true)
                }
                return existing
            }
            is VfsResult.Err -> if (existing.error != VfsError.NOT_FOUND) return existing
        }

        if (MountFlags.READ_ONLY in parent.path.mount.flags) {
            return VfsResult.Err(VfsError.READ_ONLY)
        }
        val directory = parent.path.inode ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val backend = directory.backend as? DirectoryBackend
            ?: return VfsResult.Err(VfsError.NOT_DIRECTORY)
        val inode = when (val created = backend.create(directory, parent.name, options.createMode)) {
            is VfsResult.Ok -> created.value
            is VfsResult.Err -> return created
        }
        val dentry = parent.path.dentry.cacheChild(parent.name, inode)
        return VfsResult.Ok(VfsPath(parent.path.mount, dentry))
    }

    private inline fun createChild(
        directory: VfsPath,
        name: VfsName,
        create: (DirectoryBackend, Inode) -> VfsResult<Inode>,
    ): VfsResult<VfsPath> {
        if (MountFlags.READ_ONLY in directory.mount.flags) {
            return VfsResult.Err(VfsError.READ_ONLY)
        }
        val parent = directory.inode ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val backend = parent.backend as? DirectoryBackend
            ?: return VfsResult.Err(VfsError.NOT_DIRECTORY)
        val inode = when (val result = create(backend, parent)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        return VfsResult.Ok(VfsPath(directory.mount, directory.dentry.cacheChild(name, inode)))
    }

    private fun remove(
        context: FileSystemContext,
        pathname: VfsPathname,
        directory: Boolean,
    ): VfsResult<Unit> {
        val parent = when (val result = resolveParent(context, pathname)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        if (MountFlags.READ_ONLY in parent.path.mount.flags) {
            return VfsResult.Err(VfsError.READ_ONLY)
        }
        val target = when (
            val result = lookupChild(context, parent.path, parent.name, followMount = false)
        ) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        if (context.namespace.mountedAt(target) != null) {
            return VfsResult.Err(VfsError.BUSY)
        }
        val inode = target.inode ?: return VfsResult.Err(VfsError.NOT_FOUND)
        if (directory != (inode.type == InodeType.DIRECTORY)) {
            return VfsResult.Err(if (directory) VfsError.NOT_DIRECTORY else VfsError.IS_DIRECTORY)
        }
        val parentInode = parent.path.inode ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val backend = parentInode.backend as? DirectoryBackend
            ?: return VfsResult.Err(VfsError.NOT_DIRECTORY)
        val removed = if (directory) {
            backend.rmdir(parentInode, parent.name)
        } else {
            backend.unlink(parentInode, parent.name)
        }
        if (removed is VfsResult.Ok) {
            parent.path.dentry.markChildNegative(parent.name)
        }
        return removed
    }

    private data class ParentPath(val path: VfsPath, val name: VfsName)

    private fun resolveParent(
        context: FileSystemContext,
        pathname: VfsPathname,
    ): VfsResult<ParentPath> = resolveParent(
        context = context,
        directory = context.workingDirectory,
        pathname = pathname,
    )

    private fun resolveParent(
        context: FileSystemContext,
        directory: VfsPath,
        pathname: VfsPathname,
    ): VfsResult<ParentPath> {
        if (pathname.size == 0) {
            return VfsResult.Err(VfsError.NOT_FOUND)
        }
        val components = when (val result = pathname.components()) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        if (components.isEmpty()) {
            return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        }
        val name = components.last()
        if (name.isDot || name.isDotDot) {
            return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        }
        val start = if (pathname.isAbsolute) context.root else directory
        val parent = when (val result = walk(context, start, components.dropLast(1), true)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        if (parent.inode?.type != InodeType.DIRECTORY) {
            return VfsResult.Err(VfsError.NOT_DIRECTORY)
        }
        return VfsResult.Ok(ParentPath(parent, name))
    }

    private fun walk(
        context: FileSystemContext,
        start: VfsPath,
        components: List<VfsName>,
        followFinalSymlink: Boolean,
    ): VfsResult<VfsPath> {
        var current = followMounts(context.namespace, start)
        var symlinkDepth = 0
        val remaining = ArrayDeque(components)

        while (remaining.isNotEmpty()) {
            val name = remaining.removeFirst()
            when {
                name.isDot -> continue
                name.isDotDot -> {
                    current = walkUp(context, current)
                    continue
                }
            }

            val parent = current
            val next = when (val result = lookupChild(context, parent, name)) {
                is VfsResult.Ok -> result.value
                is VfsResult.Err -> return result
            }
            val inode = next.inode ?: return VfsResult.Err(VfsError.NOT_FOUND)
            val shouldFollow = inode.type == InodeType.SYMLINK &&
                (remaining.isNotEmpty() || followFinalSymlink)
            if (!shouldFollow) {
                current = next
                continue
            }

            if (++symlinkDepth > maxSymlinkDepth) {
                return VfsResult.Err(VfsError.TOO_MANY_SYMLINKS)
            }
            val symlink = inode.backend as? SymlinkBackend
                ?: return VfsResult.Err(VfsError.NOT_SUPPORTED)
            val target = when (val result = symlink.readLink(inode)) {
                is VfsResult.Ok -> result.value
                is VfsResult.Err -> return result
            }
            val targetComponents = when (val result = target.components()) {
                is VfsResult.Ok -> result.value
                is VfsResult.Err -> return result
            }
            current = if (target.isAbsolute) context.root else parent
            for (index in targetComponents.indices.reversed()) {
                remaining.addFirst(targetComponents[index])
            }
        }
        return VfsResult.Ok(current)
    }

    private fun lookupChild(
        context: FileSystemContext,
        parent: VfsPath,
        name: VfsName,
        followMount: Boolean = true,
    ): VfsResult<VfsPath> {
        parent.dentry.cachedChild(name)?.let { cached ->
            val inode = cached.inode()
            if (inode == null &&
                (parent.inode?.backend as? DirectoryBackend)?.cacheNegativeLookups != false
            ) {
                return VfsResult.Err(VfsError.NOT_FOUND)
            }
            if (inode != null) {
                val path = VfsPath(parent.mount, cached)
                return VfsResult.Ok(if (followMount) followMounts(context.namespace, path) else path)
            }
        }

        val directory = parent.inode ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val backend = directory.backend as? DirectoryBackend
            ?: return VfsResult.Err(VfsError.NOT_DIRECTORY)
        val inode = when (val result = backend.lookup(directory, name)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        if (inode == null) {
            if (backend.cacheNegativeLookups) {
                parent.dentry.cacheChild(name, null)
            }
            return VfsResult.Err(VfsError.NOT_FOUND)
        }
        val dentry = parent.dentry.cacheChild(name, inode)
        val path = VfsPath(parent.mount, dentry)
        return VfsResult.Ok(if (followMount) followMounts(context.namespace, path) else path)
    }

    private fun followMounts(namespace: MountNamespace, initial: VfsPath): VfsPath {
        var current = initial
        while (true) {
            val mounted = namespace.mountedAt(current) ?: return current
            current = VfsPath(mounted, mounted.root)
        }
    }

    private fun walkUp(context: FileSystemContext, initial: VfsPath): VfsPath {
        var current = initial
        if (current == context.root) {
            return current
        }

        while (current.dentry === current.mount.root) {
            val parentMount = current.mount.parent ?: return current
            val mountPoint = current.mount.mountPoint ?: return current
            current = VfsPath(parentMount, mountPoint)
            if (current == context.root) {
                return current
            }
        }

        val parent = current.dentry.parent ?: return current
        return VfsPath(current.mount, parent)
    }
}

private data object PathOnlyHandle : OpenFileBackend

private class PipeInode(
    private val state: PipeState,
    private val writable: Boolean,
) : InodeBackend {
    override val type: InodeType = InodeType.PIPE

    override fun open(inode: Inode, options: OpenOptions): VfsResult<OpenFileBackend> =
        VfsResult.Ok(PipeEndpoint(state, writable))
}

private class PipeEndpoint(
    private val state: PipeState,
    private val writable: Boolean,
) : WaitableOpenFileBackend {
    override fun read(
        inode: Inode,
        destination: PreparedBufferDestination,
        destinationOffset: Int,
        count: Int,
    ): IoResult = if (writable) {
        IoResult.failure(VfsError.BAD_DESCRIPTOR)
    } else {
        state.read(destination, destinationOffset, count)
    }

    override fun write(
        inode: Inode,
        source: PreparedBufferSource,
        sourceOffset: Int,
        count: Int,
        mode: IoMode,
    ): IoResult = if (!writable) {
        IoResult.failure(VfsError.BAD_DESCRIPTOR)
    } else {
        state.write(source, sourceOffset, count, mode)
    }

    override fun await(event: IoEvent, count: Int) {
        check(writable == (event == IoEvent.WRITABLE))
        state.await(event, count)
    }

    override fun poll(inode: Inode, events: Int): Long = state.poll(events, writable)

    override fun release() {
        state.close(writable)
    }
}

private class PipeWaitQueue {
    class Waiter {
        var minimumBytes = 0
        lateinit var thread: Thread
        var ready = false

        fun arm(minimumBytes: Int, thread: Thread) {
            this.minimumBytes = minimumBytes
            this.thread = thread
            ready = false
        }
    }

    private val waiters = ArrayDeque<Waiter>()
    private val recycled = ArrayDeque<Waiter>()

    fun acquire(minimumBytes: Int, thread: Thread): Waiter =
        (recycled.removeFirstOrNull() ?: Waiter()).also { waiter ->
            waiter.arm(minimumBytes, thread)
            waiters.addLast(waiter)
        }

    fun release(waiter: Waiter) {
        check(waiter.ready)
        recycled.addLast(waiter)
    }

    fun notifyReady(availableBytes: Int) {
        val waiter = waiters.firstOrNull() ?: return
        if (availableBytes < waiter.minimumBytes) return
        waiters.removeFirst()
        waiter.ready = true
        Scheduler.wake(waiter.thread)
    }

    fun notifyAllWaiters() {
        while (waiters.isNotEmpty()) {
            val waiter = waiters.removeFirst()
            waiter.ready = true
            Scheduler.wake(waiter.thread)
        }
    }
}

private class PipeState {
    private companion object {
        const val CAPACITY_PAGES = 16
        val CAPACITY_BYTES = CAPACITY_PAGES * PAGE_SIZE_BYTES.toInt()
        val ATOMIC_WRITE_BYTES = PAGE_SIZE_BYTES.toInt()
    }

    private val lock = IrqSpinLock()
    private val buffer = ByteArray(CAPACITY_BYTES)
    private val readWaiters = PipeWaitQueue()
    private val writeWaiters = PipeWaitQueue()
    private var head = 0
    private var tail = 0
    private var size = 0
    private var readers = 1
    private var writers = 1

    fun read(destination: PreparedBufferDestination, offset: Int, count: Int): IoResult = lock.withLock {
        if (count == 0) return@withLock IoResult.success(0)
        if (size == 0) {
            return@withLock if (writers == 0) IoResult.success(0)
            else IoResult.failure(VfsError.WOULD_BLOCK)
        }
        val requestedTransfer = minOf(count, size)
        val firstChunk = minOf(requestedTransfer, buffer.size - tail)
        var transferred = destination.copyFrom(offset, buffer, tail, firstChunk)
        if (transferred == firstChunk) {
            val remaining = requestedTransfer - firstChunk
            if (remaining != 0) {
                transferred += destination.copyFrom(offset + firstChunk, buffer, 0, remaining)
            }
        }
        if (transferred == 0) return@withLock IoResult.failure(VfsError.FAULT)
        tail = (tail + transferred) % buffer.size
        size -= transferred
        writeWaiters.notifyReady(buffer.size - size)
        IoResult.success(transferred)
    }

    fun write(
        source: PreparedBufferSource,
        offset: Int,
        count: Int,
        mode: IoMode,
    ): IoResult = lock.withLock {
        if (readers == 0) return@withLock IoResult.failure(VfsError.BROKEN_PIPE)
        if (count == 0) return@withLock IoResult.success(0)
        val available = buffer.size - size
        val minimumWriteSize = when {
            mode == IoMode.BLOCKING -> minOf(count, buffer.size)
            count <= ATOMIC_WRITE_BYTES -> count
            else -> 1
        }
        if (available < minimumWriteSize) {
            return@withLock IoResult.failure(VfsError.WOULD_BLOCK)
        }
        val requestedTransfer = minOf(count, available)
        val firstChunk = minOf(requestedTransfer, buffer.size - head)
        var transferred = source.copyTo(offset, buffer, head, firstChunk)
        if (transferred == firstChunk) {
            val remaining = requestedTransfer - firstChunk
            if (remaining != 0) {
                transferred += source.copyTo(offset + firstChunk, buffer, 0, remaining)
            }
        }
        if (transferred == 0) return@withLock IoResult.failure(VfsError.FAULT)
        head = (head + transferred) % buffer.size
        size += transferred
        readWaiters.notifyReady(size)
        IoResult.success(transferred)
    }

    fun await(event: IoEvent, count: Int) {
        val thread = checkNotNull(ProcessManager.currentThread())
        val minimumBytes = if (event == IoEvent.READABLE) {
            1
        } else {
            minOf(count, buffer.size)
        }
        val queue = if (event == IoEvent.READABLE) readWaiters else writeWaiters
        var waiter: PipeWaitQueue.Waiter? = null
        lock.withLock {
            val availableBytes = when (event) {
                IoEvent.READABLE -> size
                IoEvent.WRITABLE -> buffer.size - size
            }
            val becameReady = availableBytes >= minimumBytes || when (event) {
                IoEvent.READABLE -> writers == 0
                IoEvent.WRITABLE -> readers == 0
            }
            if (!becameReady) {
                waiter = queue.acquire(minimumBytes, thread)
            }
        }
        val queued = waiter ?: return

        do {
            check(Scheduler.parkCurrent()) { "Cannot park a pipe waiter" }
        } while (lock.withLock { !queued.ready })
        lock.withLock { queue.release(queued) }
    }

    fun poll(events: Int, writable: Boolean): Long = lock.withLock {
        var available = 0
        if (writable) {
            if (readers == 0) available = PollEvents.POLLERR
            else if (buffer.size - size >= ATOMIC_WRITE_BYTES) {
                available = PollEvents.NORMAL_OUTPUT
            }
        } else if (size != 0 || writers == 0) {
            available = PollEvents.NORMAL_INPUT
        }
        (available and events).toLong()
    }

    fun close(writable: Boolean) = lock.withLock {
        if (writable) {
            check(writers > 0)
            writers--
            if (writers == 0) readWaiters.notifyAllWaiters()
        } else {
            check(readers > 0)
            readers--
            if (readers == 0) writeWaiters.notifyAllWaiters()
        }
    }
}
