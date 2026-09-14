package org.plos_clan.cpos.drivers.input

internal enum class KeyAction(val value: Int) {
    RELEASED(0),
    PRESSED(1),
    REPEATED(2),
}

internal enum class KeyModifier(val mask: Int) {
    LEFT_SHIFT(1 shl 0), RIGHT_SHIFT(1 shl 1),
    LEFT_CONTROL(1 shl 2), RIGHT_CONTROL(1 shl 3),
    LEFT_ALT(1 shl 4), RIGHT_ALT(1 shl 5),
    LEFT_META(1 shl 6), RIGHT_META(1 shl 7);

    companion object {
        const val SHIFT = 0b0000_0011
        const val CONTROL = 0b0000_1100
        const val ALT = 0b0011_0000
    }
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
    LEFT_CTRL(29u, 0xE0, 0x1D, modifier = KeyModifier.LEFT_CONTROL),
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
    LEFT_SHIFT(42u, 0xE1, 0x2A, modifier = KeyModifier.LEFT_SHIFT),
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
    RIGHT_SHIFT(54u, 0xE5, 0x36, modifier = KeyModifier.RIGHT_SHIFT),
    KEYPAD_MULTIPLY(55u, 0x55, 0x37, normal = '*'),
    LEFT_ALT(56u, 0xE2, 0x38, modifier = KeyModifier.LEFT_ALT),
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
    RIGHT_CTRL(97u, 0xE4, 0x1D, extendedSet1 = true, modifier = KeyModifier.RIGHT_CONTROL),
    KEYPAD_DIVIDE(98u, 0x54, 0x35, extendedSet1 = true, normal = '/'),
    PRINT_SCREEN(99u, 0x46, 0x37, extendedSet1 = true, repeatable = false),
    RIGHT_ALT(100u, 0xE6, 0x38, extendedSet1 = true, modifier = KeyModifier.RIGHT_ALT),
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
    LEFT_META(125u, 0xE3, 0x5B, extendedSet1 = true, modifier = KeyModifier.LEFT_META),
    RIGHT_META(126u, 0xE7, 0x5C, extendedSet1 = true, modifier = KeyModifier.RIGHT_META),
    APPLICATION(127u, 0x65, 0x5D, extendedSet1 = true, repeatable = false),
    ;

    val sequence: ByteArray? = sequence?.encodeToByteArray()

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
