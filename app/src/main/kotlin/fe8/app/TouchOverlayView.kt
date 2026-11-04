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
 * Transparent view over the game that draws the on-screen GBA buttons and feeds touches to
 * [buttons] (task M3-15). All layout and hit-testing lives in the JVM-tested [TouchButtons];
 * this class only forwards pointer events and paints the buttons. The D-pad is drawn as a cross
 * with arrows (its hit area stays the 3x3 grid of [TouchButtons.keysAt], so diagonals work).
 * Buttons are laid out inside the system bar / display cutout insets (issue #3: the app is
 * edge-to-edge on targetSdk 35+). Nothing allocates per event or draw.
 */
public class TouchOverlayView(
    context: Context,
    /** The button state the game polls (combined with hardware keys in [GameSurfaceView]). */
    public val buttons: TouchButtons,
) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40FFFFFF }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt()
        textAlign = Paint.Align.CENTER
    }
    private val names = TouchButtons.Button.entries.map { it.name }
    private val arrows = Path()
    private var insetL = 0
    private var insetT = 0
    private var insetR = 0
    private var insetB = 0

    init {
        // Inset changes (rotation, cutout mode); AndroidView in Compose may not forward them,
        // so relayout() also reads the root insets directly.
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            readInsets(insets)
            relayout()
            insets
        }
    }

    private fun readInsets(insets: WindowInsetsCompat) {
        val i = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
        )
        insetL = i.left
        insetT = i.top
        insetR = i.right
        insetB = i.bottom
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        relayout()
        label.textSize = minOf(w, h) / 20f
    }

    private fun relayout() {
        if (width == 0 || height == 0) return
        ViewCompat.getRootWindowInsets(this)?.let(::readInsets)
        buttons.resize(width, height, insetL, insetT, insetR, insetB)
        buildArrows()
        invalidate()
    }

    /** Arrow triangles in the four D-pad arms (built on layout, not per draw). */
    private fun buildArrows() {
        val i = TouchButtons.Button.DPAD.ordinal * 4
        val l = buttons.layout
        val c = (l[i + 2] - l[i]) / 3f
        val cx = l[i] + c * 1.5f
        val cy = l[i + 1] + c * 1.5f
        val a = c * 0.25f
        arrows.reset()
        for (d in 0 until 4) {
            // Tip offset (dx, dy) from centre towards the arm; base is perpendicular.
            val dx = ARROW_DX[d]
            val dy = ARROW_DY[d]
            val tipX = cx + dx * c * 1.25f
            val tipY = cy + dy * c * 1.25f
            val baseX = cx + dx * c * 0.75f
            val baseY = cy + dy * c * 0.75f
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
            canvas.drawRoundRect(l[i], l[i + 1], l[i + 2], l[i + 3], RADIUS, RADIUS, fill)
            val y = (l[i + 1] + l[i + 3]) / 2 - (label.ascent() + label.descent()) / 2
            canvas.drawText(names[b.ordinal], (l[i] + l[i + 2]) / 2, y, label)
        }
    }

    private fun drawDpad(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        val c = (right - left) / 3f
        // Vertical bar, then the two horizontal arms (no overlap so the alpha stays uniform).
        canvas.drawRoundRect(left + c, top, right - c, bottom, RADIUS, RADIUS, fill)
        canvas.drawRoundRect(left, top + c, left + c, bottom - c, RADIUS, RADIUS, fill)
        canvas.drawRoundRect(right - c, top + c, right, bottom - c, RADIUS, RADIUS, fill)
        canvas.drawPath(arrows, label)
    }

    private companion object {
        private const val RADIUS = 24f
        private val ARROW_DX = intArrayOf(0, 0, -1, 1)
        private val ARROW_DY = intArrayOf(-1, 1, 0, 0)
    }
}
