package gbafe.extract

/**
 * Upper-case hexadecimal of [v] as its 32-bit two's-complement bit pattern, zero-padded to
 * [width] digits: the common-code replacement for `"%X"` / `"%08X"`-style `String.format` calls.
 */
internal fun hexUpper(v: Int, width: Int = 1): String =
    v.toUInt().toString(16).uppercase().padStart(width, '0')

/**
 * Decimal of [v] zero-padded to [width] characters, the sign counting towards the width (the
 * padding goes after the sign, as `"%03d"` does).
 */
internal fun padDec(v: Int, width: Int): String {
    // Widen to Long first so Int.MIN_VALUE can be negated.
    val digits = v.toLong().let { if (it < 0) -it else it }.toString()
    return if (v < 0) "-" + digits.padStart(width - 1, '0') else digits.padStart(width, '0')
}
