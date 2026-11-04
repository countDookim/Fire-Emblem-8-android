package gba.render

/**
 * Rasterises the [RenderState.sprites] of one scanline into per-pixel OBJ buffers, the way the GBA OBJ
 * engine fills its line buffer: regular and affine (incl. double-size) objects, 4bpp/8bpp, flips, 1D/2D
 * tile mapping; per pixel the opaque sprite with the lowest priority value wins (ties: lower index); OBJ
 * window sprites only mark [window]. Shared by [SoftwareRenderer] and the GPU backend so both read the
 * same sprite rules. [line] allocates nothing.
 */
public class SpriteRasterizer(
    /** Frame state whose sprites are drawn; read only. */
    public val state: RenderState,
    /** Tile graphics referenced by the sprites' sheet ids. */
    public val sheets: TileSheets,
) {
    /** BGR555 colour of the winning sprite pixel per column, or -1 for none. */
    public val color: IntArray = IntArray(FrameBuffer.GBA_WIDTH)

    /** Priority (0..3) of the winning sprite pixel per column; valid where [color] >= 0. */
    public val priority: IntArray = IntArray(FrameBuffer.GBA_WIDTH)

    /** Whether the winning sprite pixel per column is semi-transparent; valid where [color] >= 0. */
    public val semi: BooleanArray = BooleanArray(FrameBuffer.GBA_WIDTH)

    /** Whether an opaque OBJ window sprite pixel covers the column. */
    public val window: BooleanArray = BooleanArray(FrameBuffer.GBA_WIDTH)

    /**
     * Fills the buffers for scanline [y], resolving colours through [palette] (0..255 BG, 256..511 OBJ: the
     * caller's working palette, so per-line palette writes apply).
     */
    public fun line(y: Int, palette: ShortArray) {
        color.fill(-1)
        window.fill(false)
        val sp = state.sprites
        for (i in 0 until sp.count) {
            val flags = sp.flags[i].toInt()
            val isWindow = flags and SpriteBatch.MODE_WINDOW != 0
            val isSemi = flags and SpriteBatch.MODE_SEMI != 0
            val w = sp.width[i].toInt() and 0xFF
            val h = sp.height[i].toInt() and 0xFF
            val affine = flags and SpriteBatch.AFFINE != 0
            val dbl = affine && flags and SpriteBatch.DOUBLE_SIZE != 0
            val bw = if (dbl) 2 * w else w
            val bh = if (dbl) 2 * h else h
            val sy = sp.y[i]
            if (y < sy || y >= sy + bh) continue
            val sheet = sheets[sp.sheet[i]] ?: continue
            val sx = sp.x[i]
            val prio = sp.priority[i].toInt()
            val bpp8 = flags and SpriteBatch.BPP8 != 0
            val base = sp.tile[i].toInt() and 0x3FF
            val stride = if (sp.mapping1d) (w shr 3) * (if (bpp8) 2 else 1) else 32
            val palBase = PaletteBank.COLORS + if (bpp8) 0 else (sp.palette[i].toInt() and 0xF) shl 4
            var pa = 0x100
            var pb = 0
            var pc = 0
            var pd = 0x100
            if (affine) {
                val m = (sp.affineIndex[i].toInt() and 0x1F) * 4
                pa = sp.affine[m].toInt()
                pb = sp.affine[m + 1].toInt()
                pc = sp.affine[m + 2].toInt()
                pd = sp.affine[m + 3].toInt()
            }
            val ry = y - sy
            for (x in maxOf(0, sx) until minOf(FrameBuffer.GBA_WIDTH, sx + bw)) {
                if (!isWindow && color[x] >= 0 && priority[x] <= prio) continue
                var tx: Int
                var ty: Int
                if (affine) {
                    val dx = x - sx - (bw shr 1)
                    val dy = ry - (bh shr 1)
                    tx = ((pa * dx + pb * dy) shr 8) + (w shr 1)
                    ty = ((pc * dx + pd * dy) shr 8) + (h shr 1)
                    if (tx < 0 || tx >= w || ty < 0 || ty >= h) continue
                } else {
                    tx = x - sx
                    ty = ry
                    if (flags and SpriteBatch.HFLIP != 0) tx = w - 1 - tx
                    if (flags and SpriteBatch.VFLIP != 0) ty = h - 1 - ty
                }
                val unit = base + (ty shr 3) * stride + (tx shr 3) * (if (bpp8) 2 else 1)
                val px = tx and 7
                val py = ty and 7
                val idx = if (bpp8) {
                    val o = (unit and 0x3FF) * TileSheets.TILE_BYTES_4BPP + py * 8 + px
                    if (o >= sheet.size) 0 else sheet[o].toInt() and 0xFF
                } else {
                    TileSheets.pixel(sheet, unit and 0x3FF, px, py, false)
                }
                if (idx == 0) continue
                if (isWindow) {
                    window[x] = true
                    continue
                }
                color[x] = palette[palBase + idx].toInt() and 0x7FFF
                priority[x] = prio
                semi[x] = isSemi
            }
        }
    }
}
