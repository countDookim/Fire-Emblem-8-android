package gbafe.render

/**
 * Helpers for the games' BG tilemap buffers (`gBG0TilemapBuffer`..`gBG3TilemapBuffer`: 32×32 u16 entries each,
 * copied to VRAM on BG sync).
 */
public object BgTilemap {
    /** Entries per tilemap row. */
    public const val ROW: Int = 32

    /**
     * Writes the TSA blob at [tsaOffset] of [tsa] (byte 0 = width - 1, byte 1 = height - 1, then u16 entries
     * bottom row first) into [tilemap] with its top-left corner at entry [offset] (`TILEMAP_LOCATED(x, y)` =
     * y * 32 + x), adding [tileBase] to every entry (16-bit wrap).
     *
     * Ported from asm/arm.s: TmApplyTsa
     *
     * Ported from asm/arm_call.s: CallARM_FillTileRect
     */
    public fun applyTsa(tilemap: ShortArray, offset: Int, tsa: ByteArray, tsaOffset: Int, tileBase: Int) {
        val wMinus1 = tsa[tsaOffset].toInt() and 0xFF
        val hMinus1 = tsa[tsaOffset + 1].toInt() and 0xFF
        var src = tsaOffset + 2
        for (row in hMinus1 downTo 0) {
            var dst = offset + row * ROW
            for (x in 0..wMinus1) {
                val tileEntry = (tsa[src].toInt() and 0xFF) or ((tsa[src + 1].toInt() and 0xFF) shl 8)
                tilemap[dst++] = (tileEntry + tileBase).toShort()
                src += 2
            }
        }
    }

    /**
     * Writes a byte-entry TSA (byte 0 = width - 1, byte 1 = height - 1, entries from offset +2, bottom
     * row first) into [tilemap]'s bytes (little-endian halfwords) with row stride `1 shl linebits` bytes,
     * adding [base] to every entry (8-bit wrap). Used for affine (8-bit) maps.
     *
     * Ported from src/hardware.c: sub_800154C
     */
    public fun applyByteTsa(tilemap: ShortArray, tsa: ByteArray, tsaOffset: Int, base: Int, linebits: Int) {
        var it = tsaOffset + 2
        val xSize = tsa[tsaOffset].toInt() and 0xFF
        val ySize = tsa[tsaOffset + 1].toInt() and 0xFF
        for (iy in ySize downTo 0) {
            var out = iy shl linebits
            for (ix in xSize downTo 0) {
                val value = (tsa[it++].toInt() + base) and 0xFF
                val word = out shr 1
                val old = tilemap[word].toInt()
                tilemap[word] = (if (out and 1 == 0) (old and 0xFF00) or value
                    else (old and 0xFF) or (value shl 8)).toShort()
                out++
            }
        }
    }
}
