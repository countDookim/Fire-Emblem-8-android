/**
 * Hardware controller and keyboard input translation.
 *
 * Maps Android [KeyEvent] key codes and [MotionEvent] joystick / D-pad axes to the Game Boy Advance
 * hardware `KEYINPUT` bitmask format (active-high bits for A, B, Select, Start, Right, Left, Up, Down, R, L).
 */

package fe8.app

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import gba.input.ActionSource

/**
 * [ActionSource] for physical keyboards and gamepads.
 *
 * Maps Android key codes, the analog D-pad hat, and the primary left analog stick to standard
 * GBA button bits in `KEYINPUT` bit order. Hardware events are fed on the Android UI thread
 * via [onKey] and [onMotion], while the OpenGL render loop concurrently reads state via [poll].
 * Operates entirely without per-event or per-poll heap allocations.
 */
public class HardwareKeySource : ActionSource {
    @Volatile private var keys: Long = 0L
    @Volatile private var axes: Long = 0L

    /**
     * Polls the current combined state of physical keys and joystick axes.
     *
     * @return Bitmask of currently depressed GBA buttons in `KEYINPUT` bit order.
     */
    override fun poll(): Long = keys or axes

    /**
     * Handles an Android key event; updates internal state if mapped to a GBA button.
     *
     * @param event The hardware key event to process.
     * @return `true` if the key code maps to a recognized GBA control, `false` otherwise.
     */
    public fun onKey(event: KeyEvent): Boolean {
        val bit = bitFor(event.keyCode)
        if (bit < 0) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> keys = keys or (1L shl bit)
            KeyEvent.ACTION_UP -> keys = keys and (1L shl bit).inv()
        }
        return true
    }

    /**
     * Handles joystick and gamepad motion events (hat switch and primary analog stick).
     *
     * @param event The motion event to inspect.
     * @return `true` if consumed as joystick movement, `false` otherwise.
     */
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

    /**
     * Releases all currently held keys and directional axes.
     * Called when the view loses focus or the activity pauses.
     */
    public fun clear() {
        keys = 0L
        axes = 0L
    }

    /** Selects the D-pad hat deflection if non-zero, otherwise falling back to the analog stick. */
    private fun pick(hat: Float, stick: Float): Float = if (hat != 0f) hat else stick

    /**
     * Constants and mappings from Android input codes to GBA KEYINPUT bit indices.
     */
    public companion object {
        /** GBA Button A bit index (bit 0). */
        public const val A: Int = 0

        /** GBA Button B bit index (bit 1). */
        public const val B: Int = 1

        /** GBA Select button bit index (bit 2). */
        public const val SELECT: Int = 2

        /** GBA Start button bit index (bit 3). */
        public const val START: Int = 3

        /** GBA D-pad Right direction bit index (bit 4). */
        public const val RIGHT: Int = 4

        /** GBA D-pad Left direction bit index (bit 5). */
        public const val LEFT: Int = 5

        /** GBA D-pad Up direction bit index (bit 6). */
        public const val UP: Int = 6

        /** GBA D-pad Down direction bit index (bit 7). */
        public const val DOWN: Int = 7

        /** GBA Shoulder button R bit index (bit 8). */
        public const val R: Int = 8

        /** GBA Shoulder button L bit index (bit 9). */
        public const val L: Int = 9

        /** Analog joystick dead zone threshold in the range [-1.0, 1.0]. */
        public const val DEAD_ZONE: Float = 0.5f

        /**
         * Resolves the corresponding GBA key bit index for an Android [keyCode], or `-1` if unmapped.
         *
         * Standard mappings:
         * - Gamepad: A/B face buttons, Select, Start, L1/L2, R1/R2.
         * - Keyboard: X = A, Z = B, Backspace = Select, Enter = Start, A = L, S = R, arrow keys = D-pad.
         *
         * @param keyCode Android [KeyEvent] keycode.
         * @return GBA bit index in 0..9, or -1 if unmapped.
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
