package gba.render

/**
 * Per-frame data the GPU backend uploads: everything [SoftwareRenderer] resolves per scanline, packed into
 * flat arrays so a fragment shader only has to fetch tiles and composite per pixel ([PackedCompositor] is
 * the CPU spec of that shader). Static layer state (priority, maps, pa/pc, flags) is read from [state]
 * directly. [pack] allocates nothing.
 */
public class GpuFrame(
    /** Frame state to pack; read only. */
    public val state: RenderState,
    /** Tile graphics referenced by the sprites' sheet ids. */
    sheets: TileSheets,
) {
    private val objs = SpriteRasterizer(state, sheets)
    private val work = ShortArray(PALETTE_WIDTH)

    // Affine line reference points: x for BG0..3, then y for BG0..3.
    private val ref = IntArray(2 * RenderState.LAYERS)

    /**
     * Per-line palettes, [PALETTE_WIDTH] BGR555 colours per row (0..255 BG, 256..511 OBJ); only the first
     * [paletteRows] rows are valid, and line y uses row `min(y, paletteRows - 1)`.
     */
    public val palette: ShortArray = ShortArray(PALETTE_WIDTH * RenderState.LINES)

    /** 1 when no LineTable palette writes happened this frame, else [RenderState.LINES]. */
    public var paletteRows: Int = 1
        private set

    /** [LINE_INTS] ints per scanline; see the `L_*` offsets. */
    public val lines: IntArray = IntArray(LINE_INTS * RenderState.LINES)

    /**
     * 240×160 OBJ plane: bits 0..14 colour, [OBJ_SEMI], priority at [OBJ_PRIO_SHIFT], [OBJ_OPAQUE],
     * [OBJ_WINDOW].
     */
    public val obj: IntArray = IntArray(FrameBuffer.GBA_WIDTH * FrameBuffer.GBA_HEIGHT)

    /** Packs the current [state]. */
    public fun pack() {
        val lt = state.lines
        state.palettes.bg.copyInto(work)
        state.palettes.obj.copyInto(work, PaletteBank.COLORS)
        paletteRows = if (lt.active && lt.paletteWrites > 0) RenderState.LINES else 1
        var nextWrite = 0
        val fx = state.effects
        for (bg in 0 until RenderState.LAYERS) {
            ref[bg] = state.layers[bg].refX
            ref[bg + RenderState.LAYERS] = state.layers[bg].refY
        }
        val mvMod = maxOf(1, fx.mosaic[1])
        for (y in 0 until RenderState.LINES) {
            val mask = if (lt.active) lt.mask[y] else 0
            if (lt.active) {
                while (nextWrite < lt.paletteWrites && lt.pwLine[nextWrite] <= y) {
                    work[lt.pwIndex[nextWrite].toInt() and 0x1FF] = lt.pwColor[nextWrite]
                    nextWrite++
                }
            }
            if (y < paletteRows) work.copyInto(palette, y * PALETTE_WIDTH)
            val o = y * LINE_INTS
            for (bg in 0 until RenderState.LAYERS) {
                val layer = state.layers[bg]
                val scroll = mask and (LineTable.BG_SCROLL shl bg) != 0
                var sx: Int
                var sy: Int
                if (layer.affine) {
                    if (scroll) {
                        ref[bg] = lt.scrollX[bg * RenderState.LINES + y]
                        ref[bg + RenderState.LAYERS] = lt.scrollY[bg * RenderState.LINES + y]
                    }
                    sx = ref[bg]
                    sy = ref[bg + RenderState.LAYERS]
                    ref[bg] += layer.pb
                    ref[bg + RenderState.LAYERS] += layer.pd
                } else {
                    sx = if (scroll) lt.scrollX[bg * RenderState.LINES + y] else layer.scrollX
                    sy = if (scroll) lt.scrollY[bg * RenderState.LINES + y] else layer.scrollY
                    sy += if (layer.mosaic) y - y % mvMod else y
                }
                lines[o + 2 * bg] = sx
                lines[o + 2 * bg + 1] = sy
            }
            var w0l = fx.winLeft[0]
            var w0r = fx.winRight[0]
            var w1l = fx.winLeft[1]
            var w1r = fx.winRight[1]
            if (mask and LineTable.WIN0_X != 0) {
                w0l = lt.winLeft[y]
                w0r = lt.winRight[y]
            }
            if (mask and LineTable.WIN1_X != 0) {
                w1l = lt.winLeft[RenderState.LINES + y]
                w1r = lt.winRight[RenderState.LINES + y]
            }
            lines[o + L_WIN_X] = (w0l and 0xFF) or ((w0r and 0xFF) shl 8) or
                ((w1l and 0xFF) shl 16) or ((w1r and 0xFF) shl 24)
            var flags = 0
            if (fx.win0 && inside(y, fx.winTop[0], fx.winBottom[0])) flags = flags or F_IN0Y
            if (fx.win1 && inside(y, fx.winTop[1], fx.winBottom[1])) flags = flags or F_IN1Y
            lines[o + L_FLAGS] = flags
            var eva = fx.eva
            var evb = fx.evb
            var evy = fx.evy
            if (mask and LineTable.BLEND != 0) {
                val b = lt.blend[y]
                eva = b and 0xFF
                evb = (b shr 8) and 0xFF
                evy = (b shr 16) and 0xFF
            }
            lines[o + L_COEFF] = minOf(eva, 16) or (minOf(evb, 16) shl 8) or (minOf(evy, 16) shl 16)
            lines[o + L_BLEND] = if (mask and LineTable.BLEND_CONTROL != 0) {
                lt.blendControl[y] and 0x3FFF
            } else {
                (fx.blendTop and 0x3F) or ((fx.blendMode and 3) shl 6) or ((fx.blendBottom and 0x3F) shl 8)
            }
            objs.line(y, work)
            val row = y * FrameBuffer.GBA_WIDTH
            for (x in 0 until FrameBuffer.GBA_WIDTH) {
                var v = if (objs.window[x]) OBJ_WINDOW else 0
                val c = objs.color[x]
                if (c >= 0) {
                    v = v or c or OBJ_OPAQUE or (objs.priority[x] shl OBJ_PRIO_SHIFT)
                    if (objs.semi[x]) v = v or OBJ_SEMI
                }
                obj[row + x] = v
            }
        }
    }

    /** Layout constants shared with the shader and [PackedCompositor]. */
    public companion object {
        /** Colours per palette row. */
        public const val PALETTE_WIDTH: Int = 2 * PaletteBank.COLORS

        /** Ints per line: 4 × (x, y) layer origin, then [L_WIN_X], [L_FLAGS], [L_COEFF], [L_BLEND]. */
        public const val LINE_INTS: Int = 12

        /** Window 0/1 left/right bounds, one byte each (w0l, w0r, w1l, w1r). */
        public const val L_WIN_X: Int = 8

        /** [F_IN0Y] / [F_IN1Y]. */
        public const val L_FLAGS: Int = 9

        /** eva | evb << 8 | evy << 16, each clamped to 16. */
        public const val L_COEFF: Int = 10

        /** BLDCNT-style: blendTop | mode << 6 | blendBottom << 8. */
        public const val L_BLEND: Int = 11

        /** Window 0 is enabled and covers the line. */
        public const val F_IN0Y: Int = 1

        /** Window 1 is enabled and covers the line. */
        public const val F_IN1Y: Int = 2

        /** OBJ plane: the sprite pixel is semi-transparent. */
        public const val OBJ_SEMI: Int = 1 shl 15

        /** OBJ plane: priority bit position (2 bits). */
        public const val OBJ_PRIO_SHIFT: Int = 16

        /** OBJ plane: a visible sprite pixel is present. */
        public const val OBJ_OPAQUE: Int = 1 shl 18

        /** OBJ plane: an OBJ window sprite covers the pixel. */
        public const val OBJ_WINDOW: Int = 1 shl 19

        /** Hardware window bound test: [lo] until [hi], wrapping around when lo > hi. */
        public fun inside(v: Int, lo: Int, hi: Int): Boolean =
            if (lo <= hi) v in lo until hi else v >= lo || v < hi
    }
}
