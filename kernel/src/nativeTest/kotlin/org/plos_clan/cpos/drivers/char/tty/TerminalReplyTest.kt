@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.drivers.char.tty

import bridge.free
import bridge.malloc
import kotlinx.cinterop.reinterpret
import org.plos_clan.cpos.drivers.TtyGraphicsDevice
import org.plos_clan.cpos.drivers.char.terminal.NativeTerminal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TerminalReplyTest {
    @Test
    fun cursorReportsSurviveSplitWritesAndRepliesAreConsumedOnce() {
        val framebuffer = assertNotNull(malloc(640uL * 480uL * 4uL))
        try {
            val device = TtyGraphicsDevice(
                framebuffer.reinterpret(), 640uL, 480uL, 2560uL,
                8u, 16u, 8u, 8u, 8u, 0u,
            )
            val terminal = assertNotNull(NativeTerminal.create(device) {})
            try {
                val prefix = "\u001b[3;7H\u001b[".encodeToByteArray()
                assertNull(terminal.process(prefix, 0, prefix.size))
                val suffix = "6n".encodeToByteArray()
                val reply = assertNotNull(terminal.process(suffix, 0, suffix.size))
                assertEquals("\u001b[3;7R", reply.decodeToString())
                val text = "text".encodeToByteArray()
                assertNull(terminal.process(text, 0, text.size))
            } finally {
                terminal.destroy()
            }
        } finally {
            free(framebuffer)
        }
    }
}
