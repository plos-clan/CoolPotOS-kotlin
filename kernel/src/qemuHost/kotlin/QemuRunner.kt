import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.OutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

internal data class QemuConfiguration(
    val mode: QemuMode,
    val command: List<String>,
    val directory: File,
    val kernel: File,
    val rootfs: File?,
    val boots: Int,
    val timeoutSeconds: Long,
    val buildType: String,
    val gc: String,
) {
    init {
        require(command.isNotEmpty()) { "A QEMU command is required" }
        require(boots > 0 && timeoutSeconds > 0) { "Boot count and timeout must be positive" }
        require(mode == QemuMode.SYSTEM || boots == 1) { "Tests run once" }
        require(kernel.isFile && (rootfs == null || rootfs.isFile)) { "Input image is missing" }
    }

    companion object {
        fun parse(arguments: Array<String>): QemuConfiguration {
            val separator = arguments.indexOf("--")
            require(separator >= 0 && separator % 2 == 0) { "Expected options followed by -- and a QEMU command" }
            val options = arguments.take(separator).chunked(2)
            val settings = options.associate { (name, value) -> name to value }
            val names = setOf(
                "--mode", "--output", "--kernel", "--rootfs",
                "--boots", "--timeout", "--build-type", "--gc",
            )
            require(settings.size == options.size && settings.keys.all { it in names }) {
                "Unknown or duplicate QEMU option"
            }
            return QemuConfiguration(
                mode = QemuMode.valueOf(settings.getValue("--mode").uppercase()),
                command = arguments.drop(separator + 1),
                directory = File(settings.getValue("--output")),
                kernel = File(settings.getValue("--kernel")),
                rootfs = settings["--rootfs"]?.let(::File),
                boots = settings.getValue("--boots").toInt(),
                timeoutSeconds = settings.getValue("--timeout").toLong(),
                buildType = settings.getValue("--build-type"),
                gc = settings.getValue("--gc"),
            )
        }
    }
}

internal class QemuRunner(private val config: QemuConfiguration) {
    fun run(): Boolean {
        val directory = config.directory
        directory.deleteRecursively()
        check(directory.mkdirs()) { "Cannot create $directory" }
        val metadata = buildJsonObject {
            put("buildType", config.buildType)
            put("gc", config.gc)
            put("command", JsonArray(config.command.map(::JsonPrimitive)))
            for ((name, file) in listOf("kernel" to config.kernel, "rootfs" to config.rootfs)) {
                if (file == null) continue
                val digest = MessageDigest.getInstance("SHA-256")
                DigestInputStream(file.inputStream(), digest).use { input ->
                    input.transferTo(OutputStream.nullOutputStream())
                }
                put("${name}Sha256", digest.digest().joinToString("") { "%02x".format(it) })
            }
        }
        val combined = QemuReport(config.mode)
        var completed = 0
        for (boot in 1..config.boots) {
            val output = if (config.mode == QemuMode.SYSTEM) directory.resolve("boot-$boot") else directory
            output.mkdirs()
            val report = execute(output, metadata)
            combined.merge(report, boot)
            completed = boot
            val status = if (report.successful) "PASSED" else "FAILED"
            println("QEMU ${config.mode.protocol}: boot $boot/${config.boots} $status; $output")
            if (!report.successful) break
        }
        if (config.mode == QemuMode.SYSTEM) {
            val summary = buildJsonObject {
                metadata.forEach { (key, value) -> put(key, value) }
                put("boots", completed)
                put("requestedBoots", config.boots)
            }
            combined.write(directory.resolve(config.mode.reportFile), summary)
        }
        return combined.successful
    }

    private fun execute(output: File, metadata: JsonObject): QemuReport {
        val report = QemuReport(config.mode)
        val log = output.resolve("serial.log")
        val start = System.nanoTime()
        var exitCode: Int? = null
        try {
            val process = ProcessBuilder(config.command)
                .redirectErrorStream(true)
                .redirectOutput(log)
                .start()
            try {
                process.outputStream.close()
                if (process.waitFor(config.timeoutSeconds, TimeUnit.SECONDS)) {
                    exitCode = process.exitValue()
                } else {
                    report.errors += "QEMU timed out after ${config.timeoutSeconds} seconds"
                }
            } finally {
                if (process.isAlive) {
                    process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
                    process.destroyForcibly().waitFor()
                }
            }
        } catch (failure: Exception) {
            report.errors += failure.toString()
        }
        if (log.exists()) report.read(log) else report.errors += "QEMU produced no serial log"
        if (exitCode != config.mode.exitCode) {
            report.errors += "QEMU exit status: $exitCode, expected ${config.mode.exitCode}"
        }
        val details = buildJsonObject {
            metadata.forEach { (key, value) -> put(key, value) }
            put("returncode", exitCode)
            put("elapsedSeconds", (System.nanoTime() - start) / 1e9)
        }
        val filename = if (config.mode == QemuMode.SYSTEM) "boot.json" else config.mode.reportFile
        report.write(output.resolve(filename), details)
        if (config.mode == QemuMode.TEST) report.writeJUnit(output.resolve("junit.xml"))
        if (!report.successful) {
            report.errors.forEach(System.err::println)
            if (log.isFile) log.readLines().takeLast(40).forEach(System.err::println)
        }
        return report
    }
}

fun main(arguments: Array<String>) {
    val config = QemuConfiguration.parse(arguments)
    if (!QemuRunner(config).run()) exitProcess(1)
}
