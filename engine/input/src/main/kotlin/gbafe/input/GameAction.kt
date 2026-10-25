package gbafe.input

/** The four movement directions, in GBA d-pad bit order (Right, Left, Up, Down). */
public enum class Direction(
    /** Horizontal step in map cells (+1 = right). */
    public val dx: Int,
    /** Vertical step in map cells (+1 = down, screen coordinates). */
    public val dy: Int,
) {
    /** D-pad right. */
    RIGHT(1, 0),

    /** D-pad left. */
    LEFT(-1, 0),

    /** D-pad up. */
    UP(0, -1),

    /** D-pad down. */
    DOWN(0, 1),
}

/**
 * Abstract game actions. Game logic only ever sees these, never raw buttons, so buttons, touch and
 * controllers are interchangeable [ActionSource]s and touch UIs can be added without touching logic.
 *
 * Each action has a dense [bit] used in the per-frame [InputFrame] bitmask (allocation-free reads).
 * Parameterised actions ([Move], [SelectItem]) are pre-allocated singletons, never created per frame.
 *
 * In vanilla mode each action maps 1:1 to the GBA button the original game reads (see
 * [VanillaButtonMap]), which is what keeps differential tests input-identical with mGBA.
 */
public sealed class GameAction(
    /** Dense bit index of this action in [InputFrame] masks; unique per action. */
    public val bit: Int,
) {
    /** A button: confirm / advance text. */
    public data object Confirm : GameAction(0)

    /** B button: cancel / back / speed up. */
    public data object Cancel : GameAction(1)

    /** Select button. */
    public data object Select : GameAction(2)

    /** Start button: map menu / skip. */
    public data object Menu : GameAction(3)

    /** D-pad direction. Use [of] to get the singleton. */
    public class Move private constructor(
        /** The direction moved. */
        public val direction: Direction,
    ) : GameAction(4 + direction.ordinal) {
        /** Debug form, e.g. `Move(UP)`. */
        override fun toString(): String = "Move($direction)"

        /** Holds the four pre-allocated instances. */
        public companion object {
            private val all = Direction.entries.map { Move(it) }

            /** Singleton for [d]. */
            public fun of(d: Direction): Move = all[d.ordinal]
        }
    }

    /** R button: info / status. */
    public data object Info : GameAction(8)

    /** L button: cycle to next unit. */
    public data object NextUnit : GameAction(9)

    /** Direct selection of list item [index]: an extension point for touch/number-key mods. The base game
     * never produces it (docs/decisions/0009-vanilla-product-mod-groundwork.md). */
    public class SelectItem private constructor(
        /** Zero-based list position. */
        public val index: Int,
    ) : GameAction(FIRST_SELECT_BIT + index) {
        /** Debug form, e.g. `SelectItem(3)`. */
        override fun toString(): String = "SelectItem($index)"

        /** Holds the [MAX] pre-allocated instances. */
        public companion object {
            /** Maximum directly selectable items. */
            public const val MAX: Int = 16
            private val all = (0 until MAX).map { SelectItem(it) }

            /** Singleton for [index] in `0 until MAX`. */
            public fun of(index: Int): SelectItem = all[index]
        }
    }

    /** Bit layout constants. */
    public companion object {
        internal const val FIRST_SELECT_BIT: Int = 16
    }
}

/**
 * Per-tick input snapshot as bitmasks over [GameAction.bit]. One instance is reused every tick.
 *
 * `pressed` = went down this tick, `held` = currently down, `released` = went up this tick, mirroring
 * how the original game distinguishes new presses from repeats.
 */
public class InputFrame {
    /** Bits of actions currently held. */
    public var held: Long = 0L
        private set

    /** Bits of actions that became held this tick. */
    public var pressed: Long = 0L
        private set

    /** Bits of actions released this tick. */
    public var released: Long = 0L
        private set

    /** Advances to a new tick with [nowHeld] as the held set. Allocation-free. */
    public fun update(nowHeld: Long) {
        pressed = nowHeld and held.inv()
        released = held and nowHeld.inv()
        held = nowHeld
    }

    /** True if [a] is held. */
    public fun isHeld(a: GameAction): Boolean = held and (1L shl a.bit) != 0L

    /** True if [a] was newly pressed this tick. */
    public fun isPressed(a: GameAction): Boolean = pressed and (1L shl a.bit) != 0L
}

/** Something that produces held actions each tick: buttons, touch overlay, gamepad, replay file. */
public fun interface ActionSource {
    /** Returns the bitmask of actions this source currently holds. Must not allocate. */
    public fun poll(): Long
}

/** The vanilla mapping between actions and GBA key bits (KEYINPUT order). */
public object VanillaButtonMap {
    /** GBA key bit for each action bit 0..9 (identity by construction of [GameAction.bit]). */
    public fun toGbaKeys(actionBits: Long): Int = (actionBits and 0x3FF).toInt()

    /** Inverse of [toGbaKeys]. */
    public fun fromGbaKeys(keys: Int): Long = (keys and 0x3FF).toLong()
}
