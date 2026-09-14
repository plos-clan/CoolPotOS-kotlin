@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

import kotlin.native.internal.test.GeneratedSuites

@CName("kernel_main")
fun kernelMain() = KernelVerification.start("test") { report ->
    val suites = GeneratedSuites.suites.associateWith { suite ->
        suite.testCases.values.filter { report.filter.containsMatchIn("${suite.name}.${it.name}") }
    }.filterValues { it.isNotEmpty() }
    report.begin(suites.values.sumOf { it.size })
    for ((suite, cases) in suites) {
        val active = !suite.ignored && cases.any { !it.ignored }
        try {
            if (active) suite.doBeforeClass()
            for (case in cases) {
                report.case("${suite.name}.${case.name}", suite.ignored || case.ignored, case::run)
            }
        } finally {
            if (active) suite.doAfterClass()
        }
    }
}
