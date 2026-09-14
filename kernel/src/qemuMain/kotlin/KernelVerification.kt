@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

import kotlin.io.encoding.Base64
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import org.plos_clan.cpos.coroutines.KernelCoroutines
import org.plos_clan.cpos.drivers.char.serialPrint
import org.plos_clan.cpos.drivers.TscClock
import org.plos_clan.cpos.utils.Cmdline

class KernelVerification(private val mode: String) {
    private var completed = 0
    private var failures = 0
    private var expected = 0
    val filter = Regex(Cmdline["verify.filter"] ?: ".*")

    fun begin(count: Int) {
        require(count > 0) { "No verification cases selected" }
        expected = count
        emit("begin", mode, count.toString(), KotlinVersion.CURRENT.toString())
    }

    fun case(name: String, ignored: Boolean = false, body: () -> Unit) {
        emit("start", name)
        val start = TscClock.nanoTime()
        try {
            if (!ignored) body()
            emit(if (ignored) "skip" else "pass", name, (TscClock.nanoTime() - start).toString())
        } catch (failure: Throwable) {
            failures++
            emit("fail", name, failure.toString())
        }
        completed++
    }

    fun emit(vararg fields: String) {
        val bytes = fields.joinToString("\t", prefix = "CPOS\t", postfix = "\n") {
            Base64.encode(it.encodeToByteArray())
        }.encodeToByteArray()
        bytes.usePinned { serialPrint(it.addressOf(0), bytes.size.toULong()) }
    }

    companion object {
        fun start(mode: String, body: (KernelVerification) -> Unit) = KernelBoot.start {
            val verification = KernelVerification(mode)
            KernelCoroutines.launch("qemu-$mode") {
                try {
                    body(verification)
                    check(verification.completed == verification.expected && verification.expected > 0)
                } catch (failure: Throwable) {
                    verification.failures++
                    verification.emit("error", failure.toString())
                }
                verification.emit("end", verification.completed.toString(), verification.failures.toString())
                bridge.io_out32(0xf4u, if (verification.failures == 0) 0x10u else 0x11u)
                while (true) bridge.asm_pause()
            }
        }
    }
}
