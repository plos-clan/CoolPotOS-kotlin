import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.Base64
import javax.xml.stream.XMLOutputFactory

enum class QemuMode(val reportFile: String, val exitCode: Int) {
    TEST("results.json", 33),
    SYSTEM("system.json", 0);

    val protocol: String get() = name.lowercase()
}

class QemuReport(val mode: QemuMode) {
    private enum class Outcome(val junitTag: String?) {
        RUNNING("error"), PASS(null), SKIP("skipped"), FAIL("failure");

        val protocol: String get() = name.lowercase()
    }

    private data class Case(val name: String) {
        var status = Outcome.RUNNING
        var nanoseconds = 0L
        var failure: String? = null
        var unit: String? = null
        var expectedSamples = 0
        val samples = mutableListOf<Double>()

        fun accept(mode: QemuMode, event: String, values: List<String>) {
            require(values.first() == name)
            when (event) {
                "metric" -> {
                    require(mode == QemuMode.SYSTEM && values.size == 3 && unit == null)
                    unit = values[1].also { require(it.isNotBlank()) }
                    expectedSamples = values[2].toInt().also { require(it > 0) }
                }
                "sample" -> {
                    require(values.size == 3 && unit != null && values[1].toInt() == samples.size)
                    samples += values[2].toDouble().also { require(it.isFinite() && it >= 0) }
                }
                "pass", "skip", "fail" -> {
                    require(values.size == 2)
                    val outcome = Outcome.valueOf(event.uppercase())
                    if (outcome == Outcome.FAIL) failure = values[1]
                    else nanoseconds = values[1].toLong().also { require(it >= 0) }
                    if (mode == QemuMode.SYSTEM && outcome == Outcome.PASS) {
                        require(expectedSamples > 0 && samples.size == expectedSamples)
                    }
                    status = outcome
                }
                else -> error("Unknown event $event")
            }
        }

        fun json(mode: QemuMode) = buildJsonObject {
            put("name", name)
            put("samples", JsonArray(samples.map(::JsonPrimitive)))
            if (mode == QemuMode.SYSTEM) {
                put("unit", requireNotNull(unit))
            } else {
                put("status", status.protocol)
                put("nanoseconds", nanoseconds)
                failure?.let { put("failure", it) }
            }
        }
    }

    private var expected = 0
    private var active: Case? = null
    private var finished = false
    private val cases = linkedMapOf<String, Case>()
    val errors = mutableListOf<String>()
    var kotlinVersion: String? = null
        private set

    val successful: Boolean
        get() = errors.isEmpty() && finished && cases.values.all { case ->
            case.status == Outcome.PASS || mode == QemuMode.TEST && case.status == Outcome.SKIP
        }

    fun read(log: File) {
        val decoder = Base64.getDecoder()
        log.forEachLine { line ->
            if (!line.startsWith("CPOS\t")) return@forEachLine
            try {
                val fields = line.split('\t').drop(1).map { field ->
                    decoder.decode(field).toString(Charsets.UTF_8)
                }
                accept(fields)
            } catch (failure: Exception) {
                errors += "Invalid verification record: $line: ${failure.message}"
            }
        }
        val incomplete = !finished || active != null || cases.size != expected || expected <= 0
        if (incomplete) errors += "Incomplete verification: ${cases.size}/$expected, active=${active?.name}"
    }

    private fun accept(fields: List<String>) {
        check(!finished) { "Record after completion" }
        val event = fields.first()
        val values = fields.drop(1)
        when (event) {
            "begin" -> {
                require(values.size == 3 && values[0] == mode.protocol && expected == 0)
                expected = values[1].toInt().also { require(it > 0) }
                kotlinVersion = values[2]
            }
            "error" -> errors.add(values.single())
            "end" -> {
                require(values.size == 2 && active == null)
                val completed = values[0].toInt()
                val failures = cases.values.count { it.status == Outcome.FAIL } + errors.size
                require(completed == expected && cases.size == expected)
                require(values[1].toInt() == failures)
                finished = true
            }
            "start" -> {
                val name = values.single()
                require(expected > 0 && active == null && name !in cases)
                active = Case(name).also { cases[name] = it }
            }
            else -> {
                val case = requireNotNull(active)
                case.accept(mode, event, values)
                if (case.status != Outcome.RUNNING) active = null
            }
        }
    }

    fun merge(report: QemuReport, boot: Int) {
        require(mode == report.mode)
        errors += report.errors.map { "Boot $boot: $it" }
        if (boot > 1 && cases.keys != report.cases.keys) errors += "Boot $boot: benchmark suite changed"
        for ((name, case) in report.cases) {
            val previous = cases[name]
            if (previous == null) {
                cases[name] = case
                continue
            }
            if (previous.unit != case.unit) errors += "Boot $boot: unit changed for $name"
            previous.samples += case.samples
            if (previous.status == Outcome.PASS) {
                previous.status = case.status
                previous.failure = case.failure
            }
        }
        expected = report.expected
        finished = report.finished
        kotlinVersion = report.kotlinVersion
    }

    fun write(file: File, metadata: JsonObject) {
        val results = cases.values.filter { mode == QemuMode.TEST || it.status == Outcome.PASS }
        val failures = if (mode == QemuMode.SYSTEM) {
            cases.values.mapNotNull { case -> case.failure?.let { "${case.name}: $it" } }
        } else emptyList()
        val document = buildJsonObject {
            put("mode", mode.protocol)
            put("metadata", buildJsonObject {
                metadata.forEach { (key, value) -> put(key, value) }
                kotlinVersion?.let { put("kotlin", it) }
            })
            put("expected", expected)
            put("results", JsonArray(results.map { it.json(mode) }))
            put("errors", JsonArray((errors + failures).map(::JsonPrimitive)))
        }
        file.writeText(Json.encodeToString(document))
    }

    fun writeJUnit(file: File) {
        val invalid = Regex("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f\\ufffe\\uffff]")
        val counts = cases.values.groupingBy { it.status }.eachCount()
        val unfinished = counts.getOrDefault(Outcome.RUNNING, 0)
        file.bufferedWriter().use { output ->
            val xml = XMLOutputFactory.newFactory().createXMLStreamWriter(output)
            xml.writeStartDocument()
            xml.writeStartElement("testsuite")
            xml.writeAttribute("name", "qemuTest")
            xml.writeAttribute("tests", (cases.size + errors.size).toString())
            xml.writeAttribute("failures", counts.getOrDefault(Outcome.FAIL, 0).toString())
            xml.writeAttribute("errors", (errors.size + unfinished).toString())
            xml.writeAttribute("skipped", counts.getOrDefault(Outcome.SKIP, 0).toString())
            for (case in cases.values) {
                val name = invalid.replace(case.name.substringAfterLast('.'), "\uFFFD")
                val group = invalid.replace(case.name.substringBeforeLast('.', ""), "\uFFFD")
                xml.writeStartElement("testcase")
                xml.writeAttribute("name", name)
                xml.writeAttribute("classname", group)
                xml.writeAttribute("time", (case.nanoseconds / 1e9).toString())
                case.status.junitTag?.let { tag ->
                    xml.writeStartElement(tag)
                    xml.writeCharacters(invalid.replace(case.failure ?: "", "\uFFFD"))
                    xml.writeEndElement()
                }
                xml.writeEndElement()
            }
            for (error in errors) {
                xml.writeStartElement("testcase")
                xml.writeAttribute("name", "infrastructure")
                xml.writeStartElement("error")
                xml.writeCharacters(invalid.replace(error, "\uFFFD"))
                xml.writeEndElement()
                xml.writeEndElement()
            }
            xml.writeEndElement()
            xml.writeEndDocument()
            xml.close()
        }
    }
}
