package org.plos_clan.cpos.fs.tmpfs

import org.plos_clan.cpos.fs.DeviceNode
import org.plos_clan.cpos.fs.sock.SocketNodeBackend
import org.plos_clan.cpos.fs.vfs.CacheValidity
import org.plos_clan.cpos.fs.vfs.EmptyFileSystemOptions
import org.plos_clan.cpos.fs.vfs.FifoBackend
import org.plos_clan.cpos.fs.vfs.FileMode
import org.plos_clan.cpos.fs.vfs.FileSystemStatistics
import org.plos_clan.cpos.fs.vfs.FileSystemOptions
import org.plos_clan.cpos.fs.vfs.FileSystemType
import org.plos_clan.cpos.fs.vfs.Inode
import org.plos_clan.cpos.fs.vfs.InodeAttributes
import org.plos_clan.cpos.fs.vfs.InodeAttributeSnapshot
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
import org.plos_clan.cpos.fs.vfs.VfsPathname
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.mem.BuddyFrameAllocator
import org.plos_clan.cpos.utils.IrqSpinLock
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES

data class TmpfsOptions(
    val sizeLimit: ULong? = null,
    val pageSize: Int = PAGE_SIZE_BYTES.toInt(),
    val rootMode: FileMode = FileMode(0x1EDu),
    val rootUid: UInt = 0u,
    val rootGid: UInt = 0u,
) : FileSystemOptions {
    init {
        require(pageSize > 0 && pageSize and (pageSize - 1) == 0)
    }

    companion object {
        internal fun parse(data: ByteArray?): VfsResult<TmpfsOptions> {
            if (data == null || data.isEmpty()) return VfsResult.Ok(TmpfsOptions())

            var sizeLimit: ULong? = null
            var mode = 0x1EDu
            var uid = 0u
            var gid = 0u
            for (option in data.decodeToString().split(',')) {
                val separator = option.indexOf('=')
                if (separator <= 0 || separator == option.lastIndex) {
                    return VfsResult.Err(VfsError.INVALID_ARGUMENT)
                }
                val value = option.substring(separator + 1)
                when (option.substring(0, separator)) {
                    "size" -> sizeLimit = parseSize(value)
                        ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
                    "mode" -> mode = value.toUIntOrNull(8)?.takeIf { it <= 0xFFFu }
                        ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
                    "uid" -> uid = value.toUIntOrNull()
                        ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
                    "gid" -> gid = value.toUIntOrNull()
                        ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
                    else -> return VfsResult.Err(VfsError.INVALID_ARGUMENT)
                }
            }
            return VfsResult.Ok(
                TmpfsOptions(
                    sizeLimit = sizeLimit,
                    rootMode = FileMode(mode),
                    rootUid = uid,
                    rootGid = gid,
                ),
            )
        }

        private fun parseSize(value: String): ULong? {
            if (value.endsWith('%')) {
                val percentage = value.dropLast(1).toUIntOrNull()?.takeIf { it <= 100u }
                    ?: return null
                val total = BuddyFrameAllocator.statistics().totalBytes
                val share = percentage.toULong()
                return total / 100uL * share + total % 100uL * share / 100uL
            }
            val shift = when (value.lastOrNull()?.lowercaseChar()) {
                'k' -> 10
                'm' -> 20
                'g' -> 30
                't' -> 40
                'p' -> 50
                'e' -> 60
                else -> 0
            }
            val digits = if (shift == 0) value else value.dropLast(1)
            val units = digits.toULongOrNull() ?: return null
            return units.takeIf { it <= ULong.MAX_VALUE shr shift }?.shl(shift)
        }
    }
}

abstract class TmpfsFileSystemType protected constructor(
    name: String,
    private val backendFactory: (TmpfsOptions) -> SuperBlockBackend,
) : FileSystemType(name, 0x0102_1994uL) {
    final override fun configure(
        source: String?,
        data: ByteArray?,
    ): VfsResult<TmpfsOptions> = TmpfsOptions.parse(data)

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
    private var allocatedBytes = 0uL

    val pageSize: Int
        get() = options.pageSize

    override fun createRoot(superBlock: SuperBlock): Inode = newDirectory(
        superBlock = superBlock,
        mode = options.rootMode,
        uid = options.rootUid,
        gid = options.rootGid,
        parent = null,
    )

    override fun statistics(caller: VfsOperationContext): VfsResult<FileSystemStatistics> {
        val (capacity, available) = lock.withLock {
            val total = options.sizeLimit ?: BuddyFrameAllocator.statistics().totalBytes
            total to (total - minOf(total, allocatedBytes))
        }
        val blockSize = pageSize.toULong()
        return VfsResult.Ok(
            FileSystemStatistics(
                blockSize = blockSize,
                blocks = capacity / blockSize,
                freeBlocks = available / blockSize,
            ),
        )
    }

    fun newRegularFile(
        superBlock: SuperBlock,
        mode: FileMode,
        uid: UInt = 0u,
        gid: UInt = 0u,
    ): Inode =
        newInode(
            superBlock,
            TmpfsRegularFile(this),
            InodeMetadata(mode = mode, linkCount = 1u, uid = uid, gid = gid),
        )

    fun newDirectory(
        superBlock: SuperBlock,
        mode: FileMode,
        uid: UInt = 0u,
        gid: UInt = 0u,
        automatic: Boolean = false,
        parent: Inode?,
    ): Inode = newInode(
        superBlock,
        TmpfsDirectory(this, automatic, parent),
        InodeMetadata(mode = mode, linkCount = 2u, uid = uid, gid = gid),
    )

    fun newSymlink(
        superBlock: SuperBlock,
        target: VfsPathname,
        uid: UInt = 0u,
        gid: UInt = 0u,
    ): Inode =
        newInode(
            superBlock = superBlock,
            backend = TmpfsSymlink(target),
            metadata = InodeMetadata(
                mode = FileMode(0x1FFu),
                size = target.size.toULong(),
                linkCount = 1u,
                uid = uid,
                gid = gid,
            ),
        )

    fun newNode(superBlock: SuperBlock, node: NodeCreation, parent: Inode): Inode =
        when (val kind = node.kind) {
            NodeKind.Regular -> newRegularFile(superBlock, node.mode, node.uid, node.gid)
            NodeKind.Directory -> newDirectory(
                superBlock,
                node.mode,
                node.uid,
                node.gid,
                parent = parent,
            )
            NodeKind.Fifo -> newInode(
                superBlock,
                FifoBackend(),
                InodeMetadata(node.mode, linkCount = 1u, uid = node.uid, gid = node.gid),
            )
            NodeKind.Socket -> newInode(
                superBlock,
                SocketNodeBackend,
                InodeMetadata(node.mode, linkCount = 1u, uid = node.uid, gid = node.gid),
            )
            is NodeKind.SymbolicLink -> newSymlink(superBlock, kind.target, node.uid, node.gid)
            is NodeKind.Device -> newInode(
                superBlock,
                DeviceNode(kind.type, kind.number),
                InodeMetadata(
                    mode = node.mode,
                    linkCount = 1u,
                    deviceNumber = kind.number,
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

    fun reserve(bytes: ULong): Boolean = lock.withLock {
        val limit = options.sizeLimit
        if (bytes > ULong.MAX_VALUE - allocatedBytes ||
            (limit != null && allocatedBytes + bytes > limit)
        ) {
            false
        } else {
            allocatedBytes += bytes
            true
        }
    }

    fun release(bytes: ULong) {
        lock.withLock {
            check(bytes <= allocatedBytes)
            allocatedBytes -= bytes
        }
    }

    fun <T> mutate(operation: () -> T): T = mutationLock.withLock(operation)

    private fun newInode(
        superBlock: SuperBlock,
        backend: InodeBackend,
        metadata: InodeMetadata,
    ): Inode {
        val id = lock.withLock { InodeId(nextInodeId++) }
        return Inode(
            id = id,
            superBlock = superBlock,
            backend = backend,
            initialAttributes = InodeAttributeSnapshot(
                InodeAttributes(metadata),
                CacheValidity.Persistent,
            ),
        )
    }

    internal fun newSpecialNode(
        superBlock: SuperBlock,
        backend: InodeBackend,
        metadata: InodeMetadata,
    ): Inode = newInode(superBlock, backend, metadata)
}
