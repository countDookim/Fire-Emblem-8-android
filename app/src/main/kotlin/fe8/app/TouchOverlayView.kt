/**
 * The on-screen buttons the player taps on a phone: D-pad, A, B, L, R, Start, Select, drawn over
 * the game picture, with multi-touch turned into button presses via [gba.input.TouchButtons].
 * Buttons are kept clear of the system bars and display cutout.
 */

package fe8.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import gba.input.TouchButtons

/**
 * Translucent view over the game surface that draws the on-screen buttons and feeds touches to
 * [buttons]. Where each button sits and which one a touch hits is decided by [TouchButtons]
 * (`resize`, `keysAt`); this view only draws that layout and forwards pointers.
 *
 * The D-pad is drawn as a cross of three bars with an arrow per arm, but [TouchButtons] treats it
 * as a 3x3 grid whose corner cells press two directions, so diagonals work (the corners are not
 * drawn). Onscreen insets come from the system bars and display cutout. The draw and touch paths
 * allocate nothing: the arrow path is rebuilt only on layout.
 *
 * @property buttons The underlying touch input state polled by the game loop.
 */
public class TouchOverlayView(
    context: Context,
    /** The button state the game polls (combined with hardware keys in [GameSurfaceView]). */
    public val buttons: TouchButtons,
) : View(context) {
    /** Paint for button bodies. */
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_BUTTON_FILL }

    /** Paint for button letters and the D-pad arrows. */
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_BUTTON_LABEL
        textAlign = Paint.Align.CENTER
    }

    /** Text on each button, by [TouchButtons.Button] ordinal (the enum name, e.g. `START`). */
    private val names = TouchButtons.Button.entries.map { it.name }

    /** The four D-pad arrow triangles, rebuilt by [buildArrows]. */
    private val arrows = Path()

    /** Safe-area insets in pixels from the system bars and cutout, last read by [readInsets]. */
    private var insetLeft = 0
    private var insetTop = 0
    private var insetRight = 0
    private var insetBottom = 0

    init {
        // Inset changes (rotation, cutout mode) re-lay out the buttons. The view sits inside a
        // Compose AndroidView, which may not deliver insets here (Android behaviour, not checked
        // here), so relayout() also reads the root window insets itself.
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            readInsets(insets)
            relayout()
            insets
        }
    }

    /** Stores the system-bar and display-cutout insets so buttons are laid out inside them. */
    private fun readInsets(insets: WindowInsetsCompat) {
        val i = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
        )
        insetLeft = i.left
        insetTop = i.top
        insetRight = i.right
        insetBottom = i.bottom
    }

    /** View resized: re-lay out the buttons; label text is a twentieth of the shorter side. */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        relayout()
        label.textSize = minOf(w, h) / LABEL_TEXT_SIZE_DIVISOR
    }

    /** Asks [TouchButtons] for button rectangles for the current size and insets, then redraws. */
    private fun relayout() {
        if (width == 0 || height == 0) return
        ViewCompat.getRootWindowInsets(this)?.let(::readInsets)
        buttons.resize(width, height, insetLeft, insetTop, insetRight, insetBottom)
        buildArrows()
        invalidate()
    }

    /**
     * Builds the four arrow triangles (up, down, left, right) of the D-pad from its current
     * rectangle. Done on layout, not per draw, so drawing allocates nothing.
     */
    private fun buildArrows() {
        val dpadIndex = TouchButtons.Button.DPAD.ordinal * 4
        val rects = buttons.layout
        // The D-pad rectangle is 3 cells wide; one cell is the width of an arm.
        val cell = (rects[dpadIndex + 2] - rects[dpadIndex]) / DPAD_ARM_DIVISIONS
        val centerX = rects[dpadIndex] + cell * DPAD_CENTER_OFFSET
        val centerY = rects[dpadIndex + 1] + cell * DPAD_CENTER_OFFSET
        val halfWidth = cell * ARROW_HALF_WIDTH_FACTOR
        arrows.reset()
        for (arm in 0 until 4) {
            // (dx, dy) is the unit step from the centre towards this arm; the triangle's base
            // is perpendicular to it, hence the swapped dx and dy in the base corners.
            val dx = ARROW_DX[arm]
            val dy = ARROW_DY[arm]
            val tipX = centerX + dx * cell * ARROW_TIP_FACTOR
            val tipY = centerY + dy * cell * ARROW_TIP_FACTOR
            val baseX = centerX + dx * cell * ARROW_BASE_FACTOR
            val baseY = centerY + dy * cell * ARROW_BASE_FACTOR
            arrows.moveTo(tipX, tipY)
            arrows.lineTo(baseX + dy * halfWidth, baseY + dx * halfWidth)
            arrows.lineTo(baseX - dy * halfWidth, baseY - dx * halfWidth)
            arrows.close()
        }
    }

    /**
     * Touch to buttons. Down and move re-evaluate every finger, so sliding from one button to
     * another switches the press; a lifted finger releases only its own buttons; a cancelled
     * gesture releases everything. Always consumes the event so the game view below gets none.
     */
    @SuppressLint("ClickableViewAccessibility") // game input, not a clickable control
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_MOVE ->
                for (i in 0 until event.pointerCount) {
                    buttons.pointerAt(event.getPointerId(i), event.getX(i), event.getY(i))
                }
            MotionEvent.ACTION_POINTER_UP -> buttons.pointerUp(event.getPointerId(event.actionIndex))
            MotionEvent.ACTION_UP -> buttons.pointerUp(event.getPointerId(0))
            MotionEvent.ACTION_CANCEL -> buttons.clear()
        }
        return true
    }

    /** Draws each button at its [TouchButtons.layout] rectangle (D-pad cross, or a named box). */
    override fun onDraw(canvas: Canvas) {
        val rects = buttons.layout
        for (button in TouchButtons.Button.entries) {
            val i = button.ordinal * 4
            if (button == TouchButtons.Button.DPAD) {
                drawDpad(canvas, rects[i], rects[i + 1], rects[i + 2], rects[i + 3])
                continue
            }
            val left = rects[i]
            val top = rects[i + 1]
            val right = rects[i + 2]
            val bottom = rects[i + 3]
            canvas.drawRoundRect(left, top, right, bottom, BUTTON_RADIUS, BUTTON_RADIUS, fill)
            // Baseline that centres the text vertically (ascent is negative).
            val baseline = (top + bottom) / 2 - (label.ascent() + label.descent()) / 2
            canvas.drawText(names[button.ordinal], (left + right) / 2, baseline, label)
        }
    }

    /**
     * Draws the D-pad as a vertical bar plus a left and a right arm, then the arrows. The three
     * rectangles do not overlap, so the translucent fill has the same alpha everywhere.
     */
    private fun drawDpad(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        val cell = (right - left) / DPAD_ARM_DIVISIONS
        val radius = BUTTON_RADIUS
        canvas.drawRoundRect(left + cell, top, right - cell, bottom, radius, radius, fill)
        canvas.drawRoundRect(left, top + cell, left + cell, bottom - cell, radius, radius, fill)
        canvas.drawRoundRect(right - cell, top + cell, right, bottom - cell, radius, radius, fill)
        canvas.drawPath(arrows, label)
    }

    private companion object {
        /** Button shape corner radius in pixels. */
        private const val BUTTON_RADIUS = 24f

        /** Button fill: white at alpha 0x40 (64/255, about 25% opaque). */
        private const val COLOR_BUTTON_FILL = 0x40FFFFFF

        /** Label and arrow colour: white at alpha 0x99 (153/255 = 60% opaque). */
        private const val COLOR_BUTTON_LABEL = 0x99FFFFFF.toInt()

        /** Label text size is the view's shorter side divided by this. */
        private const val LABEL_TEXT_SIZE_DIVISOR = 20f

        /** Cells across (and down) the D-pad: a 3x3 grid, matching `TouchButtons.keysAt`. */
        private const val DPAD_ARM_DIVISIONS = 3f

        /** Distance from the D-pad's top-left corner to its centre, in cells (half of 3). */
        private const val DPAD_CENTER_OFFSET = 1.5f

        /** Half the width of an arrow's base, in cells. */
        private const val ARROW_HALF_WIDTH_FACTOR = 0.25f

        /** Distance of an arrow's tip from the D-pad centre, in cells (an arm spans 0.5 to 1.5). */
        private const val ARROW_TIP_FACTOR = 1.25f

        /** Distance of an arrow's base from the D-pad centre, in cells. */
        private const val ARROW_BASE_FACTOR = 0.75f

        /** Unit step in x towards each arm, in the order up, down, left, right. */
        private val ARROW_DX = intArrayOf(0, 0, -1, 1)

        /** Unit step in y (screen y grows downwards) towards each arm, same order as [ARROW_DX]. */
        private val ARROW_DY = intArrayOf(-1, 1, 0, 0)
    }
}
