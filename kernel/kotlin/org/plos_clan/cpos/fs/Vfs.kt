@file:OptIn(ExperimentalAtomicApi::class)

package org.plos_clan.cpos.fs

import org.plos_clan.cpos.mem.BufferDestination
import org.plos_clan.cpos.mem.BufferSource
import org.plos_clan.cpos.mem.PageCache
import org.plos_clan.cpos.mem.PageCacheFailure
import org.plos_clan.cpos.mem.PageCacheProvider
import org.plos_clan.cpos.mem.PageCacheSource
import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.mem.PreparedBufferSource
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.tasks.Scheduler
import org.plos_clan.cpos.tasks.Thread
import org.plos_clan.cpos.utils.IrqSpinLock
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import org.plos_clan.cpos.utils.PollEvents
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

internal const val ALLOCATION_BLOCK_SIZE = 512uL
internal const val EXTENDED_ATTRIBUTE_VALUE_MAX = 65_536

enum class VfsError(val errno: Int) {
    NOT_PERMITTED(1),
    NO_SUCH_DEVICE_OR_ADDRESS(6),
    INTERRUPTED(4),
    IO(5),
    EXEC_FORMAT(8),
    BAD_DESCRIPTOR(9),
    WOULD_BLOCK(11),
    NO_MEMORY(12),
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
    TOO_MANY_LINKS(31),
    BROKEN_PIPE(32),
    RANGE(34),
    NAME_TOO_LONG(36),
    NOT_EMPTY(39),
    TOO_MANY_SYMLINKS(40),
    NO_DATA(61),
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
        const val MAX_LENGTH = 255
        internal val ROOT = VfsName(ByteArray(0))

        fun fromBytes(bytes: ByteArray): VfsResult<VfsName> {
            if (bytes.isEmpty() || bytes.any { it == 0.toByte() || it == '/'.code.toByte() }) {
                return VfsResult.Err(VfsError.INVALID_ARGUMENT)
            }
            if (bytes.size > MAX_LENGTH) return VfsResult.Err(VfsError.NAME_TOO_LONG)
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

    val requiresDirectory: Boolean
        get() = bytes.lastOrNull() == '/'.code.toByte()

    val isRoot: Boolean
        get() = bytes.isNotEmpty() && bytes.all { it == '/'.code.toByte() }

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
            if (cursor - start > VfsName.MAX_LENGTH) {
                return VfsResult.Err(VfsError.NAME_TOO_LONG)
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

class ExtendedAttributeName private constructor(private val bytes: ByteArray) {
    private val hash = bytes.contentHashCode()

    val size: Int
        get() = bytes.size

    internal fun copyInto(destination: ByteArray, offset: Int) {
        bytes.copyInto(destination, offset)
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is ExtendedAttributeName && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = hash

    override fun toString(): String = bytes.decodeToString()

    companion object {
        const val MAX_LENGTH = 255

        fun fromBytes(bytes: ByteArray): VfsResult<ExtendedAttributeName> = when {
            bytes.isEmpty() -> VfsResult.Err(VfsError.INVALID_ARGUMENT)
            bytes.size > MAX_LENGTH -> VfsResult.Err(VfsError.RANGE)
            bytes.any { it == 0.toByte() } -> VfsResult.Err(VfsError.INVALID_ARGUMENT)
            else -> VfsResult.Ok(ExtendedAttributeName(bytes.copyOf()))
        }
    }
}

enum class ExtendedAttributeMode {
    CREATE_OR_REPLACE,
    CREATE,
    REPLACE,
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
    val nonBlocking: Boolean = false,
    val createUid: UInt = 0u,
    val createGid: UInt = 0u,
)

enum class MountFlag(bit: Int, internal val optionName: String? = null) {
    READ_ONLY(0),
    NO_SUID(1, "nosuid"),
    NO_DEVICE(2, "nodev"),
    NO_EXEC(3, "noexec"),
    SYNCHRONOUS(4, "sync"),
    DIRECTORY_SYNC(7, "dirsync"),
    NO_SYMLINK_FOLLOW(8, "nosymfollow"),
    NO_ATIME(10, "noatime"),
    NO_DIRECTORY_ATIME(11, "nodiratime"),
    RELATIVE_ATIME(21, "relatime"),
    STRICT_ATIME(24, "strictatime"),
    LAZY_TIME(25, "lazytime");

    internal val mask = 1u shl bit
}

value class MountFlags private constructor(private val bits: UInt) {
    operator fun contains(flag: MountFlag): Boolean = bits and flag.mask != 0u
    operator fun plus(flag: MountFlag): MountFlags = MountFlags(bits or flag.mask)

    companion object {
        val NONE = MountFlags(0u)
        private val supported = MountFlag.entries.fold(NONE, MountFlags::plus)

        fun of(vararg flags: MountFlag): MountFlags = flags.fold(NONE, MountFlags::plus)

        internal fun fromBits(bits: ULong): MountFlags? = bits
            .takeIf { it <= UInt.MAX_VALUE.toULong() }
            ?.toUInt()
            ?.takeIf { it and supported.bits.inv() == 0u }
            ?.let(::MountFlags)
    }
}

interface FileSystemOptions

data object EmptyFileSystemOptions : FileSystemOptions

data class RootMountOptions(
    val source: String? = null,
    val flags: MountFlags = MountFlags.NONE,
    val fileSystemOptions: FileSystemOptions = EmptyFileSystemOptions,
)

data class MountRequest(
    val fileSystemName: String,
    val source: String? = null,
    val flags: MountFlags = MountFlags.NONE,
    val data: ByteArray? = null,
)

enum class UnmountMode {
    REGULAR,
    FORCE,
    DETACH;

    internal fun unmount(namespace: MountNamespace, mount: Mount): VfsResult<Unit> = when (this) {
        REGULAR,
        FORCE,
        -> when (val result = mount.superBlock.backend.sync()) {
            is VfsResult.Ok -> namespace.unmount(mount)
            is VfsResult.Err -> result
        }
        DETACH -> namespace.detach(mount)
    }
}

abstract class FileSystemType(
    val name: String,
    val magic: ULong,
    val requiresDevice: Boolean = false,
) {
    open fun parseOptions(source: String?, data: ByteArray?): VfsResult<FileSystemOptions> =
        if (data == null || data.isEmpty()) VfsResult.Ok(EmptyFileSystemOptions)
        else VfsResult.Err(VfsError.INVALID_ARGUMENT)

    internal fun createSuperBlock(
        source: String?,
        options: FileSystemOptions,
    ): VfsResult<SuperBlock> {
        if (requiresDevice && source == null) {
            return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        }
        return when (val result = createBackend(options)) {
            is VfsResult.Ok -> VfsResult.Ok(SuperBlock(this, result.value))
            is VfsResult.Err -> result
        }
    }

    protected abstract fun createBackend(options: FileSystemOptions): VfsResult<SuperBlockBackend>
}

interface SuperBlockBackend {
    fun createRoot(superBlock: SuperBlock): Inode

    fun sync(): VfsResult<Unit> = VfsResult.Ok(Unit)

    fun release() {}
}

class SuperBlock internal constructor(
    val type: FileSystemType,
    val backend: SuperBlockBackend,
) {
    val root: Dentry = Dentry(
        superBlock = this,
        name = VfsName.ROOT,
        parent = null,
        inode = backend.createRoot(this).also { require(it.superBlock === this) },
    )
}

interface InodeBackend {
    val type: InodeType

    fun open(inode: Inode, options: OpenOptions): VfsResult<OpenFileBackend>

    fun setMode(inode: Inode, mode: FileMode): VfsResult<Unit> =
        VfsResult.Err(VfsError.NOT_SUPPORTED)

    fun sync(inode: Inode, dataOnly: Boolean): VfsResult<Unit> =
        inode.superBlock.backend.sync()

    fun allocatedBlocks(inode: Inode): ULong {
        val size = inode.metadata().size
        return size / ALLOCATION_BLOCK_SIZE +
            if (size % ALLOCATION_BLOCK_SIZE == 0uL) 0uL else 1uL
    }

    fun getExtendedAttribute(
        inode: Inode,
        name: ExtendedAttributeName,
    ): VfsResult<ByteArray> = VfsResult.Err(VfsError.NOT_SUPPORTED)

    fun listExtendedAttributes(inode: Inode): VfsResult<ByteArray> =
        VfsResult.Err(VfsError.NOT_SUPPORTED)

    fun setExtendedAttribute(
        inode: Inode,
        name: ExtendedAttributeName,
        value: ByteArray,
        mode: ExtendedAttributeMode,
    ): VfsResult<Unit> = VfsResult.Err(VfsError.NOT_SUPPORTED)

    fun removeExtendedAttribute(
        inode: Inode,
        name: ExtendedAttributeName,
    ): VfsResult<Unit> = VfsResult.Err(VfsError.NOT_SUPPORTED)

    fun evict(inode: Inode) {}
}

interface MutableInodeBackend : InodeBackend {
    override fun setMode(inode: Inode, mode: FileMode): VfsResult<Unit> {
        inode.updateMetadata { it.copy(mode = mode) }
        return VfsResult.Ok(Unit)
    }

    override fun getExtendedAttribute(
        inode: Inode,
        name: ExtendedAttributeName,
    ): VfsResult<ByteArray> = inode.getExtendedAttribute(name)

    override fun listExtendedAttributes(inode: Inode): VfsResult<ByteArray> =
        inode.listExtendedAttributes()

    override fun setExtendedAttribute(
        inode: Inode,
        name: ExtendedAttributeName,
        value: ByteArray,
        mode: ExtendedAttributeMode,
    ): VfsResult<Unit> = inode.setExtendedAttribute(name, value, mode)

    override fun removeExtendedAttribute(
        inode: Inode,
        name: ExtendedAttributeName,
    ): VfsResult<Unit> = inode.removeExtendedAttribute(name)
}

enum class FileAllocationMode(val keepsSize: Boolean) {
    EXTEND(false),
    KEEP_SIZE(true),
}

abstract class RegularFileBackend : InodeBackend {
    final override val type: InodeType = InodeType.REGULAR

    open fun resize(inode: Inode, size: ULong): VfsResult<Unit> =
        VfsResult.Err(VfsError.NOT_SUPPORTED)

    open fun allocate(
        inode: Inode,
        offset: ULong,
        length: ULong,
        mode: FileAllocationMode,
    ): VfsResult<Unit> = VfsResult.Err(VfsError.NOT_SUPPORTED)
}

interface ContentBackedFile {
    fun attachContent(inode: Inode, content: FileContent, offset: Int, size: Int): Boolean
}

interface SymlinkBackend : InodeBackend {
    fun readLink(inode: Inode): VfsResult<VfsPathname>
}

sealed class NodeKind {
    data object Regular : NodeKind()
    data object Directory : NodeKind()
    data object Fifo : NodeKind()
    data object Socket : NodeKind()
    data class SymbolicLink(val target: VfsPathname) : NodeKind()
    data class Device(val type: InodeType, val number: ULong) : NodeKind() {
        init {
            require(type == InodeType.CHARACTER_DEVICE || type == InodeType.BLOCK_DEVICE)
        }
    }
}

data class NodeCreation(
    val kind: NodeKind,
    val mode: FileMode,
    val uid: UInt = 0u,
    val gid: UInt = 0u,
)

enum class RemoveMode {
    FILE,
    DIRECTORY,
}

enum class RenameMode {
    REPLACE,
    NO_REPLACE,
    EXCHANGE,
}

interface DirectoryBackend : InodeBackend {
    val cachePositiveLookups: Boolean
        get() = true

    val cacheNegativeLookups: Boolean
        get() = true

    fun lookup(directory: Inode, name: VfsName): VfsResult<Inode?>

    fun create(directory: Inode, name: VfsName, node: NodeCreation): VfsResult<Inode> =
        VfsResult.Err(VfsError.NOT_SUPPORTED)

    fun link(directory: Inode, name: VfsName, target: Inode): VfsResult<Unit> =
        VfsResult.Err(VfsError.NOT_SUPPORTED)

    fun rename(
        sourceDirectory: Inode,
        sourceName: VfsName,
        source: Inode,
        targetDirectory: Inode,
        targetName: VfsName,
        target: Inode?,
        mode: RenameMode,
    ): VfsResult<Unit> = VfsResult.Err(VfsError.NOT_SUPPORTED)

    fun remove(
        directory: Inode,
        name: VfsName,
        target: Inode,
        mode: RemoveMode,
    ): VfsResult<Unit> =
        VfsResult.Err(VfsError.NOT_SUPPORTED)
}

class FilePosition(var value: Long = 0)

data class DirectoryEntry(
    val name: VfsName,
    val inodeId: InodeId,
    val type: InodeType,
)

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

internal interface CachedFileBackend : OpenFileBackend, PageCacheSource {
    override fun read(
        inode: Inode,
        destination: PreparedBufferDestination,
        destinationOffset: Int,
        count: Int,
        position: FilePosition,
    ): IoResult {
        if (count == 0 || position.value < 0) return IoResult.success(0)
        val sourceOffset = position.value.toULong()
        val fileSize = inode.metadata().size
        if (sourceOffset >= fileSize) return IoResult.success(0)

        val available = minOf(count.toULong(), fileSize - sourceOffset).toInt()
        val result = PageCache.read(this, sourceOffset, destination, destinationOffset, available)
        if (!result.isSuccess) {
            val error = when (result.failure) {
                PageCacheFailure.OUT_OF_MEMORY -> VfsError.NO_MEMORY
                PageCacheFailure.IO_ERROR -> VfsError.IO
            }
            return IoResult.failure(error)
        }
        position.value += result.bytes
        return IoResult.success(result.bytes)
    }
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
    private var extendedAttributes: MutableMap<ExtendedAttributeName, ByteArray>? = null
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

    internal fun getExtendedAttribute(name: ExtendedAttributeName): VfsResult<ByteArray> =
        lock.withLock {
            val value = extendedAttributes?.get(name)
                ?: return@withLock VfsResult.Err(VfsError.NO_DATA)
            VfsResult.Ok(value.copyOf())
        }

    internal fun listExtendedAttributes(): VfsResult<ByteArray> = lock.withLock {
        val attributes = extendedAttributes ?: return@withLock VfsResult.Ok(ByteArray(0))
        val size = attributes.keys.sumOf { it.size + 1 }
        val result = ByteArray(size)
        var offset = 0
        for (name in attributes.keys) {
            name.copyInto(result, offset)
            offset += name.size + 1
        }
        VfsResult.Ok(result)
    }

    internal fun setExtendedAttribute(
        name: ExtendedAttributeName,
        value: ByteArray,
        mode: ExtendedAttributeMode,
    ): VfsResult<Unit> = lock.withLock {
        if (value.size > EXTENDED_ATTRIBUTE_VALUE_MAX) {
            return@withLock VfsResult.Err(VfsError.RANGE)
        }
        val attributes = extendedAttributes
        val exists = attributes?.containsKey(name) == true
        if (mode == ExtendedAttributeMode.CREATE && exists) {
            return@withLock VfsResult.Err(VfsError.ALREADY_EXISTS)
        }
        if (mode == ExtendedAttributeMode.REPLACE && !exists) {
            return@withLock VfsResult.Err(VfsError.NO_DATA)
        }
        if (!exists) {
            val listSize = attributes?.keys?.sumOf { it.size + 1 } ?: 0
            if (name.size + 1 > EXTENDED_ATTRIBUTE_VALUE_MAX - listSize) {
                return@withLock VfsResult.Err(VfsError.NO_SPACE)
            }
        }
        val destination = attributes ?: linkedMapOf<ExtendedAttributeName, ByteArray>().also {
            extendedAttributes = it
        }
        destination[name] = value.copyOf()
        VfsResult.Ok(Unit)
    }

    internal fun removeExtendedAttribute(name: ExtendedAttributeName): VfsResult<Unit> =
        lock.withLock {
            val attributes = extendedAttributes
            if (attributes?.remove(name) == null) {
                return@withLock VfsResult.Err(VfsError.NO_DATA)
            }
            if (attributes.isEmpty()) extendedAttributes = null
            VfsResult.Ok(Unit)
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
    name: VfsName,
    parent: Dentry?,
    inode: Inode?,
) {
    private val lock = IrqSpinLock()
    private var currentName = name
    private var currentParent = parent
    private var currentInode = inode
    private val children = mutableMapOf<VfsName, Dentry>()

    val name: VfsName
        get() = lock.withLock { currentName }

    val parent: Dentry?
        get() = lock.withLock { currentParent }

    fun inode(): Inode? = lock.withLock { currentInode }

    internal fun cachedChild(name: VfsName): Dentry? = lock.withLock { children[name] }

    internal fun cacheChild(name: VfsName, inode: Inode?): Dentry = lock.withLock {
        children[name]?.let { cached ->
            val current = cached.inode()
            if (inode == null || current == null || current === inode) {
                if (current !== inode) cached.install(inode)
                return@withLock cached
            }
            cached.detach()
        }
        Dentry(superBlock, name, this, inode).also { children[name] = it }
    }

    internal fun markChildNegative(name: VfsName, expected: Dentry) {
        lock.withLock {
            children[name]?.takeIf { it === expected }?.install(null)
        }
    }

    internal fun invalidateNegativeChild(name: VfsName) {
        lock.withLock {
            val child = children[name] ?: return@withLock
            if (child.inode() == null) children.remove(name)
        }
    }

    internal fun renameChild(
        source: Dentry,
        targetParent: Dentry,
        targetName: VfsName,
        exchange: Dentry?,
    ) {
        renameLock.withLock {
            if (this === targetParent) {
                lock.withLock { renameChildLocked(source, targetParent, targetName, exchange) }
            } else {
                lock.withLock {
                    targetParent.lock.withLock {
                        renameChildLocked(source, targetParent, targetName, exchange)
                    }
                }
            }
        }
    }

    private fun renameChildLocked(
        source: Dentry,
        targetParent: Dentry,
        targetName: VfsName,
        exchange: Dentry?,
    ) {
        if (children[source.currentName] === source) {
            children.remove(source.currentName)
        }
        if (exchange == null) {
            targetParent.children.put(targetName, source)?.detach()
        } else {
            targetParent.children[targetName] = source
            children[source.currentName] = exchange
            exchange.relocate(this, source.currentName)
        }
        source.relocate(targetParent, targetName)
    }

    private fun relocate(parent: Dentry, name: VfsName) = lock.withLock {
        currentParent = parent
        currentName = name
    }

    private fun detach() = lock.withLock {
        currentParent = null
    }

    private fun install(inode: Inode?) {
        lock.withLock { currentInode = inode }
    }

    private companion object {
        val renameLock = IrqSpinLock()
    }
}

class Mount internal constructor(
    val superBlock: SuperBlock,
    val source: String,
    val root: Dentry = superBlock.root,
    val flags: MountFlags = MountFlags.NONE,
    attachment: VfsPath? = null,
) {
    private val references = AtomicInt(1)
    private val attachmentReference = AtomicReference(attachment)

    init {
        require(root.superBlock === superBlock)
        check(attachment?.mount?.retain() != false)
    }

    internal val attachment: VfsPath?
        get() = attachmentReference.load()

    internal fun retain(): Boolean {
        var observed = references.load()
        while (observed in 1 until Int.MAX_VALUE) {
            if (references.compareAndSet(observed, observed + 1)) return true
            observed = references.load()
        }
        return false
    }

    internal fun release() {
        var observed = references.load()
        while (observed > 0) {
            if (!references.compareAndSet(observed, observed - 1)) {
                observed = references.load()
                continue
            }
            if (observed == 1) releaseResources()
            return
        }
    }

    internal fun tryBeginUnmount(): Boolean = references.compareAndSet(2, 0)

    internal fun completeUnmount() = releaseResources()

    internal fun detachFromParent() {
        attachmentReference.exchange(null)?.mount?.release()
    }

    internal fun isDescendantOf(ancestor: Mount): Boolean {
        var current: Mount? = this
        while (current != null && current !== ancestor) current = current.attachment?.mount
        return current === ancestor
    }

    private fun releaseResources() {
        superBlock.backend.release()
        detachFromParent()
    }
}

data class VfsPath(val mount: Mount, val dentry: Dentry) {
    val inode: Inode?
        get() = dentry.inode()
}

class MountNamespace internal constructor(val root: Mount) {
    private val lock = IrqSpinLock()
    private val mounts = mutableMapOf<VfsPath, Mount>()
    private val references = AtomicInt(0)

    internal fun retain(): Boolean {
        var observed = references.load()
        while (observed in 0 until Int.MAX_VALUE) {
            if (references.compareAndSet(observed, observed + 1)) return true
            observed = references.load()
        }
        return false
    }

    internal fun release() {
        var observed = references.load()
        while (observed > 0) {
            val updated = if (observed == 1) CLOSED else observed - 1
            if (!references.compareAndSet(observed, updated)) {
                observed = references.load()
                continue
            }
            if (updated == CLOSED) releaseResources()
            return
        }
    }

    internal fun mountedAt(path: VfsPath): Mount? =
        lock.withLock { mounts[path] }

    internal fun attach(
        target: VfsPath,
        superBlock: SuperBlock,
        source: String,
        flags: MountFlags,
    ): VfsResult<Unit> = lock.withLock {
        if (!contains(target.mount)) return@withLock VfsResult.Err(VfsError.NOT_FOUND)
        if (mounts.containsKey(target)) return@withLock VfsResult.Err(VfsError.BUSY)

        mounts[target] = Mount(
            superBlock = superBlock,
            source = source,
            flags = flags,
            attachment = target,
        )
        VfsResult.Ok(Unit)
    }

    internal fun unmount(mount: Mount): VfsResult<Unit> {
        val detached = lock.withLock {
            val target = attachedAt(mount) ?: return@withLock false
            if (!mount.tryBeginUnmount()) return@withLock false
            mounts.remove(target)
            true
        }
        if (!detached) return VfsResult.Err(VfsError.BUSY)
        mount.completeUnmount()
        return VfsResult.Ok(Unit)
    }

    internal fun detach(mount: Mount): VfsResult<Unit> {
        val subtree = lock.withLock {
            attachedAt(mount) ?: return@withLock null
            buildList<Mount> {
                val iterator = mounts.entries.iterator()
                while (iterator.hasNext()) {
                    val candidate = iterator.next().value
                    if (!candidate.isDescendantOf(mount)) continue
                    add(candidate)
                    iterator.remove()
                }
            }
        } ?: return VfsResult.Err(VfsError.BUSY)
        mount.detachFromParent()
        mount.release()
        subtree.forEach(Mount::release)
        return VfsResult.Ok(Unit)
    }

    internal fun snapshotMounts(): Map<VfsPath, Mount> = lock.withLock {
        LinkedHashMap<VfsPath, Mount>(mounts.size + 1).apply {
            put(VfsPath(root, root.root), root)
            putAll(mounts)
        }
    }

    private fun contains(mount: Mount): Boolean = mount === root || attachedAt(mount) != null

    private fun attachedAt(mount: Mount): VfsPath? =
        mount.attachment?.takeIf { mounts[it] === mount }

    private fun releaseResources() {
        val detached = lock.withLock {
            mounts.values.toList().also { mounts.clear() }
        }
        detached.forEach(Mount::release)
        root.release()
    }

    private companion object {
        const val CLOSED = -1
    }
}

class FileSystemContext internal constructor(
    val namespace: MountNamespace,
    val root: VfsPath = VfsPath(namespace.root, namespace.root.root),
    workingDirectory: VfsPath = root,
) {
    private val lock = IrqSpinLock()
    private var currentWorkingDirectory: VfsPath? = workingDirectory

    init {
        check(namespace.retain())
        check(root.mount.retain())
        check(workingDirectory.mount.retain())
    }

    val workingDirectory: VfsPath
        get() = lock.withLock { checkNotNull(currentWorkingDirectory) }

    internal fun changeWorkingDirectory(path: VfsPath): Boolean {
        if (!path.mount.retain()) return false
        val previous = lock.withLock {
            currentWorkingDirectory?.also { currentWorkingDirectory = path }
        }
        if (previous == null) {
            path.mount.release()
            return false
        }
        previous.mount.release()
        return true
    }

    internal fun fork(): FileSystemContext = lock.withLock {
        FileSystemContext(namespace, root, checkNotNull(currentWorkingDirectory))
    }

    internal fun release() {
        val workingDirectory = lock.withLock {
            currentWorkingDirectory?.also { currentWorkingDirectory = null } ?: return
        }
        workingDirectory.mount.release()
        root.mount.release()
        namespace.release()
    }
}

enum class SeekOrigin {
    START,
    CURRENT,
    END,
}

class OpenFileDescription private constructor(
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

    companion object {
        internal fun open(
            path: VfsPath,
            inode: Inode,
            options: OpenOptions,
        ): VfsResult<OpenFileDescription> {
            if (!path.mount.retain()) return VfsResult.Err(VfsError.NOT_FOUND)
            if (!inode.acquireOpenReference()) {
                path.mount.release()
                return VfsResult.Err(VfsError.NOT_FOUND)
            }

            val backend = if (options.access == AccessMode.PATH) {
                PathOnlyHandle
            } else {
                when (val result = inode.backend.open(inode, options)) {
                    is VfsResult.Ok -> result.value
                    is VfsResult.Err -> {
                        inode.releaseOpenReference()
                        path.mount.release()
                        return result
                    }
                }
            }
            if (options.truncate && inode.type == InodeType.REGULAR) {
                val result = (inode.backend as? RegularFileBackend)?.resize(inode, 0uL)
                    ?: VfsResult.Err(VfsError.INVALID_ARGUMENT)
                if (result is VfsResult.Err) {
                    backend.release()
                    inode.releaseOpenReference()
                    path.mount.release()
                    return result
                }
            }
            return VfsResult.Ok(
                OpenFileDescription(path, inode, options.access, options.append, backend),
            )
        }
    }

    val offset: Long
        get() = positionLock.withLock { position.value }

    internal val cacheSource: PageCacheSource?
        get() = (backend as? PageCacheProvider)?.cacheSource

    fun getStatusFlags(): Int = statusFlags.load()

    fun setStatusFlags(flags: Int) {
        statusFlags.store(flags and (OpenFlags.O_APPEND or OpenFlags.O_NONBLOCK))
    }

    fun sync(dataOnly: Boolean): VfsResult<Unit> = when {
        references.load() == 0 || access == AccessMode.PATH ->
            VfsResult.Err(VfsError.BAD_DESCRIPTOR)
        inode.type == InodeType.PIPE || inode.type == InodeType.SOCKET ->
            VfsResult.Err(VfsError.INVALID_ARGUMENT)
        else -> inode.backend.sync(inode, dataOnly)
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
            path.mount.release()
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
        if (positionlessBackend != null) return IoResult.failure(VfsError.ILLEGAL_SEEK)
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
        return writeBackend(prepared, offset, count, position)
    }

    internal fun write(source: PreparedBufferSource, offset: Int, count: Int): IoResult {
        writeError(offset, count)?.let { return IoResult.failure(it) }
        return writeBackend(source, offset, count, position)
    }

    fun writeAt(
        fileOffset: ULong,
        source: BufferSource,
        offset: Int,
        count: Int,
    ): IoResult {
        if (fileOffset > Long.MAX_VALUE.toULong()) {
            return IoResult.failure(VfsError.INVALID_ARGUMENT)
        }
        writeError(offset, count)?.let { return IoResult.failure(it) }
        if (positionlessBackend != null) return IoResult.failure(VfsError.ILLEGAL_SEEK)
        val prepared = source.prepareRead(offset, count)
            ?: return IoResult.failure(VfsError.FAULT)
        return writeBackend(prepared, offset, count, FilePosition(fileOffset.toLong()))
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

    private fun writeBackend(
        source: PreparedBufferSource,
        offset: Int,
        count: Int,
        filePosition: FilePosition,
    ): IoResult {
        val positionless = positionlessBackend
        if (positionless == null) {
            if (filePosition !== position) {
                return backend.write(inode, source, offset, count, filePosition, false)
            }
            return positionLock.withLock {
                val append = statusFlags.load() and OpenFlags.O_APPEND != 0
                backend.write(inode, source, offset, count, filePosition, append)
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
        inode.type == InodeType.REGULAR && MountFlag.READ_ONLY in path.mount.flags ->
            VfsError.READ_ONLY
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

    fun snapshotFileSystems(): List<FileSystemType> =
        registryLock.withLock {
            fileSystems.values.toList()
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
        options: RootMountOptions = RootMountOptions(),
    ): VfsResult<FileSystemContext> {
        val fileSystem = registryLock.withLock { fileSystems[fileSystemName] }
            ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val superBlock = when (
            val result = fileSystem.createSuperBlock(options.source, options.fileSystemOptions)
        ) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        val rootMount = Mount(
            superBlock = superBlock,
            source = options.source ?: fileSystem.name,
            flags = options.flags,
        )
        return VfsResult.Ok(FileSystemContext(MountNamespace(rootMount)))
    }

    fun createPipe(context: FileSystemContext): VfsResult<Pair<OpenFileDescription, OpenFileDescription>> {
        val path = context.root
        val superBlock = path.mount.superBlock
        val state = PipeState(readers = 1, writers = 1)
        val readInode = pipeInode(superBlock, state, AccessMode.READ)
        val writeInode = pipeInode(superBlock, state, AccessMode.WRITE)
        val readFile = when (val result = OpenFileDescription.open(
            path,
            readInode,
            OpenOptions(access = AccessMode.READ),
        )) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return VfsResult.Err(VfsError.IO)
        }
        val writeFile = when (val result = OpenFileDescription.open(
            path,
            writeInode,
            OpenOptions(access = AccessMode.WRITE),
        )) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> {
                state.close(AccessMode.WRITE)
                readFile.release()
                return VfsResult.Err(VfsError.IO)
            }
        }
        return VfsResult.Ok(readFile to writeFile)
    }

    private fun pipeInode(
        superBlock: SuperBlock,
        state: PipeState,
        access: AccessMode,
    ): Inode = Inode(
        id = pipeLock.withLock { InodeId(nextPipeInode--) },
        superBlock = superBlock,
        backend = PipeInode(state, access),
        metadata = InodeMetadata(FileMode(0x1A4u)),
    )

    fun mount(
        context: FileSystemContext,
        target: VfsPathname,
        request: MountRequest,
    ): VfsResult<Unit> {
        val fileSystem = registryLock.withLock { fileSystems[request.fileSystemName] }
            ?: return VfsResult.Err(VfsError.NO_DEVICE)
        val fileSystemOptions = when (
            val result = fileSystem.parseOptions(request.source, request.data)
        ) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        val path = when (val result = resolve(context, target)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        if (path.inode?.type != InodeType.DIRECTORY) {
            return VfsResult.Err(VfsError.NOT_DIRECTORY)
        }

        val superBlock = when (
            val result = fileSystem.createSuperBlock(request.source, fileSystemOptions)
        ) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        return when (val attached = context.namespace.attach(
            target = path,
            superBlock = superBlock,
            source = request.source ?: fileSystem.name,
            flags = request.flags,
        )) {
            is VfsResult.Ok -> attached
            is VfsResult.Err -> {
                superBlock.backend.release()
                attached
            }
        }
    }

    fun unmount(
        context: FileSystemContext,
        target: VfsPathname,
        mode: UnmountMode = UnmountMode.REGULAR,
        followFinalSymlink: Boolean = true,
    ): VfsResult<Unit> {
        val path = when (val result = resolve(context, target, followFinalSymlink)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        val mount = path.mount
        if (path.dentry !== mount.root) return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        if (mount === context.namespace.root) return VfsResult.Err(VfsError.BUSY)
        if (!mount.retain()) return VfsResult.Err(VfsError.INVALID_ARGUMENT)

        val result = mode.unmount(context.namespace, mount)
        if (result is VfsResult.Err) mount.release()
        return result
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
        allowEmpty: Boolean = false,
    ): VfsResult<VfsPath> {
        if (pathname.size == 0) {
            return if (allowEmpty) VfsResult.Ok(directory)
            else VfsResult.Err(VfsError.NOT_FOUND)
        }
        val start = when {
            pathname.isAbsolute -> context.root
            directory.inode?.type == InodeType.DIRECTORY -> directory
            else -> return VfsResult.Err(VfsError.NOT_DIRECTORY)
        }
        val components = when (val result = pathname.components()) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        val result = walk(
            context,
            start,
            components,
            followFinalSymlink || pathname.requiresDirectory,
        )
        if (result is VfsResult.Ok && pathname.requiresDirectory &&
            result.value.inode?.type != InodeType.DIRECTORY
        ) {
            return VfsResult.Err(VfsError.NOT_DIRECTORY)
        }
        return result
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
        if (options.access.canWrite && inode.type == InodeType.REGULAR &&
            MountFlag.READ_ONLY in path.mount.flags
        ) {
            return VfsResult.Err(VfsError.READ_ONLY)
        }
        if (MountFlag.NO_DEVICE in path.mount.flags &&
            (inode.type == InodeType.CHARACTER_DEVICE || inode.type == InodeType.BLOCK_DEVICE)
        ) {
            return VfsResult.Err(VfsError.PERMISSION_DENIED)
        }
        return OpenFileDescription.open(path, inode, options)
    }

    fun resize(path: VfsPath, size: ULong): VfsResult<Unit> {
        if (MountFlag.READ_ONLY in path.mount.flags) {
            return VfsResult.Err(VfsError.READ_ONLY)
        }
        val inode = path.inode ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val backend = inode.backend as? RegularFileBackend
            ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        return backend.resize(inode, size)
    }

    fun allocate(
        path: VfsPath,
        offset: ULong,
        length: ULong,
        mode: FileAllocationMode,
    ): VfsResult<Unit> {
        if (length == 0uL || offset > ULong.MAX_VALUE - length) {
            return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        }
        if (MountFlag.READ_ONLY in path.mount.flags) {
            return VfsResult.Err(VfsError.READ_ONLY)
        }
        val inode = path.inode ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val backend = inode.backend as? RegularFileBackend
            ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        return backend.allocate(inode, offset, length, mode)
    }

    fun setMode(path: VfsPath, mode: FileMode): VfsResult<Unit> {
        if (MountFlag.READ_ONLY in path.mount.flags) {
            return VfsResult.Err(VfsError.READ_ONLY)
        }
        val inode = path.inode ?: return VfsResult.Err(VfsError.NOT_FOUND)
        return inode.backend.setMode(inode, mode)
    }

    fun getExtendedAttribute(
        inode: Inode,
        name: ExtendedAttributeName,
    ): VfsResult<ByteArray> = inode.backend.getExtendedAttribute(inode, name)

    fun listExtendedAttributes(inode: Inode): VfsResult<ByteArray> =
        inode.backend.listExtendedAttributes(inode)

    fun setExtendedAttribute(
        mount: Mount,
        inode: Inode,
        name: ExtendedAttributeName,
        value: ByteArray,
        mode: ExtendedAttributeMode,
    ): VfsResult<Unit> {
        if (MountFlag.READ_ONLY in mount.flags) {
            return VfsResult.Err(VfsError.READ_ONLY)
        }
        return inode.backend.setExtendedAttribute(inode, name, value, mode)
    }

    fun removeExtendedAttribute(
        mount: Mount,
        inode: Inode,
        name: ExtendedAttributeName,
    ): VfsResult<Unit> {
        if (MountFlag.READ_ONLY in mount.flags) {
            return VfsResult.Err(VfsError.READ_ONLY)
        }
        return inode.backend.removeExtendedAttribute(inode, name)
    }

    internal fun createFile(
        directory: VfsPath,
        name: VfsName,
        mode: FileMode,
        content: FileContent,
        contentOffset: Int,
        contentSize: Int,
    ): VfsResult<VfsPath> {
        val path = when (val result = createChild(
            directory,
            name,
            NodeCreation(NodeKind.Regular, mode),
        )) {
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
        backend.remove(parent, name, inode, RemoveMode.FILE)
        directory.dentry.markChildNegative(name, path.dentry)
        return VfsResult.Err(VfsError.IO)
    }

    fun createNode(
        context: FileSystemContext,
        directory: VfsPath,
        pathname: VfsPathname,
        node: NodeCreation,
    ): VfsResult<VfsPath> {
        if (pathname.isRoot) return VfsResult.Err(VfsError.ALREADY_EXISTS)
        val parent = when (val result = resolveParent(context, directory, pathname)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        if (pathname.requiresDirectory && node.kind != NodeKind.Directory &&
            !parent.name.isDot && !parent.name.isDotDot
        ) {
            return when (val existing = lookupChild(context, parent.path, parent.name)) {
                is VfsResult.Ok -> VfsResult.Err(VfsError.ALREADY_EXISTS)
                is VfsResult.Err -> existing
            }
        }
        return createChild(parent.path, parent.name, node)
    }

    fun createNode(
        context: FileSystemContext,
        pathname: VfsPathname,
        node: NodeCreation,
    ): VfsResult<VfsPath> = createNode(context, context.workingDirectory, pathname, node)

    fun remove(
        context: FileSystemContext,
        directory: VfsPath,
        pathname: VfsPathname,
        mode: RemoveMode,
    ): VfsResult<Unit> {
        if (pathname.isRoot) return VfsResult.Err(VfsError.BUSY)
        val parent = when (val result = resolveParent(context, directory, pathname)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        if (parent.name.isDot || parent.name.isDotDot) {
            return VfsResult.Err(
                when (mode) {
                    RemoveMode.FILE -> VfsError.IS_DIRECTORY
                    RemoveMode.DIRECTORY -> if (parent.name.isDot) {
                        VfsError.INVALID_ARGUMENT
                    } else {
                        VfsError.NOT_EMPTY
                    }
                },
            )
        }
        if (MountFlag.READ_ONLY in parent.path.mount.flags) {
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
        if (pathname.requiresDirectory && inode.type != InodeType.DIRECTORY) {
            return VfsResult.Err(VfsError.NOT_DIRECTORY)
        }
        val removesDirectory = mode == RemoveMode.DIRECTORY
        if (removesDirectory != (inode.type == InodeType.DIRECTORY)) {
            return VfsResult.Err(
                if (removesDirectory) VfsError.NOT_DIRECTORY else VfsError.IS_DIRECTORY,
            )
        }
        val parentInode = parent.path.inode ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val backend = parentInode.backend as? DirectoryBackend
            ?: return VfsResult.Err(VfsError.NOT_DIRECTORY)
        val result = backend.remove(parentInode, parent.name, inode, mode)
        if (result is VfsResult.Ok) {
            parent.path.dentry.markChildNegative(parent.name, target.dentry)
        }
        return result
    }

    fun link(
        context: FileSystemContext,
        sourceMount: Mount,
        sourceInode: Inode,
        targetDirectory: VfsPath,
        target: VfsPathname,
    ): VfsResult<Unit> {
        if (target.isRoot) return VfsResult.Err(VfsError.ALREADY_EXISTS)
        if (sourceInode.type == InodeType.DIRECTORY) {
            return VfsResult.Err(VfsError.NOT_PERMITTED)
        }
        val parent = when (val result = resolveParent(context, targetDirectory, target)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        if (parent.name.isDot || parent.name.isDotDot) {
            return VfsResult.Err(VfsError.ALREADY_EXISTS)
        }
        if (target.requiresDirectory) {
            return when (val existing = lookupChild(context, parent.path, parent.name)) {
                is VfsResult.Ok -> VfsResult.Err(VfsError.ALREADY_EXISTS)
                is VfsResult.Err -> existing
            }
        }
        if (sourceMount !== parent.path.mount) {
            return VfsResult.Err(VfsError.CROSS_DEVICE)
        }
        if (MountFlag.READ_ONLY in parent.path.mount.flags) {
            return VfsResult.Err(VfsError.READ_ONLY)
        }
        val directory = parent.path.inode ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val backend = directory.backend as? DirectoryBackend
            ?: return VfsResult.Err(VfsError.NOT_DIRECTORY)
        val result = backend.link(directory, parent.name, sourceInode)
        if (result is VfsResult.Ok) {
            parent.path.dentry.cacheChild(parent.name, sourceInode)
        }
        return result
    }

    fun rename(
        context: FileSystemContext,
        sourceDirectory: VfsPath,
        source: VfsPathname,
        targetDirectory: VfsPath,
        target: VfsPathname,
        mode: RenameMode,
    ): VfsResult<Unit> {
        if (source.isRoot || target.isRoot) return VfsResult.Err(VfsError.BUSY)
        val sourceParent = when (val result = resolveParent(context, sourceDirectory, source)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        val targetParent = when (val result = resolveParent(context, targetDirectory, target)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        if (sourceParent.name.isDot || sourceParent.name.isDotDot ||
            targetParent.name.isDot || targetParent.name.isDotDot
        ) {
            return VfsResult.Err(VfsError.BUSY)
        }
        if (sourceParent.path.mount !== targetParent.path.mount) {
            return VfsResult.Err(VfsError.CROSS_DEVICE)
        }
        if (MountFlag.READ_ONLY in sourceParent.path.mount.flags) {
            return VfsResult.Err(VfsError.READ_ONLY)
        }
        val sourcePath = when (
            val result = lookupChild(context, sourceParent.path, sourceParent.name, followMount = false)
        ) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        if (context.namespace.mountedAt(sourcePath) != null) {
            return VfsResult.Err(VfsError.BUSY)
        }
        val sourceInode = sourcePath.inode ?: return VfsResult.Err(VfsError.NOT_FOUND)
        if (source.requiresDirectory && sourceInode.type != InodeType.DIRECTORY) {
            return VfsResult.Err(VfsError.NOT_DIRECTORY)
        }
        if (sourceInode.type == InodeType.DIRECTORY &&
            isDescendant(targetParent.path.dentry, sourcePath.dentry)
        ) {
            return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        }
        val targetPath = when (
            val result = lookupChild(context, targetParent.path, targetParent.name, followMount = false)
        ) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> if (result.error == VfsError.NOT_FOUND) null else return result
        }
        if (target.requiresDirectory && targetPath?.inode?.type != InodeType.DIRECTORY) {
            return VfsResult.Err(
                if (targetPath == null) VfsError.NOT_FOUND else VfsError.NOT_DIRECTORY,
            )
        }
        if (targetPath != null && context.namespace.mountedAt(targetPath) != null) {
            return VfsResult.Err(VfsError.BUSY)
        }
        if (mode == RenameMode.EXCHANGE && targetPath == null) {
            return VfsResult.Err(VfsError.NOT_FOUND)
        }
        if (mode == RenameMode.NO_REPLACE && targetPath != null) {
            return VfsResult.Err(VfsError.ALREADY_EXISTS)
        }
        if (mode == RenameMode.EXCHANGE && targetPath?.inode?.type == InodeType.DIRECTORY &&
            isDescendant(sourceParent.path.dentry, targetPath.dentry)
        ) {
            return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        }
        if (targetPath?.inode === sourceInode) return VfsResult.Ok(Unit)
        val sourceParentInode = sourceParent.path.inode
            ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val targetParentInode = targetParent.path.inode
            ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val backend = sourceParentInode.backend as? DirectoryBackend
            ?: return VfsResult.Err(VfsError.NOT_DIRECTORY)
        val result = backend.rename(
            sourceParentInode,
            sourceParent.name,
            sourceInode,
            targetParentInode,
            targetParent.name,
            targetPath?.inode,
            mode,
        )
        if (result is VfsResult.Ok) {
            sourceParent.path.dentry.renameChild(
                sourcePath.dentry,
                targetParent.path.dentry,
                targetParent.name,
                targetPath?.dentry.takeIf { mode == RenameMode.EXCHANGE },
            )
        }
        return result
    }

    fun chdir(context: FileSystemContext, pathname: VfsPathname): VfsResult<Unit> {
        val path = when (val result = resolve(context, pathname)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        return chdir(context, path)
    }

    internal fun chdir(context: FileSystemContext, path: VfsPath): VfsResult<Unit> {
        val inode = path.inode ?: return VfsResult.Err(VfsError.NOT_FOUND)
        if (inode.type != InodeType.DIRECTORY) {
            return VfsResult.Err(VfsError.NOT_DIRECTORY)
        }
        return if (context.changeWorkingDirectory(path)) VfsResult.Ok(Unit)
        else VfsResult.Err(VfsError.NOT_FOUND)
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
                current = current.mount.attachment
                    ?: return VfsResult.Err(VfsError.NOT_FOUND)
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

    private fun openOrCreate(
        context: FileSystemContext,
        directory: VfsPath,
        pathname: VfsPathname,
        options: OpenOptions,
    ): VfsResult<VfsPath> {
        if (pathname.isRoot) {
            return if (options.create == CreateDisposition.CREATE_NEW) {
                VfsResult.Err(VfsError.ALREADY_EXISTS)
            } else {
                resolveAt(context, directory, pathname, options.followFinalSymlink)
            }
        }
        val parent = when (val result = resolveParent(context, directory, pathname)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        if (parent.name.isDot || parent.name.isDotDot) {
            return if (options.create == CreateDisposition.CREATE_NEW) {
                VfsResult.Err(VfsError.ALREADY_EXISTS)
            } else {
                resolveAt(context, directory, pathname, options.followFinalSymlink)
            }
        }

        when (val existing = lookupChild(context, parent.path, parent.name)) {
            is VfsResult.Ok -> {
                if (options.create == CreateDisposition.CREATE_NEW) {
                    return VfsResult.Err(VfsError.ALREADY_EXISTS)
                }
                if (options.followFinalSymlink && existing.value.inode?.type == InodeType.SYMLINK) {
                    return resolveAt(context, directory, pathname, followFinalSymlink = true)
                }
                return existing
            }
            is VfsResult.Err -> if (existing.error != VfsError.NOT_FOUND) return existing
        }

        if (pathname.requiresDirectory) return VfsResult.Err(VfsError.NOT_FOUND)

        if (MountFlag.READ_ONLY in parent.path.mount.flags) {
            return VfsResult.Err(VfsError.READ_ONLY)
        }
        val directory = parent.path.inode ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val backend = directory.backend as? DirectoryBackend
            ?: return VfsResult.Err(VfsError.NOT_DIRECTORY)
        val node = NodeCreation(
            kind = NodeKind.Regular,
            mode = options.createMode,
            uid = options.createUid,
            gid = options.createGid,
        )
        val inode = when (val created = backend.create(directory, parent.name, node)) {
            is VfsResult.Ok -> created.value
            is VfsResult.Err -> {
                if (created.error == VfsError.ALREADY_EXISTS &&
                    options.create == CreateDisposition.OPEN_OR_CREATE
                ) {
                    parent.path.dentry.invalidateNegativeChild(parent.name)
                    return lookupChild(context, parent.path, parent.name)
                }
                return created
            }
        }
        val dentry = parent.path.dentry.cacheChild(parent.name, inode)
        return VfsResult.Ok(VfsPath(parent.path.mount, dentry))
    }

    private fun createChild(
        directory: VfsPath,
        name: VfsName,
        node: NodeCreation,
    ): VfsResult<VfsPath> {
        if (name.isDot || name.isDotDot) {
            return VfsResult.Err(VfsError.ALREADY_EXISTS)
        }
        if (MountFlag.READ_ONLY in directory.mount.flags) {
            return VfsResult.Err(VfsError.READ_ONLY)
        }
        val parent = directory.inode ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val backend = parent.backend as? DirectoryBackend
            ?: return VfsResult.Err(VfsError.NOT_DIRECTORY)
        val inode = when (val result = backend.create(parent, name, node)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        return VfsResult.Ok(VfsPath(directory.mount, directory.dentry.cacheChild(name, inode)))
    }

    private data class ParentPath(val path: VfsPath, val name: VfsName)

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
            if (MountFlag.NO_SYMLINK_FOLLOW in next.mount.flags) {
                return VfsResult.Err(VfsError.TOO_MANY_SYMLINKS)
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
        val directory = parent.inode ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val backend = directory.backend as? DirectoryBackend
            ?: return VfsResult.Err(VfsError.NOT_DIRECTORY)
        parent.dentry.cachedChild(name)?.let { cached ->
            val inode = cached.inode()
            if (inode == null && backend.cacheNegativeLookups) {
                return VfsResult.Err(VfsError.NOT_FOUND)
            }
            if (inode != null && backend.cachePositiveLookups) {
                val path = VfsPath(parent.mount, cached)
                return VfsResult.Ok(
                    if (followMount) followMounts(context.namespace, path) else path,
                )
            }
        }

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

    private fun isDescendant(candidate: Dentry, ancestor: Dentry): Boolean {
        var current: Dentry? = candidate
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent
        }
        return false
    }

    private fun walkUp(context: FileSystemContext, initial: VfsPath): VfsPath {
        var current = initial
        if (current == context.root) {
            return current
        }

        while (current.dentry === current.mount.root) {
            current = current.mount.attachment ?: return current
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
    private val access: AccessMode,
) : InodeBackend {
    override val type: InodeType = InodeType.PIPE

    override fun open(inode: Inode, options: OpenOptions): VfsResult<OpenFileBackend> =
        VfsResult.Ok(PipeEndpoint(state, access))
}

internal class FifoBackend : MutableInodeBackend {
    override val type: InodeType = InodeType.PIPE
    private val state = PipeState(readers = 0, writers = 0)

    override fun open(inode: Inode, options: OpenOptions): VfsResult<OpenFileBackend> =
        state.open(options.access, options.nonBlocking)
}

internal data object SocketNodeBackend : MutableInodeBackend {
    override val type: InodeType = InodeType.SOCKET

    override fun open(inode: Inode, options: OpenOptions): VfsResult<OpenFileBackend> =
        VfsResult.Err(VfsError.NO_SUCH_DEVICE_OR_ADDRESS)
}

private class PipeEndpoint(
    private val state: PipeState,
    private val access: AccessMode,
) : WaitableOpenFileBackend {
    override fun read(
        inode: Inode,
        destination: PreparedBufferDestination,
        destinationOffset: Int,
        count: Int,
    ): IoResult = if (!access.canRead) {
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
    ): IoResult = if (!access.canWrite) {
        IoResult.failure(VfsError.BAD_DESCRIPTOR)
    } else {
        state.write(source, sourceOffset, count, mode)
    }

    override fun await(event: IoEvent, count: Int) {
        check(if (event == IoEvent.WRITABLE) access.canWrite else access.canRead)
        state.await(event, count)
    }

    override fun poll(inode: Inode, events: Int): Long = state.poll(events, access)

    override fun release() = state.close(access)
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

private class PipeState(
    private var readers: Int,
    private var writers: Int,
) {
    private companion object {
        const val CAPACITY_PAGES = 16
        val CAPACITY_BYTES = CAPACITY_PAGES * PAGE_SIZE_BYTES.toInt()
        val ATOMIC_WRITE_BYTES = PAGE_SIZE_BYTES.toInt()
    }

    private val lock = IrqSpinLock()
    private val buffer = ByteArray(CAPACITY_BYTES)
    private val readWaiters = PipeWaitQueue()
    private val writeWaiters = PipeWaitQueue()
    private val readerOpenWaiters = PipeWaitQueue()
    private val writerOpenWaiters = PipeWaitQueue()
    private var head = 0
    private var tail = 0
    private var size = 0

    fun open(access: AccessMode, nonBlocking: Boolean): VfsResult<OpenFileBackend> {
        val thread = ProcessManager.currentThread()
        var waiter: PipeWaitQueue.Waiter? = null
        val error = lock.withLock {
            when (access) {
                AccessMode.READ -> {
                    readers++
                    writerOpenWaiters.notifyAllWaiters()
                    if (!nonBlocking && writers == 0) {
                        waiter = readerOpenWaiters.acquire(1, checkNotNull(thread))
                    }
                }
                AccessMode.WRITE -> {
                    if (nonBlocking && readers == 0) return@withLock VfsError.NO_SUCH_DEVICE_OR_ADDRESS
                    writers++
                    readerOpenWaiters.notifyAllWaiters()
                    if (readers == 0) {
                        waiter = writerOpenWaiters.acquire(1, checkNotNull(thread))
                    }
                }
                AccessMode.READ_WRITE -> {
                    readers++
                    writers++
                    readerOpenWaiters.notifyAllWaiters()
                    writerOpenWaiters.notifyAllWaiters()
                }
                AccessMode.PATH -> return@withLock VfsError.BAD_DESCRIPTOR
            }
            null
        }
        if (error != null) return VfsResult.Err(error)
        val queued = waiter
        if (queued != null) {
            do {
                check(Scheduler.parkCurrent()) { "Cannot park a FIFO opener" }
            } while (lock.withLock { !queued.ready })
            lock.withLock {
                if (access == AccessMode.READ) readerOpenWaiters.release(queued)
                else writerOpenWaiters.release(queued)
            }
        }
        return VfsResult.Ok(PipeEndpoint(this, access))
    }

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

    fun poll(events: Int, access: AccessMode): Long = lock.withLock {
        var available = 0
        if (access.canWrite) {
            if (readers == 0) available = PollEvents.POLLERR
            else if (buffer.size - size >= ATOMIC_WRITE_BYTES) {
                available = PollEvents.NORMAL_OUTPUT
            }
        }
        if (access.canRead && (size != 0 || writers == 0)) {
            available = available or PollEvents.NORMAL_INPUT
        }
        (available and events).toLong()
    }

    fun close(access: AccessMode) = lock.withLock {
        if (access.canWrite) {
            check(writers > 0)
            writers--
            if (writers == 0) readWaiters.notifyAllWaiters()
        }
        if (access.canRead) {
            check(readers > 0)
            readers--
            if (readers == 0) writeWaiters.notifyAllWaiters()
        }
    }
}
