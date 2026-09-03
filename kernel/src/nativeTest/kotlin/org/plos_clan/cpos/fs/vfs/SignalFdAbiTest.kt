package org.plos_clan.cpos.fs.vfs

import org.plos_clan.cpos.tasks.Signal
import org.plos_clan.cpos.tasks.SignalInfo
import org.plos_clan.cpos.tasks.SignalPayload
import org.plos_clan.cpos.utils.LittleEndianBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SignalFdAbiTest {
    @Test
    fun queuedSenderUsesTheLinuxSignalfdLayout() {
        val bytes = ByteArray(SignalFdSigInfoAbi.SIZE) { 0x5a }
        val info = SignalInfo(
            signal = checkNotNull(Signal.from(35)),
            error = -7,
            code = SignalInfo.QUEUED,
            payload = SignalPayload.Sender(
                pid = 0x1020_3040,
                uid = 0x5060_7080,
                value = 0x1122_3344_5566_7788uL,
            ),
        )

        SignalFdSigInfoAbi.write(bytes, info)
        val encoded = LittleEndianBuffer(bytes)
        assertEquals(35u, encoded.readU32(0))
        assertEquals((-7).toUInt(), encoded.readU32(4))
        assertEquals(SignalInfo.QUEUED.toUInt(), encoded.readU32(8))
        assertEquals(0x1020_3040u, encoded.readU32(12))
        assertEquals(0x5060_7080u, encoded.readU32(16))
        assertEquals(0x5566_7788u, encoded.readU32(44))
        assertEquals(0x1122_3344_5566_7788uL, encoded.readU64(48))
        assertTrue(bytes.sliceArray(100 until SignalFdSigInfoAbi.SIZE).all { it == 0.toByte() })
    }

    @Test
    fun childAndFaultPayloadsUseDedicatedSignalfdFields() {
        val child = encode(
            SignalInfo(
                signal = Signal.CHILD,
                code = SignalInfo.CHILD_EXITED,
                payload = SignalPayload.Child(
                    pid = 42,
                    uid = 1000,
                    status = 9,
                    userTime = 123,
                    systemTime = 456,
                ),
            ),
        )
        assertEquals(42u, child.readU32(12))
        assertEquals(1000u, child.readU32(16))
        assertEquals(9u, child.readU32(40))
        assertEquals(123uL, child.readU64(56))
        assertEquals(456uL, child.readU64(64))

        val address = 0xfedc_ba98_7654_3210uL
        val fault = encode(
            SignalInfo(
                signal = Signal.SEGV,
                code = SignalInfo.SEGMENT_MAPPING_ERROR,
                payload = SignalPayload.Fault(address),
            ),
        )
        assertEquals(address, fault.readU64(72))
    }

    @Test
    fun rawNativeSiginfoUnionsAreTranslatedInsteadOfCopied() {
        val timerPayload = ByteArray(112).also { bytes ->
            LittleEndianBuffer(bytes).apply {
                writeU32(0, 17u)
                writeU32(4, 3u)
                writeU64(8, 0xaabb_ccdd_eeff_0011uL)
            }
        }
        val timer = encode(
            SignalInfo(
                signal = checkNotNull(Signal.from(35)),
                code = -2,
                payload = SignalPayload.Raw(timerPayload),
            ),
        )
        assertEquals(17u, timer.readU32(24))
        assertEquals(3u, timer.readU32(32))
        assertEquals(0xeeff_0011u, timer.readU32(44))
        assertEquals(0xaabb_ccdd_eeff_0011uL, timer.readU64(48))

        val syscallPayload = ByteArray(112).also { bytes ->
            LittleEndianBuffer(bytes).apply {
                writeU64(0, 0x1234_5678_9abcuL)
                writeU32(8, 289u)
                writeU32(12, 0xc000_003eu)
            }
        }
        val syscall = encode(
            SignalInfo(
                signal = Signal.SYS,
                code = 1,
                payload = SignalPayload.Raw(syscallPayload),
            ),
        )
        assertEquals(289u, syscall.readU32(84))
        assertEquals(0x1234_5678_9abcuL, syscall.readU64(88))
        assertEquals(0xc000_003eu, syscall.readU32(96))
    }

    private fun encode(info: SignalInfo): LittleEndianBuffer =
        LittleEndianBuffer(ByteArray(SignalFdSigInfoAbi.SIZE).also {
            SignalFdSigInfoAbi.write(it, info)
        })
}
