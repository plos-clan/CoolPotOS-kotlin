package org.plos_clan.cpos.fs.vfs

internal const val ALLOCATION_BLOCK_SIZE = 512uL
internal const val EXTENDED_ATTRIBUTE_VALUE_MAX = 65_536

value class VfsError private constructor(val errno: Int) {
    companion object {
        val NOT_PERMITTED = VfsError(1)
        val NOT_FOUND = VfsError(2)
        val NO_SUCH_PROCESS = VfsError(3)
        val INTERRUPTED = VfsError(4)
        val IO = VfsError(5)
        val NO_SUCH_DEVICE_OR_ADDRESS = VfsError(6)
        val EXEC_FORMAT = VfsError(8)
        val BAD_DESCRIPTOR = VfsError(9)
        val WOULD_BLOCK = VfsError(11)
        val NO_MEMORY = VfsError(12)
        val PERMISSION_DENIED = VfsError(13)
        val FAULT = VfsError(14)
        val BUSY = VfsError(16)
        val ALREADY_EXISTS = VfsError(17)
        val CROSS_DEVICE = VfsError(18)
        val NO_DEVICE = VfsError(19)
        val NOT_DIRECTORY = VfsError(20)
        val IS_DIRECTORY = VfsError(21)
        val INVALID_ARGUMENT = VfsError(22)
        val NOT_TTY = VfsError(25)
        val FILE_TOO_LARGE = VfsError(27)
        val NO_SPACE = VfsError(28)
        val ILLEGAL_SEEK = VfsError(29)
        val READ_ONLY = VfsError(30)
        val TOO_MANY_LINKS = VfsError(31)
        val BROKEN_PIPE = VfsError(32)
        val RANGE = VfsError(34)
        val NAME_TOO_LONG = VfsError(36)
        val NOT_EMPTY = VfsError(39)
        val TOO_MANY_SYMLINKS = VfsError(40)
        val NO_DATA = VfsError(61)
        val OVERFLOW = VfsError(75)
        val DESTINATION_ADDRESS_REQUIRED = VfsError(89)
        val MESSAGE_TOO_LONG = VfsError(90)
        val WRONG_PROTOCOL_TYPE = VfsError(91)
        val PROTOCOL_OPTION_NOT_AVAILABLE = VfsError(92)
        val PROTOCOL_NOT_SUPPORTED = VfsError(93)
        val SOCKET_TYPE_NOT_SUPPORTED = VfsError(94)
        val NOT_SUPPORTED = VfsError(95)
        val ADDRESS_FAMILY_NOT_SUPPORTED = VfsError(97)
        val ADDRESS_IN_USE = VfsError(98)
        val ADDRESS_NOT_AVAILABLE = VfsError(99)
        val NETWORK_UNREACHABLE = VfsError(101)
        val CONNECTION_ABORTED = VfsError(103)
        val CONNECTION_RESET = VfsError(104)
        val NO_BUFFER_SPACE = VfsError(105)
        val ALREADY_CONNECTED = VfsError(106)
        val NOT_CONNECTED = VfsError(107)
        val TIMED_OUT = VfsError(110)
        val CONNECTION_REFUSED = VfsError(111)
        val HOST_UNREACHABLE = VfsError(113)
        val ALREADY_IN_PROGRESS = VfsError(114)
        val IN_PROGRESS = VfsError(115)

        fun fromErrno(errno: Int): VfsError =
            if (errno in 1..MAX_ERRNO) VfsError(errno) else IO

        private const val MAX_ERRNO = 4095
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

    internal fun copyInto(destination: ByteArray, offset: Int) {
        bytes.copyInto(destination, offset)
    }

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

    fun copyBytes(): ByteArray = bytes.copyOf()

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

value class DeviceNumber private constructor(val value: ULong) {
    val major: UInt
        get() = (value shr 8 and 0xfffuL).toUInt()

    val minor: UInt
        get() = ((value and 0xffuL) or (value shr 12 and 0xfffff00uL)).toUInt()

    companion object {
        const val MAX_MAJOR = 0xfffu
        const val MAX_MINOR = 0xfffffu

        fun create(major: UInt, minor: UInt): DeviceNumber? {
            if (major > MAX_MAJOR || minor > MAX_MINOR) return null
            return DeviceNumber(
                (major.toULong() shl 8) or
                    (minor.toULong() and 0xffuL) or
                    ((minor.toULong() and 0xfffff00uL) shl 12),
            )
        }

        fun fromEncoded(value: ULong): DeviceNumber? {
            val number = DeviceNumber(value)
            return create(number.major, number.minor)?.takeIf { it.value == value }
        }
    }
}

value class FileMode(val bits: UInt) {
    val setUserId: Boolean
        get() = bits and 0x800u != 0u

    val setGroupId: Boolean
        get() = bits and 0x400u != 0u

    val groupExecutable: Boolean
        get() = bits and 0x8u != 0u
}

enum class InodeType {
    REGULAR,
    DIRECTORY,
    SYMLINK,
    CHARACTER_DEVICE,
    BLOCK_DEVICE,
    PIPE,
    SOCKET,
    EVENTFD,
    TIMERFD,
    EPOLL,
    INOTIFY,
    PIDFD,
    SIGNALFD,
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
    EXECUTE,
    PATH;

    internal val canRead: Boolean
        get() = this == READ || this == READ_WRITE || this == EXECUTE

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
    val requestedCreateMode: FileMode = createMode,
    val creationMask: UInt = 0u,
    val truncate: Boolean = false,
    val append: Boolean = false,
    val directoryOnly: Boolean = false,
    val followFinalSymlink: Boolean = true,
    val nonBlocking: Boolean = false,
    val noAtime: Boolean = false,
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
    val resources: MountResources = MountResources.NONE,
)

class MountResources internal constructor(
    private val acquireFile: (Int) -> OpenFileDescription?,
) {
    fun <T> withResource(
        descriptor: Int,
        use: (MountResource) -> VfsResult<T>,
    ): VfsResult<T> {
        val file = acquireFile(descriptor)
            ?: return VfsResult.Err(VfsError.BAD_DESCRIPTOR)
        return try {
            val resource = file.mountResource
                ?: return VfsResult.Err(VfsError.NO_DEVICE)
            use(resource)
        } finally {
            file.release()
        }
    }

    companion object {
        val NONE = MountResources { null }
    }
}

enum class UnmountMode {
    REGULAR,
    FORCE,
    DETACH;

    internal fun unmount(
        caller: VfsOperationContext,
        namespace: MountNamespace,
        mount: Mount,
    ): VfsResult<Unit> = when (this) {
        REGULAR,
        FORCE,
        -> when (val result = mount.superBlock.backend.prepareUnmount(caller, this)) {
            is VfsResult.Ok -> namespace.unmount(mount)
            is VfsResult.Err -> result
        }
        DETACH -> namespace.detach(mount)
    }
}
