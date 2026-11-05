package gbafe.save

/**
 * Standard CRC-32 (IEEE 802.3, reflected polynomial 0xEDB88320), pure Kotlin, no allocation per
 * call.
 */
public object Crc32 {
    private const val POLY: Int = 0xEDB88320.toInt()

    private val table: IntArray = IntArray(256) { n ->
        var c = n
        repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor POLY else c ushr 1 }
        c
    }

    /** Checksum of `data[offset, offset + length)`. */
    public fun compute(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int =
        update(0, data, offset, length)

    /**
     * Continues a running checksum [crc] (0 for a new one) over `data[offset, offset + length)`.
     */
    public fun update(crc: Int, data: ByteArray, offset: Int, length: Int): Int {
        var c = crc.inv()
        for (i in offset until offset + length) {
            c = table[(c xor data[i].toInt()) and 0xFF] xor (c ushr 8)
        }
        return c.inv()
    }
}
