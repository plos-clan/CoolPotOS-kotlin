import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QemuReportTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun read(mode: QemuMode, vararg records: List<String>): QemuReport {
        val log = temporary.newFile()
        log.writeText(records.joinToString("\n", prefix = "kernel output\n") { record ->
            record.joinToString("\t", prefix = "CPOS\t") { Base64.getEncoder().encodeToString(it.toByteArray()) }
        })
        return QemuReport(mode).also { it.read(log) }
    }

    private fun benchmark(value: String) = read(QemuMode.SYSTEM,
        listOf("begin", "system", "1", "2.4.10"), listOf("start", "memory"),
        listOf("metric", "memory", "bytes", "1"), listOf("sample", "memory", "0", value),
        listOf("pass", "memory", "1"), listOf("end", "1", "0"),
    )

    @Test fun combinesBootSamplesWithoutSplittingUnits() {
        val report = QemuReport(QemuMode.SYSTEM)
        report.merge(benchmark("1024"), 1)
        report.merge(benchmark("2048"), 2)
        assertTrue(report.successful)
        val output = temporary.newFile()
        report.write(output, buildJsonObject { put("boots", 2) })
        val json = Json.parseToJsonElement(output.readText()).jsonObject
        val result = json.getValue("results").jsonArray.single().jsonObject
        val samples = result.getValue("samples").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("1024.0", "2048.0"), samples)
    }

    @Test fun rejectsIncompleteAndNonfiniteSamples() {
        assertFalse(benchmark("NaN").successful)
        assertFalse(benchmark("-1").successful)
        assertFalse(read(QemuMode.SYSTEM, listOf("begin", "system", "1", "2.4.10")).successful)
    }

    @Test fun preservesFailuresAndEscapesJUnit() {
        val report = read(QemuMode.TEST,
            listOf("begin", "test", "1", "2.4.10"), listOf("start", "suite.case"),
            listOf("fail", "suite.case", "expected <actual> & valid\u0000"), listOf("end", "1", "1"),
        )
        assertFalse(report.successful)
        val xml = temporary.newFile()
        report.writeJUnit(xml)
        assertTrue("&lt;actual&gt; &amp; valid" in xml.readText())
        val document = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml)
        assertEquals(1, document.getElementsByTagName("failure").length)
    }

    @Test fun onlyTestsCanBeSkipped() {
        for (mode in QemuMode.entries) {
            val report = read(mode,
                listOf("begin", mode.protocol, "1", "2.4.10"), listOf("start", "case"),
                listOf("skip", "case", "0"), listOf("end", "1", "0"),
            )
            assertEquals(mode == QemuMode.TEST, report.successful)
        }
    }

    @Test fun failureCannotBeMaskedByACompletedBoot() {
        val report = QemuReport(QemuMode.SYSTEM)
        val failed = benchmark("1024")
        failed.errors += "QEMU timed out"
        report.merge(failed, 1)
        report.merge(benchmark("2048"), 2)
        assertFalse(report.successful)
        assertTrue(report.errors.any { "timed out" in it })
    }

    @Test fun preservesBenchmarkFailureAcrossBoots() {
        for (failedBoot in 1..2) {
            val report = QemuReport(QemuMode.SYSTEM)
            val failed = read(QemuMode.SYSTEM,
                listOf("begin", "system", "1", "2.4.10"), listOf("start", "memory"),
                listOf("metric", "memory", "bytes", "1"),
                listOf("fail", "memory", "measurement failed"), listOf("end", "1", "1"),
            )
            for (boot in 1..2) report.merge(if (boot == failedBoot) failed else benchmark("1024"), boot)
            assertFalse(report.successful)
            val output = temporary.newFile()
            report.write(output, buildJsonObject {})
            val json = Json.parseToJsonElement(output.readText()).jsonObject
            assertTrue(json.getValue("errors").jsonArray.any { "measurement failed" in it.jsonPrimitive.content })
        }
    }
}
