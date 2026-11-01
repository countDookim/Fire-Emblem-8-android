package gbafe.input

/**
 * On-screen GBA button overlay (task M3-15): the only touch input the product ships (decision
 * 0009). It emits exactly the GBA keys (bits in KEYINPUT order, as [VanillaButtonMap]), so game
 * logic cannot tell it from a gamepad.
 *
 * Platform-free so it is testable on the JVM: the Android view forwards pointer events here
 * and draws the rectangles from [layout]. Multi-touch: each pointer holds its own key mask;
 * sliding a finger across buttons updates that pointer's mask. Nothing allocates per event or
 * poll.
 */
public class TouchButtons : ActionSource {
    private val pointerKeys = LongArray(MAX_POINTERS)

    @Volatile private var held: Long = 0L

    /** Rectangles of the buttons, in view pixels, as `left, top, right, bottom` per [Button]. */
    public val layout: FloatArray = FloatArray(Button.entries.size * 4)

    /**
     * Lays the buttons out for a view of [width] x [height] pixels (call on size or inset
     * change). The `inset*` arguments are the system bar / display cutout insets in pixels:
     * every button stays inside that safe area, with a margin, so nothing sits under a camera
     * notch or the gesture bar (issue #3).
     */
    public fun resize(
        width: Int,
        height: Int,
        insetLeft: Int = 0,
        insetTop: Int = 0,
        insetRight: Int = 0,
        insetBottom: Int = 0,
    ) {
        val left = insetLeft.toFloat()
        val top = insetTop.toFloat()
        val right = (width - insetRight).toFloat()
        val safeW = right - left
        val safeH = height - insetBottom - top
        val u = minOf(safeW, safeH) / 8f
        val m = u * MARGIN
        val bottom = top + safeH - m
        // D-pad: a 3x3 grid of cells of size u at the bottom left; corners are diagonals.
        set(Button.DPAD, left + m, bottom - u * 3, left + m + u * 3, bottom)
        set(Button.A, right - m - u * 1.2f, bottom - u * 2.6f, right - m, bottom - u * 1.4f)
        set(
            Button.B,
            right - m - u * 2.6f,
            bottom - u * 1.6f,
            right - m - u * 1.4f,
            bottom - u * 0.4f,
        )
        set(Button.L, left + m, top + m, left + m + u * 2.5f, top + m + u)
        set(Button.R, right - m - u * 2.5f, top + m, right - m, top + m + u)
        val mid = left + safeW / 2f
        set(Button.SELECT, mid - u * 2.1f, bottom - u * 0.7f, mid - u * 0.1f, bottom)
        set(Button.START, mid + u * 0.1f, bottom - u * 0.7f, mid + u * 2.1f, bottom)
    }

    /** Pointer [id] went down or moved to ([x], [y]). Ids >= [MAX_POINTERS] are ignored. */
    public fun pointerAt(id: Int, x: Float, y: Float) {
        if (id !in 0 until MAX_POINTERS) return
        pointerKeys[id] = keysAt(x, y)
        recompute()
    }

    /** Pointer [id] lifted. */
    public fun pointerUp(id: Int) {
        if (id !in 0 until MAX_POINTERS) return
        pointerKeys[id] = 0L
        recompute()
    }

    /** Releases every pointer (gesture cancelled, pause). */
    public fun clear() {
        pointerKeys.fill(0L)
        held = 0L
    }

    override fun poll(): Long = held

    /** GBA key bits under ([x], [y]); 0 outside every button. */
    public fun keysAt(x: Float, y: Float): Long {
        for (b in Button.entries) {
            val i = b.ordinal * 4
            if (x < layout[i] || y < layout[i + 1] || x >= layout[i + 2] || y >= layout[i + 3]) {
                continue
            }
            if (b != Button.DPAD) return 1L shl b.gbaBit
            val cell = (layout[i + 2] - layout[i]) / 3f
            val col = ((x - layout[i]) / cell).toInt().coerceIn(0, 2)
            val row = ((y - layout[i + 1]) / cell).toInt().coerceIn(0, 2)
            var k = 0L
            if (col == 0) k = k or (1L shl LEFT)
            if (col == 2) k = k or (1L shl RIGHT)
            if (row == 0) k = k or (1L shl UP)
            if (row == 2) k = k or (1L shl DOWN)
            return k
        }
        return 0L
    }

    private fun set(b: Button, l: Float, t: Float, r: Float, bt: Float) {
        val i = b.ordinal * 4
        layout[i] = l
        layout[i + 1] = t
        layout[i + 2] = r
        layout[i + 3] = bt
    }

    private fun recompute() {
        var k = 0L
        for (m in pointerKeys) k = k or m
        held = k
    }

    /** The drawn buttons; [gbaBit] is the KEYINPUT bit ([DPAD] covers four, see [keysAt]). */
    public enum class Button(
        /** KEYINPUT bit of this button (unused for [DPAD]). */
        public val gbaBit: Int,
    ) {
        /** Direction pad, a 3x3 grid (centre cell = no key). */
        DPAD(-1),

        /** A button. */
        A(0),

        /** B button. */
        B(1),

        /** Select button. */
        SELECT(2),

        /** Start button. */
        START(3),

        /** R shoulder button. */
        R(8),

        /** L shoulder button. */
        L(9),
    }

    /** Limits and KEYINPUT bit numbers. */
    public companion object {
        /** Pointers tracked at once. */
        public const val MAX_POINTERS: Int = 10
        private const val MARGIN = 0.4f
        private const val RIGHT = 4
        private const val LEFT = 5
        private const val UP = 6
        private const val DOWN = 7
    }
}
