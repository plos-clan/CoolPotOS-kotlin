package org.plos_clan.cpos.drivers.usb.bus

import org.plos_clan.cpos.drivers.usb.defs.EndpointDescriptor
import org.plos_clan.cpos.drivers.usb.defs.SsEndpointCompanionDescriptor

data class UsbEndpoint(
    val desc: EndpointDescriptor,
    val ssDesc: SsEndpointCompanionDescriptor? = null,
)
