@file:OptIn(
    kotlin.experimental.ExperimentalNativeApi::class,
    kotlinx.benchmark.internal.KotlinxBenchmarkRuntimeInternalApi::class,
)

import kotlinx.benchmark.BenchmarkDescriptor
import kotlinx.benchmark.BenchmarkDescriptorWithBlackholeParameter
import kotlinx.benchmark.BenchmarkDescriptorWithNoBlackholeParameter
import kotlinx.benchmark.SuiteExecutorBase
import kotlinx.benchmark.generated.declareAndExecuteSuites
import kotlinx.benchmark.runWithParameters
import org.plos_clan.cpos.drivers.TscClock
import org.plos_clan.cpos.utils.Cmdline

@CName("kernel_main")
fun kernelMain() = KernelVerification.start("benchmark") { report ->
    declareAndExecuteSuites(KernelBenchmarkExecutor(report))
}

private class KernelBenchmarkExecutor(private val report: KernelVerification) : SuiteExecutorBase() {
    private val warmups = requireNotNull(Cmdline.int("verify.warmups"))
    private val iterations = requireNotNull(Cmdline.int("verify.iterations"))
    private val duration = requireNotNull(Cmdline.uLong("verify.millis")).also {
        require(it in 1uL..ULong.MAX_VALUE / 1_000_000uL)
    } * 1_000_000uL

    override fun run() {
        require(warmups >= 0 && iterations > 0 && warmups <= Int.MAX_VALUE - iterations)
        val selected = suites.flatMap { it.benchmarks }.filter { report.filter.containsMatchIn(it.name) }
        report.begin(selected.sumOf { benchmark ->
            benchmark.suite.parameters.fold(1) { count, name ->
                count * benchmark.suite.defaultParameters.getValue(name).size
            }
        })
        report.emit("settings", warmups.toString(), iterations.toString(), duration.toString())
        for (benchmark in selected) execute(benchmark)
    }

    private fun <T> execute(benchmark: BenchmarkDescriptor<T>) {
        val suite = benchmark.suite
        runWithParameters(suite.parameters, emptyMap(), suite.defaultParameters) { parameters ->
            val name = benchmark.name + parameters.entries.joinToString(",", prefix = "[", postfix = "]") { "${it.key}=${it.value}" }
            report.case(name) {
                val instance = suite.factory()
                try {
                    suite.parametrize(instance, parameters)
                    suite.setup(instance)
                    val blackhole = benchmark.blackhole
                    when (benchmark) {
                        is BenchmarkDescriptorWithNoBlackholeParameter -> {
                            val function = benchmark.function
                            measure(name) { blackhole.consume(function(instance)) }
                        }
                        is BenchmarkDescriptorWithBlackholeParameter -> {
                            val function = benchmark.function
                            measure(name) { blackhole.consume(function(instance, blackhole)) }
                        }
                        else -> error("Unsupported benchmark descriptor: ${benchmark::class}")
                    }
                } finally {
                    suite.teardown(instance)
                }
            }
        }
    }

    private inline fun measure(name: String, operation: () -> Unit) {
        var batch = 1
        repeat(warmups + iterations) { iteration ->
            var operations = 0L
            val start = TscClock.nanoTime()
            var elapsed: ULong
            do {
                repeat(batch) { operation() }
                operations += batch
                elapsed = TscClock.nanoTime() - start
                if (iteration < warmups && elapsed < duration / 16u && batch <= Int.MAX_VALUE / 2) batch *= 2
            } while (elapsed < duration)
            if (iteration >= warmups) {
                report.emit("sample", name, (iteration - warmups).toString(), operations.toString(), elapsed.toString())
            }
        }
    }
}
