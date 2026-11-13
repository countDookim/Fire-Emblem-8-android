/**
 * Keyboard and gamepad input for the game: Android key codes and stick/hat motion become the GBA
 * button bits the game polls.
 */

package fe8.app

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import gba.input.ActionSource

/**
 * [ActionSource] for physical keyboards and gamepads, polled by the game once per tick.
 *
 * Bit order is that of `REG_KEYINPUT` (A, B, Select, Start, Right, Left, Up, Down, R, L = bits
 * 0..9, `include/gba/io_reg.h:673`). A set bit means "held": the hardware register is active-low,
 * and the decomp inverts it on read (`src/hardware.c:342`), so this matches the game's view.
 * Events arrive on the UI thread via [onKey] and [onMotion]; the GL thread reads via [poll]
 * (hence the volatile fields). Keys and stick are kept in separate masks so one cannot release
 * the other. Allocates nothing.
 */
public class HardwareKeySource : ActionSource {
    /** Buttons held by key presses (keyboard or gamepad buttons). */
    @Volatile private var keys: Long = 0L

    /** Directions held by the hat or left stick; replaced whole on each motion event. */
    @Volatile private var axes: Long = 0L

    /** Returns the buttons currently held (keys or stick), as bits in the order above. */
    override fun poll(): Long = keys or axes

    /**
     * A key went down or up: sets or clears the matching GBA button bit. Other actions (e.g. a
     * multiple-key event) leave the state alone but are still consumed if the key is mapped.
     *
     * @param event The key event.
     * @return `true` if the key is mapped to a GBA button (consume it), `false` to let it pass.
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
     * A gamepad hat or stick moved: recomputes the held directions. Per axis the hat wins if it is
     * off centre, otherwise the stick counts only beyond [DEAD_ZONE]. The new direction set
     * replaces the old one, so returning to centre releases it. The left stick is
     * `AXIS_X`/`AXIS_Y` (Android behaviour, not checked here).
     *
     * @param event The motion event.
     * @return `true` for a joystick-source move event (consumed), `false` otherwise.
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
     * Releases every held key and direction. [GameSurfaceView] calls it when the window loses
     * focus, since a key-up delivered elsewhere would otherwise leave a button stuck. (It is not
     * called by the activity's own pause.)
     */
    public fun clear() {
        keys = 0L
        axes = 0L
    }

    /** One axis of input: the hat value if it is non-zero, else the stick value. */
    private fun pick(hat: Float, stick: Float): Float = if (hat != 0f) hat else stick

    /** GBA key bit indices (`REG_KEYINPUT` order) and the Android key code mapping. */
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

        /** A stick axis (range -1.0..1.0) counts as a direction only beyond +/- this value. */
        public const val DEAD_ZONE: Float = 0.5f

        /**
         * The GBA key bit for an Android [keyCode], or `-1` if the key is not used.
         *
         * - Gamepad: A, B, Select and Start buttons map to the same GBA buttons; L1 and L2 both
         *   give L; R1 and R2 both give R. The gamepad D-pad keys (`KEYCODE_DPAD_*`) give the
         *   directions, and D-pad centre gives A.
         * - Keyboard: X = A, Z = B, Backspace (`KEYCODE_DEL`) = Select, Enter = Start, A = L,
         *   S = R, arrow keys = directions.
         *
         * @param keyCode Android [KeyEvent] key code.
         * @return Bit index 0..9, or -1 if unmapped.
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
