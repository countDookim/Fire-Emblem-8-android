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

    /** The palette on the line being packed: [pack] applies the LineTable writes to it. */
    private val work = ShortArray(PALETTE_WIDTH)

    /**
     * Running affine reference point of each BG for the line being packed: x for BG0..3 in
     * `0..3`, then y for BG0..3 in `4..7`. Advances by `pb`/`pd` per line (like the hardware's
     * internal `BGxX/Y` copy), or is reloaded from a LineTable scroll override.
     */
    private val ref = IntArray(2 * RenderState.LAYERS)

    /**
     * Per-line palettes, [PALETTE_WIDTH] BGR555 colours per row (0..255 BG, 256..511 OBJ); only the first
     * [paletteRows] rows are valid, and line y uses row `min(y, paletteRows - 1)`.
     */
    public val palette: ShortArray = ShortArray(PALETTE_WIDTH * RenderState.LINES)

    /** 1 when no LineTable palette writes happened this frame, else [RenderState.LINES]. */
    public var paletteRows: Int = 1
        private set

    /**
     * [LINE_INTS] ints per scanline; see the `L_*` offsets. Ints 0..7 are, per BG, the start of the
     * line in the layer: text BG scroll x and (mosaic-adjusted) y, or the affine reference point
     * (8.8 fixed point) for that line.
     */
    public val lines: IntArray = IntArray(LINE_INTS * RenderState.LINES)

    /**
     * 240×160 OBJ plane: bits 0..14 colour, [OBJ_SEMI], priority at [OBJ_PRIO_SHIFT], [OBJ_OPAQUE],
     * [OBJ_WINDOW].
     */
    public val obj: IntArray = IntArray(FrameBuffer.GBA_WIDTH * FrameBuffer.GBA_HEIGHT)

    /** Packs the current [state]. */
    public fun pack() {
        val lineTable = state.lines
        state.palettes.bg.copyInto(work)
        state.palettes.obj.copyInto(work, PaletteBank.COLORS)
        paletteRows = if (lineTable.active && lineTable.paletteWrites > 0) RenderState.LINES else 1
        var nextWrite = 0
        val fx = state.effects
        for (bg in 0 until RenderState.LAYERS) {
            ref[bg] = state.layers[bg].refX
            ref[bg + RenderState.LAYERS] = state.layers[bg].refY
        }
        val mvMod = maxOf(1, fx.mosaic[1]) // BG mosaic vertical block height (`MOSAIC` BG V size)
        for (y in 0 until RenderState.LINES) {
            val mask = if (lineTable.active) lineTable.mask[y] else 0
            if (lineTable.active) {
                while (nextWrite < lineTable.paletteWrites && lineTable.pwLine[nextWrite] <= y) {
                    // 0x1FF: index 0..255 = BG colour, 256..511 = OBJ colour
                    work[lineTable.pwIndex[nextWrite].toInt() and 0x1FF] = lineTable.pwColor[nextWrite]
                    nextWrite++
                }
            }
            if (y < paletteRows) work.copyInto(palette, y * PALETTE_WIDTH)
            val lineOffset = y * LINE_INTS
            for (bg in 0 until RenderState.LAYERS) {
                val layer = state.layers[bg]
                val scroll = mask and (LineTable.BG_SCROLL shl bg) != 0
                var sx: Int
                var sy: Int
                if (layer.affine) {
                    if (scroll) {
                        ref[bg] = lineTable.scrollX[bg * RenderState.LINES + y]
                        ref[bg + RenderState.LAYERS] = lineTable.scrollY[bg * RenderState.LINES + y]
                    }
                    sx = ref[bg]
                    sy = ref[bg + RenderState.LAYERS]
                    ref[bg] += layer.pb
                    ref[bg + RenderState.LAYERS] += layer.pd
                } else {
                    sx = if (scroll) lineTable.scrollX[bg * RenderState.LINES + y] else layer.scrollX
                    sy = if (scroll) lineTable.scrollY[bg * RenderState.LINES + y] else layer.scrollY
                    sy += if (layer.mosaic) y - y % mvMod else y
                }
                lines[lineOffset + 2 * bg] = sx
                lines[lineOffset + 2 * bg + 1] = sy
            }
            var win0Left = fx.winLeft[0]
            var win0Right = fx.winRight[0]
            var win1Left = fx.winLeft[1]
            var win1Right = fx.winRight[1]
            if (mask and LineTable.WIN0_X != 0) {
                win0Left = lineTable.winLeft[y]
                win0Right = lineTable.winRight[y]
            }
            if (mask and LineTable.WIN1_X != 0) {
                win1Left = lineTable.winLeft[RenderState.LINES + y]
                win1Right = lineTable.winRight[RenderState.LINES + y]
            }
            lines[lineOffset + L_WIN_X] = (win0Left and 0xFF) or ((win0Right and 0xFF) shl 8) or
                ((win1Left and 0xFF) shl 16) or ((win1Right and 0xFF) shl 24)
            var flags = 0
            if (fx.win0 && inside(y, fx.winTop[0], fx.winBottom[0])) flags = flags or F_IN0Y
            if (fx.win1 && inside(y, fx.winTop[1], fx.winBottom[1])) flags = flags or F_IN1Y
            lines[lineOffset + L_FLAGS] = flags
            var eva = fx.eva
            var evb = fx.evb
            var evy = fx.evy
            if (mask and LineTable.BLEND != 0) {
                val blendVal = lineTable.blend[y]
                eva = blendVal and 0xFF
                evb = (blendVal shr 8) and 0xFF
                evy = (blendVal shr 16) and 0xFF
            }
            lines[lineOffset + L_COEFF] = minOf(eva, 16) or (minOf(evb, 16) shl 8) or (minOf(evy, 16) shl 16)
            lines[lineOffset + L_BLEND] = if (mask and LineTable.BLEND_CONTROL != 0) {
                lineTable.blendControl[y] and 0x3FFF // BLDCNT bits 0..13
            } else {
                (fx.blendTop and 0x3F) or ((fx.blendMode and 3) shl 6) or ((fx.blendBottom and 0x3F) shl 8)
            }
            objs.line(y, work)
            val row = y * FrameBuffer.GBA_WIDTH
            for (x in 0 until FrameBuffer.GBA_WIDTH) {
                var pixelValue = if (objs.window[x]) OBJ_WINDOW else 0
                val pixelColor = objs.color[x]
                if (pixelColor >= 0) {
                    pixelValue = pixelValue or pixelColor or OBJ_OPAQUE or (objs.priority[x] shl OBJ_PRIO_SHIFT)
                    if (objs.semi[x]) pixelValue = pixelValue or OBJ_SEMI
                }
                obj[row + x] = pixelValue
            }
        }
    }

    /** Layout constants shared with the shader and [PackedCompositor]. */
    public companion object {
        /** Colours per palette row. */
        public const val PALETTE_WIDTH: Int = 2 * PaletteBank.COLORS

        /** Ints per line: 4 × (x, y) layer origin, then [L_WIN_X], [L_FLAGS], [L_COEFF], [L_BLEND]. */
        public const val LINE_INTS: Int = 12

        /**
         * Window 0/1 left/right bounds, one byte each, lowest byte first (w0 left, w0 right,
         * w1 left, w1 right).
         */
        public const val L_WIN_X: Int = 8

        /** [F_IN0Y] / [F_IN1Y]. */
        public const val L_FLAGS: Int = 9

        /** eva | evb << 8 | evy << 16, each clamped to 16. */
        public const val L_COEFF: Int = 10

        /** `BLDCNT` bits 0..13: first targets (bits 0..5) | effect << 6 | second targets << 8. */
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

        /**
         * Window bound test: [coord] in [start] until [end] (`WINxH`/`WINxV`: first and one past
         * the last pixel). When start > end this port treats the range as wrapping around the
         * screen edge (not checked against hardware documentation or mGBA).
         */
        public fun inside(coord: Int, start: Int, end: Int): Boolean =
            if (start <= end) coord in start until end else coord >= start || coord < end
    }
}
