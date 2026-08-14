@file:OptIn(
    kotlin.ExperimentalStdlibApi::class,
    kotlin.native.runtime.NativeRuntimeApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
)

package org.plos_clan.cpos.mem

import bridge.runtime_vm_install
import bridge.runtime_vm_take_released
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import org.plos_clan.cpos.utils.toPointer
import kotlin.native.runtime.GC

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

internal object RuntimeMemory : PhysicalFrameReclaimer {
    private var initialized = false

    fun initialize(): Boolean {
        if (initialized) return true
        if (!BuddyFrameAllocator.isReady || !Hhdm.isReady) {
            println("Runtime memory: Buddy or HHDM is unavailable")
            return false
        }

        if (!runtime_vm_install(runtimeAllocateCallback)) {
            println("Runtime memory: failed to install the physical-memory provider")
            return false
        }
        BuddyFrameAllocator.installReclaimer(this)
        initialized = true
        return true
    }

    internal fun allocate(byteLength: ULong): COpaquePointer? {
        val frameCount = requiredFrames(byteLength)
        if (frameCount == 0uL) {
            return null
        }
        reclaimFrames()

        val physicalAddress = BuddyFrameAllocator.allocateFramesRaw(frameCount)
        if (physicalAddress == INVALID_PHYSICAL_ADDRESS) {
            return null
        }

        return Hhdm.toVirtual(physicalAddress).toPointer<UByteVar>()
    }

    override fun reclaimFrames() {
        while (true) {
            val pointer = runtime_vm_take_released() ?: return
            val byteLength = pointer.reinterpret<ULongVar>()[0]
            check(release(pointer, byteLength)) {
                "Runtime memory provider received an invalid released block"
            }
        }
    }

    private fun release(pointer: COpaquePointer, byteLength: ULong): Boolean {
        val virtualAddress = pointer.rawValue.toLong().toULong()
        val frameCount = requiredFrames(byteLength)
        if (frameCount == 0uL || virtualAddress < Hhdm.offset) {
            return false
        }

        return BuddyFrameAllocator.freeFramesRaw(virtualAddress - Hhdm.offset, frameCount)
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

private fun allocateRuntimeMemory(byteLength: ULong): COpaquePointer? =
    RuntimeMemory.allocate(byteLength)

private val runtimeAllocateCallback = staticCFunction(::allocateRuntimeMemory)
