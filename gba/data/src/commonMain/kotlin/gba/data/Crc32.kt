package gba.data

/**
 * CRC-32 (zlib / PNG / IEEE 802.3) in pure Kotlin for common code; `Crc32Test` compares it with
 * `java.util.zip.CRC32`. Uses the reflected polynomial `0xEDB88320` (the bit-reversed form of
 * `0x04C11DB7`), table-driven one byte at a time.
 */
public class Crc32 {
    /** Running register, before the final bit inversion that [value] applies. */
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

    /** Constants and the byte lookup table. */
    private companion object {
        /** Register start value (all bits set), as the CRC-32 definition requires. */
        private const val INITIAL_CRC: Int = -1

        /** Reflected CRC-32 polynomial. */
        private const val POLYNOMIAL: Int = 0xEDB88320.toInt()

        /** For each byte value n, the register after shifting n through 8 bits of [POLYNOMIAL]. */
        val TABLE: IntArray = IntArray(256) { n ->
            var remainder = n
            repeat(8) {
                val shifted = remainder ushr 1
                remainder = if (remainder and 1 != 0) shifted xor POLYNOMIAL else shifted
            }
            remainder
        }
    }
}
