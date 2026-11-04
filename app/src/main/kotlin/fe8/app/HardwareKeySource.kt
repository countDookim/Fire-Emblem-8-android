package fe8.app

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import gba.input.ActionSource

/**
 * [ActionSource] for hardware keys and gamepads (task M3-10): maps Android key codes, the D-pad
 * hat and the left stick to the GBA keys (action bits in KEYINPUT order: A, B, Select, Start,
 * Right, Left, Up, Down, R, L). Emits exactly GBA keys; no game-logic change (decision 0009).
 *
 * Feed it from the view's `onKeyDown`/`onKeyUp`/`onGenericMotionEvent` on the UI thread; the GL
 * thread [poll]s it. Nothing allocates per event or poll.
 */
public class HardwareKeySource : ActionSource {
    @Volatile private var keys: Long = 0L
    @Volatile private var axes: Long = 0L

    override fun poll(): Long = keys or axes

    /** Handles a key event; returns true if it is a mapped GBA key. */
    public fun onKey(event: KeyEvent): Boolean {
        val bit = bitFor(event.keyCode)
        if (bit < 0) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> keys = keys or (1L shl bit)
            KeyEvent.ACTION_UP -> keys = keys and (1L shl bit).inv()
        }
        return true
    }

    /** Handles joystick motion (hat and left stick); returns true if consumed. */
    public fun onMotion(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK ||
            event.action != MotionEvent.ACTION_MOVE
        ) {
            return false
        }
        val x = pick(event.getAxisValue(MotionEvent.AXIS_HAT_X), event.getAxisValue(MotionEvent.AXIS_X))
        val y = pick(event.getAxisValue(MotionEvent.AXIS_HAT_Y), event.getAxisValue(MotionEvent.AXIS_Y))
        var a = 0L
        if (x > DEAD_ZONE) a = a or (1L shl RIGHT)
        if (x < -DEAD_ZONE) a = a or (1L shl LEFT)
        if (y < -DEAD_ZONE) a = a or (1L shl UP)
        if (y > DEAD_ZONE) a = a or (1L shl DOWN)
        axes = a
        return true
    }

    /** Releases everything (focus loss, pause). */
    public fun clear() {
        keys = 0L
        axes = 0L
    }

    private fun pick(hat: Float, stick: Float): Float = if (hat != 0f) hat else stick

    /** Key codes and their GBA key bits. */
    public companion object {
        private const val A = 0
        private const val B = 1
        private const val SELECT = 2
        private const val START = 3
        private const val RIGHT = 4
        private const val LEFT = 5
        private const val UP = 6
        private const val DOWN = 7
        private const val R = 8
        private const val L = 9
        private const val DEAD_ZONE = 0.5f

        /**
         * The GBA key bit for an Android [keyCode], or -1. Gamepad: A/B as on the pad, Select,
         * Start, L1/R1 (and L2/R2). Keyboard: X = A, Z = B, Backspace = Select, Enter = Start,
         * A = L, S = R, arrows / D-pad = the D-pad.
         */
        public fun bitFor(keyCode: Int): Int = when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_DPAD_CENTER -> A
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_Z -> B
            KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_DEL -> SELECT
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_ENTER -> START
            KeyEvent.KEYCODE_DPAD_RIGHT -> RIGHT
            KeyEvent.KEYCODE_DPAD_LEFT -> LEFT
            KeyEvent.KEYCODE_DPAD_UP -> UP
            KeyEvent.KEYCODE_DPAD_DOWN -> DOWN
            KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.KEYCODE_S -> R
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_A -> L
            else -> -1
        }
    }
}
