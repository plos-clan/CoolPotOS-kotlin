package org.plos_clan.cpos.mem

import org.plos_clan.cpos.mem.addressspace.AddressSpace
import org.plos_clan.cpos.mem.addressspace.MEMORY_REGION_READABLE
import org.plos_clan.cpos.mem.addressspace.MEMORY_REGION_WRITABLE
import org.plos_clan.cpos.mem.addressspace.MemoryMapRequest
import org.plos_clan.cpos.mem.addressspace.MemoryMapResult
import org.plos_clan.cpos.mem.addressspace.MemoryRegionType
import org.plos_clan.cpos.mem.page.KernelPageDirectory

internal class MappedUserMemory(byteCount: Int) : AutoCloseable {
    private val addressSpace = AddressSpace.user(KernelPageDirectory.getDirectory().createUserDirectory())
    private val address = try {
        require(byteCount > 0)
        val mapped = addressSpace.map(
            MemoryMapRequest(
                hint = 0uL,
                length = byteCount.toULong(),
                access = MEMORY_REGION_READABLE or MEMORY_REGION_WRITABLE,
                fixed = false,
                noReplace = false,
                shared = false,
                type = MemoryRegionType.ANONYMOUS,
                populate = true,
            ),
        )
        check(mapped is MemoryMapResult.Ok) { "Cannot map benchmark memory: $mapped" }
        check(UserMemory(addressSpace, mapped.value).fill(0, byteCount, 0x5a) == byteCount)
        mapped.value
    } catch (failure: Throwable) {
        addressSpace.release()
        throw failure
    }

    fun create(): UserMemory = UserMemory(addressSpace, address)

    override fun close() = addressSpace.release()
}
