@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)

import kotlin.native.internal.test.GeneratedSuites
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import org.plos_clan.cpos.coroutines.KernelCoroutines
import org.plos_clan.cpos.drivers.TscClock
import org.plos_clan.cpos.drivers.char.serialPrint
import org.plos_clan.cpos.utils.Cmdline

@CName("kernel_main")
fun kernelMain() = KernelBoot.start {
    KernelCoroutines.launch("qemu-test") {
        val filter = Regex(Cmdline["verify.filter"] ?: ".*")
        val suites = GeneratedSuites.suites.associateWith { suite ->
            suite.testCases.values.filter { filter.containsMatchIn("${suite.name}.${it.name}") }
        }.filterValues { it.isNotEmpty() }
        val report = VerificationReport("test", { TscClock.nanoTime().toLong() }) { record ->
            val bytes = record.encodeToByteArray()
            bytes.usePinned { serialPrint(it.addressOf(0), bytes.size.toULong()) }
        }
        val success = report.run(suites.values.sumOf { it.size }) {
            for ((suite, cases) in suites) {
                val active = !suite.ignored && cases.any { !it.ignored }
                try {
                    if (active) suite.doBeforeClass()
                    for (case in cases) {
                        val name = "${suite.name}.${case.name}"
                        report.case(name, suite.ignored || case.ignored, case::run)
                    }
                } finally {
                    if (active) suite.doAfterClass()
                }
            }
        }
        bridge.io_out32(0xf4u, if (success) 0x10u else 0x11u)
        while (true) bridge.asm_pause()
    }
}
