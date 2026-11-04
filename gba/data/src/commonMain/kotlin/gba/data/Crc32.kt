package gba.data

/**
 * CRC-32 (zlib / PNG polynomial) in pure Kotlin for common code; same results as
 * `java.util.zip.CRC32`.
 */
public class Crc32 {
    private var crc: Int = -1

    /** Adds the low 8 bits of [b]. */
    public fun update(b: Int) {
        crc = TABLE[(crc xor b) and 0xFF] xor (crc ushr 8)
    }

    /** Adds [length] bytes of [bytes] starting at [offset]. */
    public fun update(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        var c = crc
        for (i in offset until offset + length) {
            c = TABLE[(c xor bytes[i].toInt()) and 0xFF] xor (c ushr 8)
        }
        crc = c
    }

    /** The CRC-32 of everything added so far, 0..0xFFFFFFFF. */
    public val value: Long get() = (crc.inv()).toLong() and 0xFFFFFFFFL

    /** Starts over with empty input. */
    public fun reset() {
        crc = -1
    }

    private companion object {
        val TABLE = IntArray(256) { n ->
            var c = n
            repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
            c
        }
    }
}
