package org.plos_clan.cpos.syscall

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import org.plos_clan.cpos.fs.DirectoryEntry
import org.plos_clan.cpos.fs.InodeId
import org.plos_clan.cpos.fs.InodeType
import org.plos_clan.cpos.fs.VfsName
import org.plos_clan.cpos.fs.VfsResult

@State(Scope.Benchmark)
class LinuxDirent64Benchmark {
    @Param("1", "128", "2048")
    var entryCount = 0

    private lateinit var entries: List<DirectoryEntry>
    private var outputSize = 0

    @Setup
    fun prepare() {
        val nameResult = VfsName.fromBytes("benchmark".encodeToByteArray())
        check(nameResult is VfsResult.Ok)
        val name = nameResult.value
        entries = List(entryCount) { index ->
            DirectoryEntry(name, InodeId(index.toULong()), InodeType.REGULAR)
        }
        outputSize = LinuxDirent64(entries.first(), 1).recordSize * entryCount
    }

    @Benchmark
    fun serializeBatch(): ByteArray {
        val output = ByteArray(outputSize)
        var offset = 0
        for (index in entries.indices) {
            val record = LinuxDirent64(entries[index], index.toLong() + 1L)
            record.toNativeBytes().copyInto(output, offset)
            offset += record.recordSize
        }
        return output
    }
}
