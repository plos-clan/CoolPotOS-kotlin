package org.plos_clan.cpos.drivers.input

import kotlinx.coroutines.delay
import org.plos_clan.cpos.coroutines.KernelCoroutines
import org.plos_clan.cpos.drivers.DeviceManager
import org.plos_clan.cpos.drivers.DeviceRegistration
import org.plos_clan.cpos.drivers.DeviceType
import org.plos_clan.cpos.drivers.LinuxDeviceMajor
import org.plos_clan.cpos.drivers.TscClock
import org.plos_clan.cpos.drivers.char.tty.TtyManager
import org.plos_clan.cpos.fs.sysfs.SysfsDevicePublication
import org.plos_clan.cpos.utils.IrqSpinLock

internal enum class KeyModifier(val mask: Int) {
    SHIFT(1 shl 0),
    CONTROL(1 shl 1),
    ALT(1 shl 2),
    META(1 shl 3),
}

internal enum class KeyCode(
    val linuxCode: UShort,
    val hidUsage: Int = -1,
    val set1Code: Int = -1,
    val extendedSet1: Boolean = false,
    val normal: Char? = null,
    val shifted: Char? = normal,
    val letter: Boolean = false,
    sequence: String? = null,
    val modifier: KeyModifier? = null,
    val repeatable: Boolean = normal != null || sequence != null,
) {
    ESCAPE(1u, 0x29, 0x01, normal = '\u001B'),
    DIGIT_1(2u, 0x1E, 0x02, normal = '1', shifted = '!'),
    DIGIT_2(3u, 0x1F, 0x03, normal = '2', shifted = '@'),
    DIGIT_3(4u, 0x20, 0x04, normal = '3', shifted = '#'),
    DIGIT_4(5u, 0x21, 0x05, normal = '4', shifted = '$'),
    DIGIT_5(6u, 0x22, 0x06, normal = '5', shifted = '%'),
    DIGIT_6(7u, 0x23, 0x07, normal = '6', shifted = '^'),
    DIGIT_7(8u, 0x24, 0x08, normal = '7', shifted = '&'),
    DIGIT_8(9u, 0x25, 0x09, normal = '8', shifted = '*'),
    DIGIT_9(10u, 0x26, 0x0A, normal = '9', shifted = '('),
    DIGIT_0(11u, 0x27, 0x0B, normal = '0', shifted = ')'),
    MINUS(12u, 0x2D, 0x0C, normal = '-', shifted = '_'),
    EQUAL(13u, 0x2E, 0x0D, normal = '=', shifted = '+'),
    BACKSPACE(14u, 0x2A, 0x0E, normal = '\u007F'),
    TAB(15u, 0x2B, 0x0F, normal = '\t'),
    Q(16u, 0x14, 0x10, normal = 'q', shifted = 'Q', letter = true),
    W(17u, 0x1A, 0x11, normal = 'w', shifted = 'W', letter = true),
    E(18u, 0x08, 0x12, normal = 'e', shifted = 'E', letter = true),
    R(19u, 0x15, 0x13, normal = 'r', shifted = 'R', letter = true),
    T(20u, 0x17, 0x14, normal = 't', shifted = 'T', letter = true),
    Y(21u, 0x1C, 0x15, normal = 'y', shifted = 'Y', letter = true),
    U(22u, 0x18, 0x16, normal = 'u', shifted = 'U', letter = true),
    I(23u, 0x0C, 0x17, normal = 'i', shifted = 'I', letter = true),
    O(24u, 0x12, 0x18, normal = 'o', shifted = 'O', letter = true),
    P(25u, 0x13, 0x19, normal = 'p', shifted = 'P', letter = true),
    LEFT_BRACKET(26u, 0x2F, 0x1A, normal = '[', shifted = '{'),
    RIGHT_BRACKET(27u, 0x30, 0x1B, normal = ']', shifted = '}'),
    ENTER(28u, 0x28, 0x1C, normal = '\n'),
    LEFT_CTRL(29u, 0xE0, 0x1D, modifier = KeyModifier.CONTROL),
    A(30u, 0x04, 0x1E, normal = 'a', shifted = 'A', letter = true),
    S(31u, 0x16, 0x1F, normal = 's', shifted = 'S', letter = true),
    D(32u, 0x07, 0x20, normal = 'd', shifted = 'D', letter = true),
    F(33u, 0x09, 0x21, normal = 'f', shifted = 'F', letter = true),
    G(34u, 0x0A, 0x22, normal = 'g', shifted = 'G', letter = true),
    H(35u, 0x0B, 0x23, normal = 'h', shifted = 'H', letter = true),
    J(36u, 0x0D, 0x24, normal = 'j', shifted = 'J', letter = true),
    K(37u, 0x0E, 0x25, normal = 'k', shifted = 'K', letter = true),
    L(38u, 0x0F, 0x26, normal = 'l', shifted = 'L', letter = true),
    SEMICOLON(39u, 0x33, 0x27, normal = ';', shifted = ':'),
    APOSTROPHE(40u, 0x34, 0x28, normal = '\'', shifted = '"'),
    GRAVE(41u, 0x35, 0x29, normal = '`', shifted = '~'),
    LEFT_SHIFT(42u, 0xE1, 0x2A, modifier = KeyModifier.SHIFT),
    BACKSLASH(43u, 0x31, 0x2B, normal = '\\', shifted = '|'),
    Z(44u, 0x1D, 0x2C, normal = 'z', shifted = 'Z', letter = true),
    X(45u, 0x1B, 0x2D, normal = 'x', shifted = 'X', letter = true),
    C(46u, 0x06, 0x2E, normal = 'c', shifted = 'C', letter = true),
    V(47u, 0x19, 0x2F, normal = 'v', shifted = 'V', letter = true),
    B(48u, 0x05, 0x30, normal = 'b', shifted = 'B', letter = true),
    N(49u, 0x11, 0x31, normal = 'n', shifted = 'N', letter = true),
    M(50u, 0x10, 0x32, normal = 'm', shifted = 'M', letter = true),
    COMMA(51u, 0x36, 0x33, normal = ',', shifted = '<'),
    DOT(52u, 0x37, 0x34, normal = '.', shifted = '>'),
    SLASH(53u, 0x38, 0x35, normal = '/', shifted = '?'),
    RIGHT_SHIFT(54u, 0xE5, 0x36, modifier = KeyModifier.SHIFT),
    KEYPAD_MULTIPLY(55u, 0x55, 0x37, normal = '*'),
    LEFT_ALT(56u, 0xE2, 0x38, modifier = KeyModifier.ALT),
    SPACE(57u, 0x2C, 0x39, normal = ' '),
    CAPS_LOCK(58u, 0x39, 0x3A, repeatable = false),
    F1(59u, 0x3A, 0x3B, sequence = "\u001BOP"),
    F2(60u, 0x3B, 0x3C, sequence = "\u001BOQ"),
    F3(61u, 0x3C, 0x3D, sequence = "\u001BOR"),
    F4(62u, 0x3D, 0x3E, sequence = "\u001BOS"),
    F5(63u, 0x3E, 0x3F, sequence = "\u001B[15~"),
    F6(64u, 0x3F, 0x40, sequence = "\u001B[17~"),
    F7(65u, 0x40, 0x41, sequence = "\u001B[18~"),
    F8(66u, 0x41, 0x42, sequence = "\u001B[19~"),
    F9(67u, 0x42, 0x43, sequence = "\u001B[20~"),
    F10(68u, 0x43, 0x44, sequence = "\u001B[21~"),
    NUM_LOCK(69u, 0x53, 0x45, repeatable = false),
    SCROLL_LOCK(70u, 0x47, 0x46, repeatable = false),
    KEYPAD_7(71u, 0x5F, 0x47, normal = '7'),
    KEYPAD_8(72u, 0x60, 0x48, normal = '8'),
    KEYPAD_9(73u, 0x61, 0x49, normal = '9'),
    KEYPAD_MINUS(74u, 0x56, 0x4A, normal = '-'),
    KEYPAD_4(75u, 0x5C, 0x4B, normal = '4'),
    KEYPAD_5(76u, 0x5D, 0x4C, normal = '5'),
    KEYPAD_6(77u, 0x5E, 0x4D, normal = '6'),
    KEYPAD_PLUS(78u, 0x57, 0x4E, normal = '+'),
    KEYPAD_1(79u, 0x59, 0x4F, normal = '1'),
    KEYPAD_2(80u, 0x5A, 0x50, normal = '2'),
    KEYPAD_3(81u, 0x5B, 0x51, normal = '3'),
    KEYPAD_0(82u, 0x62, 0x52, normal = '0'),
    KEYPAD_DOT(83u, 0x63, 0x53, normal = '.'),
    F11(87u, 0x44, 0x57, sequence = "\u001B[23~"),
    F12(88u, 0x45, 0x58, sequence = "\u001B[24~"),
    KEYPAD_ENTER(96u, 0x58, 0x1C, extendedSet1 = true, normal = '\n'),
    RIGHT_CTRL(97u, 0xE4, 0x1D, extendedSet1 = true, modifier = KeyModifier.CONTROL),
    KEYPAD_DIVIDE(98u, 0x54, 0x35, extendedSet1 = true, normal = '/'),
    PRINT_SCREEN(99u, 0x46, 0x37, extendedSet1 = true, repeatable = false),
    RIGHT_ALT(100u, 0xE6, 0x38, extendedSet1 = true, modifier = KeyModifier.ALT),
    HOME(102u, 0x4A, 0x47, extendedSet1 = true, sequence = "\u001B[H"),
    UP(103u, 0x52, 0x48, extendedSet1 = true, sequence = "\u001B[A"),
    PAGE_UP(104u, 0x4B, 0x49, extendedSet1 = true, sequence = "\u001B[5~"),
    LEFT(105u, 0x50, 0x4B, extendedSet1 = true, sequence = "\u001B[D"),
    RIGHT(106u, 0x4F, 0x4D, extendedSet1 = true, sequence = "\u001B[C"),
    END(107u, 0x4D, 0x4F, extendedSet1 = true, sequence = "\u001B[F"),
    DOWN(108u, 0x51, 0x50, extendedSet1 = true, sequence = "\u001B[B"),
    PAGE_DOWN(109u, 0x4E, 0x51, extendedSet1 = true, sequence = "\u001B[6~"),
    INSERT(110u, 0x49, 0x52, extendedSet1 = true, sequence = "\u001B[2~"),
    DELETE(111u, 0x4C, 0x53, extendedSet1 = true, sequence = "\u001B[3~"),
    PAUSE(119u, 0x48, repeatable = false),
    LEFT_META(125u, 0xE3, 0x5B, extendedSet1 = true, modifier = KeyModifier.META),
    RIGHT_META(126u, 0xE7, 0x5C, extendedSet1 = true, modifier = KeyModifier.META),
    APPLICATION(127u, 0x65, 0x5D, extendedSet1 = true, repeatable = false),
    ;

    val sequence: CharArray? = sequence?.toCharArray()

    companion object {
        internal val stateSize = entries.maxOf { it.linuxCode.toInt() } + 1

        private val byHidUsage = arrayOfNulls<KeyCode>(entries.maxOf { it.hidUsage } + 1)
        private val byLinuxCode = arrayOfNulls<KeyCode>(stateSize)
        private val bySet1Code = arrayOfNulls<KeyCode>(entries.maxOf { it.set1Code } + 1)
        private val byExtendedSet1Code = arrayOfNulls<KeyCode>(bySet1Code.size)

        init {
            entries.forEach { key ->
                if (key.hidUsage in byHidUsage.indices) byHidUsage[key.hidUsage] = key
                if (key.linuxCode.toInt() in byLinuxCode.indices) {
                    byLinuxCode[key.linuxCode.toInt()] = key
                }
                if (key.set1Code in bySet1Code.indices) {
                    val table = if (key.extendedSet1) byExtendedSet1Code else bySet1Code
                    table[key.set1Code] = key
                }
            }
        }

        fun fromHidUsage(usage: Int): KeyCode? = byHidUsage.getOrNull(usage)

        fun fromLinuxCode(code: Int): KeyCode? = byLinuxCode.getOrNull(code)

        fun fromSet1(code: Int, extended: Boolean): KeyCode? =
            (if (extended) byExtendedSet1Code else bySet1Code).getOrNull(code)
    }
}

internal class KeyboardReport(
    private val keyboard: KeyboardInputDevice,
) {
    private val pressed = BooleanArray(KeyCode.stateSize)
    private val updated = BooleanArray(KeyCode.stateSize)
    private var valid = true

    internal fun reset(): KeyboardReport {
        pressed.fill(false)
        updated.fill(false)
        valid = true
        return this
    }

    fun setHidUsage(usage: Int, pressed: Boolean = true) {
        if (usage in HID_ERROR_ROLLOVER..HID_ERROR_UNDEFINED) {
            valid = false
            return
        }
        val key = KeyCode.fromHidUsage(usage) ?: return
        val index = key.linuxCode.toInt()
        updated[index] = true
        this.pressed[index] = this.pressed[index] || pressed
    }

    fun coverHidRange(firstUsage: UInt, lastUsage: UInt) {
        val first = (firstUsage and HID_USAGE_MASK).toInt()
        val last = (lastUsage and HID_USAGE_MASK).toInt()
        if (first > last) {
            valid = false
            return
        }
        KeyCode.entries.forEach { key ->
            if (key.hidUsage in first..last) {
                updated[key.linuxCode.toInt()] = true
            }
        }
    }

    fun invalidate() {
        valid = false
    }

    fun commit() {
        if (valid) keyboard.submitReport(pressed, updated)
    }

    private companion object {
        const val HID_ERROR_ROLLOVER = 1
        const val HID_ERROR_UNDEFINED = 3
        const val HID_USAGE_MASK = 0xffffu
    }
}

internal class KeyboardInputDevice(
    private val eventIndex: Int,
    deviceName: String,
    physicalPath: String,
    id: InputId,
) : RepeatController {
    private data class Transition(val key: KeyCode, val action: KeyAction)

    private val lock = IrqSpinLock()
    private val pressed = BooleanArray(KeyCode.stateSize)
    private val report = KeyboardReport(this)
    private val console = ConsoleKeyboard()
    private val evdev = EvdevDevice(deviceName, physicalPath, id, KeyCode.entries, this)
    private var repeatDelayMillis = DEFAULT_REPEAT_DELAY_MILLIS
    private var repeatPeriodMillis = DEFAULT_REPEAT_PERIOD_MILLIS
    private var repeatKey: KeyCode? = null
    private var repeatGeneration = 0

    internal fun install(): Boolean {
        val minor = EVENT_MINOR_BASE + eventIndex
        return DeviceManager.register(
            DeviceRegistration(
                name = "input/event$eventIndex",
                type = DeviceType.CHARACTER,
                major = LinuxDeviceMajor.INPUT.number,
                minor = minor.toUInt(),
                backend = evdev,
                sysfs = SysfsDevicePublication.virtual("input", "event$eventIndex"),
            ),
        ) != null
    }

    internal fun uninstall(): Boolean = DeviceManager.unregisterAll(evdev) != 0

    fun beginReport(): KeyboardReport = report.reset()

    fun submit(key: KeyCode, pressed: Boolean) {
        var startRepeat = false
        var generation = 0
        val action = lock.withLock {
            val index = key.linuxCode.toInt()
            if (this.pressed[index] == pressed) return@withLock null
            this.pressed[index] = pressed
            val transition = if (pressed) KeyAction.PRESSED else KeyAction.RELEASED
            startRepeat = updateRepeatLocked(key, transition)
            generation = repeatGeneration
            transition
        } ?: return
        if (startRepeat) launchRepeat(key, generation)
        publish(key, action)
    }

    fun submitPause() {
        val transitions = listOf(
            Transition(KeyCode.PAUSE, KeyAction.PRESSED),
            Transition(KeyCode.PAUSE, KeyAction.RELEASED),
        )
        publish(transitions)
    }

    internal fun submitReport(report: BooleanArray, updated: BooleanArray) {
        var repeatKeyToStart: KeyCode? = null
        var generation = 0
        val transitions = lock.withLock {
            var changes: MutableList<Transition>? = null
            KeyCode.entries.forEach { key ->
                val index = key.linuxCode.toInt()
                if (!updated[index] || pressed[index] == report[index]) return@forEach
                pressed[index] = report[index]
                val action = if (report[index]) KeyAction.PRESSED else KeyAction.RELEASED
                val transition = Transition(key, action)
                (changes ?: ArrayList<Transition>().also { changes = it }).add(transition)
                if (updateRepeatLocked(key, action)) {
                    repeatKeyToStart = key
                    generation = repeatGeneration
                }
            }
            changes
        } ?: return
        repeatKeyToStart?.let { launchRepeat(it, generation) }
        publish(transitions)
    }

    override fun repeatSettings(): RepeatSettings = lock.withLock {
        RepeatSettings(repeatDelayMillis, repeatPeriodMillis)
    }

    override fun configureRepeat(settings: RepeatSettings): Boolean {
        if (settings.delayMillis !in 0..MAX_REPEAT_MILLIS ||
            settings.periodMillis !in 0..MAX_REPEAT_MILLIS
        ) return false
        var keyToRestart: KeyCode? = null
        var generation = 0
        lock.withLock {
            repeatDelayMillis = settings.delayMillis
            repeatPeriodMillis = settings.periodMillis
            repeatGeneration++
            keyToRestart = repeatKey?.takeIf {
                pressed[it.linuxCode.toInt()] && settings.periodMillis != 0
            }
            generation = repeatGeneration
        }
        keyToRestart?.let { launchRepeat(it, generation) }
        return true
    }

    private fun updateRepeatLocked(key: KeyCode, action: KeyAction): Boolean = when (action) {
        KeyAction.PRESSED if key.repeatable -> {
            repeatKey = key
            repeatGeneration++
            repeatPeriodMillis != 0
        }
        KeyAction.RELEASED if repeatKey == key -> {
            repeatKey = null
            repeatGeneration++
            false
        }
        else -> false
    }

    private fun launchRepeat(key: KeyCode, generation: Int) {
        KernelCoroutines.launch("event$eventIndex-repeat") {
            var waitMillis = lock.withLock { repeatDelayMillis }
            while (true) {
                delay(waitMillis.toLong())
                val period = lock.withLock {
                    if (repeatGeneration != generation || repeatKey != key ||
                        !pressed[key.linuxCode.toInt()] || repeatPeriodMillis == 0
                    ) return@launch
                    repeatPeriodMillis
                }
                publish(key, KeyAction.REPEATED)
                waitMillis = period
            }
        }
    }

    private fun publish(key: KeyCode, action: KeyAction) {
        val timestamp = TscClock.nanoTime()
        val event = InputEvent(timestamp, InputEventType.KEY, key.linuxCode, action.value)
        console.receive(event)
        evdev.receive(event)
        evdev.receive(
            InputEvent(
                timestamp,
                InputEventType.SYNCHRONIZATION,
                InputEvent.SYN_REPORT,
                0,
            ),
        )
    }

    private fun publish(transitions: List<Transition>) {
        val timestamp = TscClock.nanoTime()
        transitions.forEach { transition ->
            val event = InputEvent(
                timestamp,
                InputEventType.KEY,
                transition.key.linuxCode,
                transition.action.value,
            )
            console.receive(event)
            evdev.receive(event)
        }
        evdev.receive(
            InputEvent(
                timestamp,
                InputEventType.SYNCHRONIZATION,
                InputEvent.SYN_REPORT,
                0,
            ),
        )
    }

    private companion object {
        const val EVENT_MINOR_BASE = 64
        const val DEFAULT_REPEAT_DELAY_MILLIS = 250
        const val DEFAULT_REPEAT_PERIOD_MILLIS = 33
        const val MAX_REPEAT_MILLIS = 60_000
    }
}

internal object InputManager {
    private val lock = IrqSpinLock()
    private val keyboards = mutableMapOf<Any, KeyboardInputDevice>()
    private var nextEventIndex = 0

    fun findKeyboard(source: Any): KeyboardInputDevice? =
        lock.withLock { keyboards[source] }

    fun unregisterKeyboard(source: Any): Boolean = lock.withLock {
        val keyboard = keyboards.remove(source) ?: return@withLock false
        keyboard.uninstall()
    }

    fun registerKeyboard(
        source: Any,
        name: String,
        physicalPath: String,
        id: InputId,
    ): KeyboardInputDevice? = lock.withLock {
        keyboards[source]?.let { return@withLock it }
        val eventIndex = nextEventIndex
        val keyboard = KeyboardInputDevice(eventIndex, name, physicalPath, id)
        if (!keyboard.install()) return@withLock null
        nextEventIndex++
        keyboards[source] = keyboard
        keyboard
    }
}

private class ConsoleKeyboard : InputEventSink {
    private var modifiers = 0
    private var capsLock = false

    override fun receive(event: InputEvent) {
        if (event.type != InputEventType.KEY) return
        val key = KeyCode.fromLinuxCode(event.code.toInt()) ?: return
        val action = when (event.value) {
            KeyAction.RELEASED.value -> KeyAction.RELEASED
            KeyAction.PRESSED.value -> KeyAction.PRESSED
            KeyAction.REPEATED.value -> KeyAction.REPEATED
            else -> return
        }
        key.modifier?.let { modifier ->
            modifiers = when (action) {
                KeyAction.PRESSED -> modifiers or modifier.mask
                KeyAction.RELEASED -> modifiers and modifier.mask.inv()
                KeyAction.REPEATED -> modifiers
            }
            return
        }
        if (key == KeyCode.CAPS_LOCK) {
            if (action == KeyAction.PRESSED) capsLock = !capsLock
            return
        }
        if (action == KeyAction.RELEASED || TtyManager.vts.isEmpty()) return

        val input = key.sequence ?: key.character(modifiers, capsLock)?.let { character ->
            ASCII_INPUT.getOrNull(character.code)
        } ?: return
        val terminal = TtyManager.getActiveVT()
        if (modifiers and KeyModifier.ALT.mask != 0) terminal.keyboardInput(ESCAPE_PREFIX)
        terminal.keyboardInput(input)
    }

    private fun KeyCode.character(modifiers: Int, capsLock: Boolean): Char? {
        val shifted = modifiers and KeyModifier.SHIFT.mask != 0
        val selected = when {
            letter && shifted.xor(capsLock) -> this.shifted
            shifted -> this.shifted
            else -> normal
        } ?: return null
        if (!letter || modifiers and KeyModifier.CONTROL.mask == 0) return selected
        return (selected.lowercaseChar().code - 'a'.code + 1).toChar()
    }

    private companion object {
        val ASCII_INPUT = Array(128) { charArrayOf(it.toChar()) }
        val ESCAPE_PREFIX = charArrayOf('\u001B')
    }
}
