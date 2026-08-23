package org.plos_clan.cpos.fs

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import org.plos_clan.cpos.fs.vfs.VfsName
import org.plos_clan.cpos.fs.vfs.VfsPathname
import org.plos_clan.cpos.fs.vfs.VfsResult

@State(Scope.Benchmark)
class VfsPathnameBenchmark {
    @Param("1", "8", "64", "512")
    var componentCount = 0

    private lateinit var pathname: VfsPathname

    @Setup
    fun prepare() {
        val path = "segment/".repeat(componentCount).dropLast(1)
        pathname = VfsPathname.fromString(path)
    }

    @Benchmark
    fun parseComponents(): VfsResult<List<VfsName>> = pathname.components()
}
