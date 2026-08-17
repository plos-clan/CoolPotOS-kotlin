package org.plos_clan.cpos.mem.addressspace

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES

@State(Scope.Benchmark)
class MemoryRegionMapBenchmark {
    @Param("16", "256", "4096")
    var regionCount = 0

    private lateinit var map: MemoryRegionMap
    private var addresses = ULongArray(0)
    private var cursor = 0

    @Setup
    fun prepare() {
        val stride = PAGE_SIZE_BYTES * 2uL
        val addressLimit = PAGE_SIZE_BYTES + stride * regionCount.toULong()
        map = MemoryRegionMap(PAGE_SIZE_BYTES, addressLimit, addressLimit)
        addresses = ULongArray(regionCount)

        repeat(regionCount) { index ->
            val start = PAGE_SIZE_BYTES + stride * index.toULong()
            val region = MemoryRegion(
                start = start,
                end = start + PAGE_SIZE_BYTES,
                access = MEMORY_REGION_READABLE,
                name = null,
            )
            check(map.insertOwned(region))
            addresses[index] = start + PAGE_SIZE_BYTES / 2uL
        }
        cursor = 0
    }

    @Benchmark
    fun findMappedRegion(): MemoryRegion? {
        val result = map.find(addresses[cursor])
        cursor = if (cursor == addresses.lastIndex) 0 else cursor + 1
        return result
    }

    @Benchmark
    fun findFragmentedUnmappedArea(): ULong? =
        map.findUnmappedArea(0uL, PAGE_SIZE_BYTES * 2uL)
}
