package gba.render

/**
 * Reference CPU backend: composes a [RenderState] into a [FrameBuffer] scanline by scanline, the way the
 * GBA PPU does. Used by JVM tests and frame comparison against mGBA, and as the behavioural spec for the
 * GPU backend.
 *
 * Backgrounds: text-mode (tiled, scrolling) background layers with 32/64-tile wrap,
 * horizontal/vertical tile flips, 4bpp palette banks and 8bpp tiles, colour 0 transparency, priority
 * ordering (lower priority value first, ties by lower BG number) and the backdrop colour (BG palette
 * colour 0). Per-line scroll overrides and palette writes from [RenderState.lines] are applied.
 * Sprites ([SpriteRasterizer]): regular and affine (incl. double-size) objects, 4bpp/8bpp, flips, 1D/2D tile mapping,
 * OBJ palette; per pixel the opaque sprite with the lowest priority value wins (ties: lower index), and it
 * is drawn in front of every BG layer whose priority value is greater than or equal to its own.
 * Effects: windows 0/1 (incl. wrap-around bounds when left > right) and the OBJ window select
 * per pixel which layers are visible and whether colour effects apply; alpha blending of the top two
 * visible pixels, brightness up/down, semi-transparent sprites (always blended over a second target),
 * BG mosaic. Per-line window x bounds and blend coefficients from [RenderState.lines] are applied.
 * Affine BG layers: 8bpp maps of 16..128 tiles with one-byte tile indices, sampled at
 * `(ref + pa·x, ref + pc·x) >> 8` per line, the line reference advancing by `pb`/`pd` per line (a LineTable
 * scroll override reloads it, like an HBlank write to `BGxX/Y`); wrap or transparent outside.
 * Not yet: OBJ mosaic, mosaic on affine BGs. Checked against mGBA frames (`FrameCompareTest`, `Fe8BootTest`).
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
    /** Colour-effect arithmetic; [ColorMath.Hardware] except in emulator comparisons. */
    public val colorMath: ColorMath = ColorMath.Hardware,
) : Renderer {
    /** Working palette for this frame: 0..255 BG, 256..511 OBJ (LineTable writes land here, not in [state]). */
    private val palette = ShortArray(2 * PaletteBank.COLORS)
    private val order = IntArray(RenderState.LAYERS)
    private val lineScrollX = IntArray(RenderState.LAYERS)
    private val lineScrollY = IntArray(RenderState.LAYERS)
    private val affX = IntArray(RenderState.LAYERS)
    private val affY = IntArray(RenderState.LAYERS)
    private val lineSheet = arrayOfNulls<ByteArray>(RenderState.LAYERS)
    private val objs = SpriteRasterizer(state, sheets)
    private val objColor = objs.color
    private val objPrio = objs.priority
    private val objSemi = objs.semi
    private val objWin = objs.window

    // Top two visible pixels of the current composite: colour and layer bit (Effects mask bits).
    private var color1 = 0
    private var layerBit1 = 0
    private var semiTransparent1 = false
    private var color2 = 0
    private var layerBit2 = 0
    private var filled = 0

    override fun render(camera: Camera) {
        state.palettes.bg.copyInto(palette)
        state.palettes.obj.copyInto(palette, PaletteBank.COLORS)
        val layerCount = sortLayers()
        val lines = state.lines
        var nextWrite = 0
        val width = minOf(fb.width, FrameBuffer.GBA_WIDTH)
        val height = minOf(fb.height, FrameBuffer.GBA_HEIGHT)
        for (i in 0 until layerCount) {
            val layer = state.layers[order[i]]
            lineSheet[i] = sheets[layer.sheet]
            affX[i] = layer.refX
            affY[i] = layer.refY
        }
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
                if (layer.affine) {
                    if (lineMask and (LineTable.BG_SCROLL shl bg) != 0) {
                        affX[i] = lines.scrollX[bg * RenderState.LINES + y]
                        affY[i] = lines.scrollY[bg * RenderState.LINES + y]
                    }
                    lineScrollX[i] = affX[i]
                    lineScrollY[i] = affY[i]
                    affX[i] += layer.pb
                    affY[i] += layer.pd
                } else if (lineMask and (LineTable.BG_SCROLL shl bg) != 0) {
                    lineScrollX[i] = lines.scrollX[bg * RenderState.LINES + y]
                    lineScrollY[i] = lines.scrollY[bg * RenderState.LINES + y]
                } else {
                    lineScrollX[i] = layer.scrollX
                    lineScrollY[i] = layer.scrollY
                }
            }
            objs.line(y, palette)
            val fx = state.effects
            val windows = fx.win0 || fx.win1 || fx.objWindow
            var win0Left = fx.winLeft[0]; var win0Right = fx.winRight[0]
            var win1Left = fx.winLeft[1]; var win1Right = fx.winRight[1]
            if (lineMask and LineTable.WIN0_X != 0) {
                win0Left = lines.winLeft[y]
                win0Right = lines.winRight[y]
            }
            if (lineMask and LineTable.WIN1_X != 0) {
                win1Left = lines.winLeft[RenderState.LINES + y]
                win1Right = lines.winRight[RenderState.LINES + y]
            }
            val inWin0Y = fx.win0 && inside(y, fx.winTop[0], fx.winBottom[0])
            val inWin1Y = fx.win1 && inside(y, fx.winTop[1], fx.winBottom[1])
            var eva = fx.eva; var evb = fx.evb; var evy = fx.evy
            if (lineMask and LineTable.BLEND != 0) {
                val blendVal = lines.blend[y]
                eva = blendVal and 0xFF; evb = (blendVal shr 8) and 0xFF; evy = (blendVal shr 16) and 0xFF
            }
            eva = minOf(eva, 16); evb = minOf(evb, 16); evy = minOf(evy, 16)
            var blendMode = fx.blendMode; var blendTop = fx.blendTop; var blendBottom = fx.blendBottom
            if (lineMask and LineTable.BLEND_CONTROL != 0) {
                val blendControlVal = lines.blendControl[y]
                blendTop = blendControlVal and 0x3F
                blendMode = (blendControlVal shr 6) and 3
                blendBottom = (blendControlVal shr 8) and 0x3F
            }
            val mh = maxOf(1, fx.mosaic[0])
            val mvY = y - y % maxOf(1, fx.mosaic[1])
            val backdrop = palette[0].toInt() and 0x7FFF
            val row = y * fb.width
            for (x in 0 until width) {
                val wmask = when {
                    !windows -> ALL
                    inWin0Y && inside(x, win0Left, win0Right) -> fx.winInside[0]
                    inWin1Y && inside(x, win1Left, win1Right) -> fx.winInside[1]
                    fx.objWindow && objWin[x] -> fx.winInside[2]
                    else -> fx.winOutside
                }
                filled = 0
                var objPending = objColor[x] >= 0 && wmask and Effects.OBJ != 0
                for (i in 0 until layerCount) {
                    val bg = order[i]
                    val layer = state.layers[bg]
                    if (objPending && objPrio[x] <= layer.priority) {
                        push(objColor[x], Effects.OBJ, objSemi[x])
                        objPending = false
                        if (filled == 2) break
                    }
                    if (wmask and (1 shl bg) == 0) continue
                    val sheet = lineSheet[i] ?: continue
                    val pixelColor = if (layer.affine) {
                        affinePixel(layer, sheet, lineScrollX[i] + layer.pa * x, lineScrollY[i] + layer.pc * x)
                    } else if (layer.mosaic) {
                        textPixel(layer, sheet, x - x % mh + lineScrollX[i], mvY + lineScrollY[i])
                    } else {
                        textPixel(layer, sheet, x + lineScrollX[i], y + lineScrollY[i])
                    }
                    if (pixelColor >= 0) {
                        push(pixelColor, 1 shl bg, false)
                        if (filled == 2) break
                    }
                }
                if (filled < 2 && objPending) push(objColor[x], Effects.OBJ, objSemi[x])
                if (filled < 2) push(backdrop, Effects.BACKDROP, false)
                var color = color1
                if (wmask and Effects.BACKDROP != 0) {
                    if (semiTransparent1 && blendBottom and layerBit2 != 0) {
                        color = colorMath.alpha(color1, color2, eva, evb)
                    } else if (blendTop and layerBit1 != 0) {
                        color = when (blendMode) {
                            Effects.BLEND_ALPHA -> if (blendBottom and layerBit2 != 0) colorMath.alpha(color1, color2, eva, evb) else color1
                            Effects.BLEND_BRIGHTEN -> colorMath.brighten(color1, evy)
                            Effects.BLEND_DARKEN -> colorMath.darken(color1, evy)
                            else -> color1
                        }
                    }
                }
                fb.pixels[row + x] = FrameBuffer.bgr555ToArgb(color)
            }
        }
    }

    private fun push(color: Int, layerBit: Int, semi: Boolean) {
        if (filled == 0) {
            color1 = color; layerBit1 = layerBit; semiTransparent1 = semi
        } else {
            color2 = color; layerBit2 = layerBit
        }
        filled++
    }

    /** Fills [order] with the enabled layers front to back; returns how many. */
    private fun sortLayers(): Int {
        var n = 0
        for (prio in 0..3) {
            for (bg in 0 until RenderState.LAYERS) {
                val l = state.layers[bg]
                if (l.enabled && l.priority == prio) order[n++] = bg
            }
        }
        return n
    }

    /** BGR555 colour of affine layer [layer] at texture position ([fx], [fy]) in 8.8 fixed point, or -1. */
    private fun affinePixel(layer: TileLayer, sheet: ByteArray, fx: Int, fy: Int): Int {
        val size = layer.widthTiles * 8
        var tx = fx shr 8
        var ty = fy shr 8
        if (layer.wrap) {
            tx = tx and (size - 1)
            ty = ty and (size - 1)
        } else if (tx < 0 || ty < 0 || tx >= size || ty >= size) {
            return -1
        }
        val tile = layer.entry(tx shr 3, ty shr 3) and 0xFF
        val idx = TileSheets.pixel(sheet, tile, tx and 7, ty and 7, true)
        return if (idx == 0) -1 else palette[idx].toInt() and 0x7FFF
    }

    /** BGR555 colour of the layer at world pixel ([wx], [wy]) (scroll applied, not yet wrapped), or -1. */
    private fun textPixel(layer: TileLayer, sheet: ByteArray, wx: Int, wy: Int): Int {
        val mx = wx and (layer.widthTiles * 8 - 1)
        val my = wy and (layer.heightTiles * 8 - 1)
        val tileEntry = layer.entry(mx shr 3, my shr 3)
        var px = mx and 7
        var py = my and 7
        if (tileEntry and TileLayer.HFLIP != 0) px = 7 - px
        if (tileEntry and TileLayer.VFLIP != 0) py = 7 - py
        val idx = TileSheets.pixel(sheet, tileEntry and TileLayer.TILE_MASK, px, py, layer.bpp8)
        if (idx == 0) return -1
        val colorIndex = if (layer.bpp8) idx else ((tileEntry ushr TileLayer.PALETTE_SHIFT) shl 4) or idx
        return palette[colorIndex].toInt() and 0x7FFF
    }

    private companion object {
        /** Window mask with every layer and colour effects enabled (no windows active). */
        const val ALL = 0x3F

        /** Hardware window bound test: [lo] until [hi], wrapping around when lo > hi. */
        fun inside(coord: Int, start: Int, end: Int): Boolean = if (start <= end) coord in start until end else coord >= start || coord < end
    }
}
