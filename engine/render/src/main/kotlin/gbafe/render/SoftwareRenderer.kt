package gbafe.render

/**
 * Reference CPU backend: composes a [RenderState] into a [FrameBuffer] scanline by scanline, the way the
 * GBA PPU does. Used by JVM tests and frame comparison against mGBA, and as the behavioural spec for the
 * GPU backend.
 *
 * Implemented so far (M3-03): text-mode (tiled, scrolling) background layers with 32/64-tile wrap,
 * horizontal/vertical tile flips, 4bpp palette banks and 8bpp tiles, colour 0 transparency, priority
 * ordering (lower priority value first, ties by lower BG number) and the backdrop colour (BG palette
 * colour 0). Per-line scroll overrides and palette writes from [RenderState.lines] are applied.
 * Sprites (M3-04): regular and affine (incl. double-size) objects, 4bpp/8bpp, flips, 1D/2D tile mapping,
 * OBJ palette; per pixel the opaque sprite with the lowest priority value wins (ties: lower index), and it
 * is drawn in front of every BG layer whose priority value is greater than or equal to its own.
 * OBJ-window sprites are not drawn. Not yet: affine BG layers (skipped), blending, semi-transparency,
 * windows and mosaic (M3-05).
 *
 * The camera offset is ignored: vanilla mode always shows the 240×160 screen at the origin.
 * [render] allocates nothing.
 */
public class SoftwareRenderer(
    /** Frame state to draw; read only. */
    public val state: RenderState,
    /** Tile graphics referenced by the layers' sheet ids. */
    public val sheets: TileSheets,
    /** Output; every pixel in its first 160 lines and 240 columns is written each frame. */
    public val fb: FrameBuffer,
) : Renderer {
    /** Working palette for this frame: 0..255 BG, 256..511 OBJ (LineTable writes land here, not in [state]). */
    private val palette = ShortArray(2 * PaletteBank.COLORS)
    private val order = IntArray(RenderState.LAYERS)
    private val lineScrollX = IntArray(RenderState.LAYERS)
    private val lineScrollY = IntArray(RenderState.LAYERS)
    private val lineSheet = arrayOfNulls<ByteArray>(RenderState.LAYERS)
    private val objColor = IntArray(FrameBuffer.GBA_WIDTH)
    private val objPrio = IntArray(FrameBuffer.GBA_WIDTH)

    override fun render(camera: Camera) {
        state.palettes.bg.copyInto(palette)
        state.palettes.obj.copyInto(palette, PaletteBank.COLORS)
        val layerCount = sortLayers()
        val lines = state.lines
        var nextWrite = 0
        val width = minOf(fb.width, FrameBuffer.GBA_WIDTH)
        val height = minOf(fb.height, FrameBuffer.GBA_HEIGHT)
        for (i in 0 until layerCount) lineSheet[i] = sheets[state.layers[order[i]].sheet]
        for (y in 0 until height) {
            val lineMask = if (lines.active) lines.mask[y] else 0
            if (lines.active) {
                while (nextWrite < lines.paletteWrites && lines.pwLine[nextWrite] <= y) {
                    palette[lines.pwIndex[nextWrite].toInt() and 0x1FF] = lines.pwColor[nextWrite]
                    nextWrite++
                }
            }
            for (i in 0 until layerCount) {
                val bg = order[i]
                val layer = state.layers[bg]
                if (lineMask and (LineTable.BG_SCROLL shl bg) != 0) {
                    lineScrollX[i] = lines.scrollX[bg * RenderState.LINES + y]
                    lineScrollY[i] = lines.scrollY[bg * RenderState.LINES + y]
                } else {
                    lineScrollX[i] = layer.scrollX
                    lineScrollY[i] = layer.scrollY
                }
            }
            spriteLine(y)
            val backdrop = palette[0].toInt() and 0x7FFF
            val row = y * fb.width
            for (x in 0 until width) {
                val oc = objColor[x]
                val op = objPrio[x]
                var color = -1
                for (i in 0 until layerCount) {
                    val layer = state.layers[order[i]]
                    if (oc >= 0 && op <= layer.priority) break
                    val c = textPixel(layer, lineSheet[i] ?: continue, x + lineScrollX[i], y + lineScrollY[i])
                    if (c >= 0) {
                        color = c
                        break
                    }
                }
                if (color < 0) color = if (oc >= 0) oc else backdrop
                fb.pixels[row + x] = FrameBuffer.bgr555ToArgb(color)
            }
        }
    }

    /** Fills [objColor] / [objPrio] for scanline [y] (-1 colour = no sprite pixel). */
    private fun spriteLine(y: Int) {
        objColor.fill(-1)
        val sp = state.sprites
        for (i in 0 until sp.count) {
            val flags = sp.flags[i].toInt()
            if (flags and SpriteBatch.MODE_WINDOW != 0) continue
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
            var pa = 0x100; var pb = 0; var pc = 0; var pd = 0x100
            if (affine) {
                val m = (sp.affineIndex[i].toInt() and 0x1F) * 4
                pa = sp.affine[m].toInt(); pb = sp.affine[m + 1].toInt(); pc = sp.affine[m + 2].toInt(); pd = sp.affine[m + 3].toInt()
            }
            val ry = y - sy
            for (x in maxOf(0, sx) until minOf(FrameBuffer.GBA_WIDTH, sx + bw)) {
                if (objColor[x] >= 0 && objPrio[x] <= prio) continue
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
                objColor[x] = palette[palBase + idx].toInt() and 0x7FFF
                objPrio[x] = prio
            }
        }
    }

    /** Fills [order] with the enabled text-mode layers front to back; returns how many. */
    private fun sortLayers(): Int {
        var n = 0
        for (prio in 0..3) {
            for (bg in 0 until RenderState.LAYERS) {
                val l = state.layers[bg]
                if (l.enabled && !l.affine && l.priority == prio) order[n++] = bg
            }
        }
        return n
    }

    /** BGR555 colour of the layer at world pixel ([wx], [wy]) (scroll applied, not yet wrapped), or -1. */
    private fun textPixel(layer: TileLayer, sheet: ByteArray, wx: Int, wy: Int): Int {
        val mx = wx and (layer.widthTiles * 8 - 1)
        val my = wy and (layer.heightTiles * 8 - 1)
        val e = layer.entry(mx shr 3, my shr 3)
        var px = mx and 7
        var py = my and 7
        if (e and TileLayer.HFLIP != 0) px = 7 - px
        if (e and TileLayer.VFLIP != 0) py = 7 - py
        val idx = TileSheets.pixel(sheet, e and TileLayer.TILE_MASK, px, py, layer.bpp8)
        if (idx == 0) return -1
        val ci = if (layer.bpp8) idx else ((e ushr TileLayer.PALETTE_SHIFT) shl 4) or idx
        return palette[ci].toInt() and 0x7FFF
    }
}
