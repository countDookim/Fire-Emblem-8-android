package fe8.game.bm

/**
 * The scrolling fog tilemaps of `src/chapterintrofx.c` (used by the chapter intro and the game
 * over screen). The fog image sits at BG tile 0x100 (`BGCHR_BMFX_IMG`) and is 32 x 16 tiles; its
 * upper half repeats over rows 0-7 and 16-23 and its lower half over rows 8-15 and 24-31, in
 * palette bank 4.
 */
public object ScreenFogEffect {
    /** `TILEREF(0x3FF & (0x500 + 0x00), 4)`: tile 0x100, palette 4. */
    private const val TILE_REF_A: Int = 0x100 or (4 shl 12)

    /** `TILEREF(0x3FF & (0x500 + 0x10), 4)`: tile 0x110, palette 4. */
    private const val TILE_REF_B: Int = 0x110 or (4 shl 12)

    /** Horizontal flip bit of a tilemap entry (the `+ 0x400` of the overlayed map). */
    private const val H_FLIP: Int = 0x400

    private const val ROW: Int = 32

    /**
     * Fills BG3's tilemap [bg3] with the fog: tile rows 0-7 and 16-23 from the image's first
     * 16 tiles per row, rows 8-15 and 24-31 from the second image half, both halves of the
     * 32 columns alike.
     *
     * Ported from src/chapterintrofx.c: PutScreenFogEffect
     */
    public fun putScreenFogEffect(bg3: ShortArray) {
        var tileRefA = TILE_REF_A
        var tileRefB = TILE_REF_B
        for (iy in 0 until 8) {
            for (ix in 0 until 16) {
                bg3[iy * ROW + ix] = tileRefA.toShort()
                bg3[iy * ROW + 0x10 + ix] = tileRefA.toShort()
                bg3[(iy + 0x10) * ROW + ix] = tileRefA.toShort()
                bg3[(iy + 0x10) * ROW + 0x10 + ix] = tileRefA.toShort()

                bg3[(iy + 0x08) * ROW + ix] = tileRefB.toShort()
                bg3[(iy + 0x08) * ROW + 0x10 + ix] = tileRefB.toShort()
                bg3[(iy + 0x18) * ROW + ix] = tileRefB.toShort()
                bg3[(iy + 0x18) * ROW + 0x10 + ix] = tileRefB.toShort()

                tileRefA++
                tileRefB++
            }
            tileRefA += 16
            tileRefB += 16
        }
    }

    /**
     * Fills BG2's tilemap [bg2] with the horizontally mirrored fog (`+ 0x400` flips every tile
     * and each row is written right to left), laid out like [putScreenFogEffect].
     *
     * Ported from src/chapterintrofx.c: PutScreenFogEffectOverlayed
     */
    public fun putScreenFogEffectOverlayed(bg2: ShortArray) {
        var tileRefA = TILE_REF_A
        var tileRefB = TILE_REF_B
        for (iy in 0 until 8) {
            for (ix in 0 until 16) {
                val a = (tileRefA + H_FLIP).toShort()
                val b = (tileRefB + H_FLIP).toShort()
                bg2[iy * ROW + 0x0F - ix] = a
                bg2[iy * ROW + 0x1F - ix] = a
                bg2[(iy + 0x10) * ROW + 0x0F - ix] = a
                bg2[(iy + 0x10) * ROW + 0x1F - ix] = a

                bg2[(iy + 0x08) * ROW + 0x0F - ix] = b
                bg2[(iy + 0x08) * ROW + 0x1F - ix] = b
                bg2[(iy + 0x18) * ROW + 0x0F - ix] = b
                bg2[(iy + 0x18) * ROW + 0x1F - ix] = b

                tileRefA++
                tileRefB++
            }
            tileRefA += 16
            tileRefB += 16
        }
    }
}
