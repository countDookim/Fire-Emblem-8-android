package gba.data

/**
 * CRC-32 (zlib / PNG polynomial) in pure Kotlin for common code; same results as
 * `java.util.zip.CRC32`.
 *
 * Implements the standard IEEE 802.3 32-bit cyclic redundancy check polynomial
 * (`0xEDB88320`, the bit-reversed form of `0x04C11DB7`).
 */
public class Crc32 {
    /** Running CRC accumulator state. Initialized to all 1s (`-1`). */
    private var crc: Int = INITIAL_CRC

    /** Adds the low 8 bits of [b] to the running CRC calculation. */
    public fun update(b: Int) {
        crc = TABLE[(crc xor b) and 0xFF] xor (crc ushr 8)
    }

    /** Adds [length] bytes of [bytes] starting at [offset] to the running CRC calculation. */
    public fun update(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        var currentCrc = crc
        for (i in offset until offset + length) {
            currentCrc = TABLE[(currentCrc xor bytes[i].toInt()) and 0xFF] xor (currentCrc ushr 8)
        }
        crc = currentCrc
    }

    /** The CRC-32 of everything added so far, 0..0xFFFFFFFF. */
    public val value: Long get() = (crc.inv()).toLong() and 0xFFFFFFFFL

    /** Starts over with empty input. */
    public fun reset() {
        crc = INITIAL_CRC
    }

    private companion object {
        /** Initial value of the CRC-32 register. */
        private const val INITIAL_CRC: Int = -1

        /** Reversed polynomial for IEEE 802.3 CRC-32. */
        private const val POLYNOMIAL: Int = 0xEDB88320.toInt()

        /** Lookup table of 256 precomputed 32-bit remainders. */
        val TABLE: IntArray = IntArray(256) { n ->
            var remainder = n
            repeat(8) {
                remainder = if (remainder and 1 != 0) (remainder ushr 1) xor POLYNOMIAL else remainder ushr 1
            }
            remainder
        }
    }
}
