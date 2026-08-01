@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.module

import bridge.module_request
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.get
import kotlinx.cinterop.plus
import kotlinx.cinterop.pointed
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.memcpy

class ModuleData internal constructor(
    private val address: CPointer<UByteVar>,
    val size: Int,
) {
    operator fun get(index: Int): Byte {
        require(index in 0 until size)
        return address[index].toByte()
    }

    fun copyInto(
        destination: ByteArray,
        destinationOffset: Int,
        sourceOffset: Int,
        count: Int,
    ) {
        require(sourceOffset >= 0 && count >= 0 && sourceOffset <= size - count)
        require(destinationOffset >= 0 && destinationOffset <= destination.size - count)
        destination.usePinned { pinned ->
            memcpy(
                pinned.addressOf(destinationOffset),
                address + sourceOffset,
                count.toULong(),
            )
        }
    }

    fun copyOfRange(startIndex: Int, endIndex: Int): ByteArray {
        require(startIndex >= 0 && endIndex >= startIndex && endIndex <= size)
        return ByteArray(endIndex - startIndex).also { destination ->
            copyInto(destination, 0, startIndex, destination.size)
        }
    }
}

data class Module(val name: String, val path: String, val data: ModuleData)

object ModuleManager {
    val modules = mutableListOf<Module>()

    fun initialize() {
        val moduleResponse = module_request.response?.pointed ?: run {
            println("error: cannot find modules.")
            return
        }

        val count = moduleResponse.module_count
        for (index in 0 until count.toLong()) {
            val entry = (moduleResponse.modules?.get(index) ?: continue).pointed
            val path = entry.path?.toKString() ?: continue
            val filename = path.substringAfterLast('/')
            val buffer = entry.address ?: continue
            if (entry.size > Int.MAX_VALUE.toULong()) {
                println("MOD: skip module $path: size ${entry.size} exceeds Int.MAX_VALUE")
                continue
            }
            modules += Module(
                filename,
                path,
                ModuleData(buffer.reinterpret(), entry.size.toInt()),
            )
            println("MOD: load module $path size=${entry.size}")
        }
    }

    operator fun contains(name: String): Boolean =
        modules.any { it.name == name }

    operator fun get(name: String): Module? =
        modules.lastOrNull { it.name == name }
}
