@file:OptIn(
    kotlin.ExperimentalStdlibApi::class,
    kotlin.native.runtime.NativeRuntimeApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
)

package org.plos_clan.cpos.mem

import kotlin.native.runtime.GC
import bridge.runtime_vm_add_region
import kotlinx.cinterop.UByteVar
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import org.plos_clan.cpos.utils.hex
import org.plos_clan.cpos.utils.toPointer

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

object RuntimeMemory {
    private const val TARGET_POOL_BYTES = 1_073_741_824uL
    private const val MINIMUM_POOL_BYTES = 67_108_864uL
    private val poolChunkFrames = ulongArrayOf(65_536uL, 16_384uL)

    private var initialized = false
    private var poolBytes = 0uL

    fun initialize(): Boolean {
        if (initialized) return true
        if (!BuddyFrameAllocator.isReady || !Hhdm.isReady) {
            println("Runtime memory: Buddy or HHDM is unavailable")
            return false
        }

        for (frameCount in poolChunkFrames) {
            while (poolBytes < TARGET_POOL_BYTES && addPool(frameCount)) {
                // Keep adding independently backed regions until this chunk size no longer fits.
            }
        }
        initialized = poolBytes >= MINIMUM_POOL_BYTES
        if (!initialized) {
            println("Runtime memory: failed to reserve a Buddy-backed pool")
        }
        return initialized
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

    private fun addPool(frameCount: ULong): Boolean {
        val physicalBase = BuddyFrameAllocator.allocateFrames(frameCount) ?: return false
        val byteLength = frameCount * PAGE_SIZE_BYTES
        val virtualBase = Hhdm.toVirtual(physicalBase)
        val pointer = virtualBase.toPointer<UByteVar>()

        if (pointer == null || !runtime_vm_add_region(pointer, byteLength)) {
            BuddyFrameAllocator.freeFrames(physicalBase, frameCount)
            return false
        }

        poolBytes += byteLength
        println(
            "Runtime memory: added ${byteLength / 1_048_576uL} MiB " +
                "pool physical=${physicalBase.hex()} virtual=${virtualBase.hex()}",
        )
        return true
    }
}
