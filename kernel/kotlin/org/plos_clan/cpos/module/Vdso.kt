@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.module

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import org.plos_clan.cpos.mem.AddressSpace
import org.plos_clan.cpos.mem.MEMORY_REGION_EXECUTABLE
import org.plos_clan.cpos.mem.MEMORY_REGION_READABLE
import org.plos_clan.cpos.mem.MemoryRegion
import org.plos_clan.cpos.mem.MemoryRegionBacking
import org.plos_clan.cpos.mem.MemoryRegionType
import org.plos_clan.cpos.mem.USER_MMAP_END
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import org.plos_clan.cpos.utils.alignUp

object Vdso : MemoryRegionBacking() {
    private var image = ByteArray(0)

    override val immutablePageSource: Any
        get() = this

    fun initialize(): Boolean = memScoped {
        val embedded = alloc<bridge.vdso_image>()
        if (!bridge.runtime_vdso_initialize(embedded.ptr)) return@memScoped false

        val size = embedded.size
        val data = embedded.data ?: return@memScoped false
        if (size == 0uL || size > Int.MAX_VALUE.toULong()) return@memScoped false

        image = data.reinterpret<ByteVar>().readBytes(size.toInt())
        true
    }

    fun install(addressSpace: AddressSpace): ULong? {
        if (image.isEmpty()) return null
        val length = image.size.toULong().alignUp(PAGE_SIZE_BYTES) ?: return null
        val installed = addressSpace.insert(
            MemoryRegion(
                start = USER_MMAP_END,
                end = USER_MMAP_END + length,
                access = MEMORY_REGION_READABLE or MEMORY_REGION_EXECUTABLE,
                name = "[vdso]",
                type = MemoryRegionType.VDSO,
                shared = true,
                backing = this,
                sharedIdentity = this,
            ),
        )
        return USER_MMAP_END.takeIf { installed }
    }

    override fun read(offset: ULong, destination: ByteArray): Int {
        if (offset >= image.size.toULong()) return 0
        val start = offset.toInt()
        val count = minOf(destination.size, image.size - start)
        image.copyInto(destination, startIndex = start, endIndex = start + count)
        return count
    }

    override fun close() = Unit
}
