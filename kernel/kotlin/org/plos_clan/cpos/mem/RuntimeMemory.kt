@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.mem

import bridge.runtime_vm_add_region
import kotlinx.cinterop.UByteVar
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import org.plos_clan.cpos.utils.hex
import org.plos_clan.cpos.utils.toPointer

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
