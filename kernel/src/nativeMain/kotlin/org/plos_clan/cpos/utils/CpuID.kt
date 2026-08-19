@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.utils

import bridge.cpuid_result_t
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import org.plos_clan.cpos.tasks.CpuLocal

private data class CpuidResult(
    val eax: UInt,
    val ebx: UInt,
    val ecx: UInt,
    val edx: UInt,
)

enum class CpuidRegister {
    EAX,
    ECX,
    EDX,
    EBX,
}

enum class CpuFeature(
    val leaf: UInt,
    val subleaf: UInt,
    val register: CpuidRegister,
    val bit: Int,
    val procName: String,
) {
    SSE3(
        1u, 0u,
        CpuidRegister.ECX,
        0,
        "sse3"
    ),

    PCLMULQDQ(
        1u, 0u,
        CpuidRegister.ECX,
        1,
        "pclmulqdq"
    ),

    VMX(
        1u, 0u,
        CpuidRegister.ECX,
        5,
        "vmx"
    ),

    SSSE3(
        1u, 0u,
        CpuidRegister.ECX,
        9,
        "ssse3"
    ),

    FMA(
        1u, 0u,
        CpuidRegister.ECX,
        12,
        "fma"
    ),

    SSE4_1(
        1u, 0u,
        CpuidRegister.ECX,
        19,
        "sse4_1"
    ),

    SSE4_2(
        1u, 0u,
        CpuidRegister.ECX,
        20,
        "sse4_2"
    ),

    X2APIC(
        1u, 0u,
        CpuidRegister.ECX,
        21,
        "x2apic"
    ),

    POPCNT(
        1u, 0u,
        CpuidRegister.ECX,
        23,
        "popcnt"
    ),

    AES(
        1u, 0u,
        CpuidRegister.ECX,
        25,
        "aes"
    ),

    XSAVE(
        1u, 0u,
        CpuidRegister.ECX,
        26,
        "xsave"
    ),

    OSXSAVE(
        1u, 0u,
        CpuidRegister.ECX,
        27,
        "osxsave"
    ),

    AVX(
        1u, 0u,
        CpuidRegister.ECX,
        28,
        "avx"
    ),

    RDRAND(
        1u, 0u,
        CpuidRegister.ECX,
        30,
        "rdrand"
    ),

    // CPUID 1 EDX
    FPU(
        1u, 0u,
        CpuidRegister.EDX,
        0,
        "fpu"
    ),

    TSC(
        1u, 0u,
        CpuidRegister.EDX,
        4,
        "tsc"
    ),

    MSR(
        1u, 0u,
        CpuidRegister.EDX,
        5,
        "msr"
    ),

    PAE(
        1u, 0u,
        CpuidRegister.EDX,
        6,
        "pae"
    ),

    APIC(
        1u, 0u,
        CpuidRegister.EDX,
        9,
        "apic"
    ),

    MMX(
        1u, 0u,
        CpuidRegister.EDX,
        23,
        "mmx"
    ),

    FXSR(
        1u, 0u,
        CpuidRegister.EDX,
        24,
        "fxsr"
    ),

    SSE(
        1u, 0u,
        CpuidRegister.EDX,
        25,
        "sse"
    ),

    SSE2(
        1u, 0u,
        CpuidRegister.EDX,
        26,
        "sse2"
    ),

    HTT(
        1u, 0u,
        CpuidRegister.EDX,
        28,
        "htt"
    ),

    // CPUID 7,0 EBX
    FSGSBASE(
        7u, 0u,
        CpuidRegister.EBX,
        0,
        "fsgsbase"
    ),

    BMI1(
        7u, 0u,
        CpuidRegister.EBX,
        3,
        "bmi1"
    ),

    AVX2(
        7u, 0u,
        CpuidRegister.EBX,
        5,
        "avx2"
    ),

    SMEP(
        7u, 0u,
        CpuidRegister.EBX,
        7,
        "smep"
    ),

    BMI2(
        7u, 0u,
        CpuidRegister.EBX,
        8,
        "bmi2"
    ),

    ERMS(
        7u, 0u,
        CpuidRegister.EBX,
        9,
        "erms"
    ),

    INVPCID(
        7u, 0u,
        CpuidRegister.EBX,
        10,
        "invpcid"
    ),

    RDSEED(
        7u, 0u,
        CpuidRegister.EBX,
        18,
        "rdseed"
    ),

    ADX(
        7u, 0u,
        CpuidRegister.EBX,
        19,
        "adx"
    ),

    SMAP(
        7u, 0u,
        CpuidRegister.EBX,
        20,
        "smap"
    ),

    SHA(
        7u, 0u,
        CpuidRegister.EBX,
        29,
        "sha"
    ),
}

object CpuID {
    private fun cpuid(
        leaf: UInt,
        subleaf: UInt = 0u,
    ): CpuidResult = memScoped {
        val result = alloc<cpuid_result_t>()
        bridge.x86_cpuid(
            leaf,
            subleaf,
            result.ptr
        )

        CpuidResult(
            eax = result.eax,
            ebx = result.ebx,
            ecx = result.ecx,
            edx = result.edx,
        )
    }

    private fun StringBuilder.appendUIntBytes(value: UInt) {
        append(((value shr 0) and 0xffu).toInt().toChar())
        append(((value shr 8) and 0xffu).toInt().toChar())
        append(((value shr 16) and 0xffu).toInt().toChar())
        append(((value shr 24) and 0xffu).toInt().toChar())
    }

    private fun StringBuilder.appendCpuidResult(
        result: CpuidResult
    ) {
        appendUIntBytes(result.eax)
        appendUIntBytes(result.ebx)
        appendUIntBytes(result.ecx)
        appendUIntBytes(result.edx)
    }

    private fun addressBits(local: CpuLocal) {
        val result = cpuid(0x80000008u)
        local.virtual = (result.eax shr 8) and 0xffu
        local.physical = result.eax and 0xffu
    }

    private fun modelName(): String {
        val maxExtended = cpuid(0x80000000u).eax

        if (maxExtended < 0x80000004u)
            return ""

        return buildString(48) {
            appendCpuidResult(cpuid(0x80000002u))
            appendCpuidResult(cpuid(0x80000003u))
            appendCpuidResult(cpuid(0x80000004u))
        }
            .trimEnd('\u0000', ' ')
    }

    private fun vendorId(): String {
        val result = cpuid(0u)

        return buildString(12) {
            appendUIntBytes(result.ebx)
            appendUIntBytes(result.edx)
            appendUIntBytes(result.ecx)
        }
    }

    fun has(feature: CpuFeature): Boolean {
        val leaf1 = cpuid(1u)
        val leaf7 = cpuid(7u, 0u)

        val result = when (feature.leaf) {
            1u -> leaf1
            7u -> leaf7
            else ->
                cpuid(
                    feature.leaf,
                    feature.subleaf
                )
        }

        val value = when (feature.register) {
            CpuidRegister.EAX -> result.eax
            CpuidRegister.EBX -> result.ebx
            CpuidRegister.ECX -> result.ecx
            CpuidRegister.EDX -> result.edx
        }

        return value.hasBit(feature.bit)
    }



    fun apInit(local: CpuLocal, isBsp: Boolean = false) {
        local.features = CpuFeature.entries
            .filter {
                val h = has(it)
                if(h) when(it) {
                    CpuFeature.SMEP -> {
                        bridge.setup_smep()
                        if(isBsp) println("CPUID: setup smep feature.")
                    }
                    CpuFeature.SMAP -> {
                        bridge.setup_smap()
                        bridge.open_smap()
                        if(isBsp) println("CPUID: setup smap feature.")
                    }
                    else -> {}
                }
                h
            }
            .joinToString(" ") {
                it.procName
            }
        addressBits(local)
        local.modelName = modelName()
        local.vendor = vendorId()
    }
}