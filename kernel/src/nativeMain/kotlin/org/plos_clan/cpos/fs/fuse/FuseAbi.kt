package org.plos_clan.cpos.fs.fuse

import org.plos_clan.cpos.drivers.TscClock
import org.plos_clan.cpos.fs.vfs.CacheValidity
import org.plos_clan.cpos.fs.vfs.DeviceNumber
import org.plos_clan.cpos.fs.vfs.FileMode
import org.plos_clan.cpos.fs.vfs.InodeAttributeSnapshot
import org.plos_clan.cpos.fs.vfs.InodeAttributes
import org.plos_clan.cpos.fs.vfs.InodeMetadata
import org.plos_clan.cpos.fs.vfs.InodeTimestamps
import org.plos_clan.cpos.fs.vfs.InodeType
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsName
import org.plos_clan.cpos.fs.vfs.VfsOperationContext
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.fs.vfs.VfsTimestamp
import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.mem.PreparedBufferSource
import org.plos_clan.cpos.utils.LittleEndianBuffer
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES

internal object FuseAbi {
    const val VERSION = 7u
    const val MINOR_VERSION = 45u
    const val ROOT_ID = 1uL
    const val SUPER_MAGIC = 0x65735546uL

    const val IN_HEADER_SIZE = 40
    const val OUT_HEADER_SIZE = 16
    const val ATTRIBUTE_SIZE = 88
    const val ENTRY_OUT_SIZE = 128
    const val ATTRIBUTE_OUT_SIZE = 104
    const val OPEN_OUT_SIZE = 16
    const val MIN_READ_BUFFER = 8192
    const val MAX_TRANSFER_SIZE = 1024 * 1024
    const val MAX_PACKET_SIZE = IN_HEADER_SIZE + 4096 + MAX_TRANSFER_SIZE
    const val MAX_PAGES = MAX_TRANSFER_SIZE / 4096
    const val INTERRUPT_UNIQUE_MASK = 1uL
    const val RESEND_UNIQUE_MASK = 0x8000_0000_0000_0000uL
    const val DIRENT_SIZE = 24
    const val DIRENTPLUS_SIZE = ENTRY_OUT_SIZE + DIRENT_SIZE

    const val FOPEN_DIRECT_IO = 0x01u
    const val FOPEN_KEEP_CACHE = 0x02u
    const val FOPEN_NONSEEKABLE = 0x04u
    const val FOPEN_CACHE_DIR = 0x08u
    const val FOPEN_STREAM = 0x10u
    const val FOPEN_NOFLUSH = 0x20u
    const val FOPEN_PARALLEL_DIRECT_WRITES = 0x40u
    const val FOPEN_PASSTHROUGH = 0x80u

    const val FATTR_MODE = 0x001u
    const val FATTR_UID = 0x002u
    const val FATTR_GID = 0x004u
    const val FATTR_SIZE = 0x008u
    const val FATTR_ATIME = 0x010u
    const val FATTR_MTIME = 0x020u
    const val FATTR_ATIME_NOW = 0x080u
    const val FATTR_MTIME_NOW = 0x100u

    const val FUSE_IOCTL_RETRY = 0x04u
    const val FUSE_FSYNC_FDATASYNC = 1u

    const val S_IFMT = 0xf000u
    const val S_IFIFO = 0x1000u
    const val S_IFCHR = 0x2000u
    const val S_IFDIR = 0x4000u
    const val S_IFBLK = 0x6000u
    const val S_IFREG = 0x8000u
    const val S_IFLNK = 0xa000u
    const val S_IFSOCK = 0xc000u
    const val PERMISSION_MASK = 0x0fffu
}

internal enum class FuseOpcode(val value: UInt) {
    LOOKUP(1u),
    FORGET(2u),
    GETATTR(3u),
    SETATTR(4u),
    READLINK(5u),
    SYMLINK(6u),
    MKNOD(8u),
    MKDIR(9u),
    UNLINK(10u),
    RMDIR(11u),
    RENAME(12u),
    LINK(13u),
    OPEN(14u),
    READ(15u),
    WRITE(16u),
    STATFS(17u),
    RELEASE(18u),
    FSYNC(20u),
    SETXATTR(21u),
    GETXATTR(22u),
    LISTXATTR(23u),
    REMOVEXATTR(24u),
    FLUSH(25u),
    INIT(26u),
    OPENDIR(27u),
    READDIR(28u),
    RELEASEDIR(29u),
    FSYNCDIR(30u),
    ACCESS(34u),
    CREATE(35u),
    INTERRUPT(36u),
    DESTROY(38u),
    IOCTL(39u),
    POLL(40u),
    BATCH_FORGET(42u),
    FALLOCATE(43u),
    READDIRPLUS(44u),
    RENAME2(45u),
    LSEEK(46u),
    COPY_FILE_RANGE(47u),
    SYNCFS(50u),
    STATX(52u),
    COPY_FILE_RANGE_64(53u),
}

internal enum class FuseNotifyCode(val value: Int) {
    POLL(1),
    INVALIDATE_INODE(2),
    INVALIDATE_ENTRY(3),
    STORE(4),
    RETRIEVE(5),
    DELETE(6),
    RESEND(7),
    INCREMENT_EPOCH(8),
    PRUNE(9),
    ;

    companion object {
        fun fromValue(value: Int): FuseNotifyCode? = entries.firstOrNull { it.value == value }
    }
}

internal enum class FuseFeature(val bit: Int, val supported: Boolean) {
    ASYNC_READ(0, true),
    POSIX_LOCKS(1, false),
    FILE_OPS(2, false),
    ATOMIC_O_TRUNC(3, true),
    EXPORT_SUPPORT(4, false),
    BIG_WRITES(5, true),
    DONT_MASK(6, true),
    SPLICE_WRITE(7, false),
    SPLICE_MOVE(8, false),
    SPLICE_READ(9, false),
    FLOCK_LOCKS(10, false),
    HAS_IOCTL_DIR(11, true),
    AUTO_INVAL_DATA(12, true),
    DO_READDIRPLUS(13, true),
    READDIRPLUS_AUTO(14, true),
    ASYNC_DIO(15, true),
    WRITEBACK_CACHE(16, false),
    NO_OPEN_SUPPORT(17, true),
    PARALLEL_DIROPS(18, true),
    HANDLE_KILLPRIV(19, false),
    POSIX_ACL(20, false),
    ABORT_ERROR(21, true),
    MAX_PAGES(22, true),
    CACHE_SYMLINKS(23, true),
    NO_OPENDIR_SUPPORT(24, true),
    EXPLICIT_INVAL_DATA(25, false),
    MAP_ALIGNMENT(26, false),
    SUBMOUNTS(27, false),
    HANDLE_KILLPRIV_V2(28, false),
    SETXATTR_EXT(29, false),
    INIT_EXT(30, true),
    INIT_RESERVED(31, false),
    SECURITY_CTX(32, false),
    HAS_INODE_DAX(33, false),
    CREATE_SUPP_GROUP(34, false),
    HAS_EXPIRE_ONLY(35, false),
    DIRECT_IO_ALLOW_MMAP(36, false),
    PASSTHROUGH(37, false),
    NO_EXPORT_SUPPORT(38, false),
    HAS_RESEND(39, false),
    ALLOW_IDMAP(40, false),
    OVER_IO_URING(41, false),
    REQUEST_TIMEOUT(42, false),
    ;

    val mask: ULong
        get() = 1uL shl bit

    companion object {
        val supportedMask: ULong = entries.fold(0uL) { bits, feature ->
            if (feature.supported) bits or feature.mask else bits
        }
    }
}

internal class FuseRequest(
    opcode: FuseOpcode,
    nodeId: ULong,
    bodySize: Int = 0,
) {
    val bytes = ByteArray(FuseAbi.IN_HEADER_SIZE + bodySize)
    private val fields = LittleEndianBuffer(bytes)

    init {
        require(bodySize >= 0 && bytes.size <= FuseAbi.MAX_PACKET_SIZE)
        fields.writeU32(0, bytes.size.toUInt())
        fields.writeU32(4, opcode.value)
        fields.writeU64(16, nodeId)
    }

    val bodySize: Int
        get() = bytes.size - FuseAbi.IN_HEADER_SIZE

    fun writeU16(offset: Int, value: UShort) =
        fields.writeU16(FuseAbi.IN_HEADER_SIZE + offset, value)

    fun writeU32(offset: Int, value: UInt) =
        fields.writeU32(FuseAbi.IN_HEADER_SIZE + offset, value)

    fun writeU64(offset: Int, value: ULong) =
        fields.writeU64(FuseAbi.IN_HEADER_SIZE + offset, value)

    fun writeBytes(offset: Int, source: ByteArray): Int {
        require(offset >= 0 && offset <= bodySize - source.size)
        source.copyInto(bytes, FuseAbi.IN_HEADER_SIZE + offset)
        return offset + source.size
    }

    fun writeCString(offset: Int, value: ByteArray): Int {
        require(value.none { it == 0.toByte() })
        val end = writeBytes(offset, value)
        require(end < bodySize)
        return end + 1
    }

    fun writeName(offset: Int, name: VfsName): Int = writeCString(offset, name.copyBytes())

    fun copyFrom(
        source: PreparedBufferSource,
        sourceOffset: Int,
        bodyOffset: Int,
        count: Int,
    ): Boolean {
        require(bodyOffset >= 0 && count >= 0 && bodyOffset <= bodySize - count)
        return source.copyTo(
            sourceOffset,
            bytes,
            FuseAbi.IN_HEADER_SIZE + bodyOffset,
            count,
        ) == count
    }

    internal fun prepare(unique: ULong, caller: VfsOperationContext) {
        fields.writeU64(8, unique)
        fields.writeU32(24, caller.uid)
        fields.writeU32(28, caller.gid)
        fields.writeU32(32, caller.processId)
        fields.writeU16(36, 0u)
        fields.writeU16(38, 0u)
    }
}

internal class FuseReply(val bytes: ByteArray) {
    private val fields = LittleEndianBuffer(bytes)

    val bodySize: Int
        get() = bytes.size - FuseAbi.OUT_HEADER_SIZE

    fun readU16(offset: Int): UShort = fields.readU16(FuseAbi.OUT_HEADER_SIZE + offset)
    fun readU32(offset: Int): UInt = fields.readU32(FuseAbi.OUT_HEADER_SIZE + offset)
    fun readU64(offset: Int): ULong = fields.readU64(FuseAbi.OUT_HEADER_SIZE + offset)

    fun copyTo(
        bodyOffset: Int,
        destination: PreparedBufferDestination,
        destinationOffset: Int,
        count: Int,
    ): Boolean {
        require(bodyOffset >= 0 && count >= 0 && bodyOffset <= bodySize - count)
        return destination.copyFrom(
            destinationOffset,
            bytes,
            FuseAbi.OUT_HEADER_SIZE + bodyOffset,
            count,
        ) == count
    }

    fun bodyBytes(offset: Int = 0, count: Int = bodySize - offset): ByteArray {
        require(offset >= 0 && count >= 0 && offset <= bodySize - count)
        val start = FuseAbi.OUT_HEADER_SIZE + offset
        return bytes.copyOfRange(start, start + count)
    }
}

internal data class FuseEntry(
    val nodeId: ULong,
    val generation: ULong,
    val entryValidity: CacheValidity,
    val attributes: InodeAttributeSnapshot?,
    val type: InodeType?,
)

internal data class FuseOpenReply(
    val handle: ULong,
    val flags: UInt,
)

internal object FuseDecoder {
    fun entry(reply: FuseReply, offset: Int = 0): VfsResult<FuseEntry> {
        if (offset < 0 || reply.bodySize - offset < FuseAbi.ENTRY_OUT_SIZE) {
            return VfsResult.Err(VfsError.IO)
        }
        val entryValidity = validity(
            reply.readU64(offset + 16),
            reply.readU32(offset + 32),
        ) ?: return VfsResult.Err(VfsError.IO)
        val nodeId = reply.readU64(offset)
        if (nodeId == 0uL) {
            return VfsResult.Ok(FuseEntry(0uL, 0uL, entryValidity, null, null))
        }
        val attributeValidity = validity(
            reply.readU64(offset + 24),
            reply.readU32(offset + 36),
        ) ?: return VfsResult.Err(VfsError.IO)
        val decoded = attribute(reply, offset + 40, attributeValidity)
            ?: return VfsResult.Err(VfsError.IO)
        return VfsResult.Ok(
            FuseEntry(
                nodeId = nodeId,
                generation = reply.readU64(offset + 8),
                entryValidity = entryValidity,
                attributes = decoded.first,
                type = decoded.second,
            ),
        )
    }

    fun attributes(reply: FuseReply): VfsResult<Pair<InodeAttributeSnapshot, InodeType>> {
        if (reply.bodySize < FuseAbi.ATTRIBUTE_OUT_SIZE) return VfsResult.Err(VfsError.IO)
        val validity = validity(reply.readU64(0), reply.readU32(8))
            ?: return VfsResult.Err(VfsError.IO)
        return attribute(reply, 16, validity)?.let(VfsResult<Pair<InodeAttributeSnapshot, InodeType>>::Ok)
            ?: VfsResult.Err(VfsError.IO)
    }

    fun open(reply: FuseReply, offset: Int = 0): VfsResult<FuseOpenReply> {
        if (offset < 0 || reply.bodySize - offset < FuseAbi.OPEN_OUT_SIZE) {
            return VfsResult.Err(VfsError.IO)
        }
        val flags = reply.readU32(offset + 8)
        if (flags and FuseAbi.FOPEN_PASSTHROUGH != 0u) {
            return VfsResult.Err(VfsError.NOT_SUPPORTED)
        }
        return VfsResult.Ok(FuseOpenReply(reply.readU64(offset), flags))
    }

    private fun attribute(
        reply: FuseReply,
        offset: Int,
        validity: CacheValidity,
    ): Pair<InodeAttributeSnapshot, InodeType>? {
        if (offset < 0 || reply.bodySize - offset < FuseAbi.ATTRIBUTE_SIZE) return null
        val accessNanoseconds = reply.readU32(offset + 48)
        val modificationNanoseconds = reply.readU32(offset + 52)
        val changeNanoseconds = reply.readU32(offset + 56)
        if (accessNanoseconds >= VfsTimestamp.NANOSECONDS_PER_SECOND ||
            modificationNanoseconds >= VfsTimestamp.NANOSECONDS_PER_SECOND ||
            changeNanoseconds >= VfsTimestamp.NANOSECONDS_PER_SECOND
        ) {
            return null
        }
        val mode = reply.readU32(offset + 60)
        val type = when (mode and FuseAbi.S_IFMT) {
            FuseAbi.S_IFREG -> InodeType.REGULAR
            FuseAbi.S_IFDIR -> InodeType.DIRECTORY
            FuseAbi.S_IFLNK -> InodeType.SYMLINK
            FuseAbi.S_IFCHR -> InodeType.CHARACTER_DEVICE
            FuseAbi.S_IFBLK -> InodeType.BLOCK_DEVICE
            FuseAbi.S_IFIFO -> InodeType.PIPE
            FuseAbi.S_IFSOCK -> InodeType.SOCKET
            else -> return null
        }
        val deviceNumber = reply.readU32(offset + 76).toULong()
        if ((type == InodeType.CHARACTER_DEVICE || type == InodeType.BLOCK_DEVICE) &&
            DeviceNumber.fromEncoded(deviceNumber) == null
        ) {
            return null
        }
        val blockSize = reply.readU32(offset + 80).takeIf { it != 0u }
            ?: PAGE_SIZE_BYTES.toUInt()
        val metadata = InodeMetadata(
            mode = FileMode(mode and FuseAbi.PERMISSION_MASK),
            size = reply.readU64(offset + 8),
            linkCount = reply.readU32(offset + 64),
            deviceNumber = deviceNumber,
            uid = reply.readU32(offset + 68),
            gid = reply.readU32(offset + 72),
            timestamps = InodeTimestamps(
                accessTime = VfsTimestamp(reply.readU64(offset + 24).toLong(), accessNanoseconds),
                modificationTime = VfsTimestamp(
                    reply.readU64(offset + 32).toLong(),
                    modificationNanoseconds,
                ),
                changeTime = VfsTimestamp(reply.readU64(offset + 40).toLong(), changeNanoseconds),
                birthTime = null,
            ),
        )
        return InodeAttributeSnapshot(
            InodeAttributes(
                metadata = metadata,
                allocatedBlocks = reply.readU64(offset + 16),
                blockSize = blockSize.toULong(),
            ),
            validity,
        ) to type
    }

    private fun validity(seconds: ULong, nanoseconds: UInt): CacheValidity? {
        if (nanoseconds >= VfsTimestamp.NANOSECONDS_PER_SECOND) return null
        return CacheValidity.expiresAfter(TscClock.nanoTime(), seconds, nanoseconds)
    }
}
