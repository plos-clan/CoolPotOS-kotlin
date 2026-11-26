package org.plos_clan.cpos.network

import org.plos_clan.cpos.fs.sock.AbstractSocket
import org.plos_clan.cpos.fs.sock.SocketDomain
import org.plos_clan.cpos.fs.sock.SocketType
import org.plos_clan.cpos.fs.vfs.Inode
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsOperationContext
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.mem.IoScratch
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.tasks.CapEnum
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.utils.LittleEndianBuffer

internal abstract class NetworkSocket(
    protected val network: NetworkStack,
    domain: SocketDomain,
    type: SocketType,
    protocol: Int,
) : AbstractSocket(domain, type, protocol) {
    private enum class InterfaceControl(val command: Int, val writes: Boolean = false) {
        NAME(0x8910),
        LIST(0x8912),
        GET_FLAGS(0x8913),
        SET_FLAGS(0x8914, true),
        GET_ADDRESS(0x8915),
        GET_DESTINATION(0x8917),
        GET_BROADCAST(0x8919),
        GET_NETMASK(0x891b),
        GET_MTU(0x8921),
        SET_MTU(0x8922, true),
        GET_HARDWARE_ADDRESS(0x8927),
        INDEX(0x8933),
    }

    override fun ioctl(
        caller: VfsOperationContext,
        inode: Inode,
        command: Int,
        args: UserMemory,
    ): Long {
        val control = InterfaceControl.entries.firstOrNull { it.command == command }
            ?: return super.ioctl(caller, inode, command, args)
        if (control == InterfaceControl.LIST) return listInterfaces(args)
        return IoScratch.use(IFREQ_SIZE) { bytes ->
            if (!args.readInto(bytes, IFREQ_SIZE)) return -VfsError.FAULT.errno.toLong()
            interfaceRequest(control, args, bytes)
        }
    }

    private fun interfaceRequest(control: InterfaceControl, args: UserMemory, bytes: ByteArray): Long {
        val data = LittleEndianBuffer(bytes)
        val intfc = if (control == InterfaceControl.NAME) {
            network.interfaceByIndex(data.readU32(IFNAMSIZ).toInt())
        } else {
            var end = 0
            while (end < IFNAMSIZ && bytes[end] != 0.toByte()) end++
            network.interfaceByName(bytes.decodeToString(0, end))
        } ?: return -VfsError.NO_DEVICE.errno.toLong()
        if (control.writes) return configureInterface(control, intfc, data)
        when (control) {
            InterfaceControl.NAME -> intfc.copyName(bytes, 0, IFNAMSIZ)
            InterfaceControl.GET_FLAGS -> data.writeU16(IFNAMSIZ, intfc.flags.toUShort())
            InterfaceControl.GET_MTU -> data.writeU32(IFNAMSIZ, intfc.mtu.toUInt())
            InterfaceControl.INDEX -> data.writeU32(IFNAMSIZ, intfc.index.toUInt())
            InterfaceControl.GET_HARDWARE_ADDRESS -> {
                bytes.fill(0, IFNAMSIZ, IFREQ_SIZE)
                data.writeU16(IFNAMSIZ, intfc.kind.hardwareType)
                intfc.hardwareAddress.copyTo(bytes, IFNAMSIZ + UShort.SIZE_BYTES)
            }
            InterfaceControl.GET_ADDRESS,
            InterfaceControl.GET_DESTINATION,
            InterfaceControl.GET_BROADCAST,
            InterfaceControl.GET_NETMASK,
            -> {
                val assigned = network.interfaceAddresses(intfc.index).firstOrNull()
                    ?: return -VfsError.ADDRESS_NOT_AVAILABLE.errno.toLong()
                val address = when (control) {
                    InterfaceControl.GET_NETMASK -> Ipv4Address.fromBits(assigned.prefix.mask)
                    InterfaceControl.GET_BROADCAST -> if (intfc.kind == NetworkInterfaceKind.LOOPBACK) {
                        Ipv4Address.ANY
                    } else assigned.prefix.broadcast
                    else -> assigned.address
                }
                SocketAddressAbi.writeIpv4(bytes, IFNAMSIZ, address)
            }
            else -> return -VfsError.INVALID_ARGUMENT.errno.toLong()
        }
        return if (args.writeFrom(bytes, IFREQ_SIZE)) 0L else -VfsError.FAULT.errno.toLong()
    }

    private fun listInterfaces(args: UserMemory): Long {
        val address = args.readULongLE(8) ?: return -VfsError.FAULT.errno.toLong()
        val length = args.readUIntLE()?.toInt() ?: return -VfsError.FAULT.errno.toLong()
        val capacity = length.coerceAtLeast(0) / IFREQ_SIZE
        return IoScratch.borrow { scratch ->
            val interfaces = network.snapshotInterfaces()
            val count = if (address == 0uL) interfaces.sumOf { network.interfaceAddresses(it.index).size }
            else encodeInterfaces(interfaces, capacity, scratch)
            val size = count * IFREQ_SIZE
            if (address != 0uL && !args.at(address).writeFrom(scratch.bytes, size)) {
                return -VfsError.FAULT.errno.toLong()
            }
            if (args.writeUInt(size.toUInt())) 0L else -VfsError.FAULT.errno.toLong()
        }
    }

    private fun encodeInterfaces(interfaces: List<NetworkInterface>, capacity: Int, scratch: IoScratch): Int {
        var count = 0
        for (intfc in interfaces) {
            if (count == capacity) break
            val addresses = network.interfaceAddresses(intfc.index)
            val entries = minOf(addresses.size, capacity - count)
            val bytes = scratch.reserve((count + entries) * IFREQ_SIZE)
            for (index in 0 until entries) {
                val offset = count * IFREQ_SIZE
                val end = offset + IFREQ_SIZE
                bytes.fill(0, offset, end)
                intfc.copyName(bytes, offset, IFNAMSIZ)
                SocketAddressAbi.writeIpv4(bytes, offset + IFNAMSIZ, addresses[index].address)
                count++
            }
        }
        return count
    }

    private fun configureInterface(
        control: InterfaceControl,
        intfc: NetworkInterface,
        data: LittleEndianBuffer,
    ): Long {
        val capabilities = ProcessManager.currentThread()?.capabilities
        if (capabilities?.hasEffective(CapEnum.NET_ADMIN) != true) {
            return -VfsError.NOT_PERMITTED.errno.toLong()
        }
        val up = if (control == InterfaceControl.SET_FLAGS) {
            val flags = data.readU16(IFNAMSIZ).toUInt()
            flags and NetworkInterface.UP_FLAG != 0u
        } else intfc.administrativeUp
        val mtu = if (control == InterfaceControl.SET_MTU) {
            data.readU32(IFNAMSIZ).toInt()
        } else null
        return when (val result = network.setLink(intfc.index, up, mtu)) {
            is VfsResult.Ok -> 0L
            is VfsResult.Err -> -result.error.errno.toLong()
        }
    }

    companion object {
        private const val IFNAMSIZ = 16
        private const val IFREQ_SIZE = 40
    }
}
