package org.plos_clan.cpos.network

import org.plos_clan.cpos.fs.sock.AbstractSocket
import org.plos_clan.cpos.fs.sock.SocketDomain
import org.plos_clan.cpos.fs.sock.SocketType
import org.plos_clan.cpos.fs.vfs.Inode
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsOperationContext
import org.plos_clan.cpos.fs.vfs.VfsResult
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
        val bytes = args.copyFromUser(IFREQ_SIZE) ?: return -VfsError.FAULT.errno.toLong()
        val data = LittleEndianBuffer(bytes)
        val terminator = bytes.indexOf(0)
        val end = if (terminator < 0) IFNAMSIZ else minOf(terminator, IFNAMSIZ)
        val name = bytes.decodeToString(0, end)
        val intfc = if (control == InterfaceControl.NAME) {
            network.interfaceByIndex(data.readU32(IFNAMSIZ).toInt())
        } else {
            network.interfaceByName(name)
        } ?: return -VfsError.NO_DEVICE.errno.toLong()
        if (control.writes) return configureInterface(control, intfc, data)
        when (control) {
            InterfaceControl.NAME -> {
                bytes.fill(0, 0, IFNAMSIZ)
                val encodedName = intfc.name.encodeToByteArray()
                encodedName.copyInto(bytes, endIndex = minOf(encodedName.size, IFNAMSIZ - 1))
            }
            InterfaceControl.GET_FLAGS -> data.writeU16(IFNAMSIZ, intfc.flags.toUShort())
            InterfaceControl.GET_MTU -> data.writeU32(IFNAMSIZ, intfc.mtu.toUInt())
            InterfaceControl.INDEX -> data.writeU32(IFNAMSIZ, intfc.index.toUInt())
            InterfaceControl.GET_HARDWARE_ADDRESS -> {
                bytes.fill(0, IFNAMSIZ)
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
                val socketAddress = Ipv4SocketAddress(address, 0u)
                SocketAddressAbi.encode(socketAddress).copyInto(bytes, IFNAMSIZ)
            }
            else -> return -VfsError.INVALID_ARGUMENT.errno.toLong()
        }
        return if (args.copyToUser(bytes)) 0L else -VfsError.FAULT.errno.toLong()
    }

    private fun listInterfaces(args: UserMemory): Long {
        val bytes = args.copyFromUser(IFCONF_SIZE) ?: return -VfsError.FAULT.errno.toLong()
        val data = LittleEndianBuffer(bytes)
        val length = data.readU32(0).toInt().coerceAtLeast(0)
        val address = data.readU64(8)
        val entries = network.snapshotInterfaces().flatMap { intfc ->
            network.interfaceAddresses(intfc.index).map { intfc.name to it.address }
        }
        val count = if (address == 0uL) entries.size else minOf(entries.size, length / IFREQ_SIZE)
        if (address != 0uL && count != 0) {
            val output = ByteArray(count * IFREQ_SIZE)
            for (index in 0 until count) {
                val (name, local) = entries[index]
                val offset = index * IFREQ_SIZE
                val encodedName = name.encodeToByteArray()
                encodedName.copyInto(output, offset, endIndex = minOf(encodedName.size, IFNAMSIZ - 1))
                val socketAddress = Ipv4SocketAddress(local, 0u)
                SocketAddressAbi.encode(socketAddress).copyInto(output, offset + IFNAMSIZ)
            }
            val process = ProcessManager.currentProcess()
                ?: return -VfsError.NO_SUCH_PROCESS.errno.toLong()
            val target = UserMemory(process.addressSpace, address)
            if (!target.copyToUser(output)) return -VfsError.FAULT.errno.toLong()
        }
        data.writeU32(0, (count * IFREQ_SIZE).toUInt())
        return if (args.copyToUser(bytes)) 0L else -VfsError.FAULT.errno.toLong()
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
        private const val IFCONF_SIZE = 16
    }
}
