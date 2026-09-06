package org.plos_clan.cpos.drivers.char

import org.plos_clan.cpos.drivers.char.tty.ConsoleDisplayMode
import org.plos_clan.cpos.drivers.char.tty.ConsoleKeyboardMode
import org.plos_clan.cpos.drivers.char.tty.TtyManager
import org.plos_clan.cpos.drivers.char.tty.TtySession
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.tasks.CapEnum
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.VTModeConstants

internal abstract class VirtualTerminal : TerminalBackend() {
    protected var displayMode = ConsoleDisplayMode.TEXT
        private set
    var keyboardMode = ConsoleKeyboardMode.XLATE
        private set

    final override fun consoleIoctl(file: TtySession.OpenFile, command: Int, args: UserMemory): Int? {
        when (command) {
            VTModeConstants.VT_DISALLOCATE -> return if (file.isHungUp) -Errno.EIO
                else TtyManager.disallocateVirtualTerminals(args.address)
            VTModeConstants.KDGETMODE, VTModeConstants.KDGKBMODE,
            VTModeConstants.KDSETMODE, VTModeConstants.KDSKBMODE -> Unit
            else -> return null
        }
        return file.control(command) {
            if (command == VTModeConstants.KDGETMODE) return@control copyIntToUser(args, displayMode.value)
            if (command == VTModeConstants.KDGKBMODE) return@control copyIntToUser(args, keyboardMode.value)
            val thread = ProcessManager.currentThread() ?: return@control -Errno.EPERM
            val sessionId = file.session.sessionId
            val ownsTerminal = sessionId != 0 && sessionId == thread.process.sessionId
            if (!ownsTerminal && !thread.capabilities.hasEffective(CapEnum.SYS_TTY_CONFIG)) {
                return@control -Errno.EPERM
            }
            if (command == VTModeConstants.KDSKBMODE) {
                val mode = ConsoleKeyboardMode.from(args.address.toUInt()) ?: return@control -Errno.EINVAL
                keyboardMode = mode
                input.flush()
                return@control Errno.EOK
            }
            val mode = ConsoleDisplayMode.from(args.address) ?: return@control -Errno.EINVAL
            outputLock.withLock {
                if (displayMode == mode) return@withLock
                displayMode = mode
                if (mode == ConsoleDisplayMode.TEXT) redrawOutput()
            }
            Errno.EOK
        }
    }

    final override fun start(session: TtySession): Boolean {
        session.resetTermios()
        return true
    }

    final override fun hangup(session: TtySession) {
        super.hangup(session)
        session.resetTermios()
    }

    final override fun redraw() = outputLock.withLock(::redrawOutput)

    protected abstract fun redrawOutput()
}
