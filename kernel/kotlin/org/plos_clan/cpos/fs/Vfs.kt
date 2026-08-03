@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package org.plos_clan.cpos.fs

import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.utils.IrqSpinLock
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
    val isAbsolute: Boolean
        get() = bytes.firstOrNull() == '/'.code.toByte()

    val size: Int
        get() = bytes.size

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
)

enum class AccessMode {
    READ,
    WRITE,
    READ_WRITE;

    internal val canRead: Boolean
        get() = this != WRITE

    internal val canWrite: Boolean
        get() = this != READ
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

interface OpenFileBackend {
    fun read(
        inode: Inode,
        destination: ByteArray,
        destinationOffset: Int,
        count: Int,
        position: FilePosition,
    ): IoResult = IoResult.failure(VfsError.NOT_SUPPORTED)

    fun write(
        inode: Inode,
        source: ByteArray,
        sourceOffset: Int,
        count: Int,
        position: FilePosition,
        append: Boolean,
    ): IoResult = IoResult.failure(VfsError.NOT_SUPPORTED)

    fun iterate(
        inode: Inode,
        position: FilePosition,
        emit: (DirectoryEntry) -> Boolean,
    ): VfsResult<Unit> = VfsResult.Err(VfsError.NOT_DIRECTORY)

    fun ioctl(inode: Inode, command: Int, args: UserMemory): Long =
        -VfsError.NOT_SUPPORTED.errno.toLong()

    fun poll(inode: Inode, events: Int): Long =
        -VfsError.NOT_SUPPORTED.errno.toLong()

    fun release() {}
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
    private val append: Boolean,
    private val backend: OpenFileBackend,
) {
    private val references = AtomicInt(1)
    private val positionLock = IrqSpinLock()
    private val position = FilePosition()

    val offset: Long
        get() = positionLock.withLock { position.value }

    fun retain(): Boolean {
        var observed = references.load()
        while (observed in 1 until Int.MAX_VALUE) {
            if (references.compareAndSet(observed, observed + 1)) {
                return true
            }
            observed = references.load()
        }
        return false
    }

    fun release() {
        var observed = references.load()
        while (observed > 0) {
            if (!references.compareAndSet(observed, observed - 1)) {
                observed = references.load()
                continue
            }
            if (observed == 1) {
                positionLock.withLock {
                    backend.release()
                    inode.releaseOpenReference()
                }
            }
            return
        }
    }

    fun read(destination: ByteArray, offset: Int = 0, count: Int = destination.size - offset): IoResult {
        if (!isValidRange(destination, offset, count)) {
            return IoResult.failure(VfsError.INVALID_ARGUMENT)
        }
        if (!access.canRead) {
            return IoResult.failure(VfsError.BAD_DESCRIPTOR)
        }
        if (inode.type == InodeType.DIRECTORY) {
            return IoResult.failure(VfsError.IS_DIRECTORY)
        }
        return positionLock.withLock {
            if (references.load() == 0) {
                return@withLock IoResult.failure(VfsError.BAD_DESCRIPTOR)
            }
            backend.read(inode, destination, offset, count, position)
        }
    }

    /** Reads without changing the open file description's shared offset. */
    fun readAt(
        fileOffset: ULong,
        destination: ByteArray,
        offset: Int = 0,
        count: Int = destination.size - offset,
    ): IoResult {
        if (!isValidRange(destination, offset, count) || fileOffset > Long.MAX_VALUE.toULong()) {
            return IoResult.failure(VfsError.INVALID_ARGUMENT)
        }
        if (!access.canRead) {
            return IoResult.failure(VfsError.BAD_DESCRIPTOR)
        }
        if (inode.type == InodeType.DIRECTORY) {
            return IoResult.failure(VfsError.IS_DIRECTORY)
        }
        return positionLock.withLock {
            if (references.load() == 0) {
                return@withLock IoResult.failure(VfsError.BAD_DESCRIPTOR)
            }
            backend.read(
                inode,
                destination,
                offset,
                count,
                FilePosition(fileOffset.toLong()),
            )
        }
    }

    fun write(source: ByteArray, offset: Int = 0, count: Int = source.size - offset): IoResult {
        if (!isValidRange(source, offset, count)) {
            return IoResult.failure(VfsError.INVALID_ARGUMENT)
        }
        if (!access.canWrite) {
            return IoResult.failure(VfsError.BAD_DESCRIPTOR)
        }
        if (MountFlags.READ_ONLY in path.mount.flags) {
            return IoResult.failure(VfsError.READ_ONLY)
        }
        if (inode.type == InodeType.DIRECTORY) {
            return IoResult.failure(VfsError.IS_DIRECTORY)
        }
        return positionLock.withLock {
            if (references.load() == 0) {
                return@withLock IoResult.failure(VfsError.BAD_DESCRIPTOR)
            }
            backend.write(inode, source, offset, count, position, append)
        }
    }

    fun iterate(emit: (DirectoryEntry) -> Boolean): VfsResult<Unit> {
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

    private fun isValidRange(buffer: ByteArray, offset: Int, count: Int): Boolean =
        offset >= 0 && count >= 0 && offset <= buffer.size - count
}

class Vfs(
    private val maxSymlinkDepth: Int = 40,
) {
    private val registryLock = IrqSpinLock()
    private val fileSystems = mutableMapOf<String, FileSystemType>()

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
    ): VfsResult<VfsPath> {
        if (pathname.size == 0) {
            return VfsResult.Err(VfsError.NOT_FOUND)
        }
        val components = when (val result = pathname.components()) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        val start = if (pathname.isAbsolute) context.root else context.workingDirectory
        return walk(context, start, components, followFinalSymlink)
    }

    fun open(
        context: FileSystemContext,
        pathname: VfsPathname,
        options: OpenOptions = OpenOptions(),
    ): VfsResult<OpenFileDescription> {
        if (options.truncate && !options.access.canWrite) {
            return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        }

        val path = when (options.create) {
            CreateDisposition.OPEN_EXISTING -> when (
                val result = resolve(context, pathname, options.followFinalSymlink)
            ) {
                is VfsResult.Ok -> result.value
                is VfsResult.Err -> return result
            }

            CreateDisposition.OPEN_OR_CREATE,
            CreateDisposition.CREATE_NEW,
            -> when (val result = openOrCreate(context, pathname, options)) {
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

        val backend = when (val result = inode.backend.open(inode, options)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> {
                inode.releaseOpenReference()
                return result
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

    private fun createSuperBlock(name: String, options: FileSystemOptions): VfsResult<SuperBlock> {
        val fileSystem = registryLock.withLock { fileSystems[name] }
            ?: return VfsResult.Err(VfsError.NOT_FOUND)
        return fileSystem.createSuperBlock(options)
    }

    private fun openOrCreate(
        context: FileSystemContext,
        pathname: VfsPathname,
        options: OpenOptions,
    ): VfsResult<VfsPath> {
        val parent = when (val result = resolveParent(context, pathname)) {
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
        val start = if (pathname.isAbsolute) context.root else context.workingDirectory
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
