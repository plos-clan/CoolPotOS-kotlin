@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(
    ExperimentalStdlibApi::class,
    NativeRuntimeApi::class,
    ExperimentalForeignApi::class,
)

package org.plos_clan.cpos.mem

import bridge.runtime_vm_enable
import bridge.runtime_vm_take_released
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import org.plos_clan.cpos.utils.toPointer
import kotlin.native.internal.ExportForCppRuntime
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi

internal data class GarbageCollectionStatistics(
    val epoch: ULong,
    val heapBeforeBytes: ULong,
    val heapAfterBytes: ULong,
    val reclaimedBytes: ULong,
    val roots: ULong,
    val markedObjects: ULong,
    val sweptObjects: ULong,
    val keptObjects: ULong,
    val pauseNanoseconds: ULong,
    val durationNanoseconds: ULong,
)

internal object RuntimeMemory : FrameReclaimer {
    private var initialized = false

    fun initialize(): Boolean {
        if (initialized) return true
        if (!BuddyFrameAllocator.isReady || !Hhdm.isReady) {
            println("Runtime memory: Buddy or HHDM is unavailable")
            return false
        }

        BuddyFrameAllocator.register(this)
        runtime_vm_enable()
        initialized = true
        return true
    }

    internal fun allocate(byteLength: ULong): COpaquePointer? {
        val frameCount = requiredFrames(byteLength)
        if (frameCount == 0uL) {
            return null
        }
        reclaim(frameCount)

        val physicalAddress = BuddyFrameAllocator.allocate(frameCount)
        if (physicalAddress == INVALID_FRAME) return null

        return Hhdm.toVirtual(physicalAddress).toPointer<UByteVar>()
    }

    override fun reclaim(target: ULong): ULong {
        var reclaimed = 0uL
        while (reclaimed < target) {
            val pointer = runtime_vm_take_released() ?: return reclaimed
            val frames = pointer.reinterpret<ULongVar>()[0] / PAGE_SIZE_BYTES
            val address = pointer.rawValue.toLong().toULong()
            check(address >= Hhdm.offset && BuddyFrameAllocator.free(address - Hhdm.offset, frames)) {
                "Runtime memory provider received an invalid released block"
            }
            reclaimed += frames
        }
        return reclaimed
    }

    internal fun lastCollectionStatistics(): GarbageCollectionStatistics? {
        val gc = GC.lastGCInfo ?: return null
        val heapBefore = gc.memoryUsageBefore.values.sumOf { it.totalObjectsSizeBytes }
        val heapAfter = gc.memoryUsageAfter.values.sumOf { it.totalObjectsSizeBytes }
        val firstPause = (gc.firstPauseEndTimeNs - gc.firstPauseStartTimeNs).coerceAtLeast(0)
        val secondPauseStart = gc.secondPauseStartTimeNs
        val secondPauseEnd = gc.secondPauseEndTimeNs
        val secondPause = if (secondPauseStart != null && secondPauseEnd != null) {
            (secondPauseEnd - secondPauseStart).coerceAtLeast(0)
        } else {
            0
        }
        val rootSet = gc.rootSet

        return GarbageCollectionStatistics(
            epoch = gc.epoch.coerceAtLeast(0).toULong(),
            heapBeforeBytes = heapBefore.coerceAtLeast(0).toULong(),
            heapAfterBytes = heapAfter.coerceAtLeast(0).toULong(),
            reclaimedBytes = (heapBefore - heapAfter).coerceAtLeast(0).toULong(),
            roots = (
                rootSet.threadLocalReferences + rootSet.stackReferences +
                    rootSet.globalReferences + rootSet.stableReferences
                ).coerceAtLeast(0).toULong(),
            markedObjects = gc.markedCount.coerceAtLeast(0).toULong(),
            sweptObjects = gc.sweepStatistics.values.sumOf { it.sweptCount }
                .coerceAtLeast(0).toULong(),
            keptObjects = gc.sweepStatistics.values.sumOf { it.keptCount }
                .coerceAtLeast(0).toULong(),
            pauseNanoseconds = (firstPause + secondPause).toULong(),
            durationNanoseconds = (gc.endTimeNs - gc.startTimeNs).coerceAtLeast(0).toULong(),
        )
    }

    private fun requiredFrames(byteLength: ULong): ULong {
        if (byteLength == 0uL || byteLength > ULong.MAX_VALUE - (PAGE_SIZE_BYTES - 1uL)) {
            return 0uL
        }
        return (byteLength + PAGE_SIZE_BYTES - 1uL) / PAGE_SIZE_BYTES
    }
}

@ExportForCppRuntime("kotlin_runtime_allocate")
fun allocateRuntimeMemory(byteLength: ULong): COpaquePointer? =
    RuntimeMemory.allocate(byteLength)
