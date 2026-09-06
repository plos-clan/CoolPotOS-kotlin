package org.plos_clan.cpos.drivers.input

import kotlinx.coroutines.delay
import org.plos_clan.cpos.coroutines.KernelCoroutines
import org.plos_clan.cpos.drivers.DeviceManager
import org.plos_clan.cpos.drivers.DeviceRegistration
import org.plos_clan.cpos.drivers.DeviceType
import org.plos_clan.cpos.drivers.LinuxDeviceMajor
import org.plos_clan.cpos.drivers.TscClock
import org.plos_clan.cpos.drivers.char.VirtualTerminal
import org.plos_clan.cpos.drivers.char.tty.TtyManager
import org.plos_clan.cpos.fs.sysfs.SysfsDevicePublication
import org.plos_clan.cpos.utils.IrqSpinLock

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
                delay(waitMillis.toLong()) // 该语句不应被优化成 milliseconds 调用形式
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
    private val encoder = ConsoleKeyEncoder()
    private val input = ByteArray(ConsoleKeyEncoder.MAX_BYTES)

    override fun receive(event: InputEvent) {
        if (event.type != InputEventType.KEY) return
        val action = when (event.value) {
            KeyAction.RELEASED.value -> KeyAction.RELEASED
            KeyAction.PRESSED.value -> KeyAction.PRESSED
            KeyAction.REPEATED.value -> KeyAction.REPEATED
            else -> return
        }
        TtyManager.withActiveVirtualTerminal { session, backend ->
            val terminal = backend as? VirtualTerminal ?: return@withActiveVirtualTerminal
            val count = encoder.encode(event.code.toInt(), action, terminal.keyboardMode, input)
            if (count != 0) terminal.receiveInput(session, input, 0, count)
        }
    }
}
