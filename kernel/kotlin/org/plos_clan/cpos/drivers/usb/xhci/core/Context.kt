@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.drivers.usb.xhci.core

import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import org.plos_clan.cpos.mem.DmaBuffer

class ErstEntry(buffer: DmaBuffer) {
    private val words = buffer.view<UIntVar>()

    var baseAddress: ULong
        get() = words[0].toULong() or (words[1].toULong() shl 32)
        set(value) {
            words[0] = value.toUInt()
            words[1] = (value shr 32).toUInt()
        }

    var size: UInt
        get() = words[2]
        set(value) {
            words[2] = value
        }
}

class SlotContext(buffer: DmaBuffer, contextSize: Int) {
    private val words = buffer.view<UIntVar>()
    private val base = contextSize / UInt.SIZE_BYTES

    private var info1: UInt
        get() = words[base]
        set(value) {
            words[base] = value
        }

    private var info2: UInt
        get() = words[base + 1]
        set(value) {
            words[base + 1] = value
        }

    private var ttId: UInt
        get() = words[base + 2]
        set(value) {
            words[base + 2] = value
        }

    fun setEntries(count: UInt) {
        info1 = info1 or ((count and 0x1fu) shl 27)
    }

    fun setRootHubPort(port: UInt) {
        info2 = info2 or ((port and 0xffu) shl 16)
    }

    fun setSpeed(speed: UInt) {
        info1 = info1 or ((speed and 0xfu) shl 20)
    }

    fun setRouteString(route: UInt) {
        info1 = info1 or (route and 0xf_ffffu)
    }

    fun setInterrupterTarget(target: UInt) {
        val mask = 0x3ffu shl 22
        ttId = (ttId and mask.inv()) or ((target and 0x3ffu) shl 22)
    }
}

class InputControlContext(buffer: DmaBuffer) {
    private val words = buffer.view<UIntVar>()

    var dropFlags: UInt
        get() = words[0]
        set(value) {
            words[0] = value
        }

    var addFlags: UInt
        get() = words[1]
        set(value) {
            words[1] = value
        }
}

class EndpointContext(buffer: DmaBuffer, dci: Int, contextSize: Int) {
    private val words = buffer.view<UIntVar>()
    private val base = (dci + 1) * contextSize / UInt.SIZE_BYTES

    private var info1: UInt
        get() = words[base]
        set(value) {
            words[base] = value
        }

    internal var info2: UInt
        get() = words[base + 1]
        set(value) {
            words[base + 1] = value
        }

    private var trDequeueLow: UInt
        get() = words[base + 2]
        set(value) {
            words[base + 2] = value
        }

    private var trDequeueHigh: UInt
        get() = words[base + 3]
        set(value) {
            words[base + 3] = value
        }

    private var txInfo: UInt
        get() = words[base + 4]
        set(value) {
            words[base + 4] = value
        }

    fun setMult(value: UInt) {
        info1 = info1 or ((value and 0x3u) shl 8)
    }

    fun setInterval(value: UInt) {
        info1 = info1 or ((value and 0xffu) shl 16)
    }

    fun setEpType(value: UInt) {
        info2 = info2 or ((value and 0x7u) shl 3)
    }

    fun setMaxPacketSize(size: UInt) {
        info2 = info2 or ((size and 0xffffu) shl 16)
    }

    fun setErrorCount(count: UInt) {
        info2 = info2 or ((count and 0x3u) shl 1)
    }

    fun setMaxBurst(size: UInt) {
        info2 = info2 or ((size and 0xffu) shl 8)
    }

    fun setAverageTrbLen(length: UInt) {
        txInfo = txInfo or (length and 0xffffu)
    }

    fun setMaxEsitPayload(size: UInt) {
        txInfo = txInfo or ((size and 0xffffu) shl 16)
    }

    fun setDequeuePointer(pointer: ULong) {
        trDequeueLow = pointer.toUInt() or 1u
        trDequeueHigh = (pointer shr 32).toUInt()
    }
}
