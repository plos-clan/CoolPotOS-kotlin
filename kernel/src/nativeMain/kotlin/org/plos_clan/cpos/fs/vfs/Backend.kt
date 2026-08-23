package org.plos_clan.cpos.fs.vfs

import org.plos_clan.cpos.mem.PageCache
import org.plos_clan.cpos.mem.PageCacheFailure
import org.plos_clan.cpos.mem.PageCacheSource
import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.mem.PreparedBufferSource
import org.plos_clan.cpos.mem.UserMemory

abstract class FileSystemType(
    val name: String,
    val magic: ULong,
    val requiresDevice: Boolean = false,
) {
    open fun parseOptions(source: String?, data: ByteArray?): VfsResult<FileSystemOptions> =
        if (data == null || data.isEmpty()) VfsResult.Ok(
            EmptyFileSystemOptions
        )
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

    fun updateTimestamps(
        inode: Inode,
        update: InodeTimestampUpdate,
    ): VfsResult<Unit> {
        inode.updateMetadata(update)
        return VfsResult.Ok(Unit)
    }

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

    fun setOwner(inode: Inode, uid: UInt?, gid: UInt?): VfsResult<Unit> =
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
    ): VfsResult<ByteArray> = VfsResult.Err(
        VfsError.NOT_SUPPORTED)

    fun listExtendedAttributes(inode: Inode): VfsResult<ByteArray> =
        VfsResult.Err(VfsError.NOT_SUPPORTED)

    fun setExtendedAttribute(
        inode: Inode,
        name: ExtendedAttributeName,
        value: ByteArray,
        mode: ExtendedAttributeMode,
    ): VfsResult<Unit> = VfsResult.Err(
        VfsError.NOT_SUPPORTED)

    fun removeExtendedAttribute(
        inode: Inode,
        name: ExtendedAttributeName,
    ): VfsResult<Unit> = VfsResult.Err(
        VfsError.NOT_SUPPORTED)

    fun evict(inode: Inode) {}
}

interface MutableInodeBackend : InodeBackend {
    override fun setMode(inode: Inode, mode: FileMode): VfsResult<Unit> {
        inode.updateMetadata(InodeTimestampEvent.STATUS_CHANGED) { it.copy(mode = mode) }
        return VfsResult.Ok(Unit)
    }

    override fun setOwner(inode: Inode, uid: UInt?, gid: UInt?): VfsResult<Unit> {
        inode.updateMetadata(InodeTimestampEvent.STATUS_CHANGED) {
            it.copy(uid = uid ?: it.uid, gid = gid ?: it.gid)
        }
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
    final override val type: InodeType
        get() = InodeType.REGULAR

    open fun resize(inode: Inode, size: ULong): VfsResult<Unit> =
        VfsResult.Err(VfsError.NOT_SUPPORTED)

    open fun allocate(
        inode: Inode,
        offset: ULong,
        length: ULong,
        mode: FileAllocationMode,
    ): VfsResult<Unit> = VfsResult.Err(
        VfsError.NOT_SUPPORTED)
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
    /** Whether this result, including a negative result, may satisfy a later lookup. */
    fun isLookupStable(name: VfsName, inode: Inode?): Boolean = true

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
    ): VfsResult<Unit> = VfsResult.Err(
        VfsError.NOT_SUPPORTED)

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
    ): IoResult = IoResult.failure(
        VfsError.NOT_SUPPORTED)

    fun write(
        inode: Inode,
        source: PreparedBufferSource,
        sourceOffset: Int,
        count: Int,
        position: FilePosition,
        append: Boolean,
    ): IoResult = IoResult.failure(
        VfsError.NOT_SUPPORTED)

    fun iterate(
        inode: Inode,
        position: FilePosition,
        emit: (entry: DirectoryEntry, nextOffset: Long) -> Boolean,
    ): VfsResult<Unit> = VfsResult.Err(
        VfsError.NOT_DIRECTORY)

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
    ): IoResult = IoResult.failure(
        VfsError.NOT_SUPPORTED)

    fun write(
        inode: Inode,
        source: PreparedBufferSource,
        sourceOffset: Int,
        count: Int,
    ): IoResult = IoResult.failure(
        VfsError.NOT_SUPPORTED)

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

/** A positionless backend that owns the complete blocking operation. */
interface ModeAwareOpenFileBackend : PositionlessOpenFileBackend {
    fun read(
        inode: Inode,
        destination: PreparedBufferDestination,
        destinationOffset: Int,
        count: Int,
        mode: IoMode,
    ): IoResult

    fun write(
        inode: Inode,
        source: PreparedBufferSource,
        sourceOffset: Int,
        count: Int,
        mode: IoMode,
    ): IoResult

    override fun read(
        inode: Inode,
        destination: PreparedBufferDestination,
        destinationOffset: Int,
        count: Int,
    ): IoResult = read(inode, destination, destinationOffset, count, IoMode.BLOCKING)

    override fun write(
        inode: Inode,
        source: PreparedBufferSource,
        sourceOffset: Int,
        count: Int,
    ): IoResult = write(inode, source, sourceOffset, count, IoMode.BLOCKING)
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

    fun await(event: IoEvent, count: Int): Boolean
}
