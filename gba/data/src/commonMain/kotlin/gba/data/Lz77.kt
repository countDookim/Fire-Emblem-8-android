package gba.data

/**
 * Decompressor for the GBA BIOS LZ77 format (`LZ77UnCompWram`/`LZ77UnCompVram`, SWI 0x11/0x12),
 * which the original game uses for most graphics and tilemaps.
 *
 * [decode] is load-time only (extraction and asset loading) and returns a fresh array;
 * [decodeInto] does not allocate.
 *
 * Format: a 4-byte header `0x10 | (decompressedSize << 8)`, then blocks of one flag byte (MSB
 * first)
 * followed by 8 items: flag 0 = one literal byte; flag 1 = a 2-byte back-reference
 * `length = (b0 >> 4) + 3`, `distance = ((b0 & 0xF) << 8 | b1) + 1`. References may overlap
 * their own
 * output (run-length style), so bytes are copied one at a time. Decoding stops exactly at
 * `decompressedSize`, ignoring any remaining flag bits, like the BIOS.
 */
public object Lz77 {
    /** Header type byte of an LZ77 stream. */
    public const val TYPE: Int = 0x10

    /** Result of [decode]: the output bytes and how many input bytes the stream occupied. */
    public class Result(
        /** Decompressed data (`size` bytes from the header). */
        public val data: ByteArray,
        /** Input bytes consumed, including the header; ROM pads streams to 4 bytes after this. */
        public val consumed: Int,
    )

    /** Decompressed size announced by the header at [offset], or -1 if it is not an LZ77 header. */
    public fun headerSize(src: ByteArray, offset: Int = 0): Int {
        if (offset + 4 > src.size || (src[offset].toInt() and 0xFF) != TYPE) return -1
        return u8(src, offset + 1) or (u8(src, offset + 2) shl 8) or (u8(src, offset + 3) shl 16)
    }

    /**
     * Decodes the stream starting at [offset] in [src].
     *
     * @throws IllegalArgumentException if the header is not LZ77, a reference points before the
     *   start of the output, or the input ends early (corrupt data or wrong offset).
     */
    public fun decode(src: ByteArray, offset: Int = 0): Result {
        val size = headerSize(src, offset)
        require(size >= 0) { "not an LZ77 stream at 0x${offset.toString(16)}" }
        val out = ByteArray(size)
        val consumed = decodeInto(src, offset, out, 0)
        return Result(out, consumed)
    }

    /**
     * Decodes the stream starting at [offset] in [src] into [dst] at [dstOffset] without allocating
     * (map reloads on the game loop use this); returns the input bytes consumed, including the
     * header. The decompressed size is the one in the header (see [headerSize]); [dst] must have
     * room for it.
     *
     * @throws IllegalArgumentException as [decode]; also if [dst] is too small.
     */
    public fun decodeInto(src: ByteArray, offset: Int, dst: ByteArray, dstOffset: Int): Int {
        val size = headerSize(src, offset)
        require(size >= 0) { "not an LZ77 stream at 0x${offset.toString(16)}" }
        require(dstOffset >= 0 && dstOffset + size <= dst.size) { "LZ77 destination too small" }
        var o = 0
        var i = offset + 4
        while (o < size) {
            require(i < src.size) { "LZ77 input truncated" }
            val flags = u8(src, i++)
            var bit = 7
            while (bit >= 0 && o < size) {
                if (flags and (1 shl bit) == 0) {
                    require(i < src.size) { "LZ77 input truncated" }
                    dst[dstOffset + o++] = src[i++]
                } else {
                    require(i + 1 < src.size) { "LZ77 input truncated" }
                    val b0 = u8(src, i)
                    val b1 = u8(src, i + 1)
                    i += 2
                    val len = (b0 shr 4) + 3
                    val dist = ((b0 and 0xF) shl 8 or b1) + 1
                    require(dist <= o) { "LZ77 back-reference before start (distance $dist at $o)" }
                    var n = 0
                    while (n < len && o < size) {
                        dst[dstOffset + o] = dst[dstOffset + o - dist]
                        o++
                        n++
                    }
                }
                bit--
            }
        }
        return i - offset
    }

    private fun u8(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF
}
