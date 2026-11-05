package gba.data

/**
 * `Decompress` (src/bmlib.c): unpacks a ROM graphics blob by the type in its header's high nibble:
 * 0x00 raw (`UnpackRaw`: size in header bytes 1..3 includes the 4-byte header) or 0x10 LZ77.
 * Huffman (0x20) and RL (0x30) are not supported yet. The VRAM/WRAM variants of the BIOS calls only
 * differ in write width, so the result is the same for either destination.
 *
 * Partially ported from src/bmlib.c: Decompress
 */
public object Decompress {
    /**
     * Decodes the blob at [offset] of [src] into [dst] at [dstOffset]; returns the bytes written.
     * Does not allocate.
     */
    public fun into(src: ByteArray, offset: Int, dst: ByteArray, dstOffset: Int): Int {
        val type = src[offset].toInt() and 0xF0
        return when (type) {
            0x00 -> {
                val size = rawSize(src, offset)
                src.copyInto(dst, dstOffset, offset + 4, offset + 4 + size)
                size
            }
            Lz77.TYPE -> {
                Lz77.decodeInto(src, offset, dst, dstOffset)
                Lz77.headerSize(src, offset)
            }
            else -> throw UnsupportedOperationException(
                "Decompress type 0x${type.toString(16)} not supported yet",
            )
        }
    }

    /** Decodes the blob at [offset] of [src] into a new array. */
    public fun decode(src: ByteArray, offset: Int): ByteArray {
        val type = src[offset].toInt() and 0xF0
        if (type == Lz77.TYPE) return Lz77.decode(src, offset).data
        val out = ByteArray(maxOf(rawSize(src, offset), 0))
        into(src, offset, out, 0)
        return out
    }

    private fun rawSize(src: ByteArray, offset: Int): Int {
        val b1 = src[offset + 1].toInt() and 0xFF
        val b2 = src[offset + 2].toInt() and 0xFF
        val b3 = src[offset + 3].toInt() and 0xFF
        return (b1 or (b2 shl 8) or (b3 shl 16)) - 4
    }
}
