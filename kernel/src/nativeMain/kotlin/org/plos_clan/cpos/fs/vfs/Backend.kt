package org.plos_clan.cpos.fs.vfs

import org.plos_clan.cpos.mem.PageCache
import org.plos_clan.cpos.mem.PageCacheFailure
import org.plos_clan.cpos.mem.PageCacheSource
import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.mem.PreparedBufferSource
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.utils.IrqSpinLock
import org.plos_clan.cpos.utils.PollEvents

abstract class FileSystemType(
    val name: String,
    val magic: ULong,
    val requiresDevice: Boolean = false,
) {
    open fun accepts(fileSystemName: String): Boolean = fileSystemName == name

    protected open fun configure(
        source: String?,
        data: ByteArray?,
    ): VfsResult<FileSystemOptions> =
        if (data == null || data.isEmpty()) VfsResult.Ok(
            EmptyFileSystemOptions
        )
        else VfsResult.Err(VfsError.INVALID_ARGUMENT)

    protected open fun createMountedBackend(
        request: MountRequest,
    ): VfsResult<SuperBlockBackend> = when (
        val options = configure(request.source, request.data)
    ) {
        is VfsResult.Ok -> createBackend(options.value)
        is VfsResult.Err -> options
    }

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

    internal fun createSuperBlock(request: MountRequest): VfsResult<SuperBlock> {
        if (requiresDevice && request.source == null) {
            return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        }
        return when (val result = createMountedBackend(request)) {
            is VfsResult.Ok -> VfsResult.Ok(SuperBlock(this, result.value))
            is VfsResult.Err -> result
        }
    }

    protected abstract fun createBackend(options: FileSystemOptions): VfsResult<SuperBlockBackend>
}

interface SuperBlockBackend {
    fun createRoot(superBlock: SuperBlock): Inode

    fun updateTimestamps(
        caller: VfsOperationContext,
        inode: Inode,
        update: InodeTimestampUpdate,
    ): VfsResult<Unit> {
        inode.updateMetadata(update)
        return VfsResult.Ok(Unit)
    }

    fun sync(caller: VfsOperationContext): VfsResult<Unit> = VfsResult.Ok(Unit)

    fun prepareUnmount(
        caller: VfsOperationContext,
        mode: UnmountMode,
    ): VfsResult<Unit> = sync(caller)

    fun statistics(caller: VfsOperationContext): VfsResult<FileSystemStatistics> =
        VfsResult.Ok(FileSystemStatistics(blockSize = 4096uL))

    fun release() {}
}

interface MountResource

interface MountResourceProvider {
    val mountResource: MountResource?
}

class SuperBlock internal constructor(
    val type: FileSystemType,
    val backend: SuperBlockBackend,
) {
    private val observerLock = IrqSpinLock()
    private var observedInodes: MutableSet<Inode>? = mutableSetOf()

    val root: Dentry = Dentry(
        superBlock = this,
        name = VfsName.ROOT,
        parent = null,
        inode = backend.createRoot(this).also { require(it.superBlock === this) },
    )

    internal fun trackObservedInode(inode: Inode): Boolean = observerLock.withLock {
        val observed = observedInodes ?: return@withLock false
        observed += inode
        true
    }

    internal fun stopTrackingObservedInode(inode: Inode) = observerLock.withLock {
        observedInodes?.remove(inode)
    }

    internal fun unmount() {
        val observed = observerLock.withLock {
            val current = observedInodes ?: return
            observedInodes = null
            current.toList()
        }
        observed.forEach { it.removeObservers(InodeObserverRemoval.UNMOUNTED, tracked = false) }
    }
}

interface InodeBackend {
    val type: InodeType

    fun open(
        caller: VfsOperationContext,
        inode: Inode,
        options: OpenOptions,
    ): VfsResult<OpenFileBackend>

    fun setMode(caller: VfsOperationContext, inode: Inode, mode: FileMode): VfsResult<Unit> =
        VfsResult.Err(VfsError.NOT_SUPPORTED)

    fun setOwner(
        caller: VfsOperationContext,
        inode: Inode,
        uid: UInt?,
        gid: UInt?,
    ): VfsResult<Unit> =
        VfsResult.Err(VfsError.NOT_SUPPORTED)

    fun sync(
        caller: VfsOperationContext,
        inode: Inode,
        dataOnly: Boolean,
    ): VfsResult<Unit> = inode.superBlock.backend.sync(caller)

    fun loadAttributes(
        caller: VfsOperationContext,
        inode: Inode,
    ): VfsResult<InodeAttributeSnapshot> = VfsResult.Ok(
        InodeAttributeSnapshot(
            InodeAttributes(inode.metadata()),
            CacheValidity.Persistent,
        ),
    )

    fun checkAccess(
        caller: VfsOperationContext,
        inode: Inode,
        requested: AccessPermissions,
    ): VfsResult<Unit> {
        if (requested == AccessPermissions.NONE) return VfsResult.Ok(Unit)
        val metadata = when (val result = inode.attributes(caller)) {
            is VfsResult.Ok -> result.value.metadata
            is VfsResult.Err -> return result
        }
        if (caller.privileged) {
            val executable = metadata.mode.bits and 0x49u != 0u
            return if (AccessPermission.EXECUTE !in requested ||
                inode.type == InodeType.DIRECTORY || executable
            ) {
                VfsResult.Ok(Unit)
            } else {
                VfsResult.Err(VfsError.PERMISSION_DENIED)
            }
        }

        val shift = when {
            caller.uid == metadata.uid -> 6
            caller.belongsToGroup(metadata.gid) -> 3
            else -> 0
        }
        val allowed = metadata.mode.bits shr shift and 0x7u
        return if (allowed and requested.bits == requested.bits) VfsResult.Ok(Unit)
        else VfsResult.Err(VfsError.PERMISSION_DENIED)
    }

    fun access(
        caller: VfsOperationContext,
        inode: Inode,
        requested: AccessPermissions,
    ): VfsResult<Unit> = checkAccess(caller, inode, requested)

    fun pageCacheIdentity(inode: Inode): Any = inode

    fun getExtendedAttribute(
        caller: VfsOperationContext,
        inode: Inode,
        name: ExtendedAttributeName,
    ): VfsResult<ByteArray> = VfsResult.Err(
        VfsError.NOT_SUPPORTED)

    fun listExtendedAttributes(
        caller: VfsOperationContext,
        inode: Inode,
    ): VfsResult<ByteArray> =
        VfsResult.Err(VfsError.NOT_SUPPORTED)

    fun setExtendedAttribute(
        caller: VfsOperationContext,
        inode: Inode,
        name: ExtendedAttributeName,
        value: ByteArray,
        mode: ExtendedAttributeMode,
    ): VfsResult<Unit> = VfsResult.Err(
        VfsError.NOT_SUPPORTED)

    fun removeExtendedAttribute(
        caller: VfsOperationContext,
        inode: Inode,
        name: ExtendedAttributeName,
    ): VfsResult<Unit> = VfsResult.Err(
        VfsError.NOT_SUPPORTED)

    fun evict(inode: Inode) {}
}

interface MutableInodeBackend : InodeBackend {
    override fun setMode(
        caller: VfsOperationContext,
        inode: Inode,
        mode: FileMode,
    ): VfsResult<Unit> {
        inode.updateMetadata(InodeTimestampEvent.STATUS_CHANGED) { it.copy(mode = mode) }
        return VfsResult.Ok(Unit)
    }

    override fun setOwner(
        caller: VfsOperationContext,
        inode: Inode,
        uid: UInt?,
        gid: UInt?,
    ): VfsResult<Unit> {
        inode.updateMetadata(InodeTimestampEvent.STATUS_CHANGED) {
            it.copy(uid = uid ?: it.uid, gid = gid ?: it.gid)
        }
        return VfsResult.Ok(Unit)
    }

    override fun getExtendedAttribute(
        caller: VfsOperationContext,
        inode: Inode,
        name: ExtendedAttributeName,
    ): VfsResult<ByteArray> = inode.getExtendedAttribute(name)

    override fun listExtendedAttributes(
        caller: VfsOperationContext,
        inode: Inode,
    ): VfsResult<ByteArray> =
        inode.listExtendedAttributes()

    override fun setExtendedAttribute(
        caller: VfsOperationContext,
        inode: Inode,
        name: ExtendedAttributeName,
        value: ByteArray,
        mode: ExtendedAttributeMode,
    ): VfsResult<Unit> = inode.setExtendedAttribute(name, value, mode)

    override fun removeExtendedAttribute(
        caller: VfsOperationContext,
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

    open fun resize(
        caller: VfsOperationContext,
        inode: Inode,
        size: ULong,
    ): VfsResult<Unit> =
        VfsResult.Err(VfsError.NOT_SUPPORTED)

    open fun allocate(
        caller: VfsOperationContext,
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
    fun readLink(caller: VfsOperationContext, inode: Inode): VfsResult<VfsPathname>
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
    val requestedMode: FileMode = mode,
    val creationMask: UInt = 0u,
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
    fun lookup(
        caller: VfsOperationContext,
        directory: Inode,
        name: VfsName,
    ): VfsResult<DirectoryLookup>

    fun create(
        caller: VfsOperationContext,
        directory: Inode,
        name: VfsName,
        node: NodeCreation,
    ): VfsResult<Inode> =
        VfsResult.Err(VfsError.NOT_SUPPORTED)

    fun createEntry(
        caller: VfsOperationContext,
        directory: Inode,
        name: VfsName,
        node: NodeCreation,
    ): VfsResult<DirectoryLookup> = when (val result = create(caller, directory, name, node)) {
        is VfsResult.Ok -> VfsResult.Ok(DirectoryLookup(result.value))
        is VfsResult.Err -> result
    }

    fun link(
        caller: VfsOperationContext,
        directory: Inode,
        name: VfsName,
        target: Inode,
    ): VfsResult<Unit> =
        VfsResult.Err(VfsError.NOT_SUPPORTED)

    fun linkEntry(
        caller: VfsOperationContext,
        directory: Inode,
        name: VfsName,
        target: Inode,
    ): VfsResult<DirectoryLookup> = when (val result = link(caller, directory, name, target)) {
        is VfsResult.Ok -> VfsResult.Ok(DirectoryLookup(target))
        is VfsResult.Err -> result
    }

    fun rename(
        caller: VfsOperationContext,
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
        caller: VfsOperationContext,
        directory: Inode,
        name: VfsName,
        target: Inode,
        mode: RemoveMode,
    ): VfsResult<Unit> =
        VfsResult.Err(VfsError.NOT_SUPPORTED)
}

data class AtomicOpenResult(
    val entry: DirectoryLookup,
    val backend: OpenFileBackend,
) {
    init {
        require(entry.inode != null)
    }
}

interface AtomicCreateDirectoryBackend : DirectoryBackend {
    fun createAndOpen(
        caller: VfsOperationContext,
        directory: Inode,
        name: VfsName,
        node: NodeCreation,
        options: OpenOptions,
    ): VfsResult<AtomicOpenResult>?
}

class FilePosition(var value: Long = 0)

data class DirectoryEntry(
    val name: VfsName,
    val inodeId: InodeId,
    val type: InodeType?,
    internal val lookup: DirectoryLookup? = null,
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
    val readinessVersion: Int
        get() = 0

    val seekable: Boolean
        get() = true

    val handlesOpenTruncate: Boolean
        get() = false

    fun read(
        caller: VfsOperationContext,
        inode: Inode,
        destination: PreparedBufferDestination,
        destinationOffset: Int,
        count: Int,
        position: FilePosition,
    ): IoResult = IoResult.failure(
        VfsError.NOT_SUPPORTED)

    fun write(
        caller: VfsOperationContext,
        inode: Inode,
        source: PreparedBufferSource,
        sourceOffset: Int,
        count: Int,
        position: FilePosition,
        append: Boolean,
    ): IoResult = IoResult.failure(
        VfsError.NOT_SUPPORTED)

    fun iterate(
        caller: VfsOperationContext,
        inode: Inode,
        position: FilePosition,
        emit: (entry: DirectoryEntry, nextOffset: Long) -> Boolean,
    ): VfsResult<Unit> = VfsResult.Err(
        VfsError.NOT_DIRECTORY)

    fun ioctl(
        caller: VfsOperationContext,
        inode: Inode,
        command: Int,
        args: UserMemory,
    ): Long =
        -VfsError.NOT_SUPPORTED.errno.toLong()

    fun poll(caller: VfsOperationContext, inode: Inode, events: Int): Long =
        if (inode.type == InodeType.REGULAR || inode.type == InodeType.DIRECTORY) {
            (events and PollEvents.DEFAULT_FILE_EVENTS).toLong()
        } else {
            -VfsError.NOT_SUPPORTED.errno.toLong()
        }

    fun flush(caller: VfsOperationContext, inode: Inode): VfsResult<Unit> = VfsResult.Ok(Unit)

    fun syncHandle(
        caller: VfsOperationContext,
        inode: Inode,
        dataOnly: Boolean,
    ): VfsResult<Unit> = inode.backend.sync(caller, inode, dataOnly)

    fun release() {}
}

interface FixedSizeIoOpenFileBackend : OpenFileBackend {
    val ioSize: Int
}

interface AllocatingOpenFileBackend : OpenFileBackend {
    fun allocate(
        caller: VfsOperationContext,
        inode: Inode,
        offset: ULong,
        length: ULong,
        mode: FileAllocationMode,
    ): VfsResult<Unit>
}

interface CopyingOpenFileBackend : OpenFileBackend {
    fun copyFileRange(
        caller: VfsOperationContext,
        sourceInode: Inode,
        sourceOffset: ULong,
        destinationInode: Inode,
        destination: OpenFileBackend,
        destinationOffset: ULong,
        length: ULong,
        flags: UInt,
    ): VfsResult<ULong>
}

internal interface CachedFileBackend : OpenFileBackend, PageCacheSource {
    override fun read(
        caller: VfsOperationContext,
        inode: Inode,
        destination: PreparedBufferDestination,
        destinationOffset: Int,
        count: Int,
        position: FilePosition,
    ): IoResult {
        if (count == 0 || position.value < 0) return IoResult.success(0)
        val sourceOffset = position.value.toULong()
        val fileSize = when (val result = inode.attributes(caller)) {
            is VfsResult.Ok -> result.value.metadata.size
            is VfsResult.Err -> return IoResult.failure(result.error)
        }
        if (sourceOffset >= fileSize) return IoResult.success(0)

        val available = minOf(count.toULong(), fileSize - sourceOffset).toInt()
        val result = PageCache.read(this, sourceOffset, destination, destinationOffset, available)
        if (!result.isSuccess) {
            val error = when (result.failure) {
                PageCacheFailure.OUT_OF_MEMORY -> VfsError.NO_MEMORY
                PageCacheFailure.IO_ERROR -> VfsError.IO
                PageCacheFailure.INTERRUPTED -> VfsError.INTERRUPTED
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
        caller: VfsOperationContext,
        inode: Inode,
        destination: PreparedBufferDestination,
        destinationOffset: Int,
        count: Int,
    ): IoResult = IoResult.failure(
        VfsError.NOT_SUPPORTED)

    fun write(
        caller: VfsOperationContext,
        inode: Inode,
        source: PreparedBufferSource,
        sourceOffset: Int,
        count: Int,
    ): IoResult = IoResult.failure(
        VfsError.NOT_SUPPORTED)

    override fun read(
        caller: VfsOperationContext,
        inode: Inode,
        destination: PreparedBufferDestination,
        destinationOffset: Int,
        count: Int,
        position: FilePosition,
    ): IoResult = read(caller, inode, destination, destinationOffset, count)

    override fun write(
        caller: VfsOperationContext,
        inode: Inode,
        source: PreparedBufferSource,
        sourceOffset: Int,
        count: Int,
        position: FilePosition,
        append: Boolean,
    ): IoResult = write(caller, inode, source, sourceOffset, count)
}

interface ModeAwareOpenFileBackend : PositionlessOpenFileBackend {
    fun read(
        caller: VfsOperationContext,
        inode: Inode,
        destination: PreparedBufferDestination,
        destinationOffset: Int,
        count: Int,
        mode: IoMode,
    ): IoResult

    fun write(
        caller: VfsOperationContext,
        inode: Inode,
        source: PreparedBufferSource,
        sourceOffset: Int,
        count: Int,
        mode: IoMode,
    ): IoResult

    override fun read(
        caller: VfsOperationContext,
        inode: Inode,
        destination: PreparedBufferDestination,
        destinationOffset: Int,
        count: Int,
    ): IoResult = read(caller, inode, destination, destinationOffset, count, IoMode.BLOCKING)

    override fun write(
        caller: VfsOperationContext,
        inode: Inode,
        source: PreparedBufferSource,
        sourceOffset: Int,
        count: Int,
    ): IoResult = write(caller, inode, source, sourceOffset, count, IoMode.BLOCKING)
}

interface DiscardingOpenFileBackend : PositionlessOpenFileBackend {
    fun discard(inode: Inode, count: Int): IoResult

    override fun write(
        caller: VfsOperationContext,
        inode: Inode,
        source: PreparedBufferSource,
        sourceOffset: Int,
        count: Int,
    ): IoResult = discard(inode, count)
}

interface WaitableOpenFileBackend : PositionlessOpenFileBackend {
    fun write(
        caller: VfsOperationContext,
        inode: Inode,
        source: PreparedBufferSource,
        sourceOffset: Int,
        count: Int,
        mode: IoMode,
    ): IoResult

    override fun write(
        caller: VfsOperationContext,
        inode: Inode,
        source: PreparedBufferSource,
        sourceOffset: Int,
        count: Int,
    ): IoResult = write(caller, inode, source, sourceOffset, count, IoMode.BLOCKING)

    fun await(event: IoEvent, count: Int): Boolean
}

internal interface SpliceSourceOpenFileBackend : OpenFileBackend {
    fun spliceTo(
        caller: VfsOperationContext,
        inode: Inode,
        destination: OpenFileDescription,
        destinationOffset: ULong?,
        count: Int,
        sourceMode: IoMode,
        destinationMode: IoMode,
    ): IoResult
}

internal interface SpliceDestinationOpenFileBackend : OpenFileBackend {
    fun spliceFrom(
        caller: VfsOperationContext,
        inode: Inode,
        source: OpenFileDescription,
        sourceOffset: ULong?,
        count: Int,
        sourceMode: IoMode,
        destinationMode: IoMode,
    ): IoResult
}
