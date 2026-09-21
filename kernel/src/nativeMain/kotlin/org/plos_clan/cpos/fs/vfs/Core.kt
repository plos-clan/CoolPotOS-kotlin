package org.plos_clan.cpos.fs.vfs

import org.plos_clan.cpos.drivers.RealtimeClock

data class InodeMetadata(
    val mode: FileMode,
    val size: ULong = 0uL,
    val linkCount: UInt = 1u,
    val deviceNumber: ULong = 0uL,
    val uid: UInt = 0u,
    val gid: UInt = 0u,
    val timestamps: InodeTimestamps = InodeTimestamps.at(RealtimeClock.now()),
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

enum class PathResolutionBoundary {
    NONE,
    BENEATH,
    IN_ROOT,
}

enum class SymlinkResolution {
    FOLLOW,
    NO_MAGIC_LINKS,
    NO_SYMLINKS,
}

data class PathResolution(
    val boundary: PathResolutionBoundary = PathResolutionBoundary.NONE,
    val symlinks: SymlinkResolution = SymlinkResolution.FOLLOW,
    val allowMountCrossing: Boolean = true,
    val cachedOnly: Boolean = false,
) {
    companion object {
        val DEFAULT = PathResolution()
    }
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
    val noControllingTerminal: Boolean = false,
    val noAtime: Boolean = false,
    val resolution: PathResolution = PathResolution.DEFAULT,
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
    internal operator fun plus(flags: MountFlags): MountFlags = MountFlags(bits or flags.bits)
    internal operator fun minus(flag: MountFlag): MountFlags = MountFlags(bits and flag.mask.inv())

    internal val storage: Int
        get() = bits.toInt()

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

        internal fun fromStorage(bits: Int) = MountFlags(bits.toUInt())
    }
}

internal enum class MountPropagation(val bits: ULong) {
    PRIVATE(0x040000uL),
    SHARED(0x100000uL),
    SLAVE(0x080000uL),
    UNBINDABLE(0x020000uL);

    companion object {
        fun fromBits(bits: ULong): MountPropagation? = entries.firstOrNull { it.bits == bits }
    }
}

internal open class MountFlagUpdate(
    private val set: MountFlags,
    private val clear: MountFlags,
) {
    internal open fun applyTo(flags: MountFlags): MountFlags = MountFlags.fromStorage(
        flags.storage and clear.storage.inv() or set.storage,
    )

    internal fun with(flag: MountFlag, enabled: Boolean): MountFlagUpdate =
        if (enabled) MountFlagUpdate(set + flag, clear - flag)
        else MountFlagUpdate(set - flag, clear + flag)

    companion object {
        val NONE = MountFlagUpdate(MountFlags.NONE, MountFlags.NONE)
    }
}

internal class MountAttributeUpdate(
    set: MountFlags,
    clear: MountFlags,
    internal val propagation: MountPropagation? = null,
) : MountFlagUpdate(set, clear) {
    override fun applyTo(flags: MountFlags): MountFlags =
        super.applyTo(flags).withDefaultAtimePolicy()
}

data class RootMountOptions(
    val source: String? = null,
    val flags: MountFlags = MountFlags.NONE,
    val fileSystemOptions: FileSystemOptions = EmptyFileSystemOptions,
)

data class FileSystemConfiguration(
    val fileSystemName: String,
    val source: String? = null,
    val flags: MountFlags = MountFlags.NONE,
    val parameters: FileSystemParameters = FileSystemParameters.EMPTY,
    val resources: MountResources = MountResources.NONE,
    val exclusive: Boolean = false,
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

class FileSystemFileParameter(
    override val key: String,
    val file: OpenFileDescription,
    val type: Type,
) : FileSystemParameter(key) {
    enum class Type { PATH, DESCRIPTOR }

    override fun release() = file.release()
}
