package gba.data

/**
 * Decompressor for the GBA BIOS LZ77 format (`LZ77UnCompWram`/`LZ77UnCompVram`, SWI 0x11/0x12),
 * which the original game uses for most graphics and tilemaps.
 *
 * [decode] is load-time only (extraction and asset loading) and returns a fresh array;
 * [decodeInto] does not allocate.
 *
 * ## Binary Layout
 * - A 4-byte header: `0x10 | (decompressedSize << 8)` (little-endian 24-bit size).
 * - Compressed blocks: one flag byte (MSB first) followed by up to 8 items:
 *   - Flag bit 0: one literal byte copied directly.
 *   - Flag bit 1: a 2-byte back-reference:
 *     - `length = (byte0 >> 4) + 3` (3..18 bytes)
 *     - `distance = (((byte0 & 0x0F) << 8) | byte1) + 1` (1..4096 bytes back)
 *
 * References may overlap their own output (run-length style repetition), so bytes are copied one at a time.
 * Decoding stops exactly when `decompressedSize` bytes have been emitted, ignoring any remaining flag bits.
 */
public object Lz77 {
    /** Header type byte of an LZ77 stream in GBA BIOS format. */
    public const val TYPE: Int = 0x10

    /** Header size in bytes. */
    public const val HEADER_SIZE: Int = 4

    /** Base length added to the 4-bit length field in back-reference items. */
    private const val LENGTH_BIAS: Int = 3

    /** Base distance added to the 12-bit distance field in back-reference items. */
    private const val DISTANCE_BIAS: Int = 1

    /** Result of [decode]: the output bytes and how many input bytes the stream occupied. */
    public class Result(
        /** Decompressed data (`size` bytes from the header). */
        public val data: ByteArray,
        /** Input bytes consumed, including the header; ROM pads streams to 4 bytes after this. */
        public val consumed: Int,
    )

    /** Decompressed size announced by the header at [offset], or -1 if it is not an LZ77 header. */
    public fun headerSize(src: ByteArray, offset: Int = 0): Int {
        if (offset + HEADER_SIZE > src.size || (src[offset].toInt() and 0xFF) != TYPE) return -1
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
        var outPos = 0
        var inPos = offset + HEADER_SIZE
        while (outPos < size) {
            require(inPos < src.size) { "LZ77 input truncated" }
            val flags = u8(src, inPos++)
            var bit = 7
            while (bit >= 0 && outPos < size) {
                if (flags and (1 shl bit) == 0) {
                    // Literal byte
                    require(inPos < src.size) { "LZ77 input truncated" }
                    dst[dstOffset + outPos++] = src[inPos++]
                } else {
                    // 2-byte back-reference
                    require(inPos + 1 < src.size) { "LZ77 input truncated" }
                    val b0 = u8(src, inPos)
                    val b1 = u8(src, inPos + 1)
                    inPos += 2
                    val length = (b0 shr 4) + LENGTH_BIAS
                    val distance = (((b0 and 0x0F) shl 8) or b1) + DISTANCE_BIAS
                    require(distance <= outPos) {
                        "LZ77 back-reference before start (distance $distance at $outPos)"
                    }
                    var count = 0
                    while (count < length && outPos < size) {
                        dst[dstOffset + outPos] = dst[dstOffset + outPos - distance]
                        outPos++
                        count++
                    }
                }
                bit--
            }
        }
        return inPos - offset
    }

    /** Reads the unsigned byte value at index [index] in [bytes]. */
    private fun u8(bytes: ByteArray, index: Int): Int = bytes[index].toInt() and 0xFF
}
