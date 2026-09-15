@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlin.time.Duration.Companion.seconds
import platform.linux.RB_POWER_OFF
import platform.linux.reboot
import platform.posix.fflush
import platform.posix.stdout

fun main() {
    val boot = Snapshot.bootNanoseconds()
    val commandLine = SystemFileSystem.source(Path("/proc/cmdline")).buffered().use { it.readString() }
    require("cpos.benchmark" in commandLine.trim().split(' ')) { "Not a benchmark boot" }
    val settings = BenchmarkSettings(
        warmups = 5,
        iterations = 10,
        duration = 1.seconds,
    )
    try {
        val transferBytes = 64 * 1024 * 1024
        val blockSize = 512
        val transfer = "yes | dd of=/dev/null bs=$blockSize count=${transferBytes / blockSize} " +
            "iflag=fullblock status=none"
        val completed = "test \"${'$'}{PIPESTATUS[*]}\" = \"141 0\""
        val benchmarks = listOf(
            Snapshot("boot.toBenchmarkService", "ns") { boot.toDouble() },
            Snapshot("boot.usedMemory", "bytes", Snapshot::usedMemory),
            GetPidBenchmark,
            ThreadWakeupBenchmark(),
            CommandBenchmark(
                "pipe.yesDd[bytes=$transferBytes]",
                "/usr/bin/bash -c '$transfer; $completed'",
            ),
        )
        val report = VerificationReport("system", Snapshot::bootNanoseconds) { record ->
            print("\n$record")
            fflush(stdout)
        }
        report.run(benchmarks.size) { benchmarks.forEach { it.run(report, settings) } }
    } finally {
        check(reboot(RB_POWER_OFF) == 0) { "Cannot power off the benchmark guest" }
    }
}
