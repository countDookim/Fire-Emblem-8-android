package gba.input

import kotlin.concurrent.Volatile

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
    /** KEYINPUT bits held by each pointer id; the union of all is [held]. */
    private val pointerKeys = LongArray(MAX_POINTERS)

    /** Union of every pointer's keys; volatile because the UI thread writes and the game polls. */
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
        val safeWidth = right - left
        val safeHeight = height - insetBottom - top
        // Layout unit: an eighth of the safe area's shorter side, so buttons scale with the view.
        val unit = minOf(safeWidth, safeHeight) / 8f
        val margin = unit * MARGIN
        val bottom = top + safeHeight - margin
        // D-pad: a 3x3 grid of cells of size unit at the bottom left; corners are diagonals.
        set(Button.DPAD, left + margin, bottom - unit * 3, left + margin + unit * 3, bottom)
        set(
            Button.A,
            right - margin - unit * 1.2f,
            bottom - unit * 2.6f,
            right - margin,
            bottom - unit * 1.4f,
        )
        set(
            Button.B,
            right - margin - unit * 2.6f,
            bottom - unit * 1.6f,
            right - margin - unit * 1.4f,
            bottom - unit * 0.4f,
        )
        set(Button.L, left + margin, top + margin, left + margin + unit * 2.5f, top + margin + unit)
        set(
            Button.R,
            right - margin - unit * 2.5f,
            top + margin,
            right - margin,
            top + margin + unit,
        )
        val mid = left + safeWidth / 2f
        set(Button.SELECT, mid - unit * 2.1f, bottom - unit * 0.7f, mid - unit * 0.1f, bottom)
        set(Button.START, mid + unit * 0.1f, bottom - unit * 0.7f, mid + unit * 2.1f, bottom)
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

    /** Returns the keys currently held by any pointer. */
    override fun poll(): Long = held

    /** GBA key bits under ([x], [y]); 0 outside every button. */
    public fun keysAt(x: Float, y: Float): Long {
        for (button in Button.entries) {
            val base = button.ordinal * 4
            val outside = x < layout[base] || y < layout[base + 1] ||
                x >= layout[base + 2] || y >= layout[base + 3]
            if (outside) {
                continue
            }
            if (button != Button.DPAD) return 1L shl button.gbaBit
            // D-pad: the pressed cell of its 3x3 grid picks the direction bits (diagonals = 2).
            val cell = (layout[base + 2] - layout[base]) / 3f
            val col = ((x - layout[base]) / cell).toInt().coerceIn(0, 2)
            val row = ((y - layout[base + 1]) / cell).toInt().coerceIn(0, 2)
            var keys = 0L
            if (col == 0) keys = keys or (1L shl LEFT)
            if (col == 2) keys = keys or (1L shl RIGHT)
            if (row == 0) keys = keys or (1L shl UP)
            if (row == 2) keys = keys or (1L shl DOWN)
            return keys
        }
        return 0L
    }

    /** Stores [button]'s rectangle (left, top, right, bottom) into [layout]. */
    private fun set(button: Button, left: Float, top: Float, right: Float, bottom: Float) {
        val base = button.ordinal * 4
        layout[base] = left
        layout[base + 1] = top
        layout[base + 2] = right
        layout[base + 3] = bottom
    }

    /** Rebuilds [held] as the union of all pointers' key masks. */
    private fun recompute() {
        var union = 0L
        for (pointerMask in pointerKeys) union = union or pointerMask
        held = union
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
        /** Edge margin as a fraction of the layout unit. */
        private const val MARGIN = 0.4f
        private const val RIGHT = 4
        private const val LEFT = 5
        private const val UP = 6
        private const val DOWN = 7
    }
}
