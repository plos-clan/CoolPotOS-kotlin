package org.plos_clan.cpos.drivers.usb.adapt.msc

import org.plos_clan.cpos.block.BlockDevices
import org.plos_clan.cpos.drivers.Device
import org.plos_clan.cpos.drivers.scsi.ScsiDisk
import org.plos_clan.cpos.drivers.usb.bus.CompletionEvent
import org.plos_clan.cpos.drivers.usb.bus.UsbDriver
import org.plos_clan.cpos.drivers.usb.bus.UsbInterface
import org.plos_clan.cpos.mem.DmaMemory

class MassStorage private constructor(private val transport: StorageTransport) : UsbDriver {
    private val disks = mutableListOf<Device>()

    override fun quiesce() {
        transport.quiesce()
        disks.forEach(BlockDevices::unregister)
        disks.clear()
    }

    override suspend fun disconnect() {
        quiesce()
        transport.close()
    }

    override fun handleCompletion(event: CompletionEvent) = Unit

    companion object {
        suspend fun probe(iface: UsbInterface): UsbDriver? {
            val settings =
                iface.settings
                    .filter {
                        it.desc.interfaceClass == 8u.toUByte() &&
                            it.desc.interfaceSubclass == 6u.toUByte()
                    }
                    .sortedByDescending { it.desc.interfaceProtocol == 0x62u.toUByte() }
            val original = iface.desc.alternateSetting
            for (setting in settings) {
                val protocol = setting.desc.interfaceProtocol.toInt()
                if (protocol != 0x50 && protocol != 0x62) continue
                if (!iface.selectAlternateSetting(setting.desc.alternateSetting)) continue
                val transport =
                    if (protocol == 0x62) UasTransport.create(iface, DmaMemory::allocate)
                    else BotTransport.create(iface, DmaMemory::allocate)
                if (transport == null) continue
                val driver = MassStorage(transport)
                iface.driver = driver
                var bound = false
                try {
                    for (lun in transport.logicalUnits()) {
                        val disk = ScsiDisk.probe(transport, lun) ?: continue
                        if (!transport.connected) break
                        val device = BlockDevices.register(disk) ?: continue
                        driver.disks.add(device)
                        println(
                            "USB storage: ${device.name}, ${disk.model}, ${disk.geometry.byteSize} bytes"
                        )
                    }
                    if (transport.connected && driver.disks.isNotEmpty()) {
                        bound = true
                        return driver
                    }
                } finally {
                    if (!bound) {
                        driver.disconnect()
                        if (iface.driver === driver) iface.driver = null
                    }
                }
            }
            iface.selectAlternateSetting(original)
            return null
        }
    }
}
