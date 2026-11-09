/**
 * Transparent touch overlay view for on-screen GBA game controls.
 *
 * Renders virtual game buttons (D-pad with direction arrows, A, B, L, R, Start, Select) over
 * the game view, translating multi-touch pointer events into button presses via [gba.input.TouchButtons].
 * Automatically adjusts control positions to accommodate Android 15 edge-to-edge system bars and display cutouts.
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
 * Transparent view overlaid over the game rendering surface that draws on-screen GBA controls
 * and routes multi-touch events to [buttons] (task M3-15).
 *
 * Hit testing and dynamic bounding-box calculation are managed by [TouchButtons].
 * The D-pad is drawn as a cross with direction indicators while maintaining a 3x3 touch grid
 * to support diagonal inputs cleanly. Layout bounds adjust dynamically for window insets.
 * Operates without per-event or per-frame allocations during gameplay.
 *
 * @property buttons The underlying touch input state polled by the game loop.
 */
public class TouchOverlayView(
    context: Context,
    /** The button state the game polls (combined with hardware keys in [GameSurfaceView]). */
    public val buttons: TouchButtons,
) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_BUTTON_FILL }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_BUTTON_LABEL
        textAlign = Paint.Align.CENTER
    }
    private val names = TouchButtons.Button.entries.map { it.name }
    private val arrows = Path()
    private var insetLeft = 0
    private var insetTop = 0
    private var insetRight = 0
    private var insetBottom = 0

    init {
        // Inset changes (rotation, cutout mode); AndroidView in Compose may not forward them,
        // so relayout() also reads the root insets directly.
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            readInsets(insets)
            relayout()
            insets
        }
    }

    /** Extracts system bar and display cutout insets to prevent button clipping. */
    private fun readInsets(insets: WindowInsetsCompat) {
        val i = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
        )
        insetLeft = i.left
        insetTop = i.top
        insetRight = i.right
        insetBottom = i.bottom
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        relayout()
        label.textSize = minOf(w, h) / LABEL_TEXT_SIZE_DIVISOR
    }

    /** Recomputes button layout regions and D-pad arrows when dimensions or insets change. */
    private fun relayout() {
        if (width == 0 || height == 0) return
        ViewCompat.getRootWindowInsets(this)?.let(::readInsets)
        buttons.resize(width, height, insetLeft, insetTop, insetRight, insetBottom)
        buildArrows()
        invalidate()
    }

    /** Pre-computes arrow triangles in the four D-pad arms during layout to avoid per-draw geometry generation. */
    private fun buildArrows() {
        val i = TouchButtons.Button.DPAD.ordinal * 4
        val l = buttons.layout
        val c = (l[i + 2] - l[i]) / DPAD_ARM_DIVISIONS
        val cx = l[i] + c * DPAD_CENTER_OFFSET
        val cy = l[i + 1] + c * DPAD_CENTER_OFFSET
        val a = c * ARROW_HALF_WIDTH_FACTOR
        arrows.reset()
        for (d in 0 until 4) {
            // Tip offset (dx, dy) from centre towards the arm; base is perpendicular.
            val dx = ARROW_DX[d]
            val dy = ARROW_DY[d]
            val tipX = cx + dx * c * ARROW_TIP_FACTOR
            val tipY = cy + dy * c * ARROW_TIP_FACTOR
            val baseX = cx + dx * c * ARROW_BASE_FACTOR
            val baseY = cy + dy * c * ARROW_BASE_FACTOR
            arrows.moveTo(tipX, tipY)
            arrows.lineTo(baseX + dy * a, baseY + dx * a)
            arrows.lineTo(baseX - dy * a, baseY - dx * a)
            arrows.close()
        }
    }

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

    override fun onDraw(canvas: Canvas) {
        val l = buttons.layout
        for (b in TouchButtons.Button.entries) {
            val i = b.ordinal * 4
            if (b == TouchButtons.Button.DPAD) {
                drawDpad(canvas, l[i], l[i + 1], l[i + 2], l[i + 3])
                continue
            }
            canvas.drawRoundRect(l[i], l[i + 1], l[i + 2], l[i + 3], BUTTON_RADIUS, BUTTON_RADIUS, fill)
            val y = (l[i + 1] + l[i + 3]) / 2 - (label.ascent() + label.descent()) / 2
            canvas.drawText(names[b.ordinal], (l[i] + l[i + 2]) / 2, y, label)
        }
    }

    /** Draws the composite D-pad cross without overlapping rounded rects to maintain uniform alpha blending. */
    private fun drawDpad(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        val c = (right - left) / DPAD_ARM_DIVISIONS
        // Vertical bar, then the two horizontal arms (no overlap so the alpha stays uniform).
        canvas.drawRoundRect(left + c, top, right - c, bottom, BUTTON_RADIUS, BUTTON_RADIUS, fill)
        canvas.drawRoundRect(left, top + c, left + c, bottom - c, BUTTON_RADIUS, BUTTON_RADIUS, fill)
        canvas.drawRoundRect(right - c, top + c, right, bottom - c, BUTTON_RADIUS, BUTTON_RADIUS, fill)
        canvas.drawPath(arrows, label)
    }

    private companion object {
        /** Button shape corner radius in pixels. */
        private const val BUTTON_RADIUS = 24f

        /** Fill color: 25% semi-transparent white (`0x40FFFFFF`). */
        private const val COLOR_BUTTON_FILL = 0x40FFFFFF

        /** Label and arrow color: 60% semi-transparent white (`0x99FFFFFF`). */
        private const val COLOR_BUTTON_LABEL = 0x99FFFFFF.toInt()

        /** Ratio divisor used to size button text labels relative to view dimensions. */
        private const val LABEL_TEXT_SIZE_DIVISOR = 20f

        /** D-pad arm grid division count (3x3 grid). */
        private const val DPAD_ARM_DIVISIONS = 3f

        /** Offset factor to locate the center of the D-pad cross. */
        private const val DPAD_CENTER_OFFSET = 1.5f

        /** Half-width scaling factor for D-pad direction arrow bases. */
        private const val ARROW_HALF_WIDTH_FACTOR = 0.25f

        /** Length factor for arrow tip positions. */
        private const val ARROW_TIP_FACTOR = 1.25f

        /** Length factor for arrow base positions. */
        private const val ARROW_BASE_FACTOR = 0.75f

        /** Arrow direction X-offsets: Up, Down, Left, Right. */
        private val ARROW_DX = intArrayOf(0, 0, -1, 1)

        /** Arrow direction Y-offsets: Up, Down, Left, Right. */
        private val ARROW_DY = intArrayOf(-1, 1, 0, 0)
    }
}
