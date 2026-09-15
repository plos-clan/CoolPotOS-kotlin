import kotlin.io.encoding.Base64

class VerificationReport(
    private val mode: String,
    private val clock: () -> Long,
    private val output: (String) -> Unit,
) {
    private var completed = 0
    private var failures = 0

    fun run(count: Int, body: () -> Unit): Boolean {
        emit("begin", mode, count.toString(), KotlinVersion.CURRENT.toString())
        try {
            require(count > 0) { "No verification cases selected" }
            body()
            check(completed == count) { "Incomplete verification: $completed/$count" }
        } catch (failure: Throwable) {
            failures++
            emit("error", failure.toString())
        }
        emit("end", completed.toString(), failures.toString())
        return failures == 0
    }

    fun case(name: String, ignored: Boolean = false, body: () -> Unit) {
        emit("start", name)
        val start = clock()
        try {
            if (!ignored) body()
            emit(if (ignored) "skip" else "pass", name, (clock() - start).toString())
        } catch (failure: Throwable) {
            failures++
            emit("fail", name, failure.toString())
        }
        completed++
    }

    fun benchmark(name: String, unit: String, body: () -> DoubleArray) = case(name) {
        val samples = body()
        require(samples.isNotEmpty() && samples.all { it.isFinite() && it >= 0 })
        emit("metric", name, unit, samples.size.toString())
        samples.forEachIndexed { index, value -> emit("sample", name, index.toString(), value.toString()) }
    }

    private fun emit(vararg fields: String) {
        val encodedFields = fields.map { field -> Base64.encode(field.encodeToByteArray()) }
        val joinedString = encodedFields.joinToString("\t", prefix = "CPOS\t", postfix = "\n")
        output(joinedString)
    }
}
