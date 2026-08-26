@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.drivers.char

import bridge.asm_pause
import bridge.io_in8
import bridge.io_out8
import kotlin.experimental.ExperimentalNativeApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.get
import kotlinx.coroutines.isActive
import kotlinx.coroutines.yield
import org.plos_clan.cpos.coroutines.KernelCoroutines
import org.plos_clan.cpos.drivers.LinuxDeviceMajor
import org.plos_clan.cpos.drivers.char.tty.TtyDriver
import org.plos_clan.cpos.drivers.char.tty.TtyEndpoint
import org.plos_clan.cpos.drivers.char.tty.TtyManager
import org.plos_clan.cpos.drivers.char.tty.TtySession
import org.plos_clan.cpos.drivers.char.tty.WinSize
import org.plos_clan.cpos.fault.IRQ_BASE_VECTOR
import org.plos_clan.cpos.fault.IrqController
import org.plos_clan.cpos.utils.ByteRingBuffer
import org.plos_clan.cpos.utils.Cmdline
import org.plos_clan.cpos.utils.IrqSpinLock

@ExperimentalNativeApi
@Suppress("unused")
@CName("serial_print")
fun serialPrint(buffer: CPointer<ByteVar>?, size: ULong) =
    KernelSerialConsole.write(buffer, size)

private object KernelSerialConsole {
    private const val IO_BASE = 0x3F8u
    private const val LINE_STATUS = 5u
    private const val TRANSMIT_HOLDING_EMPTY = 0x20

    private val lock = IrqSpinLock()
    private var initialized = false

    fun write(buffer: CPointer<ByteVar>?, size: ULong) {
        if (buffer == null || size > Long.MAX_VALUE.toULong()) return
        lock.withLock {
            if (!initialized) {
                configure()
                initialized = true
            }
            for (index in 0L until size.toLong()) {
                val value = buffer[index].toUByte().toInt()
                if (value == '\n'.code) writeByte('\r'.code)
                writeByte(value)
            }
        }
    }

    private fun configure() {
        io_out8((IO_BASE + 1u).toUShort(), 0u)
        io_out8((IO_BASE + 3u).toUShort(), 0x80u)
        io_out8(IO_BASE.toUShort(), 3u)
        io_out8((IO_BASE + 1u).toUShort(), 0u)
        io_out8((IO_BASE + 3u).toUShort(), 3u)
        io_out8((IO_BASE + 2u).toUShort(), 0xC7u)
        io_out8((IO_BASE + 4u).toUShort(), 0x0Bu)
    }

    private fun writeByte(value: Int) {
        while (io_in8((IO_BASE + LINE_STATUS).toUShort()).toInt() and
            TRANSMIT_HOLDING_EMPTY == 0
        ) {
            asm_pause()
        }
        io_out8(IO_BASE.toUShort(), value.toUByte())
    }
}

private enum class SerialParity(
    val option: Char,
    val lineControlBits: Int,
) {
    NONE('n', 0),
    ODD('o', 0x08),
    EVEN('e', 0x18),
    ;

    companion object {
        fun fromOption(option: Char): SerialParity? =
            entries.firstOrNull { it.option == option.lowercaseChar() }
    }
}

private data class SerialLineConfiguration(
    val baudRate: Int = 115_200,
    val parity: SerialParity = SerialParity.NONE,
    val dataBits: Int = 8,
    val stopBits: Int = 1,
)

private data class SerialPortConfiguration(
    val deviceName: String,
    val index: Int,
    val ioBase: UInt,
    val irq: UInt,
    val line: SerialLineConfiguration,
    val rows: Int = 25,
    val columns: Int = 80,
)

private enum class LegacySerialPort(
    val deviceName: String,
    val ioBase: UInt,
    val irq: UInt,
) {
    COM1("ttyS0", 0x3F8u, 4u),
    COM2("ttyS1", 0x2F8u, 3u),
    COM3("ttyS2", 0x3E8u, 4u),
    COM4("ttyS3", 0x2E8u, 3u),
}

internal object SerialConsole {
    fun install(): Boolean {
        val console = Cmdline["console"]
        val selectedName = console?.substringBefore(',')
        val selectedPort = LegacySerialPort.entries.firstOrNull {
            it.deviceName == selectedName
        }
        val selectedOptions = console?.substringAfter(',', missingDelimiterValue = "")
        val selectedLine = when {
            selectedPort == null || selectedOptions.isNullOrEmpty() -> SerialLineConfiguration()
            else -> parseLineConfiguration(selectedOptions) ?: run {
                println("Serial: invalid console options '$console'")
                return false
            }
        }

        for ((index, port) in LegacySerialPort.entries.withIndex()) {
            val line = if (port == selectedPort) selectedLine else SerialLineConfiguration()
            val driver = SerialTtyDriver(
                SerialPortConfiguration(
                    deviceName = port.deviceName,
                    index = index,
                    ioBase = port.ioBase,
                    irq = port.irq,
                    line = line,
                ),
            )
            if (!TtyManager.install(driver)) return false
        }
        return true
    }

    private fun parseLineConfiguration(options: String): SerialLineConfiguration? {
        val baudEnd = options.indexOfFirst { !it.isDigit() }
        if (baudEnd <= 0 || options.length != baudEnd + 2) return null
        val baudRate = options.substring(0, baudEnd).toIntOrNull() ?: return null
        val parity = SerialParity.fromOption(options[baudEnd]) ?: return null
        val dataBits = options[baudEnd + 1].digitToIntOrNull() ?: return null
        return SerialLineConfiguration(baudRate, parity, dataBits)
    }
}

private class SerialTtyDriver(
    private val configuration: SerialPortConfiguration,
) : TtyDriver(
    consoleName = configuration.deviceName,
    terminalType = "vt100",
    bufferedOutput = false,
) {
    override fun createEndpoints(invalidate: () -> Unit): List<TtyEndpoint> = listOf(
        TtyEndpoint(
            name = configuration.deviceName,
            major = LinuxDeviceMajor.TTY.number,
            minor = SERIAL_MINOR_BASE + configuration.index.toUInt(),
            backend = SerialTerminal(configuration),
            inputSpeed = configuration.line.baudRate,
        ),
    )

    private companion object {
        const val SERIAL_MINOR_BASE = 64u
    }
}

private class SerialTerminal(
    private val configuration: SerialPortConfiguration,
) : TerminalBackend() {
    private val uart = Uart16550(configuration)
    private val inputReady = KernelCoroutines.dispatcher.createEvent()

    override fun start(session: TtySession): Boolean {
        val wakeInput = inputReady::signal
        if (!uart.initialize(wakeInput)) return false

        KernelCoroutines.launch("${configuration.deviceName}-rx") {
            val bytes = ByteArray(INPUT_BATCH_SIZE)
            while (isActive) {
                inputReady.await()
                var count = uart.read(bytes)
                while (count != 0) {
                    session.receiveInput(bytes, 0, count)
                    yield()
                    count = uart.read(bytes)
                }
            }
        }
        uart.enableReceiver(wakeInput)
        return true
    }

    override fun writeOutput(data: ByteArray, offset: Int, count: Int) =
        uart.write(data, offset, count)

    override fun windowSize() = WinSize(
        wsRow = configuration.rows.toShort(),
        wsCol = configuration.columns.toShort(),
        wsXpixel = 0,
        wsYpixel = 0,
    )

    override fun closeOutput() = uart.close()

    private companion object {
        const val INPUT_BATCH_SIZE = 256
    }
}

private class Uart16550(
    private val configuration: SerialPortConfiguration,
) {
    private val received = ByteRingBuffer(RECEIVE_BUFFER_SIZE)

    fun initialize(wakeup: () -> Unit): Boolean {
        val divisor = baudDivisor() ?: run {
            println("Serial: unsupported baud rate ${configuration.line.baudRate}")
            return false
        }
        val lineControl = lineControl() ?: run {
            println("Serial: invalid line format for ${configuration.deviceName}")
            return false
        }
        if (configuration.ioBase + Register.SCRATCH.offset > UShort.MAX_VALUE.toUInt() ||
            configuration.rows !in 1..UShort.MAX_VALUE.toInt() ||
            configuration.columns !in 1..UShort.MAX_VALUE.toInt()
        ) {
            println("Serial: invalid configuration for ${configuration.deviceName}")
            return false
        }

        writeRegister(Register.INTERRUPT_ENABLE, 0)
        writeRegister(Register.LINE_CONTROL, DLAB)
        writeRegister(Register.DATA, divisor and 0xFF)
        writeRegister(Register.INTERRUPT_ENABLE, divisor shr Byte.SIZE_BITS)
        writeRegister(Register.LINE_CONTROL, lineControl)
        writeRegister(Register.FIFO_CONTROL, FIFO_CONFIGURATION)
        writeRegister(Register.MODEM_CONTROL, MODEM_CONFIGURATION)

        if (!scratchRegisterWorks() ||
            readRegister(Register.INTERRUPT_IDENTIFICATION) and FIFO_STATUS != FIFO_STATUS
        ) {
            writeRegister(Register.INTERRUPT_ENABLE, 0)
            println("Serial: no 16550 UART at 0x${configuration.ioBase.toString(16)}")
            return false
        }

        val vector = IRQ_BASE_VECTOR + configuration.irq
        if (!IrqController.registerIoApic(
                irq = configuration.irq,
                vector = vector,
                masked = false,
                name = configuration.deviceName,
                handler = { handleInterrupt(wakeup) },
            )
        ) {
            println("Serial: cannot register IRQ ${configuration.irq}")
            return false
        }

        println(
            "Serial: ${configuration.deviceName} io=0x${configuration.ioBase.toString(16)} " +
                "irq=${configuration.irq} baud=${configuration.line.baudRate}",
        )
        return true
    }

    fun enableReceiver(wakeup: () -> Unit) {
        writeRegister(
            Register.INTERRUPT_ENABLE,
            INTERRUPT_RECEIVED_DATA or INTERRUPT_LINE_STATUS,
        )
        handleInterrupt(wakeup)
    }

    fun read(destination: ByteArray): Int = received.read(destination)

    fun write(data: ByteArray, offset: Int, count: Int) {
        var index = offset
        val end = offset + count
        while (index < end) {
            while (readRegister(Register.LINE_STATUS) and TRANSMITTER_EMPTY == 0) asm_pause()
            val batchEnd = minOf(index + FIFO_DEPTH, end)
            while (index < batchEnd) {
                writeRegister(Register.DATA, data[index].toUByte().toInt())
                index++
            }
        }
    }

    fun close() {
        writeRegister(Register.INTERRUPT_ENABLE, 0)
    }

    private fun handleInterrupt(wakeup: () -> Unit) {
        var accepted = false
        received.transaction {
            while (readRegister(Register.LINE_STATUS) and DATA_READY != 0) {
                if (offer(readRegister(Register.DATA).toByte())) accepted = true
            }
        }
        if (accepted) wakeup()
    }

    private fun baudDivisor(): Int? {
        val baudRate = configuration.line.baudRate
        if (baudRate <= 0 || UART_BASE_BAUD % baudRate != 0) return null
        return (UART_BASE_BAUD / baudRate).takeIf { it in 1..UShort.MAX_VALUE.toInt() }
    }

    private fun lineControl(): Int? {
        val line = configuration.line
        if (line.dataBits !in 5..8 || line.stopBits !in 1..2) return null
        return (line.dataBits - 5) or
            (if (line.stopBits == 2) STOP_BITS_2 else 0) or
            line.parity.lineControlBits
    }

    private fun scratchRegisterWorks(): Boolean {
        val original = readRegister(Register.SCRATCH)
        writeRegister(Register.SCRATCH, 0x5A)
        val first = readRegister(Register.SCRATCH)
        writeRegister(Register.SCRATCH, 0xA5)
        val second = readRegister(Register.SCRATCH)
        writeRegister(Register.SCRATCH, original)
        return first == 0x5A && second == 0xA5
    }

    private fun readRegister(register: Register): Int =
        io_in8((configuration.ioBase + register.offset).toUShort()).toInt()

    private fun writeRegister(register: Register, value: Int) {
        io_out8(
            (configuration.ioBase + register.offset).toUShort(),
            value.toUByte(),
        )
    }

    private enum class Register(val offset: UInt) {
        DATA(0u),
        INTERRUPT_ENABLE(1u),
        INTERRUPT_IDENTIFICATION(2u),
        FIFO_CONTROL(2u),
        LINE_CONTROL(3u),
        MODEM_CONTROL(4u),
        LINE_STATUS(5u),
        SCRATCH(7u),
    }

    private companion object {
        const val UART_BASE_BAUD = 115_200
        const val RECEIVE_BUFFER_SIZE = 4096
        const val FIFO_DEPTH = 16

        const val DATA_READY = 0x01
        const val TRANSMITTER_EMPTY = 0x20
        const val FIFO_STATUS = 0xC0

        const val INTERRUPT_RECEIVED_DATA = 0x01
        const val INTERRUPT_LINE_STATUS = 0x04

        const val DLAB = 0x80
        const val STOP_BITS_2 = 0x04
        const val FIFO_CONFIGURATION = 0xC7
        const val MODEM_CONFIGURATION = 0x0B
    }
}
