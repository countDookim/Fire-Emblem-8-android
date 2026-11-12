package gba.render

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
    /** Composites every pixel of the GBA screen (240×160) into [fb]. */
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
        val lineOffset = y * GpuFrame.LINE_INTS
        val lines = frame.lines
        val palRow = minOf(y, frame.paletteRows - 1) * GpuFrame.PALETTE_WIDTH
        val objPixel = frame.obj[y * FrameBuffer.GBA_WIDTH + x]
        val winX = lines[lineOffset + GpuFrame.L_WIN_X]
        val winFlags = lines[lineOffset + GpuFrame.L_FLAGS]
        // Layer mask visible at this pixel (Effects mask bits; bit 5 = colour effects allowed):
        // no window -> all; window 0, window 1, OBJ window in that order; else the outside mask.
        val wmask = when {
            !(fx.win0 || fx.win1 || fx.objWindow) -> 0x3F
            winFlags and GpuFrame.F_IN0Y != 0 &&
                GpuFrame.inside(x, winX and 0xFF, (winX shr 8) and 0xFF) -> fx.winInside[0]
            winFlags and GpuFrame.F_IN1Y != 0 &&
                GpuFrame.inside(x, (winX shr 16) and 0xFF, (winX ushr 24) and 0xFF) -> fx.winInside[1]
            fx.objWindow && objPixel and GpuFrame.OBJ_WINDOW != 0 -> fx.winInside[2]
            else -> fx.winOutside
        }
        // The top two visible pixels (colour, source layer bit) the blend works on.
        var color1 = 0
        var layerMask1 = 0
        var semiTransparent1 = false
        var color2 = 0
        var layerMask2 = 0
        var filled = 0
        var objPending = objPixel and GpuFrame.OBJ_OPAQUE != 0 && wmask and Effects.OBJ != 0
        val objPrio = (objPixel shr GpuFrame.OBJ_PRIO_SHIFT) and 3
        val mh = maxOf(1, fx.mosaic[0]) // BG mosaic horizontal block width
        // Layers front to back: priority, then BG number (same order as SoftwareRenderer.sortLayers).
        for (prio in 0..3) {
            for (bg in 0 until RenderState.LAYERS) {
                if (filled == 2) break
                val layer = state.layers[bg]
                if (!layer.enabled || layer.priority != prio) continue
                if (objPending && objPrio <= prio) {
                    if (filled == 0) {
                        color1 = objPixel and 0x7FFF
                        layerMask1 = Effects.OBJ
                        semiTransparent1 = objPixel and GpuFrame.OBJ_SEMI != 0
                    } else {
                        color2 = objPixel and 0x7FFF
                        layerMask2 = Effects.OBJ
                    }
                    filled++
                    objPending = false
                    if (filled == 2) break
                }
                if (wmask and (1 shl bg) == 0) continue
                val sheet = sheets[layer.sheet] ?: continue
                val sx = lines[lineOffset + 2 * bg]
                val sy = lines[lineOffset + 2 * bg + 1]
                val ci = if (layer.affine) {
                    affineIndex(layer, sheet, sx + layer.pa * x, sy + layer.pc * x)
                } else {
                    textIndex(layer, sheet, (if (layer.mosaic) x - x % mh else x) + sx, sy)
                }
                if (ci >= 0) {
                    val c = frame.palette[palRow + ci].toInt() and 0x7FFF
                    if (filled == 0) {
                        color1 = c
                        layerMask1 = 1 shl bg
                        semiTransparent1 = false
                    } else {
                        color2 = c
                        layerMask2 = 1 shl bg
                    }
                    filled++
                }
            }
        }
        if (filled < 2 && objPending) {
            if (filled == 0) {
                color1 = objPixel and 0x7FFF
                layerMask1 = Effects.OBJ
                semiTransparent1 = objPixel and GpuFrame.OBJ_SEMI != 0
            } else {
                color2 = objPixel and 0x7FFF
                layerMask2 = Effects.OBJ
            }
            filled++
        }
        if (filled < 2) {
            val c = frame.palette[palRow].toInt() and 0x7FFF
            if (filled == 0) {
                color1 = c
                layerMask1 = Effects.BACKDROP
                semiTransparent1 = false
            } else {
                color2 = c
                layerMask2 = Effects.BACKDROP
            }
        }
        if (wmask and Effects.BACKDROP == 0) return color1
        val coeff = lines[lineOffset + GpuFrame.L_COEFF]
        val eva = coeff and 0xFF
        val evb = (coeff shr 8) and 0xFF
        val evy = (coeff shr 16) and 0xFF
        val blend = lines[lineOffset + GpuFrame.L_BLEND]
        val top = blend and 0x3F
        val mode = (blend shr 6) and 3
        val bottom = (blend shr 8) and 0x3F
        if (semiTransparent1 && bottom and layerMask2 != 0) return colorMath.alpha(color1, color2, eva, evb)
        if (top and layerMask1 == 0) return color1
        return when (mode) {
            Effects.BLEND_ALPHA -> if (bottom and layerMask2 != 0) colorMath.alpha(color1, color2, eva, evb) else color1
            Effects.BLEND_BRIGHTEN -> colorMath.brighten(color1, evy)
            Effects.BLEND_DARKEN -> colorMath.darken(color1, evy)
            else -> color1
        }
    }

    /**
     * Palette index (0..255) of affine [layer] at texture position ([fx], [fy]) (8.8 fixed point),
     * or -1 if transparent (colour 0, or outside the map when it does not wrap).
     */
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

    /**
     * Palette index (0..255, 4bpp tiles: palette bank * 16 + colour) of text [layer] at world pixel
     * ([wx], [wy]) with scroll applied but not yet wrapped, or -1 if transparent (colour 0).
     */
    private fun textIndex(layer: TileLayer, sheet: ByteArray, wx: Int, wy: Int): Int {
        val mx = wx and (layer.widthTiles * 8 - 1)
        val my = wy and (layer.heightTiles * 8 - 1)
        val tileEntry = layer.entry(mx shr 3, my shr 3)
        var px = mx and 7
        var py = my and 7
        if (tileEntry and TileLayer.HFLIP != 0) px = 7 - px
        if (tileEntry and TileLayer.VFLIP != 0) py = 7 - py
        val idx = TileSheets.pixel(sheet, tileEntry and TileLayer.TILE_MASK, px, py, layer.bpp8)
        if (idx == 0) return -1
        return if (layer.bpp8) idx else ((tileEntry ushr TileLayer.PALETTE_SHIFT) shl 4) or idx
    }
}
