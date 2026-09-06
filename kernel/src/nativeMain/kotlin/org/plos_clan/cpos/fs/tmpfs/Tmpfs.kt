package org.plos_clan.cpos.fs.tmpfs

import org.plos_clan.cpos.fs.DeviceNode
import org.plos_clan.cpos.fs.sock.SocketNodeBackend
import org.plos_clan.cpos.fs.vfs.CacheValidity
import org.plos_clan.cpos.fs.vfs.EmptyFileSystemOptions
import org.plos_clan.cpos.fs.vfs.FifoBackend
import org.plos_clan.cpos.fs.vfs.FileMode
import org.plos_clan.cpos.fs.vfs.FileSystemOptions
import org.plos_clan.cpos.fs.vfs.FileSystemStatistics
import org.plos_clan.cpos.fs.vfs.FileSystemType
import org.plos_clan.cpos.fs.vfs.Inode
import org.plos_clan.cpos.fs.vfs.InodeAttributeSnapshot
import org.plos_clan.cpos.fs.vfs.InodeAttributes
import org.plos_clan.cpos.fs.vfs.InodeBackend
import org.plos_clan.cpos.fs.vfs.InodeId
import org.plos_clan.cpos.fs.vfs.InodeMetadata
import org.plos_clan.cpos.fs.vfs.NodeCreation
import org.plos_clan.cpos.fs.vfs.NodeKind
import org.plos_clan.cpos.fs.vfs.SuperBlock
import org.plos_clan.cpos.fs.vfs.SuperBlockBackend
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsName
import org.plos_clan.cpos.fs.vfs.VfsOperationContext
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.mem.BuddyFrameAllocator
import org.plos_clan.cpos.utils.IrqSpinLock

abstract class TmpfsFileSystemType protected constructor(
    name: String,
    private val backendFactory: (TmpfsOptions) -> SuperBlockBackend,
) : FileSystemType(name, 0x0102_1994uL) {
    final override fun configure(
        source: String?,
        data: ByteArray?,
    ): VfsResult<TmpfsOptions> =
        TmpfsOptions.parse(data, BuddyFrameAllocator.statistics().totalBytes)

    final override fun createBackend(
        options: FileSystemOptions,
    ): VfsResult<SuperBlockBackend> {
        val configuration = when (options) {
            EmptyFileSystemOptions -> TmpfsOptions()
            is TmpfsOptions -> options
            else -> return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        }
        return VfsResult.Ok(backendFactory(configuration))
    }
}

object Tmpfs : TmpfsFileSystemType("tmpfs", ::TmpfsInstance)

internal open class TmpfsInstance(
    private val options: TmpfsOptions,
    internal val cacheDirectoryLookups: Boolean = true,
) : SuperBlockBackend {
    private val lock = IrqSpinLock()
    private val mutationLock = IrqSpinLock()
    private var nextInodeId = 1uL
    private val spaceQuota = TmpfsQuota(options.sizeLimit)
    private val inodeQuota = TmpfsQuota(options.inodeLimit.takeUnless { it == 0uL })

    val pageSize: Int
        get() = options.pageSize

    override fun createRoot(superBlock: SuperBlock): Inode = checkNotNull(
        newNode(
            superBlock,
            NodeCreation(NodeKind.Directory, options.rootMode, options.rootUid, options.rootGid),
            parent = null,
        ),
    )

    override fun statistics(caller: VfsOperationContext): VfsResult<FileSystemStatistics> =
        lock.withLock {
            val total = options.sizeLimit ?: BuddyFrameAllocator.statistics().totalBytes
            val blockSize = pageSize.toULong()
            VfsResult.Ok(
                FileSystemStatistics(
                    blockSize = blockSize,
                    blocks = total / blockSize,
                    freeBlocks = (total - minOf(total, spaceQuota.used)) / blockSize,
                    files = options.inodeLimit,
                    freeFiles = if (options.inodeLimit == 0uL) 0uL else inodeQuota.available,
                ),
            )
        }

    override fun evict(inode: Inode) {
        super.evict(inode)
        releaseInode()
    }

    fun newNode(
        superBlock: SuperBlock,
        node: NodeCreation,
        parent: Inode?,
        automatic: Boolean = false,
    ): Inode? {
        val kind = node.kind
        val backend = when (kind) {
            NodeKind.Regular -> TmpfsRegularFile(this)
            NodeKind.Directory -> TmpfsDirectory(this, automatic, parent)
            NodeKind.Fifo -> FifoBackend()
            NodeKind.Socket -> SocketNodeBackend
            is NodeKind.SymbolicLink -> TmpfsSymlink(kind.target)
            is NodeKind.Device -> DeviceNode(kind.type, kind.number)
        }
        val symlink = kind as? NodeKind.SymbolicLink
        return newInode(
            superBlock,
            backend,
            InodeMetadata(
                mode = if (symlink == null) node.mode else FileMode(0x1FFu),
                size = symlink?.target?.size?.toULong() ?: 0uL,
                linkCount = if (kind == NodeKind.Directory) 2u else 1u,
                deviceNumber = (kind as? NodeKind.Device)?.number ?: 0uL,
                uid = node.uid,
                gid = node.gid,
            ),
        )
    }

    fun installSpecialNode(
        root: Inode,
        path: List<VfsName>,
        backend: InodeBackend,
        metadata: InodeMetadata,
    ): Boolean {
        if (path.isEmpty()) return false
        val directory = root.backend as? TmpfsDirectory ?: return false
        return mutate { directory.installSpecialNode(root, path, 0, backend, metadata) }
    }

    fun removeSpecialNode(
        root: Inode,
        path: List<VfsName>,
        matches: (InodeBackend) -> Boolean,
    ): Boolean {
        if (path.isEmpty()) return false
        val directory = root.backend as? TmpfsDirectory ?: return false
        return mutate { directory.removeSpecialNode(root, path, 0, matches) }
    }

    fun reserve(bytes: ULong): Boolean = lock.withLock { spaceQuota.reserve(bytes) }

    fun release(bytes: ULong) = lock.withLock { spaceQuota.release(bytes) }

    fun reserveInode(): Boolean = lock.withLock { inodeQuota.reserve(1uL) }

    fun releaseInode() = lock.withLock { inodeQuota.release(1uL) }

    fun <T> mutate(operation: () -> T): T = mutationLock.withLock(operation)

    internal fun newInode(
        superBlock: SuperBlock,
        backend: InodeBackend,
        metadata: InodeMetadata,
    ): Inode? {
        val id = lock.withLock {
            if (!inodeQuota.reserve(1uL)) return null
            InodeId(nextInodeId++)
        }
        return try {
            Inode(
                id = id,
                superBlock = superBlock,
                backend = backend,
                initialAttributes = InodeAttributeSnapshot(
                    InodeAttributes(metadata),
                    CacheValidity.Persistent,
                ),
            )
        } catch (error: OutOfMemoryError) {
            releaseInode()
            throw error
        }
    }
}
