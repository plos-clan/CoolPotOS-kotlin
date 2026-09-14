package org.plos_clan.cpos.mem.addressspace

import org.plos_clan.cpos.mem.PageCacheSource
import org.plos_clan.cpos.mem.PageCacheAcquireResult
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

const val MEMORY_REGION_READABLE = 0x1uL
const val MEMORY_REGION_WRITABLE = 0x2uL
const val MEMORY_REGION_EXECUTABLE = 0x4uL

internal const val MEMORY_REGION_ACCESS_MASK = 0x7uL

enum class MemoryRegionType(val userMutable: Boolean = true) {
    ANONYMOUS,
    FILE,
    IMAGE,
    STACK,
    VDSO(userMutable = false),
    MMIO,
}

data class MemoryRegion(
    var start: ULong,
    var end: ULong,
    var access: ULong,
    val name: String?,
    val maximumAccess: ULong = MEMORY_REGION_ACCESS_MASK,
    val type: MemoryRegionType = MemoryRegionType.ANONYMOUS,
    var offset: ULong = 0uL,
    val shared: Boolean = false,
    internal val backing: MemoryRegionBacking? = null,
    internal val sharedIdentity: Any? = null,
    internal val identity: Any = Any(),
) {
    val length: ULong
        get() = end - start
}

sealed interface MemoryMapResult<out T> {
    data class Ok<T>(val value: T) : MemoryMapResult<T>
    data class Err(val errno: Int) : MemoryMapResult<Nothing>
}

internal data class SharedMemoryLocation(
    val identity: Any,
    val offset: ULong,
)

enum class PageFaultResult {
    RESOLVED,
    INVALID_ADDRESS,
    ACCESS_DENIED,
    OUT_OF_MEMORY,
    IO_ERROR,
    MAPPING_FAILED,
}

data class MemoryMapRequest(
    val hint: ULong,
    val length: ULong,
    val access: ULong,
    val fixed: Boolean,
    val noReplace: Boolean,
    val shared: Boolean,
    val type: MemoryRegionType,
    val maximumAccess: ULong = MEMORY_REGION_ACCESS_MASK,
    val offset: ULong = 0uL,
    val name: String? = null,
    val backing: MemoryRegionBacking? = null,
    val populate: Boolean = false,
)

@OptIn(ExperimentalAtomicApi::class)
abstract class MemoryRegionBacking : PageCacheSource {
    private val references = AtomicInt(1)

    internal open val sharedMemoryIdentity: Any
        get() = this

    internal abstract fun acquirePage(offset: ULong, scratch: ByteArray): PageCacheAcquireResult

    internal open fun isPageCurrent(offset: ULong, frame: ULong): Boolean = true

    internal open fun attached(addressSpace: MemoryRegionOwner) {}

    internal open fun detached(addressSpace: MemoryRegionOwner) {}

    internal fun retain(addressSpace: MemoryRegionOwner? = null): Boolean {
        var observed = references.load()
        while (observed in 1 until Int.MAX_VALUE) {
            if (references.compareAndSet(observed, observed + 1)) {
                if (addressSpace != null) attached(addressSpace)
                return true
            }
            observed = references.load()
        }
        return false
    }

    internal fun release(addressSpace: MemoryRegionOwner? = null) {
        if (addressSpace != null) detached(addressSpace)
        var observed = references.load()
        while (observed > 0) {
            if (!references.compareAndSet(observed, observed - 1)) {
                observed = references.load()
                continue
            }
            if (observed == 1) close()
            return
        }
    }

    abstract override fun read(offset: ULong, destination: ByteArray): Int

    protected abstract fun close()
}

interface MemoryRegionOwner {
    fun invalidateFile(identity: Any, offset: ULong, end: ULong, privateFrames: Set<ULong>?)
}
