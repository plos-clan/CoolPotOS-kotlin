package org.plos_clan.cpos.drivers.usb.xhci.core

internal class StreamAllocation(requested: Int, deviceLimit: Int, controllerContexts: Int) {
    val count: Int =
        if (controllerContexts < 4) 0
        else minOf(requested, deviceLimit, controllerContexts - 1, UShort.MAX_VALUE.toInt())

    val contextEntries: Int
        get() = if (count == 0) 0 else maxOf(4, 1 shl (32 - count.countLeadingZeroBits()))

    init {
        require(requested >= 0 && deviceLimit >= 0 && controllerContexts >= 0)
    }
}
