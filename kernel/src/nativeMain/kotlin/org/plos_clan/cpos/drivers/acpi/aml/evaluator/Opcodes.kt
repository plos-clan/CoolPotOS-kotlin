package org.plos_clan.cpos.drivers.acpi.aml

internal const val AML_LOCAL0_OP = 0x60u
internal const val AML_LOCAL7_OP = 0x67u
internal const val AML_ARG0_OP = 0x68u
internal const val AML_ARG6_OP = 0x6Eu

internal const val AML_STORE_OP = 0x70u
internal const val AML_REF_OF_OP = 0x71u
internal const val AML_ADD_OP = 0x72u
internal const val AML_CONCAT_OP = 0x73u
internal const val AML_SUBTRACT_OP = 0x74u
internal const val AML_INCREMENT_OP = 0x75u
internal const val AML_DECREMENT_OP = 0x76u
internal const val AML_MULTIPLY_OP = 0x77u
internal const val AML_DIVIDE_OP = 0x78u
internal const val AML_SHIFT_LEFT_OP = 0x79u
internal const val AML_SHIFT_RIGHT_OP = 0x7Au
internal const val AML_AND_OP = 0x7Bu
internal const val AML_NAND_OP = 0x7Cu
internal const val AML_OR_OP = 0x7Du
internal const val AML_NOR_OP = 0x7Eu
internal const val AML_XOR_OP = 0x7Fu
internal const val AML_NOT_OP = 0x80u
internal const val AML_DEREF_OF_OP = 0x83u
internal const val AML_CONCAT_RES_OP = 0x84u
internal const val AML_MOD_OP = 0x85u
internal const val AML_NOTIFY_OP = 0x86u
internal const val AML_SIZE_OF_OP = 0x87u
internal const val AML_INDEX_OP = 0x88u
internal const val AML_OBJECT_TYPE_OP = 0x8Eu
internal const val AML_LAND_OP = 0x90u
internal const val AML_LOR_OP = 0x91u
internal const val AML_LNOT_OP = 0x92u
internal const val AML_LEQUAL_OP = 0x93u
internal const val AML_LGREATER_OP = 0x94u
internal const val AML_LLESS_OP = 0x95u
internal const val AML_TO_BUFFER_OP = 0x96u
internal const val AML_TO_INTEGER_OP = 0x99u
internal const val AML_COPY_OBJECT_OP = 0x9Du
internal const val AML_CONTINUE_OP = 0x9Fu
internal const val AML_IF_OP = 0xA0u
internal const val AML_ELSE_OP = 0xA1u
internal const val AML_WHILE_OP = 0xA2u
internal const val AML_NOOP_OP = 0xA3u
internal const val AML_RETURN_OP = 0xA4u
internal const val AML_BREAK_OP = 0xA5u

internal const val AML_EXT_COND_REF_OF_OP = 0x12u
internal const val AML_EXT_STALL_OP = 0x21u
internal const val AML_EXT_SLEEP_OP = 0x22u
internal const val AML_EXT_ACQUIRE_OP = 0x23u
internal const val AML_EXT_RELEASE_OP = 0x27u
internal const val AML_EXT_REVISION_OP = 0x30u
internal const val AML_EXT_DEBUG_OP = 0x31u
internal const val AML_EXT_TIMER_OP = 0x33u

internal const val MAX_METHOD_DEPTH = 32
internal const val MAX_METHOD_OPERATIONS = 100_000
internal const val MAX_WHILE_ITERATIONS = 4_096
internal const val MAX_RUNTIME_BUFFER_SIZE = 1_048_576
internal const val MAX_RUNTIME_PACKAGE_ELEMENTS = 65_536
internal const val MAX_AML_DELAY_MICROSECONDS = 10_000_000uL

internal object AmlRuntimeOpcode {
    const val AML_BYTE_PREFIX = 0x0Au
    const val AML_WORD_PREFIX = 0x0Bu
    const val AML_DWORD_PREFIX = 0x0Cu
    const val AML_STRING_PREFIX = 0x0Du
    const val AML_QWORD_PREFIX = 0x0Eu
    const val AML_BUFFER_OP = 0x11u
    const val AML_PACKAGE_OP = 0x12u
    const val AML_VAR_PACKAGE_OP = 0x13u
    const val AML_EXT_OP_PREFIX = 0x5Bu
}
