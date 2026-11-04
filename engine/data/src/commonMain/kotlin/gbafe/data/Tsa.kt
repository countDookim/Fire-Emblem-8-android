package gbafe.data

/**
 * GBA FE "TSA" tilemap blobs (usually LZ77-compressed in ROM): byte 0 = width - 1, byte 1 = height - 1 (in
 * tiles), then `width * height` u16 BG map entries stored **bottom row first** (the layout the games'
 * `CallARM_FillTileRect` copies into a BG tilemap buffer, adding a tile/palette base to every entry).
 * Shared by FE6/FE7/FE8; confirmed against mGBA VRAM for the FE8 health & safety screen.
 */
public class Tsa(
    /** Width in tiles. */
    public val width: Int,
    /** Height in tiles. */
    public val height: Int,
    /** Map entries top row first (`entries[y * width + x]`), raw u16 (tile | flips | palette bits). */
    public val entries: IntArray,
) {
    /** Factory. */
    public companion object {
        /** Decodes an uncompressed TSA blob from [src] at [offset]. */
        public fun decode(src: ByteArray, offset: Int = 0): Tsa {
            val w = (src[offset].toInt() and 0xFF) + 1
            val h = (src[offset + 1].toInt() and 0xFF) + 1
            require(src.size >= offset + 2 + 2 * w * h) { "TSA ${w}x$h truncated" }
            val e = IntArray(w * h)
            for (row in 0 until h) for (x in 0 until w) {
                val o = offset + 2 + 2 * (row * w + x)
                e[(h - 1 - row) * w + x] = (src[o].toInt() and 0xFF) or ((src[o + 1].toInt() and 0xFF) shl 8)
            }
            return Tsa(w, h, e)
        }
    }
}
