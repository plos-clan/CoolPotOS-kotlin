package org.plos_clan.cpos.fs.fuse

import org.plos_clan.cpos.fs.DeviceNode
import org.plos_clan.cpos.fs.OpenFlags
import org.plos_clan.cpos.fs.vfs.AccessMode
import org.plos_clan.cpos.fs.vfs.AccessPermission
import org.plos_clan.cpos.fs.vfs.AccessPermissions
import org.plos_clan.cpos.fs.vfs.AllocatingOpenFileBackend
import org.plos_clan.cpos.fs.vfs.AtomicCreateDirectoryBackend
import org.plos_clan.cpos.fs.vfs.AtomicOpenResult
import org.plos_clan.cpos.fs.vfs.CacheValidity
import org.plos_clan.cpos.fs.vfs.CopyingOpenFileBackend
import org.plos_clan.cpos.fs.vfs.DentryReference
import org.plos_clan.cpos.fs.vfs.DirectoryEntry
import org.plos_clan.cpos.fs.vfs.DirectoryLookup
import org.plos_clan.cpos.fs.vfs.EXTENDED_ATTRIBUTE_VALUE_MAX
import org.plos_clan.cpos.fs.vfs.ExtendedAttributeMode
import org.plos_clan.cpos.fs.vfs.ExtendedAttributeName
import org.plos_clan.cpos.fs.vfs.FifoBackend
import org.plos_clan.cpos.fs.vfs.FileAllocationMode
import org.plos_clan.cpos.fs.vfs.FileMode
import org.plos_clan.cpos.fs.vfs.FilePosition
import org.plos_clan.cpos.fs.vfs.FileSystemOptions
import org.plos_clan.cpos.fs.vfs.FileSystemStatistics
import org.plos_clan.cpos.fs.vfs.FileSystemType
import org.plos_clan.cpos.fs.vfs.Inode
import org.plos_clan.cpos.fs.vfs.InodeAttributeSnapshot
import org.plos_clan.cpos.fs.vfs.InodeAttributes
import org.plos_clan.cpos.fs.vfs.InodeBackend
import org.plos_clan.cpos.fs.vfs.InodeId
import org.plos_clan.cpos.fs.vfs.InodeMetadata
import org.plos_clan.cpos.fs.vfs.InodeTimestampEvent
import org.plos_clan.cpos.fs.vfs.InodeTimestampSet
import org.plos_clan.cpos.fs.vfs.InodeTimestampUpdate
import org.plos_clan.cpos.fs.vfs.InodeType
import org.plos_clan.cpos.fs.vfs.IoResult
import org.plos_clan.cpos.fs.vfs.MountRequest
import org.plos_clan.cpos.fs.vfs.NodeCreation
import org.plos_clan.cpos.fs.vfs.NodeKind
import org.plos_clan.cpos.fs.vfs.OpenFileBackend
import org.plos_clan.cpos.fs.vfs.OpenOptions
import org.plos_clan.cpos.fs.vfs.RegularFileBackend
import org.plos_clan.cpos.fs.vfs.RemoveMode
import org.plos_clan.cpos.fs.vfs.RenameMode
import org.plos_clan.cpos.fs.vfs.SuperBlock
import org.plos_clan.cpos.fs.vfs.SuperBlockBackend
import org.plos_clan.cpos.fs.vfs.SymlinkBackend
import org.plos_clan.cpos.fs.vfs.UnmountMode
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsName
import org.plos_clan.cpos.fs.vfs.VfsOperationContext
import org.plos_clan.cpos.fs.vfs.VfsPathname
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.fs.vfs.VfsTimestamp
import org.plos_clan.cpos.mem.ByteArrayBuffer
import org.plos_clan.cpos.mem.PageCache
import org.plos_clan.cpos.mem.PageCacheFailure
import org.plos_clan.cpos.mem.PageCacheProvider
import org.plos_clan.cpos.mem.PageCacheSource
import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.mem.PreparedBufferSource
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.IrqSpinLock
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import org.plos_clan.cpos.utils.PollEvents

object Fuse : FileSystemType("fuse", FuseAbi.SUPER_MAGIC) {
    override fun accepts(fileSystemName: String): Boolean =
        fileSystemName == "fuse" || fileSystemName.startsWith("fuse.") ||
            fileSystemName == "fuseblk" || fileSystemName.startsWith("fuseblk.")

    override fun createBackend(options: FileSystemOptions): VfsResult<SuperBlockBackend> =
        VfsResult.Err(VfsError.INVALID_ARGUMENT)

    override fun createMountedBackend(request: MountRequest): VfsResult<SuperBlockBackend> {
        val options = when (val parsed = FuseMountOptions.parse(request)) {
            is VfsResult.Ok -> parsed.value
            is VfsResult.Err -> return parsed
        }
        return request.resources.withResource(options.descriptor) { resource ->
            val session = resource as? FuseSession
                ?: return@withResource VfsResult.Err(VfsError.NO_DEVICE)
            val instance = FuseInstance(session, options)
            when (val attached = session.attach(options.maxRead, instance)) {
                is VfsResult.Ok -> VfsResult.Ok(instance)
                is VfsResult.Err -> attached
            }
        }
    }
}

private data class FuseMountOptions(
    val descriptor: Int,
    val rootMode: UInt,
    val userId: UInt,
    val groupId: UInt,
    val defaultPermissions: Boolean,
    val allowOther: Boolean,
    val maxRead: Int,
    val blockSize: Int,
) {
    companion object {
        fun parse(request: MountRequest): VfsResult<FuseMountOptions> {
            val values = mutableMapOf<String, String?>()
            val text = request.data?.decodeToString() ?: ""
            for (option in text.split(',').filter(String::isNotEmpty)) {
                val separator = option.indexOf('=')
                val name = if (separator < 0) option else option.substring(0, separator)
                val value = if (separator < 0) null else option.substring(separator + 1)
                if (name.isEmpty() || values.containsKey(name)) {
                    return VfsResult.Err(VfsError.INVALID_ARGUMENT)
                }
                values[name] = value
            }

            val descriptor = values.remove("fd")?.toIntOrNull()
                ?.takeIf { it >= 0 }
                ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
            val rootMode = values.remove("rootmode")?.toUIntOrNull(8)
                ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
            if (rootMode and FuseAbi.S_IFMT != FuseAbi.S_IFDIR) {
                return VfsResult.Err(VfsError.INVALID_ARGUMENT)
            }
            val userId = values.remove("user_id")?.toUIntOrNull()
                ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
            val groupId = values.remove("group_id")?.toUIntOrNull()
                ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
            val maxReadOption = values.remove("max_read")
            val maxRead = maxReadOption?.toIntOrNull()
                ?.takeIf { it in 1..FuseAbi.MAX_TRANSFER_SIZE }
                ?.coerceAtLeast(4096)
                ?: if (maxReadOption == null) FuseAbi.MAX_TRANSFER_SIZE
                else return VfsResult.Err(VfsError.INVALID_ARGUMENT)
            val isBlock = request.fileSystemName == "fuseblk" ||
                request.fileSystemName.startsWith("fuseblk.")
            val blockSizeOption = values.remove("blksize")
            if (blockSizeOption != null && !isBlock) {
                return VfsResult.Err(VfsError.INVALID_ARGUMENT)
            }
            val blockSize = blockSizeOption?.toIntOrNull()
                ?.takeIf { it in 512..PAGE_SIZE_BYTES.toInt() && it and (it - 1) == 0 }
                ?: if (blockSizeOption == null) PAGE_SIZE_BYTES.toInt()
                else return VfsResult.Err(VfsError.INVALID_ARGUMENT)
            val hasDefaultPermissions = values.containsKey("default_permissions")
            if (hasDefaultPermissions && values.remove("default_permissions") != null) {
                return VfsResult.Err(VfsError.INVALID_ARGUMENT)
            }
            val hasAllowOther = values.containsKey("allow_other")
            if (hasAllowOther && values.remove("allow_other") != null) {
                return VfsResult.Err(VfsError.INVALID_ARGUMENT)
            }
            if (values.containsKey("subtype")) {
                val subtype = values.remove("subtype")
                if (subtype.isNullOrEmpty()) return VfsResult.Err(VfsError.INVALID_ARGUMENT)
            }
            if (values.isNotEmpty()) return VfsResult.Err(VfsError.INVALID_ARGUMENT)

            return VfsResult.Ok(
                FuseMountOptions(
                    descriptor,
                    rootMode,
                    userId,
                    groupId,
                    hasDefaultPermissions,
                    hasAllowOther,
                    maxRead,
                    blockSize,
                ),
            )
        }
    }
}

private data class FuseAttributeUpdate(
    val mode: FileMode? = null,
    val uid: UInt? = null,
    val gid: UInt? = null,
    val size: ULong? = null,
    val accessTime: InodeTimestampSet.Value? = null,
    val modificationTime: InodeTimestampSet.Value? = null,
)

private class FuseInstance(
    private val session: FuseSession,
    val options: FuseMountOptions,
) : SuperBlockBackend, FuseNotificationSink {
    private data class NodeRecord(val inode: Inode, var lookups: ULong)

    private val lock = IrqSpinLock()
    private val nodes = mutableMapOf<ULong, NodeRecord>()
    private var superBlock: SuperBlock? = null

    override fun createRoot(superBlock: SuperBlock): Inode {
        check(this.superBlock == null)
        this.superBlock = superBlock
        val attributes = InodeAttributeSnapshot(
            InodeAttributes(
                InodeMetadata(
                    mode = FileMode(options.rootMode and FuseAbi.PERMISSION_MASK),
                    linkCount = 1u,
                    uid = options.userId,
                    gid = options.groupId,
                ),
                blockSize = options.blockSize.toULong(),
            ),
            CacheValidity.Volatile,
        )
        val inode = Inode(
            InodeId(FuseAbi.ROOT_ID),
            superBlock,
            FuseDirectoryNode(this, FuseAbi.ROOT_ID),
            attributes,
        )
        lock.withLock { nodes[FuseAbi.ROOT_ID] = NodeRecord(inode, 1uL) }
        return inode
    }

    override fun updateTimestamps(
        caller: VfsOperationContext,
        inode: Inode,
        update: InodeTimestampUpdate,
    ): VfsResult<Unit> {
        if (update !is InodeTimestampSet) {
            inode.updateCachedTimestamps(update)
            return VfsResult.Ok(Unit)
        }
        return setAttributes(
            caller,
            inode,
            FuseAttributeUpdate(
                accessTime = update.accessTime,
                modificationTime = update.modificationTime,
            ),
        )
    }

    override fun statistics(caller: VfsOperationContext): VfsResult<FileSystemStatistics> {
        when (val allowed = authorize(caller)) {
            is VfsResult.Ok -> Unit
            is VfsResult.Err -> return allowed
        }
        val reply = when (val result = request(caller, FuseRequest(FuseOpcode.STATFS, FuseAbi.ROOT_ID))) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        if (reply.bodySize < 80) return VfsResult.Err(VfsError.IO)
        val blockSize = reply.readU32(40).toULong()
        val nameLength = reply.readU32(44).toULong()
        val fragmentSize = reply.readU32(48).toULong().takeIf { it != 0uL } ?: blockSize
        val blocks = reply.readU64(0)
        val freeBlocks = reply.readU64(8)
        val availableBlocks = reply.readU64(16)
        val files = reply.readU64(24)
        val freeFiles = reply.readU64(32)
        if (blockSize == 0uL || fragmentSize == 0uL || nameLength == 0uL ||
            freeBlocks > blocks || availableBlocks > freeBlocks || freeFiles > files
        ) {
            return VfsResult.Err(VfsError.IO)
        }
        return VfsResult.Ok(
            FileSystemStatistics(
                blockSize,
                fragmentSize,
                blocks,
                freeBlocks,
                availableBlocks,
                files,
                freeFiles,
                nameLength,
            ),
        )
    }

    override fun sync(caller: VfsOperationContext): VfsResult<Unit> {
        if (session.isUnsupported(FuseOpcode.SYNCFS)) return VfsResult.Ok(Unit)
        val request = FuseRequest(FuseOpcode.SYNCFS, FuseAbi.ROOT_ID, ULong.SIZE_BYTES)
        return when (val result = request(caller, request)) {
            is VfsResult.Ok -> VfsResult.Ok(Unit)
            is VfsResult.Err -> if (result.error.errno == Errno.ENOSYS) {
                session.markUnsupported(FuseOpcode.SYNCFS)
                VfsResult.Ok(Unit)
            } else result
        }
    }

    override fun prepareUnmount(
        caller: VfsOperationContext,
        mode: UnmountMode,
    ): VfsResult<Unit> = if (mode == UnmountMode.FORCE) VfsResult.Ok(Unit) else sync(caller)

    override fun release() = session.destroy()

    override fun invalidateInode(nodeId: ULong, offset: Long, length: Long) {
        val inode = lock.withLock { nodes[nodeId]?.inode } ?: return
        inode.invalidateAttributes()
        if (offset >= 0) {
            when (val backend = inode.backend) {
                is FuseDirectoryNode -> backend.invalidateDirectoryCache()
                is FuseSymlinkNode -> backend.invalidateTarget()
            }
            if (length <= 0) {
                PageCache.invalidate(inode, offset.toULong())
            } else {
                PageCache.invalidate(inode, offset.toULong(), length.toULong())
            }
        }
    }

    override fun invalidateEntry(parentId: ULong, name: VfsName, childId: ULong?) {
        val parent = lock.withLock { nodes[parentId]?.inode }
        (parent?.backend as? FuseDirectoryNode)?.invalidate(name)
        childId?.let { invalidateInode(it, -1, -1) }
    }

    fun authorize(caller: VfsOperationContext): VfsResult<Unit> =
        if (options.allowOther || caller.privileged || caller.uid == options.userId) {
            VfsResult.Ok(Unit)
        } else {
            VfsResult.Err(VfsError.PERMISSION_DENIED)
        }

    fun request(caller: VfsOperationContext, request: FuseRequest): VfsResult<FuseReply> =
        session.request(caller, request)

    fun submit(caller: VfsOperationContext, request: FuseRequest) = session.submit(caller, request)

    fun maxRead(): Int = session.maximumReadSize()
    fun maxReadAhead(): Int = session.maximumReadAheadSize()
    fun maxWrite(): Int = session.maximumWriteSize()
    fun protocolMinor(): UInt = session.protocolMinorVersion()
    fun supports(feature: FuseFeature): Boolean = session.supports(feature)
    fun sessionUnsupported(opcode: FuseOpcode): Boolean = session.isUnsupported(opcode)
    fun disable(opcode: FuseOpcode) = session.markUnsupported(opcode)
    fun openFlags(options: OpenOptions, directory: Boolean): UInt =
        linuxOpenFlags(options, directory)

    fun lookup(reply: FuseReply, offset: Int = 0): VfsResult<DirectoryLookup> {
        val entry = when (val decoded = FuseDecoder.entry(reply, offset)) {
            is VfsResult.Ok -> decoded.value
            is VfsResult.Err -> return decoded
        }
        if (entry.nodeId == 0uL) return VfsResult.Ok(
            DirectoryLookup(null, entry.entryValidity),
        )
        if (entry.nodeId == FuseAbi.ROOT_ID || entry.attributes == null || entry.type == null) {
            return VfsResult.Err(VfsError.IO)
        }
        val inode = lock.withLock {
            val existing = nodes[entry.nodeId]
            if (existing != null) {
                if (existing.inode.generation != entry.generation ||
                    existing.inode.type != entry.type || existing.lookups == ULong.MAX_VALUE
                ) {
                    return@withLock null
                }
                existing.lookups++
                existing.inode
            } else {
                val block = superBlock ?: return@withLock null
                val backend = createNode(entry.nodeId, entry.type, entry.attributes.attributes.metadata)
                Inode(
                    InodeId(entry.nodeId),
                    block,
                    backend,
                    entry.attributes,
                    entry.generation,
                ).also { nodes[entry.nodeId] = NodeRecord(it, 1uL) }
            }
        } ?: return VfsResult.Err(VfsError.IO)
        installAttributes(inode, entry.attributes)
        return VfsResult.Ok(
            DirectoryLookup(
                inode,
                entry.entryValidity,
            ) { releaseLookup(entry.nodeId) },
        )
    }

    fun forget(nodeId: ULong) = session.forget(nodeId, 1uL)

    fun getAttributes(caller: VfsOperationContext, inode: Inode): VfsResult<InodeAttributeSnapshot> {
        val request = FuseRequest(FuseOpcode.GETATTR, inode.id.value, 16)
        val reply = when (val result = request(caller, request)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        val attributes = when (val decoded = FuseDecoder.attributes(reply)) {
            is VfsResult.Ok -> decoded.value
            is VfsResult.Err -> return decoded
        }
        if (attributes.second != inode.type) return VfsResult.Err(VfsError.IO)
        invalidateChangedData(inode, attributes.first)
        return VfsResult.Ok(attributes.first)
    }

    fun setAttributes(
        caller: VfsOperationContext,
        inode: Inode,
        update: FuseAttributeUpdate,
    ): VfsResult<Unit> {
        val request = FuseRequest(FuseOpcode.SETATTR, inode.id.value, 88)
        var valid = 0u
        update.mode?.let {
            valid = valid or FuseAbi.FATTR_MODE
            request.writeU32(68, it.bits)
        }
        update.uid?.let {
            valid = valid or FuseAbi.FATTR_UID
            request.writeU32(76, it)
        }
        update.gid?.let {
            valid = valid or FuseAbi.FATTR_GID
            request.writeU32(80, it)
        }
        update.size?.let {
            valid = valid or FuseAbi.FATTR_SIZE
            request.writeU64(16, it)
        }
        valid = encodeTimestamp(request, update.accessTime, true, valid)
        valid = encodeTimestamp(request, update.modificationTime, false, valid)
        request.writeU32(0, valid)
        val reply = when (val result = request(caller, request)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        val attributes = when (val decoded = FuseDecoder.attributes(reply)) {
            is VfsResult.Ok -> decoded.value
            is VfsResult.Err -> return decoded
        }
        if (attributes.second != inode.type) return VfsResult.Err(VfsError.IO)
        installAttributes(inode, attributes.first)
        return VfsResult.Ok(Unit)
    }

    fun open(
        caller: VfsOperationContext,
        inode: Inode,
        options: OpenOptions,
        directory: Boolean,
    ): VfsResult<OpenFileBackend> {
        val opcode = if (directory) FuseOpcode.OPENDIR else FuseOpcode.OPEN
        val noOpenFeature = if (directory) FuseFeature.NO_OPENDIR_SUPPORT
        else FuseFeature.NO_OPEN_SUPPORT
        val flags = linuxOpenFlags(options, directory)
        val implicitOpenFlags = FuseAbi.FOPEN_KEEP_CACHE or
            if (directory) FuseAbi.FOPEN_CACHE_DIR else 0u
        if (session.isUnsupported(opcode)) {
            return VfsResult.Ok(
                newHandle(
                    inode,
                    null,
                    implicitOpenFlags,
                    flags and OpenFlags.O_TRUNC.toUInt().inv(),
                    caller,
                    directory,
                ),
            )
        }
        val request = FuseRequest(opcode, inode.id.value, 8).apply { writeU32(0, flags) }
        val reply = when (val result = request(caller, request)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> {
                if (result.error.errno == Errno.ENOSYS && supports(noOpenFeature)) {
                    session.markUnsupported(opcode)
                    return VfsResult.Ok(
                        newHandle(
                            inode,
                            null,
                            implicitOpenFlags,
                            flags and OpenFlags.O_TRUNC.toUInt().inv(),
                            caller,
                            directory,
                        ),
                    )
                }
                return result
            }
        }
        val opened = when (val decoded = FuseDecoder.open(reply)) {
            is VfsResult.Ok -> decoded.value
            is VfsResult.Err -> return decoded
        }
        if (opened.flags and FuseAbi.FOPEN_KEEP_CACHE == 0u) {
            if (directory) {
                (inode.backend as? FuseDirectoryNode)?.invalidateDirectoryCache()
            } else {
                PageCache.invalidate(inode)
            }
        } else if (!directory && flags.toInt() and OpenFlags.O_TRUNC != 0) {
            PageCache.invalidate(inode)
        }
        return VfsResult.Ok(
            newHandle(inode, opened.handle, opened.flags, flags, caller, directory),
        )
    }

    fun getExtendedAttribute(
        caller: VfsOperationContext,
        nodeId: ULong,
        name: ExtendedAttributeName?,
        opcode: FuseOpcode,
    ): VfsResult<ByteArray> {
        if (session.isUnsupported(opcode)) return VfsResult.Err(VfsError.NOT_SUPPORTED)
        val nameBytes = name?.copyBytes()
        val nameSize = if (nameBytes == null) 0 else nameBytes.size + 1
        val query = FuseRequest(opcode, nodeId, 8 + nameSize).apply {
            if (nameBytes != null) writeCString(8, nameBytes)
        }
        val sizeReply = when (val result = request(caller, query)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> {
                if (result.error.errno == Errno.ENOSYS) {
                    session.markUnsupported(opcode)
                    return VfsResult.Err(VfsError.NOT_SUPPORTED)
                }
                return result
            }
        }
        if (sizeReply.bodySize < 8) return VfsResult.Err(VfsError.IO)
        val size = sizeReply.readU32(0).toInt()
        if (size !in 0..EXTENDED_ATTRIBUTE_VALUE_MAX) return VfsResult.Err(VfsError.RANGE)
        if (size == 0) return VfsResult.Ok(ByteArray(0))
        val fetch = FuseRequest(opcode, nodeId, 8 + nameSize).apply {
            writeU32(0, size.toUInt())
            if (nameBytes != null) writeCString(8, nameBytes)
        }
        return when (val result = request(caller, fetch)) {
            is VfsResult.Ok -> if (result.value.bodySize <= size) {
                VfsResult.Ok(result.value.bodyBytes())
            } else {
                VfsResult.Err(VfsError.IO)
            }
            is VfsResult.Err -> result
        }
    }

    fun setExtendedAttribute(
        caller: VfsOperationContext,
        nodeId: ULong,
        name: ExtendedAttributeName,
        value: ByteArray,
        mode: ExtendedAttributeMode,
    ): VfsResult<Unit> {
        if (session.isUnsupported(FuseOpcode.SETXATTR)) {
            return VfsResult.Err(VfsError.NOT_SUPPORTED)
        }
        val nameBytes = name.copyBytes()
        val request = FuseRequest(
            FuseOpcode.SETXATTR,
            nodeId,
            8 + nameBytes.size + 1 + value.size,
        ).apply {
            writeU32(0, value.size.toUInt())
            writeU32(
                4,
                when (mode) {
                    ExtendedAttributeMode.CREATE_OR_REPLACE -> 0u
                    ExtendedAttributeMode.CREATE -> 1u
                    ExtendedAttributeMode.REPLACE -> 2u
                },
            )
            val valueOffset = writeCString(8, nameBytes)
            writeBytes(valueOffset, value)
        }
        return when (val result = request(caller, request)) {
            is VfsResult.Ok -> VfsResult.Ok(Unit)
            is VfsResult.Err -> if (result.error.errno == Errno.ENOSYS) {
                session.markUnsupported(FuseOpcode.SETXATTR)
                VfsResult.Err(VfsError.NOT_SUPPORTED)
            } else result
        }
    }

    fun removeExtendedAttribute(
        caller: VfsOperationContext,
        nodeId: ULong,
        name: ExtendedAttributeName,
    ): VfsResult<Unit> {
        if (session.isUnsupported(FuseOpcode.REMOVEXATTR)) {
            return VfsResult.Err(VfsError.NOT_SUPPORTED)
        }
        val bytes = name.copyBytes()
        val request = FuseRequest(FuseOpcode.REMOVEXATTR, nodeId, bytes.size + 1).apply {
            writeCString(0, bytes)
        }
        return when (val result = request(caller, request)) {
            is VfsResult.Ok -> VfsResult.Ok(Unit)
            is VfsResult.Err -> if (result.error.errno == Errno.ENOSYS) {
                session.markUnsupported(FuseOpcode.REMOVEXATTR)
                VfsResult.Err(VfsError.NOT_SUPPORTED)
            } else result
        }
    }

    private fun releaseLookup(nodeId: ULong) {
        lock.withLock {
            val record = nodes[nodeId] ?: return@withLock
            check(record.lookups > 0uL)
            record.lookups--
            if (record.lookups == 0uL) nodes.remove(nodeId)
        }
        session.forget(nodeId, 1uL)
    }

    private fun createNode(nodeId: ULong, type: InodeType, metadata: InodeMetadata): FuseNode =
        when (type) {
            InodeType.REGULAR -> FuseRegularNode(this, nodeId)
            InodeType.DIRECTORY -> FuseDirectoryNode(this, nodeId)
            InodeType.SYMLINK -> FuseSymlinkNode(this, nodeId)
            else -> FuseSpecialNode(this, nodeId, type, metadata.deviceNumber)
        }

    private fun encodeTimestamp(
        request: FuseRequest,
        value: InodeTimestampSet.Value?,
        access: Boolean,
        initial: UInt,
    ): UInt = when (value) {
        null,
        InodeTimestampSet.Value.Omit,
        -> initial
        InodeTimestampSet.Value.Now -> initial or if (access) {
            FuseAbi.FATTR_ATIME or FuseAbi.FATTR_ATIME_NOW
        } else {
            FuseAbi.FATTR_MTIME or FuseAbi.FATTR_MTIME_NOW
        }
        is InodeTimestampSet.Value.Exact -> {
            request.writeU64(if (access) 32 else 40, value.value.seconds.toULong())
            request.writeU32(if (access) 56 else 60, value.value.nanoseconds)
            initial or if (access) FuseAbi.FATTR_ATIME else FuseAbi.FATTR_MTIME
        }
    }

    private fun linuxOpenFlags(options: OpenOptions, directory: Boolean): UInt {
        var flags = when (options.access) {
            AccessMode.READ -> OpenFlags.O_RDONLY
            AccessMode.WRITE -> OpenFlags.O_WRONLY
            AccessMode.READ_WRITE -> OpenFlags.O_RDWR
            AccessMode.EXECUTE -> OpenFlags.O_RDONLY
            AccessMode.PATH -> OpenFlags.O_PATH
        }
        if (options.append) flags = flags or OpenFlags.O_APPEND
        if (options.truncate && supports(FuseFeature.ATOMIC_O_TRUNC)) {
            flags = flags or OpenFlags.O_TRUNC
        }
        if (options.nonBlocking) flags = flags or OpenFlags.O_NONBLOCK
        if (options.noAtime) flags = flags or OpenFlags.O_NOATIME
        if (directory) flags = flags or OpenFlags.O_DIRECTORY
        return (flags or OpenFlags.O_LARGEFILE).toUInt()
    }

    private fun newHandle(
        inode: Inode,
        handle: ULong?,
        openFlags: UInt,
        fileFlags: UInt,
        caller: VfsOperationContext,
        directory: Boolean,
    ): FuseHandle = if (directory) {
        FuseDirectoryHandle(this, inode, handle, openFlags, fileFlags, caller)
    } else {
        FuseFileHandle(this, inode, handle, openFlags, fileFlags, caller)
    }

    private fun installAttributes(inode: Inode, snapshot: InodeAttributeSnapshot) {
        invalidateChangedData(inode, snapshot)
        inode.installAttributeSnapshot(snapshot)
    }

    private fun invalidateChangedData(inode: Inode, snapshot: InodeAttributeSnapshot) {
        if (inode.type != InodeType.REGULAR) return
        val previous = inode.metadata()
        val current = snapshot.attributes.metadata
        if (previous.size != current.size ||
            supports(FuseFeature.AUTO_INVAL_DATA) &&
            previous.timestamps.modificationTime != current.timestamps.modificationTime
        ) {
            PageCache.invalidate(inode)
        }
    }
}

private sealed interface FuseNode : InodeBackend {
    val instance: FuseInstance
    val nodeId: ULong

    override fun loadAttributes(
        caller: VfsOperationContext,
        inode: Inode,
    ): VfsResult<InodeAttributeSnapshot> = instance.getAttributes(caller, inode)

    override fun checkAccess(
        caller: VfsOperationContext,
        inode: Inode,
        requested: AccessPermissions,
    ): VfsResult<Unit> {
        when (val allowed = instance.authorize(caller)) {
            is VfsResult.Ok -> Unit
            is VfsResult.Err -> return allowed
        }
        if (instance.options.defaultPermissions) {
            return super.checkAccess(caller, inode, requested)
        }
        return if (AccessPermission.EXECUTE in requested && inode.type == InodeType.REGULAR) {
            super.checkAccess(caller, inode, AccessPermissions.EXECUTE)
        } else {
            VfsResult.Ok(Unit)
        }
    }

    override fun access(
        caller: VfsOperationContext,
        inode: Inode,
        requested: AccessPermissions,
    ): VfsResult<Unit> {
        if (instance.options.defaultPermissions) return checkAccess(caller, inode, requested)
        when (val allowed = instance.authorize(caller)) {
            is VfsResult.Ok -> Unit
            is VfsResult.Err -> return allowed
        }
        if (instance.sessionUnsupported(FuseOpcode.ACCESS)) return VfsResult.Ok(Unit)
        val request = FuseRequest(FuseOpcode.ACCESS, nodeId, 8).apply {
            writeU32(0, requested.bits)
        }
        return when (val result = instance.request(caller, request)) {
            is VfsResult.Ok -> VfsResult.Ok(Unit)
            is VfsResult.Err -> if (result.error.errno == Errno.ENOSYS) {
                instance.disable(FuseOpcode.ACCESS)
                VfsResult.Ok(Unit)
            } else result
        }
    }

    override fun setMode(
        caller: VfsOperationContext,
        inode: Inode,
        mode: FileMode,
    ): VfsResult<Unit> = instance.setAttributes(caller, inode, FuseAttributeUpdate(mode = mode))

    override fun setOwner(
        caller: VfsOperationContext,
        inode: Inode,
        uid: UInt?,
        gid: UInt?,
    ): VfsResult<Unit> = instance.setAttributes(
        caller,
        inode,
        FuseAttributeUpdate(uid = uid, gid = gid),
    )

    override fun getExtendedAttribute(
        caller: VfsOperationContext,
        inode: Inode,
        name: ExtendedAttributeName,
    ): VfsResult<ByteArray> = instance.getExtendedAttribute(
        caller,
        nodeId,
        name,
        FuseOpcode.GETXATTR,
    )

    override fun listExtendedAttributes(
        caller: VfsOperationContext,
        inode: Inode,
    ): VfsResult<ByteArray> = instance.getExtendedAttribute(
        caller,
        nodeId,
        null,
        FuseOpcode.LISTXATTR,
    )

    override fun setExtendedAttribute(
        caller: VfsOperationContext,
        inode: Inode,
        name: ExtendedAttributeName,
        value: ByteArray,
        mode: ExtendedAttributeMode,
    ): VfsResult<Unit> = instance.setExtendedAttribute(caller, nodeId, name, value, mode)

    override fun removeExtendedAttribute(
        caller: VfsOperationContext,
        inode: Inode,
        name: ExtendedAttributeName,
    ): VfsResult<Unit> = instance.removeExtendedAttribute(caller, nodeId, name)
}

private class FuseRegularNode(
    override val instance: FuseInstance,
    override val nodeId: ULong,
) : RegularFileBackend(), FuseNode {
    override fun open(
        caller: VfsOperationContext,
        inode: Inode,
        options: OpenOptions,
    ): VfsResult<OpenFileBackend> = instance.open(caller, inode, options, directory = false)

    override fun resize(
        caller: VfsOperationContext,
        inode: Inode,
        size: ULong,
    ): VfsResult<Unit> = instance.setAttributes(caller, inode, FuseAttributeUpdate(size = size))
}

private class FuseDirectoryNode(
    override val instance: FuseInstance,
    override val nodeId: ULong,
) : FuseNode, AtomicCreateDirectoryBackend {
    data class CachedEntry(val entry: DirectoryEntry, val nextOffset: Long)

    private class DirectoryState {
        private data class CachedRecord(var entry: DirectoryEntry, val nextOffset: Long)

        private val lock = IrqSpinLock()
        private val entries = mutableMapOf<VfsName, CacheValidity.Invalidatable>()
        private val listing = mutableMapOf<Long, CachedRecord>()
        private val ends = mutableSetOf<Long>()
        private var modificationTime: VfsTimestamp? = null
        private var readdirPlusAdvised = false

        fun track(name: VfsName, lookup: DirectoryLookup): DirectoryLookup {
            val validity = CacheValidity.Invalidatable(lookup.validity)
            lock.withLock { entries.put(name, validity)?.invalidate() }
            return lookup.copy(validity = validity)
        }

        fun adviseReaddirPlus() = lock.withLock { readdirPlusAdvised = true }

        fun useReaddirPlus(position: Long, automatic: Boolean): Boolean {
            if (!automatic) return true
            return lock.withLock {
                if (readdirPlusAdvised) {
                    readdirPlusAdvised = false
                    true
                } else {
                    position == 0L
                }
            }
        }

        fun validateListing(currentModificationTime: VfsTimestamp) {
            val retired = lock.withLock {
                if (modificationTime != null && modificationTime != currentModificationTime) {
                    clearListingLocked()
                } else {
                    emptyList()
                }.also { modificationTime = currentModificationTime }
            }
            retired.forEach(DentryReference::release)
        }

        fun take(position: Long): CachedEntry? = lock.withLock {
            val record = listing[position] ?: return@withLock null
            val entry = record.entry
            record.entry = entry.copy(lookup = null)
            CachedEntry(entry, record.nextOffset)
        }

        fun isEnd(position: Long): Boolean = lock.withLock { position in ends }

        fun cache(position: Long, entry: DirectoryEntry, nextOffset: Long) {
            val retired = lock.withLock {
                ends.remove(position)
                listing.put(position, CachedRecord(entry, nextOffset))?.entry?.lookup?.reference
            }
            retired?.release()
        }

        fun cacheEnd(position: Long) {
            val retired = lock.withLock {
                ends += position
                listing.remove(position)?.entry?.lookup?.reference
            }
            retired?.release()
        }

        fun invalidate(name: VfsName) {
            val retired = lock.withLock {
                entries.remove(name)?.invalidate()
                clearListingLocked()
            }
            retired.forEach(DentryReference::release)
        }

        fun invalidateListing() {
            val retired = lock.withLock { clearListingLocked() }
            retired.forEach(DentryReference::release)
        }

        private fun clearListingLocked(): List<DentryReference> {
            val retired = listing.values.mapNotNull { it.entry.lookup?.reference }
            listing.clear()
            ends.clear()
            modificationTime = null
            return retired
        }
    }

    override val type = InodeType.DIRECTORY
    private val state = DirectoryState()

    override fun lookup(
        caller: VfsOperationContext,
        directory: Inode,
        name: VfsName,
    ): VfsResult<DirectoryLookup> {
        state.adviseReaddirPlus()
        val request = FuseRequest(FuseOpcode.LOOKUP, nodeId, name.size + 1).apply {
            writeName(0, name)
        }
        val lookup = when (val result = instance.request(caller, request)) {
            is VfsResult.Ok -> instance.lookup(result.value)
            is VfsResult.Err -> if (result.error == VfsError.NOT_FOUND) {
                VfsResult.Ok(DirectoryLookup(null, CacheValidity.Volatile))
            } else result
        }
        return when (lookup) {
            is VfsResult.Ok -> VfsResult.Ok(track(name, lookup.value))
            is VfsResult.Err -> lookup
        }
    }

    override fun open(
        caller: VfsOperationContext,
        inode: Inode,
        options: OpenOptions,
    ): VfsResult<OpenFileBackend> = instance.open(caller, inode, options, directory = true)

    override fun createEntry(
        caller: VfsOperationContext,
        directory: Inode,
        name: VfsName,
        node: NodeCreation,
    ): VfsResult<DirectoryLookup> {
        val request = when (val kind = node.kind) {
            NodeKind.Directory -> FuseRequest(FuseOpcode.MKDIR, nodeId, 8 + name.size + 1).apply {
                writeU32(0, creationMode(node).bits)
                writeU32(4, creationMask(node))
                writeName(8, name)
            }
            is NodeKind.SymbolicLink -> {
                val target = kind.target.copyBytes()
                FuseRequest(FuseOpcode.SYMLINK, nodeId, name.size + 1 + target.size + 1).apply {
                    val targetOffset = writeName(0, name)
                    writeCString(targetOffset, target)
                }
            }
            else -> FuseRequest(FuseOpcode.MKNOD, nodeId, 16 + name.size + 1).apply {
                writeU32(0, nodeMode(node))
                writeU32(4, (kind as? NodeKind.Device)?.number?.toUInt() ?: 0u)
                writeU32(8, creationMask(node))
                writeName(16, name)
            }
        }
        return when (val result = instance.request(caller, request)) {
            is VfsResult.Ok -> when (val lookup = instance.lookup(result.value)) {
                is VfsResult.Ok -> {
                    invalidateDirectoryCache()
                    VfsResult.Ok(track(name, lookup.value))
                }
                is VfsResult.Err -> lookup
            }
            is VfsResult.Err -> result
        }
    }

    override fun createAndOpen(
        caller: VfsOperationContext,
        directory: Inode,
        name: VfsName,
        node: NodeCreation,
        options: OpenOptions,
    ): VfsResult<AtomicOpenResult>? {
        if (node.kind != NodeKind.Regular || options.access == AccessMode.PATH ||
            options.directoryOnly || instance.sessionUnsupported(FuseOpcode.CREATE)
        ) {
            return null
        }
        val flags = instance.openFlags(options, directory = false)
        val request = FuseRequest(FuseOpcode.CREATE, nodeId, 16 + name.size + 1).apply {
            writeU32(0, flags)
            writeU32(4, FuseAbi.S_IFREG or creationMode(node).bits)
            writeU32(8, creationMask(node))
            writeName(16, name)
        }
        val reply = when (val result = instance.request(caller, request)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> {
                if (result.error.errno == Errno.ENOSYS) {
                    instance.disable(FuseOpcode.CREATE)
                    return null
                }
                return result
            }
        }
        val lookup = when (val result = instance.lookup(reply)) {
            is VfsResult.Ok -> {
                invalidateDirectoryCache()
                track(name, result.value)
            }
            is VfsResult.Err -> return result
        }
        val inode = lookup.inode ?: return VfsResult.Err(VfsError.IO)
        if (inode.type != InodeType.REGULAR) {
            invalidate(name)
            lookup.reference?.release()
            return VfsResult.Err(VfsError.IO)
        }
        val opened = when (val result = FuseDecoder.open(reply, FuseAbi.ENTRY_OUT_SIZE)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> {
                invalidate(name)
                lookup.reference?.release()
                return result
            }
        }
        return VfsResult.Ok(
            AtomicOpenResult(
                lookup,
                FuseFileHandle(
                    instance,
                    inode,
                    opened.handle,
                    opened.flags,
                    flags,
                    caller,
                ),
            ),
        )
    }

    override fun linkEntry(
        caller: VfsOperationContext,
        directory: Inode,
        name: VfsName,
        target: Inode,
    ): VfsResult<DirectoryLookup> {
        if ((target.backend as? FuseNode)?.instance !== instance) {
            return VfsResult.Err(VfsError.CROSS_DEVICE)
        }
        val request = FuseRequest(FuseOpcode.LINK, nodeId, 8 + name.size + 1).apply {
            writeU64(0, target.id.value)
            writeName(8, name)
        }
        return when (val result = instance.request(caller, request)) {
            is VfsResult.Ok -> when (val lookup = instance.lookup(result.value)) {
                is VfsResult.Ok -> {
                    invalidateDirectoryCache()
                    VfsResult.Ok(track(name, lookup.value))
                }
                is VfsResult.Err -> lookup
            }
            is VfsResult.Err -> result
        }
    }

    override fun rename(
        caller: VfsOperationContext,
        sourceDirectory: Inode,
        sourceName: VfsName,
        source: Inode,
        targetDirectory: Inode,
        targetName: VfsName,
        target: Inode?,
        mode: RenameMode,
    ): VfsResult<Unit> {
        val destination = targetDirectory.backend as? FuseDirectoryNode
            ?: return VfsResult.Err(VfsError.CROSS_DEVICE)
        if (destination.instance !== instance) return VfsResult.Err(VfsError.CROSS_DEVICE)
        val flags = when (mode) {
            RenameMode.REPLACE -> 0u
            RenameMode.NO_REPLACE -> 1u
            RenameMode.EXCHANGE -> 2u
        }
        val extended = flags != 0u
        val opcode = if (extended) FuseOpcode.RENAME2 else FuseOpcode.RENAME
        if (instance.sessionUnsupported(opcode)) {
            return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        }
        val argumentSize = if (extended) 16 else 8
        val request = FuseRequest(
            opcode,
            nodeId,
            argumentSize + sourceName.size + 1 + targetName.size + 1,
        ).apply {
            writeU64(0, destination.nodeId)
            if (extended) writeU32(8, flags)
            val targetOffset = writeName(argumentSize, sourceName)
            writeName(targetOffset, targetName)
        }
        return when (val result = instance.request(caller, request)) {
            is VfsResult.Ok -> {
                invalidate(sourceName)
                destination.invalidate(targetName)
                if (mode != RenameMode.EXCHANGE && target != null) {
                    target.updateMetadata(InodeTimestampEvent.NONE) { metadata ->
                        metadata.copy(
                            linkCount = if (target.type == InodeType.DIRECTORY ||
                                metadata.linkCount == 0u
                            ) 0u else metadata.linkCount - 1u,
                        )
                    }
                }
                VfsResult.Ok(Unit)
            }
            is VfsResult.Err -> if (result.error.errno == Errno.ENOSYS) {
                instance.disable(opcode)
                VfsResult.Err(VfsError.INVALID_ARGUMENT)
            } else result
        }
    }

    override fun remove(
        caller: VfsOperationContext,
        directory: Inode,
        name: VfsName,
        target: Inode,
        mode: RemoveMode,
    ): VfsResult<Unit> {
        val request = FuseRequest(
            if (mode == RemoveMode.DIRECTORY) FuseOpcode.RMDIR else FuseOpcode.UNLINK,
            nodeId,
            name.size + 1,
        ).apply { writeName(0, name) }
        return when (val result = instance.request(caller, request)) {
            is VfsResult.Ok -> {
                invalidate(name)
                target.updateMetadata(InodeTimestampEvent.NONE) { metadata ->
                    metadata.copy(
                        linkCount = if (mode == RemoveMode.DIRECTORY ||
                            metadata.linkCount == 0u
                        ) 0u else metadata.linkCount - 1u,
                    )
                }
                VfsResult.Ok(Unit)
            }
            is VfsResult.Err -> result
        }
    }

    fun invalidate(name: VfsName) = state.invalidate(name)

    fun invalidateDirectoryCache() = state.invalidateListing()

    fun useReaddirPlus(position: Long): Boolean =
        !instance.sessionUnsupported(FuseOpcode.READDIRPLUS) &&
            instance.supports(FuseFeature.DO_READDIRPLUS) && state.useReaddirPlus(
            position,
            instance.supports(FuseFeature.READDIRPLUS_AUTO),
        )

    fun validateDirectoryCache(caller: VfsOperationContext, inode: Inode): VfsResult<Unit> =
        when (val result = inode.attributes(caller)) {
            is VfsResult.Ok -> {
                state.validateListing(result.value.metadata.timestamps.modificationTime)
                VfsResult.Ok(Unit)
            }
            is VfsResult.Err -> result
        }

    fun cachedDirectoryEntry(position: Long): CachedEntry? = state.take(position)

    fun isDirectoryCacheEnd(position: Long): Boolean = state.isEnd(position)

    fun cacheDirectoryEntry(position: Long, entry: DirectoryEntry, nextOffset: Long) =
        state.cache(position, entry, nextOffset)

    fun cacheDirectoryEnd(position: Long) = state.cacheEnd(position)

    fun track(name: VfsName, lookup: DirectoryLookup): DirectoryLookup {
        return state.track(name, lookup)
    }

    private fun nodeMode(node: NodeCreation): UInt = creationMode(node).bits or when (node.kind) {
        NodeKind.Regular -> FuseAbi.S_IFREG
        NodeKind.Fifo -> FuseAbi.S_IFIFO
        NodeKind.Socket -> FuseAbi.S_IFSOCK
        is NodeKind.Device -> if (node.kind.type == InodeType.CHARACTER_DEVICE) {
            FuseAbi.S_IFCHR
        } else {
            FuseAbi.S_IFBLK
        }
        else -> error("non-mknod FUSE node kind")
    }

    private fun creationMode(node: NodeCreation): FileMode =
        if (instance.supports(FuseFeature.DONT_MASK)) node.requestedMode else node.mode

    private fun creationMask(node: NodeCreation): UInt =
        if (instance.supports(FuseFeature.DONT_MASK)) node.creationMask else 0u
}

private class FuseSymlinkNode(
    override val instance: FuseInstance,
    override val nodeId: ULong,
) : FuseNode, SymlinkBackend {
    private val lock = IrqSpinLock()
    private var cachedTarget: VfsPathname? = null
    private var cacheGeneration = 0uL

    override fun readLink(caller: VfsOperationContext, inode: Inode): VfsResult<VfsPathname> {
        val cacheable = instance.supports(FuseFeature.CACHE_SYMLINKS)
        val generation = if (cacheable) {
            lock.withLock {
                cachedTarget?.let { return VfsResult.Ok(it) }
                cacheGeneration
            }
        } else {
            0uL
        }
        val reply = when (val result = instance.request(
            caller,
            FuseRequest(FuseOpcode.READLINK, nodeId),
        )) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        val bytes = reply.bodyBytes()
        if (bytes.isEmpty() || bytes.any { it == 0.toByte() }) {
            return VfsResult.Err(VfsError.IO)
        }
        val target = VfsPathname.fromBytes(bytes)
        if (cacheable) lock.withLock {
            if (cacheGeneration == generation) cachedTarget = target
        }
        return VfsResult.Ok(target)
    }

    fun invalidateTarget() = lock.withLock {
        cachedTarget = null
        cacheGeneration++
    }
}

private class FuseSpecialNode(
    override val instance: FuseInstance,
    override val nodeId: ULong,
    override val type: InodeType,
    deviceNumber: ULong,
) : FuseNode {
    private val local = when (type) {
        InodeType.PIPE -> FifoBackend()
        InodeType.CHARACTER_DEVICE,
        InodeType.BLOCK_DEVICE,
        -> DeviceNode(type, deviceNumber)
        else -> SocketBackend
    }

    override fun open(
        caller: VfsOperationContext,
        inode: Inode,
        options: OpenOptions,
    ): VfsResult<OpenFileBackend> = local.open(caller, inode, options)

    private data object SocketBackend : InodeBackend {
        override val type = InodeType.SOCKET

        override fun open(
            caller: VfsOperationContext,
            inode: Inode,
            options: OpenOptions,
        ): VfsResult<OpenFileBackend> = VfsResult.Err(VfsError.NO_DEVICE)
    }
}

private abstract class FuseHandle(
    protected val instance: FuseInstance,
    protected val fuseInode: Inode,
    protected val handle: ULong?,
    protected val openFlags: UInt,
    protected val fileFlags: UInt,
    protected val opener: VfsOperationContext,
    private val directory: Boolean,
) : OpenFileBackend {
    protected val nodeId: ULong
        get() = fuseInode.id.value

    override val seekable: Boolean
        get() = openFlags and (FuseAbi.FOPEN_NONSEEKABLE or FuseAbi.FOPEN_STREAM) == 0u

    override val handlesOpenTruncate: Boolean
        get() = !directory && fileFlags and OpenFlags.O_TRUNC.toUInt() != 0u

    protected val stream: Boolean
        get() = openFlags and FuseAbi.FOPEN_STREAM != 0u

    override fun flush(caller: VfsOperationContext, inode: Inode): VfsResult<Unit> {
        val currentHandle = handle ?: return VfsResult.Ok(Unit)
        if (openFlags and FuseAbi.FOPEN_NOFLUSH != 0u ||
            instance.sessionUnsupported(FuseOpcode.FLUSH)
        ) {
            return VfsResult.Ok(Unit)
        }
        val request = FuseRequest(FuseOpcode.FLUSH, nodeId, 24).apply {
            writeU64(0, currentHandle)
        }
        return when (val result = instance.request(caller, request)) {
            is VfsResult.Ok -> VfsResult.Ok(Unit)
            is VfsResult.Err -> if (result.error.errno == Errno.ENOSYS) {
                instance.disable(FuseOpcode.FLUSH)
                VfsResult.Ok(Unit)
            } else result
        }
    }

    override fun syncHandle(
        caller: VfsOperationContext,
        inode: Inode,
        dataOnly: Boolean,
    ): VfsResult<Unit> {
        val opcode = if (directory) FuseOpcode.FSYNCDIR else FuseOpcode.FSYNC
        if (instance.sessionUnsupported(opcode)) return VfsResult.Ok(Unit)
        val request = FuseRequest(opcode, nodeId, 16).apply {
            writeU64(0, handle ?: 0uL)
            writeU32(8, if (dataOnly) FuseAbi.FUSE_FSYNC_FDATASYNC else 0u)
        }
        return when (val result = instance.request(caller, request)) {
            is VfsResult.Ok -> VfsResult.Ok(Unit)
            is VfsResult.Err -> if (result.error.errno == Errno.ENOSYS) {
                instance.disable(opcode)
                VfsResult.Ok(Unit)
            } else result
        }
    }

    override fun ioctl(
        caller: VfsOperationContext,
        inode: Inode,
        command: Int,
        args: UserMemory,
    ): Long {
        if (instance.sessionUnsupported(FuseOpcode.IOCTL)) return -Errno.ENOTTY.toLong()
        if (directory && !instance.supports(FuseFeature.HAS_IOCTL_DIR)) {
            return -Errno.ENOTTY.toLong()
        }
        val bits = command.toUInt()
        val direction = bits shr 30
        val size = (bits shr 16 and 0x3fffu).toInt()
        val inputSize = if (direction and 1u != 0u) size else 0
        val outputSize = if (direction and 2u != 0u) size else 0
        val input = if (inputSize == 0) ByteArray(0) else {
            args.copyFromUser(inputSize) ?: return -Errno.EFAULT.toLong()
        }
        val request = FuseRequest(FuseOpcode.IOCTL, nodeId, 32 + input.size).apply {
            writeU64(0, handle ?: 0uL)
            writeU32(8, if (directory) 1u shl 4 else 0u)
            writeU32(12, bits)
            writeU32(24, inputSize.toUInt())
            writeU32(28, outputSize.toUInt())
            writeBytes(32, input)
        }
        val reply = when (val result = instance.request(caller, request)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return if (result.error.errno == Errno.ENOSYS) {
                instance.disable(FuseOpcode.IOCTL)
                -Errno.ENOTTY.toLong()
            } else {
                -result.error.errno.toLong()
            }
        }
        if (reply.bodySize < 16 || reply.readU32(4) and FuseAbi.FUSE_IOCTL_RETRY != 0u) {
            return -Errno.EOPNOTSUPP.toLong()
        }
        val returned = reply.bodySize - 16
        if (returned > outputSize) return -Errno.EIO.toLong()
        if (returned != 0 && !args.copyToUser(reply.bodyBytes(16, returned))) {
            return -Errno.EFAULT.toLong()
        }
        return reply.readU32(0).toInt().toLong()
    }

    override fun poll(caller: VfsOperationContext, inode: Inode, events: Int): Long {
        if (instance.sessionUnsupported(FuseOpcode.POLL)) {
            return (events and PollEvents.DEFAULT_FILE_EVENTS).toLong()
        }
        val request = FuseRequest(FuseOpcode.POLL, nodeId, 24).apply {
            writeU64(0, handle ?: 0uL)
            writeU32(20, events.toUInt())
        }
        return when (val result = instance.request(caller, request)) {
            is VfsResult.Ok -> if (result.value.bodySize >= 8) {
                result.value.readU32(0).toLong()
            } else {
                -Errno.EIO.toLong()
            }
            is VfsResult.Err -> {
                if (result.error.errno == Errno.ENOSYS) {
                    instance.disable(FuseOpcode.POLL)
                    (events and PollEvents.DEFAULT_FILE_EVENTS).toLong()
                } else {
                    -result.error.errno.toLong()
                }
            }
        }
    }

    override fun release() {
        val currentHandle = handle ?: return
        val request = FuseRequest(
            if (directory) FuseOpcode.RELEASEDIR else FuseOpcode.RELEASE,
            nodeId,
            24,
        ).apply {
            writeU64(0, currentHandle)
            writeU32(8, fileFlags)
        }
        instance.submit(opener, request)
    }
}

private class FuseFileHandle(
    instance: FuseInstance,
    inode: Inode,
    handle: ULong?,
    openFlags: UInt,
    fileFlags: UInt,
    opener: VfsOperationContext,
) : FuseHandle(instance, inode, handle, openFlags, fileFlags, opener, directory = false),
    AllocatingOpenFileBackend, CopyingOpenFileBackend, PageCacheProvider {
    private val pageSource = object : PageCacheSource {
        override val identity: Any
            get() = fuseInode

        override val readAheadSize: Int
            get() = instance.maxReadAhead()

        override fun read(offset: ULong, destination: ByteArray): Int {
            if (offset > Long.MAX_VALUE.toULong()) return -1
            val attributeVersion = fuseInode.attributeVersion()
            val buffer = ByteArrayBuffer(destination).prepareWrite(0, destination.size) ?: return -1
            val result = readDirect(
                opener,
                buffer,
                0,
                destination.size,
                FilePosition(offset.toLong()),
            )
            return if (result.isSuccess) {
                result.bytesTransferred.also { transferred ->
                    if (transferred < destination.size) {
                        fuseInode.shrinkCachedSize(
                            offset + transferred.toULong(),
                            attributeVersion,
                        )
                    }
                }
            } else if (result.error == VfsError.INTERRUPTED) {
                PageCacheSource.READ_INTERRUPTED
            } else {
                PageCacheSource.READ_ERROR
            }
        }
    }

    override val cacheSource: PageCacheSource?
        get() = pageSource.takeIf {
            !stream && openFlags and FuseAbi.FOPEN_DIRECT_IO == 0u
        }

    override fun allocate(
        caller: VfsOperationContext,
        inode: Inode,
        offset: ULong,
        length: ULong,
        mode: FileAllocationMode,
    ): VfsResult<Unit> {
        if (instance.sessionUnsupported(FuseOpcode.FALLOCATE)) {
            return VfsResult.Err(VfsError.NOT_SUPPORTED)
        }
        val request = FuseRequest(FuseOpcode.FALLOCATE, nodeId, 32).apply {
            writeU64(0, handle ?: 0uL)
            writeU64(8, offset)
            writeU64(16, length)
            writeU32(24, if (mode.keepsSize) 1u else 0u)
        }
        return when (val result = instance.request(caller, request)) {
            is VfsResult.Ok -> {
                inode.invalidateAttributes()
                VfsResult.Ok(Unit)
            }
            is VfsResult.Err -> if (result.error.errno == Errno.ENOSYS) {
                instance.disable(FuseOpcode.FALLOCATE)
                VfsResult.Err(VfsError.NOT_SUPPORTED)
            } else result
        }
    }

    override fun copyFileRange(
        caller: VfsOperationContext,
        sourceInode: Inode,
        sourceOffset: ULong,
        destinationInode: Inode,
        destination: OpenFileBackend,
        destinationOffset: ULong,
        length: ULong,
        flags: UInt,
    ): VfsResult<ULong> {
        val target = destination as? FuseFileHandle
            ?: return VfsResult.Err(VfsError.NOT_SUPPORTED)
        if (target.instance !== instance || sourceInode !== fuseInode ||
            destinationInode !== target.fuseInode
        ) {
            return VfsResult.Err(VfsError.NOT_SUPPORTED)
        }
        if (flags != 0u) return VfsResult.Err(VfsError.INVALID_ARGUMENT)

        var opcode = if (instance.protocolMinor() >= FuseAbi.MINOR_VERSION &&
            !instance.sessionUnsupported(FuseOpcode.COPY_FILE_RANGE_64)
        ) {
            FuseOpcode.COPY_FILE_RANGE_64
        } else {
            FuseOpcode.COPY_FILE_RANGE
        }
        while (true) {
            if (instance.sessionUnsupported(opcode)) {
                return VfsResult.Err(VfsError.NOT_SUPPORTED)
            }
            val requestLength = if (opcode == FuseOpcode.COPY_FILE_RANGE) {
                minOf(length, UInt.MAX_VALUE.toULong())
            } else {
                length
            }
            val request = FuseRequest(opcode, nodeId, 56).apply {
                writeU64(0, handle ?: 0uL)
                writeU64(8, sourceOffset)
                writeU64(16, target.nodeId)
                writeU64(24, target.handle ?: 0uL)
                writeU64(32, destinationOffset)
                writeU64(40, requestLength)
                writeU64(48, flags.toULong())
            }
            val reply = when (val result = instance.request(caller, request)) {
                is VfsResult.Ok -> result.value
                is VfsResult.Err -> {
                    if (result.error.errno != Errno.ENOSYS) return result
                    instance.disable(opcode)
                    if (opcode == FuseOpcode.COPY_FILE_RANGE_64) {
                        opcode = FuseOpcode.COPY_FILE_RANGE
                        continue
                    }
                    return VfsResult.Err(VfsError.NOT_SUPPORTED)
                }
            }
            if (reply.bodySize < 8) return VfsResult.Err(VfsError.IO)
            val copied = if (opcode == FuseOpcode.COPY_FILE_RANGE_64) {
                reply.readU64(0)
            } else {
                reply.readU32(0).toULong()
            }
            if (copied > requestLength) return VfsResult.Err(VfsError.IO)
            destinationInode.invalidateAttributes()
            return VfsResult.Ok(copied)
        }
    }

    override fun read(
        caller: VfsOperationContext,
        inode: Inode,
        destination: PreparedBufferDestination,
        destinationOffset: Int,
        count: Int,
        position: FilePosition,
    ): IoResult {
        val cache = cacheSource ?: return readDirect(
            caller,
            destination,
            destinationOffset,
            count,
            position,
        )
        if (!stream && (position.value < 0 || count.toLong() > Long.MAX_VALUE - position.value)) {
            return IoResult.failure(VfsError.FILE_TOO_LARGE)
        }
        if (count == 0) return IoResult.success(0)
        val sourceOffset = position.value.toULong()
        val fileSize = when (val result = inode.attributes(caller)) {
            is VfsResult.Ok -> result.value.metadata.size
            is VfsResult.Err -> return IoResult.failure(result.error)
        }
        if (sourceOffset >= fileSize) return IoResult.success(0)
        val available = minOf(count.toULong(), fileSize - sourceOffset).toInt()
        val cached = PageCache.read(cache, sourceOffset, destination, destinationOffset, available)
        if (!cached.isSuccess) {
            return IoResult.failure(when (cached.failure) {
                PageCacheFailure.OUT_OF_MEMORY -> VfsError.NO_MEMORY
                PageCacheFailure.IO_ERROR -> VfsError.IO
                PageCacheFailure.INTERRUPTED -> VfsError.INTERRUPTED
            })
        }
        position.value += cached.bytes
        return IoResult.success(cached.bytes)
    }

    private fun readDirect(
        caller: VfsOperationContext,
        destination: PreparedBufferDestination,
        destinationOffset: Int,
        count: Int,
        position: FilePosition,
    ): IoResult {
        if (!stream && (position.value < 0 || count.toLong() > Long.MAX_VALUE - position.value)) {
            return IoResult.failure(VfsError.FILE_TOO_LARGE)
        }
        var transferred = 0
        while (transferred < count) {
            val chunk = minOf(count - transferred, instance.maxRead())
            val offset = if (stream) 0uL else position.value.toULong()
            val request = FuseRequest(FuseOpcode.READ, nodeId, 40).apply {
                writeU64(0, handle ?: 0uL)
                writeU64(8, offset)
                writeU32(16, chunk.toUInt())
                writeU32(32, fileFlags)
            }
            val reply = when (val result = instance.request(caller, request)) {
                is VfsResult.Ok -> result.value
                is VfsResult.Err -> return if (transferred == 0) {
                    IoResult.failure(result.error)
                } else {
                    IoResult.success(transferred)
                }
            }
            if (reply.bodySize > chunk) {
                return if (transferred == 0) IoResult.failure(VfsError.IO)
                else IoResult.success(transferred)
            }
            if (!reply.copyTo(
                    0,
                    destination,
                    destinationOffset + transferred,
                    reply.bodySize,
                )
            ) {
                return if (transferred == 0) IoResult.failure(VfsError.FAULT)
                else IoResult.success(transferred)
            }
            val current = reply.bodySize
            transferred += current
            if (!stream) position.value += current
            if (current < chunk) break
        }
        return IoResult.success(transferred)
    }

    override fun write(
        caller: VfsOperationContext,
        inode: Inode,
        source: PreparedBufferSource,
        sourceOffset: Int,
        count: Int,
        position: FilePosition,
        append: Boolean,
    ): IoResult {
        if (append && !stream) {
            val size = when (val result = inode.attributes(caller, forceRefresh = true)) {
                is VfsResult.Ok -> result.value.metadata.size
                is VfsResult.Err -> return IoResult.failure(result.error)
            }
            if (size > Long.MAX_VALUE.toULong()) return IoResult.failure(VfsError.FILE_TOO_LARGE)
            position.value = size.toLong()
        }
        if (!stream && (position.value < 0 || count.toLong() > Long.MAX_VALUE - position.value)) {
            return IoResult.failure(VfsError.FILE_TOO_LARGE)
        }
        var transferred = 0
        while (transferred < count) {
            val chunk = minOf(count - transferred, instance.maxWrite())
            val request = FuseRequest(FuseOpcode.WRITE, nodeId, 40 + chunk).apply {
                writeU64(0, handle ?: 0uL)
                writeU64(8, if (stream) 0uL else position.value.toULong())
                writeU32(16, chunk.toUInt())
                writeU32(32, fileFlags)
            }
            if (!request.copyFrom(source, sourceOffset + transferred, 40, chunk)) {
                return if (transferred == 0) IoResult.failure(VfsError.FAULT)
                else IoResult.success(transferred)
            }
            val reply = when (val result = instance.request(caller, request)) {
                is VfsResult.Ok -> result.value
                is VfsResult.Err -> return if (transferred == 0) {
                    IoResult.failure(result.error)
                } else {
                    IoResult.success(transferred)
                }
            }
            if (reply.bodySize < 8) {
                return if (transferred == 0) IoResult.failure(VfsError.IO)
                else IoResult.success(transferred)
            }
            val current = reply.readU32(0).toInt()
            if (current !in 0..chunk) {
                return if (transferred == 0) IoResult.failure(VfsError.IO)
                else IoResult.success(transferred)
            }
            transferred += current
            if (!stream) position.value += current
            inode.invalidateAttributes()
            if (current < chunk) break
        }
        return IoResult.success(transferred)
    }
}

private class FuseDirectoryHandle(
    instance: FuseInstance,
    inode: Inode,
    handle: ULong?,
    openFlags: UInt,
    fileFlags: UInt,
    opener: VfsOperationContext,
) : FuseHandle(instance, inode, handle, openFlags, fileFlags, opener, directory = true) {
    override fun iterate(
        caller: VfsOperationContext,
        inode: Inode,
        position: FilePosition,
        emit: (entry: DirectoryEntry, nextOffset: Long) -> Boolean,
    ): VfsResult<Unit> {
        if (position.value < 0) return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        val directory = inode.backend as? FuseDirectoryNode
            ?: return VfsResult.Err(VfsError.IO)
        val cache = openFlags and FuseAbi.FOPEN_CACHE_DIR != 0u
        if (cache) {
            when (val validated = directory.validateDirectoryCache(caller, inode)) {
                is VfsResult.Ok -> Unit
                is VfsResult.Err -> return validated
            }
            while (true) {
                val cached = directory.cachedDirectoryEntry(position.value) ?: break
                if (!emit(cached.entry, cached.nextOffset)) return VfsResult.Ok(Unit)
                if (cached.nextOffset == position.value) return VfsResult.Err(VfsError.IO)
                position.value = cached.nextOffset
            }
            if (directory.isDirectoryCacheEnd(position.value)) return VfsResult.Ok(Unit)
        }

        val size = instance.maxRead()
        val requestPosition = position.value
        var plus = directory.useReaddirPlus(requestPosition)
        var opcode = if (plus) FuseOpcode.READDIRPLUS else FuseOpcode.READDIR
        var result = requestDirectory(caller, opcode, requestPosition, size)
        if (result is VfsResult.Err && plus && result.error.errno == Errno.ENOSYS) {
            instance.disable(FuseOpcode.READDIRPLUS)
            plus = false
            opcode = FuseOpcode.READDIR
            result = requestDirectory(caller, opcode, requestPosition, size)
        }
        val reply = when (result) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        if (reply.bodySize > size) return VfsResult.Err(VfsError.IO)
        if (reply.bodySize == 0) {
            if (cache) directory.cacheDirectoryEnd(requestPosition)
            return VfsResult.Ok(Unit)
        }

        val entryOffset = if (plus) FuseAbi.ENTRY_OUT_SIZE else 0
        val minimumSize = entryOffset + FuseAbi.DIRENT_SIZE
        var offset = 0
        var recordPosition = requestPosition
        var accepting = true
        while (offset < reply.bodySize) {
            if (reply.bodySize - offset < minimumSize) return VfsResult.Err(VfsError.IO)
            val dirent = offset + entryOffset
            val nameLength = reply.readU32(dirent + 16).toInt()
            val recordLength = (minimumSize + nameLength + 7) and -8
            if (nameLength !in 1..VfsName.MAX_LENGTH || recordLength > reply.bodySize - offset) {
                return VfsResult.Err(VfsError.IO)
            }
            val name = when (val parsed = VfsName.fromBytes(
                reply.bodyBytes(dirent + FuseAbi.DIRENT_SIZE, nameLength),
            )) {
                is VfsResult.Ok -> parsed.value
                is VfsResult.Err -> return VfsResult.Err(VfsError.IO)
            }
            val next = reply.readU64(dirent + 8)
            if (next > Long.MAX_VALUE.toULong()) return VfsResult.Err(VfsError.IO)
            val nextOffset = next.toLong()
            if (nextOffset == recordPosition) return VfsResult.Err(VfsError.IO)
            val lookup = if (!plus || name.isDot || name.isDotDot) {
                null
            } else {
                val lookupNodeId = reply.readU64(offset)
                if (lookupNodeId == 0uL) {
                    null
                } else when (val decoded = instance.lookup(reply, offset)) {
                    is VfsResult.Ok -> directory.track(name, decoded.value)
                    is VfsResult.Err -> {
                        instance.forget(lookupNodeId)
                        return decoded
                    }
                }
            }
            val entry = DirectoryEntry(
                name,
                InodeId(reply.readU64(dirent)),
                directoryType(reply.readU32(dirent + 20)),
                lookup,
            )
            val accepted = accepting && emit(entry, nextOffset)
            if (cache) {
                directory.cacheDirectoryEntry(
                    recordPosition,
                    if (accepting) entry.copy(lookup = null) else entry,
                    nextOffset,
                )
            } else if (!accepting) {
                lookup?.reference?.release()
            }
            if (accepting) {
                if (accepted) position.value = nextOffset else accepting = false
            }
            recordPosition = nextOffset
            offset += recordLength
        }
        return VfsResult.Ok(Unit)
    }

    private fun requestDirectory(
        caller: VfsOperationContext,
        opcode: FuseOpcode,
        position: Long,
        size: Int,
    ): VfsResult<FuseReply> = instance.request(caller, FuseRequest(opcode, nodeId, 40).apply {
        writeU64(0, handle ?: 0uL)
        writeU64(8, position.toULong())
        writeU32(16, size.toUInt())
        writeU32(32, fileFlags)
    })

    private fun directoryType(type: UInt): InodeType? = when (type) {
        1u -> InodeType.PIPE
        2u -> InodeType.CHARACTER_DEVICE
        4u -> InodeType.DIRECTORY
        6u -> InodeType.BLOCK_DEVICE
        8u -> InodeType.REGULAR
        10u -> InodeType.SYMLINK
        12u -> InodeType.SOCKET
        else -> null
    }
}
