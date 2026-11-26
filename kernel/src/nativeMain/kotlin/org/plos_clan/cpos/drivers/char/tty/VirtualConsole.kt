package org.plos_clan.cpos.drivers.char.tty

import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.tasks.Signal
import org.plos_clan.cpos.tasks.SignalInfo
import org.plos_clan.cpos.tasks.SignalRouter
import org.plos_clan.cpos.utils.Errno

internal class VirtualConsole(val number: Int, val session: TtySession) {
    private class Controller(
        val process: Process,
        val release: Signal,
        val acquire: Signal,
        val wait: Byte,
    ) {
        var target: Int = 0

        fun send(signal: Signal) {
            val info = SignalInfo(signal, SignalInfo.KERNEL)
            SignalRouter.sendProcess(null, process, info)
        }
    }

    private var controller: Controller? = null

    fun deallocate(): Boolean {
        if (!session.deallocate()) return false
        controller = null
        return true
    }

    fun writeMode(args: UserMemory): Boolean = args.writeRecord(8) { data ->
        val owner = controller ?: return@writeRecord
        data.writeU8(0, 1u)
        data.writeU8(1, owner.wait.toUByte())
        data.writeU16(2, owner.release.number.toUShort())
        data.writeU16(4, owner.acquire.number.toUShort())
    }

    fun setMode(args: UserMemory, process: Process): Int = args.readRecord(8) { data ->
        when (data.readU8(0).toInt()) {
            0 -> controller = null
            1 -> {
                val release = Signal.from(data.readU16(2).toInt()) ?: return -Errno.EINVAL
                val acquire = Signal.from(data.readU16(4).toInt()) ?: return -Errno.EINVAL
                controller = Controller(process, release, acquire, data.readU8(1).toByte())
            }
            else -> return -Errno.EINVAL
        }
        0
    } ?: -Errno.EFAULT

    fun requestRelease(target: Int): Boolean {
        val owner = controller ?: return false
        if (!owner.process.state.canReceiveSignals) {
            controller = null
            return false
        }
        owner.target = target
        owner.send(owner.release)
        return true
    }

    fun release(value: ULong): Int {
        val owner = controller ?: return -Errno.EINVAL
        if (owner.target == 0) return if (value == 2uL) 0 else -Errno.EINVAL
        if (value > 1uL) return -Errno.EINVAL
        val target = owner.target
        owner.target = 0
        return if (value == 0uL) 0 else target
    }

    fun acquire() {
        val owner = controller ?: return
        if (owner.process.state.canReceiveSignals) owner.send(owner.acquire) else controller = null
    }
}
