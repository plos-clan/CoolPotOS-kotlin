package org.plos_clan.cpos.drivers.acpi.aml.evaluator

internal const val AML_ALIAS_OP = 0x06u
internal const val AML_NAME_OP = 0x08u
internal const val AML_BYTE_PREFIX = 0x0Au
internal const val AML_WORD_PREFIX = 0x0Bu
internal const val AML_DWORD_PREFIX = 0x0Cu
internal const val AML_STRING_PREFIX = 0x0Du
internal const val AML_QWORD_PREFIX = 0x0Eu
internal const val AML_SCOPE_OP = 0x10u
internal const val AML_BUFFER_OP = 0x11u
internal const val AML_PACKAGE_OP = 0x12u
internal const val AML_VAR_PACKAGE_OP = 0x13u
internal const val AML_METHOD_OP = 0x14u
internal const val AML_EXTERNAL_OP = 0x15u
internal const val AML_EXT_OP_PREFIX = 0x5Bu

internal const val AML_EXT_MUTEX_OP = 0x01u
internal const val AML_EXT_EVENT_OP = 0x02u
internal const val AML_EXT_COND_REF_OF_OP = 0x12u
internal const val AML_EXT_STALL_OP = 0x21u
internal const val AML_EXT_SLEEP_OP = 0x22u
internal const val AML_EXT_ACQUIRE_OP = 0x23u
internal const val AML_EXT_RELEASE_OP = 0x27u
internal const val AML_EXT_REVISION_OP = 0x30u
internal const val AML_EXT_DEBUG_OP = 0x31u
internal const val AML_EXT_TIMER_OP = 0x33u
internal const val AML_EXT_OPERATION_REGION_OP = 0x80u
internal const val AML_EXT_FIELD_OP = 0x81u
internal const val AML_EXT_DEVICE_OP = 0x82u
internal const val AML_EXT_PROCESSOR_OP = 0x83u
internal const val AML_EXT_POWER_RESOURCE_OP = 0x84u
internal const val AML_EXT_THERMAL_ZONE_OP = 0x85u
internal const val AML_EXT_INDEX_FIELD_OP = 0x86u
internal const val AML_EXT_BANK_FIELD_OP = 0x87u

internal const val AML_CREATE_BYTE_FIELD_OP = 0x8Au
internal const val AML_CREATE_BIT_FIELD_OP = 0x8Bu
internal const val AML_CREATE_DWORD_FIELD_OP = 0x8Cu
internal const val AML_CREATE_WORD_FIELD_OP = 0x8Du
internal const val AML_CREATE_QWORD_FIELD_OP = 0x8Eu
internal const val AML_CREATE_FIELD_OP = 0x8Fu

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
