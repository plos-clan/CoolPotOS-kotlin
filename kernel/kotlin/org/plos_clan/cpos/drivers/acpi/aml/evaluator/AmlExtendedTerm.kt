package org.plos_clan.cpos.drivers.acpi.aml

import org.plos_clan.cpos.drivers.TscClock

internal object AmlExtendedTerm {
    fun evaluate(frame: AmlMethodFrame, reader: AmlByteReader): AmlObject? {
        reader.readU8()
        return when (val opcode = reader.readU8() ?: return null) {
            AML_EXT_COND_REF_OF_OP -> {
                val targetObject = frame.readTarget(reader)
                val resultTarget = frame.readTarget(reader) ?: return null
                AmlInteger(
                    if (targetObject != null && resultTarget.write(targetObject)) ULong.MAX_VALUE
                    else 0uL,
                )
            }
            AML_EXT_STALL_OP, AML_EXT_SLEEP_OP -> {
                val requested = frame.readTermArg(reader)?.integerValue() ?: return null
                val microseconds = if (opcode == AML_EXT_SLEEP_OP) {
                    if (requested > MAX_AML_DELAY_MICROSECONDS / 1_000uL) return null
                    requested * 1_000uL
                } else {
                    requested
                }
                if (!AmlDelay.wait(microseconds)) return null
                AmlInteger(0uL)
            }
            AML_EXT_ACQUIRE_OP -> {
                frame.readTarget(reader) ?: return null
                reader.readU16() ?: return null
                AmlInteger(0uL)
            }
            AML_EXT_RELEASE_OP -> {
                frame.readTarget(reader) ?: return null
                AmlInteger(0uL)
            }
            AML_EXT_REVISION_OP -> AmlInteger(2uL)
            AML_EXT_DEBUG_OP -> AmlReference({ AmlUninitialized }, { true })
            AML_EXT_TIMER_OP -> AmlInteger(TscClock.nanoTime() / 100uL)
            else -> null
        }
    }
}
