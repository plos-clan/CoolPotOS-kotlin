package org.plos_clan.cpos.fs.sock

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.utils.LittleEndianBuffer

class ClassicBpfTest {
    @Test
    fun filtersUdpDestinationUsingVariableIpv4HeaderLength() {
        val program = program(
            Instruction(0x30, operand = 9u),
            Instruction(0x15, falseOffset = 4, operand = 17u),
            Instruction(0xB1),
            Instruction(0x48, operand = 2u),
            Instruction(0x15, falseOffset = 1, operand = 68u),
            Instruction(0x06, operand = UShort.MAX_VALUE.toUInt()),
            Instruction(0x06),
        )
        val packet = ByteArray(32).also {
            it[0] = 0x45.toByte()
            it[9] = 17.toByte()
            it[22] = 0.toByte()
            it[23] = 68.toByte()
        }

        assertEquals(packet.size, program.filter(packet))
        packet[23] = 67.toByte()
        assertEquals(0, program.filter(packet))
    }

    @Test
    fun returnValueLimitsCapturedPacketLength() {
        val program = program(Instruction(0x06, operand = 3u))

        assertEquals(3, program.filter(ByteArray(16)))
    }

    @Test
    fun rejectsUnsafePrograms() {
        assertIs<VfsResult.Err>(decode(Instruction(0x05, operand = UInt.MAX_VALUE)))
        assertIs<VfsResult.Err>(decode(Instruction(0x34)))
        assertIs<VfsResult.Err>(decode(Instruction(0x00)))
    }

    private fun program(vararg instructions: Instruction): ClassicBpfProgram =
        assertIs<VfsResult.Ok<ClassicBpfProgram>>(decode(*instructions)).value

    private fun decode(vararg instructions: Instruction): VfsResult<ClassicBpfProgram> {
        val bytes = ByteArray(instructions.size * ClassicBpfProgram.INSTRUCTION_SIZE)
        val output = LittleEndianBuffer(bytes)
        instructions.forEachIndexed { index, instruction ->
            val offset = index * ClassicBpfProgram.INSTRUCTION_SIZE
            output.writeU16(offset, instruction.code.toUShort())
            output.writeU8(offset + 2, instruction.trueOffset.toUByte())
            output.writeU8(offset + 3, instruction.falseOffset.toUByte())
            output.writeU32(offset + 4, instruction.operand)
        }
        return ClassicBpfProgram.decode(bytes)
    }

    private data class Instruction(
        val code: Int,
        val trueOffset: Int = 0,
        val falseOffset: Int = 0,
        val operand: UInt = 0u,
    )
}
