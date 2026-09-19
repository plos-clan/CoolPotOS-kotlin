package org.plos_clan.cpos.drivers.usb.bus

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.plos_clan.cpos.drivers.usb.defs.InterfaceDescriptor
import org.plos_clan.cpos.drivers.usb.defs.REQ_DIR_IN
import org.plos_clan.cpos.drivers.usb.defs.REQ_REC_INTERFACE
import org.plos_clan.cpos.drivers.usb.defs.REQ_SET_INTERFACE
import org.plos_clan.cpos.drivers.usb.defs.SetupPacket

class UsbInterface(
    val device: UsbDevice,
    desc: InterfaceDescriptor,
) {
    var driver: UsbDriver? = null
    val settings = mutableListOf(UsbAlternateSetting(desc))
    var activeSetting: UsbAlternateSetting = settings.first()
        internal set

    val desc: InterfaceDescriptor
        get() = activeSetting.desc

    val endpoints: List<UsbEndpoint>
        get() = activeSetting.endpoints

    val extraData: UsbExtraData
        get() = activeSetting.extraData

    suspend fun allocateStreams(endpoints: List<UsbEndpoint>, count: Int): Int =
        device.configurationLock.withLock {
            require(count >= 0)
            if (
                endpoints.isEmpty() ||
                    endpoints.any { candidate -> this.endpoints.none { it === candidate } }
            ) {
                return@withLock 0
            }
            device.host.allocateStreams(device.slotId, endpoints, count)
        }

    suspend fun selectAlternateSetting(number: UByte): Boolean =
        withContext(NonCancellable) {
            device.configurationLock.withLock {
                val selected =
                    settings.firstOrNull { it.desc.alternateSetting == number }
                        ?: return@withLock false
                if (selected === activeSetting) return@withLock true
                val previous = activeSetting
                val otherEndpoints =
                    device.interfaces.filter { it !== this@UsbInterface }.flatMap { it.endpoints }
                val oldEndpoints = otherEndpoints + previous.endpoints
                val newEndpoints = otherEndpoints + selected.endpoints
                val host = device.host
                val slot = device.slotId
                if (host.configureEndpoints(slot, otherEndpoints) == null) return@withLock false
                val setup =
                    SetupPacket(
                        REQ_REC_INTERFACE,
                        REQ_SET_INTERFACE,
                        value = number.toUShort(),
                        index = desc.interfaceNumber.toUShort(),
                    )
                val changed = device.transfer(UsbTransfer(0u, setup = setup), 5_000).successful
                if (changed && host.configureEndpoints(slot, newEndpoints) != null) {
                    activeSetting = selected
                    device.updateEndpoints()
                    return@withLock true
                }
                val restored =
                    !changed ||
                        device
                            .transfer(
                                UsbTransfer(
                                    0u,
                                    setup =
                                        setup.copy(
                                            value = previous.desc.alternateSetting.toUShort()
                                        ),
                                ),
                                5_000,
                            )
                            .successful
                if (!restored || host.configureEndpoints(slot, oldEndpoints) == null)
                    host.disableDevice(slot)
                false
            }
        }

    fun matches(
        classCode: UByte,
        subClass: UByte? = null,
        protocol: UByte? = null,
    ): Boolean =
        desc.interfaceClass == classCode &&
            (subClass == null || desc.interfaceSubclass == subClass) &&
            (protocol == null || desc.interfaceProtocol == protocol)

    fun findEndpoint(endpointType: UByte, isIn: Boolean): UsbEndpoint? {
        val endpointDirection = if (isIn) REQ_DIR_IN else 0u.toUByte()

        for (endpoint in endpoints) {
            val currentDirection = endpoint.desc.endpointAddress and REQ_DIR_IN
            val currentType = endpoint.desc.attributes and 0x03u.toUByte()

            if (currentDirection == endpointDirection && currentType == endpointType) {
                return endpoint
            }
        }

        return null
    }

    fun findAssociatedInterface(
        classCode: UByte,
        subClass: UByte? = null,
        protocol: UByte? = null,
    ): UsbInterface? =
        device.interfaces.firstOrNull { iface ->
            iface.desc.interfaceNumber in extraData.associatedInterfaceNumbers &&
                iface.matches(classCode, subClass, protocol)
        }
}
