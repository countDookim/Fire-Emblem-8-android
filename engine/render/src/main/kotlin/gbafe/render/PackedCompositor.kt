package gbafe.render

/**
 * CPU transliteration of the GPU backend's fragment shader: composites one pixel from a packed [GpuFrame]
 * plus the static layer state, with no per-line state of its own. Tests check it against
 * [SoftwareRenderer]; the shader must stay a line-by-line copy of [pixel]. [render] allocates nothing.
 */
public class PackedCompositor(
    /** Packed per-line data; [GpuFrame.pack] must have run for the frame. */
    public val frame: GpuFrame,
    /** Tile graphics referenced by the layers' sheet ids. */
    public val sheets: TileSheets,
    /** Colour-effect arithmetic, as for [SoftwareRenderer]. */
    public val colorMath: ColorMath = ColorMath.Hardware,
) {
    /** Composites every pixel of the GBA screen into [fb]. */
    public fun render(fb: FrameBuffer) {
        for (y in 0 until minOf(fb.height, FrameBuffer.GBA_HEIGHT)) {
            for (x in 0 until minOf(fb.width, FrameBuffer.GBA_WIDTH)) {
                fb.pixels[y * fb.width + x] = FrameBuffer.bgr555ToArgb(pixel(x, y))
            }
        }
    }

    /** BGR555 colour of screen pixel ([x], [y]). */
    public fun pixel(x: Int, y: Int): Int {
        val state = frame.state
        val fx = state.effects
        val lo = y * GpuFrame.LINE_INTS
        val ln = frame.lines
        val palRow = minOf(y, frame.paletteRows - 1) * GpuFrame.PALETTE_WIDTH
        val o = frame.obj[y * FrameBuffer.GBA_WIDTH + x]
        val wx = ln[lo + GpuFrame.L_WIN_X]
        val wf = ln[lo + GpuFrame.L_FLAGS]
        val wmask = when {
            !(fx.win0 || fx.win1 || fx.objWindow) -> 0x3F
            wf and GpuFrame.F_IN0Y != 0 && GpuFrame.inside(x, wx and 0xFF, (wx shr 8) and 0xFF) -> fx.winInside[0]
            wf and GpuFrame.F_IN1Y != 0 &&
                GpuFrame.inside(x, (wx shr 16) and 0xFF, (wx ushr 24) and 0xFF) -> fx.winInside[1]
            fx.objWindow && o and GpuFrame.OBJ_WINDOW != 0 -> fx.winInside[2]
            else -> fx.winOutside
        }
        var c1 = 0
        var l1 = 0
        var semi1 = false
        var c2 = 0
        var l2 = 0
        var filled = 0
        var objPending = o and GpuFrame.OBJ_OPAQUE != 0 && wmask and Effects.OBJ != 0
        val objPrio = (o shr GpuFrame.OBJ_PRIO_SHIFT) and 3
        val mh = maxOf(1, fx.mosaic[0])
        // Layers front to back: priority, then BG number (same order as SoftwareRenderer.sortLayers).
        for (prio in 0..3) {
            for (bg in 0 until RenderState.LAYERS) {
                if (filled == 2) break
                val layer = state.layers[bg]
                if (!layer.enabled || layer.priority != prio) continue
                if (objPending && objPrio <= prio) {
                    if (filled == 0) { c1 = o and 0x7FFF; l1 = Effects.OBJ; semi1 = o and GpuFrame.OBJ_SEMI != 0 } else { c2 = o and 0x7FFF; l2 = Effects.OBJ }
                    filled++
                    objPending = false
                    if (filled == 2) break
                }
                if (wmask and (1 shl bg) == 0) continue
                val sheet = sheets[layer.sheet] ?: continue
                val sx = ln[lo + 2 * bg]
                val sy = ln[lo + 2 * bg + 1]
                val ci = if (layer.affine) {
                    affineIndex(layer, sheet, sx + layer.pa * x, sy + layer.pc * x)
                } else {
                    textIndex(layer, sheet, (if (layer.mosaic) x - x % mh else x) + sx, sy)
                }
                if (ci >= 0) {
                    val c = frame.palette[palRow + ci].toInt() and 0x7FFF
                    if (filled == 0) { c1 = c; l1 = 1 shl bg; semi1 = false } else { c2 = c; l2 = 1 shl bg }
                    filled++
                }
            }
        }
        if (filled < 2 && objPending) {
            if (filled == 0) { c1 = o and 0x7FFF; l1 = Effects.OBJ; semi1 = o and GpuFrame.OBJ_SEMI != 0 } else { c2 = o and 0x7FFF; l2 = Effects.OBJ }
            filled++
        }
        if (filled < 2) {
            val c = frame.palette[palRow].toInt() and 0x7FFF
            if (filled == 0) { c1 = c; l1 = Effects.BACKDROP; semi1 = false } else { c2 = c; l2 = Effects.BACKDROP }
        }
        if (wmask and Effects.BACKDROP == 0) return c1
        val k = ln[lo + GpuFrame.L_COEFF]
        val eva = k and 0xFF
        val evb = (k shr 8) and 0xFF
        val evy = (k shr 16) and 0xFF
        val b = ln[lo + GpuFrame.L_BLEND]
        val top = b and 0x3F
        val mode = (b shr 6) and 3
        val bottom = (b shr 8) and 0x3F
        if (semi1 && bottom and l2 != 0) return colorMath.alpha(c1, c2, eva, evb)
        if (top and l1 == 0) return c1
        return when (mode) {
            Effects.BLEND_ALPHA -> if (bottom and l2 != 0) colorMath.alpha(c1, c2, eva, evb) else c1
            Effects.BLEND_BRIGHTEN -> colorMath.brighten(c1, evy)
            Effects.BLEND_DARKEN -> colorMath.darken(c1, evy)
            else -> c1
        }
    }

    /** Palette index (0..255) of affine [layer] at 8.8 position ([fx], [fy]), or -1 if transparent. */
    private fun affineIndex(layer: TileLayer, sheet: ByteArray, fx: Int, fy: Int): Int {
        val size = layer.widthTiles * 8
        var tx = fx shr 8
        var ty = fy shr 8
        if (layer.wrap) {
            tx = tx and (size - 1)
            ty = ty and (size - 1)
        } else if (tx < 0 || ty < 0 || tx >= size || ty >= size) {
            return -1
        }
        val idx = TileSheets.pixel(sheet, layer.entry(tx shr 3, ty shr 3) and 0xFF, tx and 7, ty and 7, true)
        return if (idx == 0) -1 else idx
    }

    /** Palette index (0..255) of text [layer] at world pixel ([wx], [wy]), or -1 if transparent. */
    private fun textIndex(layer: TileLayer, sheet: ByteArray, wx: Int, wy: Int): Int {
        val mx = wx and (layer.widthTiles * 8 - 1)
        val my = wy and (layer.heightTiles * 8 - 1)
        val e = layer.entry(mx shr 3, my shr 3)
        var px = mx and 7
        var py = my and 7
        if (e and TileLayer.HFLIP != 0) px = 7 - px
        if (e and TileLayer.VFLIP != 0) py = 7 - py
        val idx = TileSheets.pixel(sheet, e and TileLayer.TILE_MASK, px, py, layer.bpp8)
        if (idx == 0) return -1
        return if (layer.bpp8) idx else ((e ushr TileLayer.PALETTE_SHIFT) shl 4) or idx
    }
}
