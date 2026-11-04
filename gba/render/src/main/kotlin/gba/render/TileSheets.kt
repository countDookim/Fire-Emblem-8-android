package gba.render

/**
 * Tile graphics addressed by sheet id (ADR 0008): sheet id → raw tile bytes in the GBA tile format
 * (8×8 tiles, row-major; 4bpp = 32 bytes per tile, low nibble = left pixel; 8bpp = 64 bytes per tile,
 * one byte per pixel). [TileLayer.sheet] and [SpriteBatch.sheet] refer to these ids.
 *
 * The store only holds references; callers own the arrays and may update their contents in place
 * between frames (e.g. animated tiles) without re-registering. Lookups never allocate.
 */
public class TileSheets(
    /** Number of sheet ids (0 until capacity). */
    public val capacity: Int = 64,
) {
    private val sheets = arrayOfNulls<ByteArray>(capacity)

    /** Registers [tiles] as sheet [id], replacing any previous sheet with that id. */
    public operator fun set(id: Int, tiles: ByteArray) {
        sheets[id] = tiles
    }

    /** The bytes of sheet [id], or null if none is registered. */
    public operator fun get(id: Int): ByteArray? = sheets[id]

    /** Unregisters sheet [id]. */
    public fun remove(id: Int) {
        sheets[id] = null
    }

    /** Unregisters all sheets. */
    public fun clear() {
        sheets.fill(null)
    }

    /** Pixel decoding helpers shared by renderers. */
    public companion object {
        /** Bytes per 4bpp tile. */
        public const val TILE_BYTES_4BPP: Int = 32

        /** Bytes per 8bpp tile. */
        public const val TILE_BYTES_8BPP: Int = 64

        /**
         * Colour index (0 = transparent) of pixel ([px], [py]) (0..7, flips already applied) of [tile] in
         * [sheet]; 0 if the tile lies outside the sheet. 4bpp indices are 0..15 (caller adds the bank).
         */
        public fun pixel(sheet: ByteArray, tile: Int, px: Int, py: Int, bpp8: Boolean): Int {
            return if (bpp8) {
                val o = tile * TILE_BYTES_8BPP + py * 8 + px
                if (o >= sheet.size) 0 else sheet[o].toInt() and 0xFF
            } else {
                val o = tile * TILE_BYTES_4BPP + py * 4 + (px shr 1)
                if (o >= sheet.size) 0 else (sheet[o].toInt() shr ((px and 1) shl 2)) and 0xF
            }
        }
    }
}
