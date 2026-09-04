package org.plos_clan.cpos.fs.sock

import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.utils.LittleEndianBuffer

internal class ClassicBpfProgram private constructor(
    private val instructions: Array<Instruction>,
    private val memoryWords: Int,
) {
    private data class Instruction(
        val code: Int,
        val trueOffset: Int,
        val falseOffset: Int,
        val operand: UInt,
    )

    fun filter(packet: ByteArray, offset: Int = 0, length: Int = packet.size - offset): Int {
        if (offset < 0 || length < 0 || offset > packet.size - length) return 0
        val memory = if (memoryWords == 0) null else UIntArray(memoryWords)
        var accumulator = 0u
        var index = 0u
        var instructionIndex = 0

        while (instructionIndex < instructions.size) {
            val instruction = instructions[instructionIndex]
            when (instruction.code) {
                LD_IMMEDIATE -> accumulator = instruction.operand
                LD_ABSOLUTE_WORD -> accumulator = load(
                    packet,
                    offset,
                    length,
                    instruction.operand,
                    UInt.SIZE_BYTES,
                ) ?: return 0
                LD_ABSOLUTE_HALF -> accumulator = load(
                    packet,
                    offset,
                    length,
                    instruction.operand,
                    UShort.SIZE_BYTES,
                ) ?: return 0
                LD_ABSOLUTE_BYTE -> accumulator = load(
                    packet,
                    offset,
                    length,
                    instruction.operand,
                    UByte.SIZE_BYTES,
                ) ?: return 0
                LD_INDIRECT_WORD -> accumulator = load(
                    packet,
                    offset,
                    length,
                    instruction.operand + index,
                    UInt.SIZE_BYTES,
                ) ?: return 0
                LD_INDIRECT_HALF -> accumulator = load(
                    packet,
                    offset,
                    length,
                    instruction.operand + index,
                    UShort.SIZE_BYTES,
                ) ?: return 0
                LD_INDIRECT_BYTE -> accumulator = load(
                    packet,
                    offset,
                    length,
                    instruction.operand + index,
                    UByte.SIZE_BYTES,
                ) ?: return 0
                LD_MEMORY -> accumulator = checkNotNull(memory)[instruction.operand.toInt()]
                LD_LENGTH -> accumulator = length.toUInt()
                LDX_IMMEDIATE -> index = instruction.operand
                LDX_MEMORY -> index = checkNotNull(memory)[instruction.operand.toInt()]
                LDX_LENGTH -> index = length.toUInt()
                LDX_HEADER_LENGTH -> index = ((load(
                    packet,
                    offset,
                    length,
                    instruction.operand,
                    UByte.SIZE_BYTES,
                ) ?: return 0) and 0xFu) shl 2
                STORE -> checkNotNull(memory)[instruction.operand.toInt()] = accumulator
                STORE_INDEX -> checkNotNull(memory)[instruction.operand.toInt()] = index
                ADD_CONSTANT -> accumulator += instruction.operand
                ADD_INDEX -> accumulator += index
                SUBTRACT_CONSTANT -> accumulator -= instruction.operand
                SUBTRACT_INDEX -> accumulator -= index
                MULTIPLY_CONSTANT -> accumulator *= instruction.operand
                MULTIPLY_INDEX -> accumulator *= index
                DIVIDE_CONSTANT -> accumulator /= instruction.operand
                DIVIDE_INDEX -> {
                    if (index == 0u) return 0
                    accumulator /= index
                }
                OR_CONSTANT -> accumulator = accumulator or instruction.operand
                OR_INDEX -> accumulator = accumulator or index
                AND_CONSTANT -> accumulator = accumulator and instruction.operand
                AND_INDEX -> accumulator = accumulator and index
                SHIFT_LEFT_CONSTANT -> accumulator = accumulator shl instruction.operand.toInt()
                SHIFT_LEFT_INDEX -> accumulator = if (index < UInt.SIZE_BITS.toUInt()) {
                    accumulator shl index.toInt()
                } else {
                    0u
                }
                SHIFT_RIGHT_CONSTANT -> accumulator = accumulator shr instruction.operand.toInt()
                SHIFT_RIGHT_INDEX -> accumulator = if (index < UInt.SIZE_BITS.toUInt()) {
                    accumulator shr index.toInt()
                } else {
                    0u
                }
                NEGATE -> accumulator = 0u - accumulator
                MODULO_CONSTANT -> accumulator %= instruction.operand
                MODULO_INDEX -> {
                    if (index == 0u) return 0
                    accumulator %= index
                }
                XOR_CONSTANT -> accumulator = accumulator xor instruction.operand
                XOR_INDEX -> accumulator = accumulator xor index
                JUMP -> {
                    instructionIndex += instruction.operand.toInt() + 1
                    continue
                }
                JUMP_EQUAL_CONSTANT,
                JUMP_GREATER_CONSTANT,
                JUMP_GREATER_OR_EQUAL_CONSTANT,
                JUMP_BITS_SET_CONSTANT,
                JUMP_EQUAL_INDEX,
                JUMP_GREATER_INDEX,
                JUMP_GREATER_OR_EQUAL_INDEX,
                JUMP_BITS_SET_INDEX,
                -> {
                    val compare = when (instruction.code) {
                        JUMP_EQUAL_CONSTANT -> accumulator == instruction.operand
                        JUMP_GREATER_CONSTANT -> accumulator > instruction.operand
                        JUMP_GREATER_OR_EQUAL_CONSTANT -> accumulator >= instruction.operand
                        JUMP_BITS_SET_CONSTANT -> accumulator and instruction.operand != 0u
                        JUMP_EQUAL_INDEX -> accumulator == index
                        JUMP_GREATER_INDEX -> accumulator > index
                        JUMP_GREATER_OR_EQUAL_INDEX -> accumulator >= index
                        else -> accumulator and index != 0u
                    }
                    instructionIndex += if (compare) {
                        instruction.trueOffset + 1
                    } else {
                        instruction.falseOffset + 1
                    }
                    continue
                }
                RETURN_CONSTANT -> return minOf(instruction.operand.toULong(), length.toULong()).toInt()
                RETURN_ACCUMULATOR -> return minOf(accumulator.toULong(), length.toULong()).toInt()
                TRANSFER_TO_INDEX -> index = accumulator
                TRANSFER_TO_ACCUMULATOR -> accumulator = index
            }
            instructionIndex++
        }
        return 0
    }

    companion object {
        const val INSTRUCTION_SIZE = 8
        const val MAX_INSTRUCTIONS = 4096

        fun decode(bytes: ByteArray): VfsResult<ClassicBpfProgram> {
            if (bytes.isEmpty() || bytes.size % INSTRUCTION_SIZE != 0 ||
                bytes.size / INSTRUCTION_SIZE > MAX_INSTRUCTIONS
            ) return VfsResult.Err(VfsError.INVALID_ARGUMENT)

            val input = LittleEndianBuffer(bytes)
            val instructions = Array(bytes.size / INSTRUCTION_SIZE) { index ->
                val offset = index * INSTRUCTION_SIZE
                Instruction(
                    input.readU16(offset).toInt(),
                    input.readU8(offset + 2).toInt(),
                    input.readU8(offset + 3).toInt(),
                    input.readU32(offset + 4),
                )
            }
            if (instructions.last().code != RETURN_CONSTANT &&
                instructions.last().code != RETURN_ACCUMULATOR ||
                instructions.indices.any { !valid(instructions[it], it, instructions.size) }
            ) return VfsResult.Err(VfsError.INVALID_ARGUMENT)
            val memoryWords = instructions.maxOfOrNull {
                if (it.code == LD_MEMORY || it.code == LDX_MEMORY ||
                    it.code == STORE || it.code == STORE_INDEX
                ) it.operand.toInt() + 1 else 0
            } ?: 0
            return VfsResult.Ok(ClassicBpfProgram(instructions, memoryWords))
        }

        private fun valid(instruction: Instruction, index: Int, size: Int): Boolean = when (
            instruction.code
        ) {
            LD_IMMEDIATE,
            LD_ABSOLUTE_WORD,
            LD_ABSOLUTE_HALF,
            LD_ABSOLUTE_BYTE,
            LD_INDIRECT_WORD,
            LD_INDIRECT_HALF,
            LD_INDIRECT_BYTE,
            LD_LENGTH,
            LDX_IMMEDIATE,
            LDX_LENGTH,
            LDX_HEADER_LENGTH,
            ADD_CONSTANT,
            ADD_INDEX,
            SUBTRACT_CONSTANT,
            SUBTRACT_INDEX,
            MULTIPLY_CONSTANT,
            MULTIPLY_INDEX,
            OR_CONSTANT,
            OR_INDEX,
            AND_CONSTANT,
            AND_INDEX,
            SHIFT_LEFT_INDEX,
            SHIFT_RIGHT_INDEX,
            NEGATE,
            XOR_CONSTANT,
            XOR_INDEX,
            RETURN_CONSTANT,
            RETURN_ACCUMULATOR,
            TRANSFER_TO_INDEX,
            TRANSFER_TO_ACCUMULATOR,
            -> true
            LD_MEMORY,
            LDX_MEMORY,
            STORE,
            STORE_INDEX,
            -> instruction.operand < MEMORY_WORDS.toUInt()
            DIVIDE_CONSTANT,
            MODULO_CONSTANT,
            -> instruction.operand != 0u
            DIVIDE_INDEX,
            MODULO_INDEX,
            -> true
            SHIFT_LEFT_CONSTANT,
            SHIFT_RIGHT_CONSTANT,
            -> instruction.operand < UInt.SIZE_BITS.toUInt()
            JUMP -> jumpTarget(index, instruction.operand.toULong(), size)
            JUMP_EQUAL_CONSTANT,
            JUMP_GREATER_CONSTANT,
            JUMP_GREATER_OR_EQUAL_CONSTANT,
            JUMP_BITS_SET_CONSTANT,
            JUMP_EQUAL_INDEX,
            JUMP_GREATER_INDEX,
            JUMP_GREATER_OR_EQUAL_INDEX,
            JUMP_BITS_SET_INDEX,
            -> jumpTarget(index, instruction.trueOffset.toULong(), size) &&
                jumpTarget(index, instruction.falseOffset.toULong(), size)
            else -> false
        }

        private fun jumpTarget(index: Int, offset: ULong, size: Int): Boolean =
            index.toULong() + 1uL + offset < size.toULong()

        private fun load(
            packet: ByteArray,
            packetOffset: Int,
            packetLength: Int,
            relativeOffset: UInt,
            width: Int,
        ): UInt? {
            if (relativeOffset > Int.MAX_VALUE.toUInt()) return null
            val relative = relativeOffset.toInt()
            if (relative > packetLength - width) return null
            val offset = packetOffset + relative
            return when (width) {
                UInt.SIZE_BYTES ->
                    (packet[offset].toUByte().toUInt() shl 24) or
                        (packet[offset + 1].toUByte().toUInt() shl 16) or
                        (packet[offset + 2].toUByte().toUInt() shl 8) or
                        packet[offset + 3].toUByte().toUInt()
                UShort.SIZE_BYTES ->
                    (packet[offset].toUByte().toUInt() shl 8) or
                        packet[offset + 1].toUByte().toUInt()
                else -> packet[offset].toUByte().toUInt()
            }
        }

        private const val MEMORY_WORDS = 16

        private const val LD_IMMEDIATE = 0x00
        private const val LD_ABSOLUTE_WORD = 0x20
        private const val LD_ABSOLUTE_HALF = 0x28
        private const val LD_ABSOLUTE_BYTE = 0x30
        private const val LD_INDIRECT_WORD = 0x40
        private const val LD_INDIRECT_HALF = 0x48
        private const val LD_INDIRECT_BYTE = 0x50
        private const val LD_MEMORY = 0x60
        private const val LD_LENGTH = 0x80
        private const val LDX_IMMEDIATE = 0x01
        private const val LDX_MEMORY = 0x61
        private const val LDX_LENGTH = 0x81
        private const val LDX_HEADER_LENGTH = 0xB1
        private const val STORE = 0x02
        private const val STORE_INDEX = 0x03
        private const val ADD_CONSTANT = 0x04
        private const val ADD_INDEX = 0x0C
        private const val SUBTRACT_CONSTANT = 0x14
        private const val SUBTRACT_INDEX = 0x1C
        private const val MULTIPLY_CONSTANT = 0x24
        private const val MULTIPLY_INDEX = 0x2C
        private const val DIVIDE_CONSTANT = 0x34
        private const val DIVIDE_INDEX = 0x3C
        private const val OR_CONSTANT = 0x44
        private const val OR_INDEX = 0x4C
        private const val AND_CONSTANT = 0x54
        private const val AND_INDEX = 0x5C
        private const val SHIFT_LEFT_CONSTANT = 0x64
        private const val SHIFT_LEFT_INDEX = 0x6C
        private const val SHIFT_RIGHT_CONSTANT = 0x74
        private const val SHIFT_RIGHT_INDEX = 0x7C
        private const val NEGATE = 0x84
        private const val MODULO_CONSTANT = 0x94
        private const val MODULO_INDEX = 0x9C
        private const val XOR_CONSTANT = 0xA4
        private const val XOR_INDEX = 0xAC
        private const val JUMP = 0x05
        private const val JUMP_EQUAL_CONSTANT = 0x15
        private const val JUMP_EQUAL_INDEX = 0x1D
        private const val JUMP_GREATER_CONSTANT = 0x25
        private const val JUMP_GREATER_INDEX = 0x2D
        private const val JUMP_GREATER_OR_EQUAL_CONSTANT = 0x35
        private const val JUMP_GREATER_OR_EQUAL_INDEX = 0x3D
        private const val JUMP_BITS_SET_CONSTANT = 0x45
        private const val JUMP_BITS_SET_INDEX = 0x4D
        private const val RETURN_CONSTANT = 0x06
        private const val RETURN_ACCUMULATOR = 0x16
        private const val TRANSFER_TO_INDEX = 0x07
        private const val TRANSFER_TO_ACCUMULATOR = 0x87
    }
}
