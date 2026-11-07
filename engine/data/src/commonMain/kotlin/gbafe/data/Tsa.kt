package gbafe.data

/**
 * GBA FE "TSA" tilemap blobs (usually LZ77-compressed in ROM): byte 0 = width - 1, byte 1 =
 * height - 1 (in tiles), then `width * height` u16 BG map entries stored **bottom row first**
 * (the layout the games' `CallARM_FillTileRect` copies into a BG tilemap buffer, adding a
 * tile/palette base to every entry). Shared by FE6/FE7/FE8; confirmed against mGBA VRAM for the
 * FE8 health & safety screen.
 */
public class Tsa(
    /** Width in tiles. */
    public val width: Int,
    /** Height in tiles. */
    public val height: Int,
    /** Map entries top row first (`entries[y * width + x]`), raw u16 (tile, flips, palette). */
    public val entries: IntArray,
) {
    /** Factory. */
    public companion object {
        /** Decodes an uncompressed TSA blob from [src] at [offset]. */
        public fun decode(src: ByteArray, offset: Int = 0): Tsa {
            val tilesWide = (src[offset].toInt() and 0xFF) + 1
            val tilesHigh = (src[offset + 1].toInt() and 0xFF) + 1
            require(src.size >= offset + 2 + 2 * tilesWide * tilesHigh) {
                "TSA ${tilesWide}x$tilesHigh truncated"
            }
            val mapEntries = IntArray(tilesWide * tilesHigh)
            // The blob stores rows bottom first; flip them so entries are top row first.
            for (blobRow in 0 until tilesHigh) for (x in 0 until tilesWide) {
                val entryOffset = offset + 2 + 2 * (blobRow * tilesWide + x)
                mapEntries[(tilesHigh - 1 - blobRow) * tilesWide + x] =
                    (src[entryOffset].toInt() and 0xFF) or
                        ((src[entryOffset + 1].toInt() and 0xFF) shl 8)
            }
            return Tsa(tilesWide, tilesHigh, mapEntries)
        }
    }
}
