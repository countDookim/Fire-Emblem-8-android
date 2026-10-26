package gbafe.render

/**
 * Runs a frame's HBlank handlers against an I/O register image and records the per-line register values that
 * differ from the frame's base registers as [LineTable] overrides (text BG scroll, window x, blend
 * coefficients), so the renderers reproduce mid-frame register writes without emulating HBlank timing.
 *
 * Timing matches the hardware: the handler runs after every line 0..227 (`vcount` = the line just drawn),
 * so values written at `vcount` 159..227 apply from line 0 and a value written at line L applies from L + 1.
 * One capture covers the handler calls from the previous frame's line 159 (where handlers latch their
 * next-frame settings) through line 158 of this frame.
 */
public object HBlankLines {
    /**
     * Records the lines of one frame. [base] is the register image at the start of the frame's VBlank period
     * (after the game's flush); it is not modified. [handler] receives `vcount` and the live register image.
     */
    public fun capture(base: ByteArray, state: RenderState, handler: (vcount: Int, io: ByteArray) -> Unit) {
        val io = base.copyOf()
        val lines = state.lines
        for (v in RenderState.LINES - 1 until TOTAL_LINES) handler(v, io)
        for (line in 0 until RenderState.LINES) {
            for (bg in 0 until RenderState.LAYERS) {
                val layer = state.layers[bg]
                if (layer.affine) continue
                val x = u16(io, 0x10 + 4 * bg) and 0x1FF
                val y = u16(io, 0x12 + 4 * bg) and 0x1FF
                if (x != layer.scrollX || y != layer.scrollY) lines.setScroll(line, bg, x, y)
            }
            val fx = state.effects
            for (w in 0 until 2) {
                val h = u16(io, 0x40 + 2 * w)
                val right = h and 0xFF
                val left = h shr 8
                if (left != fx.winLeft[w] || right != fx.winRight[w]) lines.setWindowX(line, w, left, right)
            }
            val alpha = u16(io, 0x52)
            val eva = alpha and 0x1F
            val evb = (alpha shr 8) and 0x1F
            val evy = u16(io, 0x54) and 0x1F
            if (eva != fx.eva || evb != fx.evb || evy != fx.evy) lines.setBlend(line, eva, evb, evy)
            if (line < RenderState.LINES - 1) handler(line, io)
        }
    }

    /** Scanlines per frame including VBlank (`VCOUNT` 0..227). */
    private const val TOTAL_LINES = 228

    private fun u16(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
}
