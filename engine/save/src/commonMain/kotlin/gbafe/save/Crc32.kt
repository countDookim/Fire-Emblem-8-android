package gbafe.save

/**
 * Standard CRC-32 (IEEE 802.3, reflected polynomial `0xEDB88320`) in pure Kotlin, allocating
 * nothing per call. Used to verify integrity of save container headers and section payloads.
 */
public object Crc32 {
    /** Reflected IEEE 802.3 polynomial (`0x04C11DB7` reversed). */
    private const val POLY: Int = 0xEDB88320.toInt()

    /** Lookup table of 256 precomputed 32-bit CRC remainders. */
    private val table: IntArray = IntArray(256) { n ->
        var remainder = n
        repeat(8) {
            remainder = if (remainder and 1 != 0) (remainder ushr 1) xor POLY else remainder ushr 1
        }
        remainder
    }

    /** Computes the CRC-32 checksum of `data[offset, offset + length)`. */
    public fun compute(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int =
        update(0, data, offset, length)

    /**
     * Continues a running checksum [crc] (pass 0 for initial calculation) over `data[offset, offset + length)`.
     */
    public fun update(crc: Int, data: ByteArray, offset: Int, length: Int): Int {
        var currentCrc = crc.inv()
        for (i in offset until offset + length) {
            currentCrc = table[(currentCrc xor data[i].toInt()) and 0xFF] xor (currentCrc ushr 8)
        }
        return currentCrc.inv()
    }
}
