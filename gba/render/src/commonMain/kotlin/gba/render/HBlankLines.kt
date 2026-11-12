package gba.render

/**
 * Runs a frame's HBlank handlers against an I/O register image and records the per-line register values that
 * differ from the frame's base registers as [LineTable] overrides (text BG scroll, window x, blend
 * coefficients and control), so the renderers reproduce mid-frame register writes without emulating HBlank timing.
 *
 * Timing as the game's handlers assume: the handler runs after every line 0..227 (`vcount` = the
 * line just drawn; the decomp's `HBlank_BgVerticalScroll` reads `REG_VCOUNT + 1` and wraps 228 to
 * 0, so it also runs in the VBlank lines), so values written at `vcount` 159..227 apply from line 0
 * and a value written at line L applies from L + 1. One capture covers the handler calls from the
 * previous frame's line 159 (where handlers latch their next-frame settings, as
 * `HBlank_BgVerticalScroll` does at line 160) through line 158 of this frame.
 */
public object HBlankLines {
    /**
     * Records the lines of one frame. [base] is the register image at the start of the frame's VBlank period
     * (after the game's flush); it is not modified. [handler] receives `vcount` and the live register image.
     */
    public fun capture(base: ByteArray, state: RenderState, handler: (vcount: Int, io: ByteArray) -> Unit) {
        capture(base, state, base.copyOf(), handler)
    }

    /**
     * [capture] with a caller-owned register image [io] (at least [base]'s size; overwritten), so a
     * per-frame caller allocates nothing.
     */
    public fun capture(
        base: ByteArray,
        state: RenderState,
        io: ByteArray,
        handler: (vcount: Int, io: ByteArray) -> Unit,
    ) {
        base.copyInto(io)
        val lines = state.lines
        for (vcount in RenderState.LINES - 1 until TOTAL_LINES) handler(vcount, io)
        for (line in 0 until RenderState.LINES) {
            for (bg in 0 until RenderState.LAYERS) {
                val layer = state.layers[bg]
                if (layer.affine) continue
                // BGnHOFS / BGnVOFS (0x04000010 + 4n, +2): 9-bit scroll offsets
                val x = u16(io, 0x10 + 4 * bg) and 0x1FF
                val y = u16(io, 0x12 + 4 * bg) and 0x1FF
                if (x != layer.scrollX || y != layer.scrollY) lines.setScroll(line, bg, x, y)
            }
            val fx = state.effects
            for (windowIndex in 0 until 2) {
                // WIN0H / WIN1H (0x04000040, 0x42): low byte = right edge, high byte = left edge
                val winCoord = u16(io, 0x40 + 2 * windowIndex)
                val right = winCoord and 0xFF
                val left = winCoord shr 8
                if (left != fx.winLeft[windowIndex] || right != fx.winRight[windowIndex]) {
                    lines.setWindowX(line, windowIndex, left, right)
                }
            }
            val alpha = u16(io, 0x52) // BLDALPHA: EVA bits 0..4, EVB bits 8..12
            val eva = alpha and 0x1F
            val evb = (alpha shr 8) and 0x1F
            val evy = u16(io, 0x54) and 0x1F // BLDY
            if (eva != fx.eva || evb != fx.evb || evy != fx.evy) lines.setBlend(line, eva, evb, evy)
            val bldcnt = u16(io, 0x50) and 0x3FFF // BLDCNT: targets, effect (bits 6..7)
            val baseCnt = fx.blendTop or (fx.blendMode shl 6) or (fx.blendBottom shl 8)
            if (bldcnt != baseCnt) lines.setBlendControl(line, bldcnt)
            if (line < RenderState.LINES - 1) handler(line, io)
        }
    }

    /** Scanlines per frame including VBlank (`REG_VCOUNT` 0..227). */
    private const val TOTAL_LINES = 228

    /** Little-endian halfword at [offset] of the I/O register image. */
    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
}
