package org.plos_clan.cpos.fs.vfs

sealed interface CacheValidity {
    fun isValid(nowNanoseconds: ULong): Boolean

    data object Persistent : CacheValidity {
        override fun isValid(nowNanoseconds: ULong): Boolean = true
    }

    data object Volatile : CacheValidity {
        override fun isValid(nowNanoseconds: ULong): Boolean = false
    }

    data class Until(val deadlineNanoseconds: ULong) : CacheValidity {
        override fun isValid(nowNanoseconds: ULong): Boolean =
            nowNanoseconds < deadlineNanoseconds
    }

    companion object {
        fun expiresAfter(
            nowNanoseconds: ULong,
            seconds: ULong,
            nanoseconds: UInt,
        ): CacheValidity {
            require(nanoseconds < VfsTimestamp.NANOSECONDS_PER_SECOND)
            val second = VfsTimestamp.NANOSECONDS_PER_SECOND.toULong()
            val secondsNanos = seconds * second
            if (seconds != 0uL && secondsNanos / second != seconds) return Persistent

            val duration = secondsNanos + nanoseconds.toULong()
            if (duration < secondsNanos || duration > ULong.MAX_VALUE - nowNanoseconds) {
                return Persistent
            }
            return if (duration == 0uL) Volatile else Until(nowNanoseconds + duration)
        }
    }
}

data class InodeAttributes(
    val metadata: InodeMetadata,
    val allocatedBlocks: ULong = metadata.size / ALLOCATION_BLOCK_SIZE +
        if (metadata.size % ALLOCATION_BLOCK_SIZE == 0uL) 0uL else 1uL,
    val blockSize: ULong = 4096uL,
) {
    init {
        require(blockSize in 1uL..UInt.MAX_VALUE.toULong())
    }
}

data class InodeAttributeSnapshot(
    val attributes: InodeAttributes,
    val validity: CacheValidity,
)

fun interface DentryReference {
    fun release()
}

data class DirectoryLookup(
    val inode: Inode?,
    val validity: CacheValidity = CacheValidity.Persistent,
    val reference: DentryReference? = null,
) {
    init {
        require(inode != null || reference == null)
    }
}

data class FileSystemStatistics(
    val blockSize: ULong,
    val fragmentSize: ULong = blockSize,
    val blocks: ULong = 0uL,
    val freeBlocks: ULong = 0uL,
    val availableBlocks: ULong = freeBlocks,
    val files: ULong = 0uL,
    val freeFiles: ULong = 0uL,
    val maximumNameLength: ULong = VfsName.MAX_LENGTH.toULong(),
) {
    init {
        require(blockSize > 0uL && fragmentSize > 0uL && maximumNameLength > 0uL)
        require(freeBlocks <= blocks && availableBlocks <= freeBlocks && freeFiles <= files)
    }
}
