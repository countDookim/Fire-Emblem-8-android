package gba.input

/**
 * The four movement directions, in GBA d-pad bit order (Right, Left, Up, Down: key bits 4..7,
 * `DPAD_RIGHT` 0x10 .. `DPAD_DOWN` 0x80 in `include/gba/io_reg.h:667-670`).
 */
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
 * Abstract game actions. Game logic only ever sees these, never raw buttons, so buttons, touch
 * and controllers are interchangeable [ActionSource]s and touch UIs can be added without
 * touching logic.
 *
 * Each action has a dense [bit] used in the per-frame [InputFrame] bitmask (allocation-free reads).
 * Parameterised actions ([Move], [SelectItem]) are pre-allocated singletons, never created per
 * frame.
 *
 * In vanilla mode each action maps 1:1 to a GBA key bit (see [VanillaButtonMap]; bit numbers match
 * `A_BUTTON`..`L_BUTTON` in `include/gba/io_reg.h:663-672`), which is what keeps differential tests
 * input-identical with mGBA. The per-action descriptions below name what FE8 uses each button for;
 * another GBA game would bind the same bits to other meanings.
 */
public sealed class GameAction(
    /** Dense bit index of this action in [InputFrame] masks; unique per action. */
    public val bit: Int,
) {
    /** A button (bit 0). In FE8: confirm / advance text. */
    public data object Confirm : GameAction(0)

    /** B button (bit 1). In FE8: cancel / back / speed up. */
    public data object Cancel : GameAction(1)

    /** Select button (bit 2). */
    public data object Select : GameAction(2)

    /** Start button (bit 3). In FE8: map menu / skip. */
    public data object Menu : GameAction(3)

    /** D-pad direction (bits 4..7, one per [Direction]). Use [of] to get the singleton. */
    public class Move private constructor(
        /** The direction moved. */
        public val direction: Direction,
    ) : GameAction(4 + direction.ordinal) {
        /** Debug form, e.g. `Move(UP)`. */
        override fun toString(): String = "Move($direction)"

        /** Holds the four pre-allocated instances. */
        public companion object {
            /** One instance per [Direction], indexed by ordinal. */
            private val all = Direction.entries.map { Move(it) }

            /** Singleton for [d]. */
            public fun of(d: Direction): Move = all[d.ordinal]
        }
    }

    /** R button (bit 8). In FE8: info / status. */
    public data object Info : GameAction(8)

    /** L button (bit 9). In FE8: cycle to next unit. */
    public data object NextUnit : GameAction(9)

    /**
     * Direct selection of list item [index]: an extension point for touch/number-key mods. The
     * base game never produces it (docs/decisions/0009-vanilla-product-mod-groundwork.md).
     */
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

            /** The pre-allocated instances, indexed by [SelectItem.index]. */
            private val all = (0 until MAX).map { SelectItem(it) }

            /** Singleton for [index] in `0 until MAX`. */
            public fun of(index: Int): SelectItem = all[index]
        }
    }

    /** Bit layout constants (bits 10..15 are unused). */
    public companion object {
        /** First action bit used by [SelectItem]; bits 0..9 belong to the GBA keys. */
        internal const val FIRST_SELECT_BIT: Int = 16
    }
}

/**
 * Per-tick input snapshot as bitmasks over [GameAction.bit]. One instance is reused every tick.
 *
 * `pressed` = went down this tick, `held` = currently down, `released` = went up this tick.
 * [held] and [pressed] correspond to `heldKeys` and `newKeys` of the decomp's `KeyStatusBuffer`
 * (`include/hardware.h:130`, computed in `_UpdateKeyStatus`, `src/hardware.c:300`). The decomp's
 * auto-repeat (`repeatedKeys`, delay 12 / interval 4 frames from `ResetKeyStatus`) and its
 * ignore mask are not modelled in this class.
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

    /**
     * Advances to a new tick with [nowHeld] as the held set; derives [pressed] and [released] by
     * comparing it with the previous tick's [held]. Allocation-free.
     */
    public fun update(nowHeld: Long) {
        // Edge detection: newly set bits are presses, newly cleared bits are releases.
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

/**
 * The vanilla mapping between actions and GBA key bits. Both sides use the order of `REG_KEYINPUT`
 * bits 0..9 (A, B, Select, Start, Right, Left, Up, Down, R, L; `KEYS_MASK` 0x3FF in
 * `include/gba/io_reg.h:673`). Here a set bit means "held"; the hardware register is active-low,
 * and the decomp inverts it (`~REG_KEYINPUT`, `src/hardware.c:342`).
 */
public object VanillaButtonMap {
    /** GBA key bits for [actionBits]; only bits 0..9 are kept (identity by construction). */
    public fun toGbaKeys(actionBits: Long): Int = (actionBits and 0x3FF).toInt()

    /** Inverse of [toGbaKeys]: GBA key bits (low 10 used) to the action bitmask. */
    public fun fromGbaKeys(keys: Int): Long = (keys and 0x3FF).toLong()
}
