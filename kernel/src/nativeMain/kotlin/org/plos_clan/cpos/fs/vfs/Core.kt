package org.plos_clan.cpos.fs.vfs

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
    DESTINATION_ADDRESS_REQUIRED(89),
    MESSAGE_TOO_LONG(90),
    WRONG_PROTOCOL_TYPE(91),
    PROTOCOL_OPTION_NOT_AVAILABLE(92),
    PROTOCOL_NOT_SUPPORTED(93),
    SOCKET_TYPE_NOT_SUPPORTED(94),
    NOT_SUPPORTED(95),
    ADDRESS_FAMILY_NOT_SUPPORTED(97),
    ADDRESS_IN_USE(98),
    ADDRESS_NOT_AVAILABLE(99),
    ALREADY_CONNECTED(106),
    NOT_CONNECTED(107),
    CONNECTION_REFUSED(111),
    ALREADY_IN_PROGRESS(114),
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
    val timestamps: InodeTimestamps = InodeTimestamps.now(),
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
    val noAtime: Boolean = false,
    val createUid: UInt = 0u,
    val createGid: UInt = 0u,
    val privileged: Boolean = false,
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

    internal fun withDefaultAtimePolicy(): MountFlags {
        val policies = MountFlag.NO_ATIME.mask or MountFlag.RELATIVE_ATIME.mask or
            MountFlag.STRICT_ATIME.mask
        val policy = when {
            bits and MountFlag.STRICT_ATIME.mask != 0u -> MountFlag.STRICT_ATIME.mask
            bits and MountFlag.NO_ATIME.mask != 0u -> MountFlag.NO_ATIME.mask
            else -> MountFlag.RELATIVE_ATIME.mask
        }
        return MountFlags(bits and policies.inv() or policy)
    }

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
