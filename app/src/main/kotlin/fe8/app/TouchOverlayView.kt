package fe8.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import gbafe.input.TouchButtons

/**
 * Transparent view over the game that draws the on-screen GBA buttons and feeds touches to
 * [buttons] (task M3-15). All layout and hit-testing lives in the JVM-tested [TouchButtons];
 * this class only forwards pointer events and paints the rectangles. Nothing allocates per
 * event or draw.
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
    private val names = TouchButtons.Button.entries.map { if (it.name == "DPAD") "+" else it.name }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        buttons.resize(w, h)
        label.textSize = minOf(w, h) / 20f
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
            canvas.drawRoundRect(l[i], l[i + 1], l[i + 2], l[i + 3], RADIUS, RADIUS, fill)
            val y = (l[i + 1] + l[i + 3]) / 2 - (label.ascent() + label.descent()) / 2
            canvas.drawText(names[b.ordinal], (l[i] + l[i + 2]) / 2, y, label)
        }
    }

    private companion object {
        private const val RADIUS = 24f
    }
}
