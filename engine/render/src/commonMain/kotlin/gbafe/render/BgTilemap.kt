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
}
