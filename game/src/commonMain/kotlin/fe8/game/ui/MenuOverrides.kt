/**
 * Forced menu command overrides (`sMenuOverrides` and `GetForceDisabledMenuItems`) of
 * `src/uimenu.c`.
 *
 * Holds up to [MENU_OVERRIDE_MAX] (16) `struct MenuItemOverride` entries (`cmdid`, `kind`, `func`
 * ROM callback address) and extracts the list of command ids whose availability is overridden to
 * `MenuAlwaysNotShown` for suspend save serialization.
 */

package fe8.game.ui

/**
 * Menu item override table (`sMenuOverrides`, 16 `struct MenuItemOverride` entries) of
 * `src/uimenu.c`.
 */
public class MenuOverrides {
    private val cmdIds = IntArray(MENU_OVERRIDE_MAX)
    private val kinds = IntArray(MENU_OVERRIDE_MAX)
    private val funcs = IntArray(MENU_OVERRIDE_MAX)

    /** Sets override slot [index] (`0 until MENU_OVERRIDE_MAX`) to ([cmdId], [kind], [func]). */
    public fun setOverride(index: Int, cmdId: Int, kind: Int, func: Int) {
        require(index in 0 until MENU_OVERRIDE_MAX) { "Invalid menu override index: $index" }
        cmdIds[index] = cmdId
        kinds[index] = kind
        funcs[index] = func
    }

    /** Returns the `cmdid` stored at [index]. */
    public fun cmdId(index: Int): Int = cmdIds[index]

    /** Returns the `kind` (`MENU_OVERRIDE_*`) stored at [index]. */
    public fun kind(index: Int): Int = kinds[index]

    /** Returns the callback ROM address (`func`) stored at [index]. */
    public fun func(index: Int): Int = funcs[index]

    /**
     * Ported from src/uimenu.c: ResetMenuOverrides
     *
     * Marks every slot unused (`kind = MENU_OVERRIDE_NONE`). Like the original, it leaves the
     * stale `cmdid` and `func` words in place.
     */
    public fun resetMenuOverrides() {
        for (i in 0 until MENU_OVERRIDE_MAX) kinds[i] = MENU_OVERRIDE_NONE
    }

    /**
     * Partially ported from src/uimenu.c: AddMenuOverride
     *
     * Verified in `ram_trace_newgame_easy` (`EasyTutorialMenuOverrideDifferentialTest`): adding
     * 14 distinct (`cmdid`, `MENU_OVERRIDE_ISAVAILABLE`) entries to a reset table fills slots 0..13
     * in order. Unverified: replacing an existing entry with the same [cmdId] and [kind] (no
     * capture adds a duplicate). The original's search is unbounded; a full table drops the new
     * entry here (unreachable: the event command adds at most 15 entries to a reset table).
     *
     * Stores ([cmdId], [kind], [func]) in the first unused slot, or in the slot that already
     * holds the same [cmdId] and [kind].
     */
    public fun addMenuOverride(cmdId: Int, kind: Int, func: Int) {
        var i = 0
        while (i < MENU_OVERRIDE_MAX && kinds[i] != MENU_OVERRIDE_NONE &&
            !(kinds[i] == kind && cmdIds[i] == cmdId)
        ) {
            i++
        }
        if (i == MENU_OVERRIDE_MAX) return
        setOverride(i, cmdId, kind, func)
    }

    /**
     * Partially ported from src/uimenu.c: OverriddenMenuAvailability (lookup half)
     *
     * Verified in `ram_trace_newgame_easy` (`EasyTutorialMenuOverrideDifferentialTest`, `f19068`):
     * every unit menu item with an override resolves to `MenuAlwaysNotShown`; the item without one
     * (Wait) resolves to none. Returns the callback address of the first
     * `MENU_OVERRIDE_ISAVAILABLE` entry whose `cmdid` is [overrideId] (an item def's `overrideId`),
     * or 0 when none matches; the caller runs it. Scanning stops at the first unused slot.
     */
    public fun availabilityOverride(overrideId: Int): Int =
        findOverride(MENU_OVERRIDE_ISAVAILABLE, overrideId)

    /**
     * Transcribed from src/uimenu.c: OverriddenMenuSelected (lookup half; not verified: no
     * capture adds a `MENU_OVERRIDE_ONSELECT` entry)
     *
     * Returns the callback address of the first `MENU_OVERRIDE_ONSELECT` entry whose `cmdid` is
     * [overrideId], or 0 when none matches; the caller runs it, or its own select callback
     * (result `0xFF`) when there is none.
     */
    public fun selectOverride(overrideId: Int): Int =
        findOverride(MENU_OVERRIDE_ONSELECT, overrideId)

    private fun findOverride(kind: Int, overrideId: Int): Int {
        var i = 0
        while (i < MENU_OVERRIDE_MAX && kinds[i] != MENU_OVERRIDE_NONE) {
            if (kinds[i] == kind && cmdIds[i] == overrideId) return funcs[i]
            i++
        }
        return 0
    }

    /**
     * Transcribed from src/uimenu.c: GetForceDisabledMenuItems (not verified: `sMenuOverrides` is
     * all zeros in every reference capture, which proves the 16-byte `MENU_ITEM_NONE` write into
     * SRAM / `gGenericBuffer`, not the `MenuAlwaysNotShown` filter branch)
     *
     * Fills [list] (size >= [MENU_OVERRIDE_MAX]) with each override's `cmdid` when its `kind` is
     * non-zero and its callback is `MenuAlwaysNotShown` ([MENU_ALWAYS_NOT_SHOWN]), or
     * [MENU_ITEM_NONE] (0) otherwise.
     */
    public fun getForceDisabledMenuItems(list: ByteArray) {
        for (i in 0 until MENU_OVERRIDE_MAX) {
            list[i] = if (kinds[i] != MENU_OVERRIDE_NONE && funcs[i] == MENU_ALWAYS_NOT_SHOWN) {
                cmdIds[i].toByte()
            } else {
                MENU_ITEM_NONE.toByte()
            }
        }
    }

    /** Constants for `sMenuOverrides` (`include/uimenu.h`) and `MenuAlwaysNotShown`. */
    public companion object {
        /** `MENU_OVERRIDE_MAX`: capacity of `sMenuOverrides`. */
        public const val MENU_OVERRIDE_MAX: Int = 0x10

        /** `MENU_ITEM_NONE`: command id written to empty/unmatched force-disabled slots. */
        public const val MENU_ITEM_NONE: Int = 0

        /** `MENU_OVERRIDE_NONE`: unused override entry. */
        public const val MENU_OVERRIDE_NONE: Int = 0

        /** `MENU_OVERRIDE_ISAVAILABLE`: overrides the item's `isAvailable` callback. */
        public const val MENU_OVERRIDE_ISAVAILABLE: Int = 1

        /** `MENU_OVERRIDE_ONSELECT`: overrides the item's `onSelected` callback. */
        public const val MENU_OVERRIDE_ONSELECT: Int = 2

        /** Thumb ROM address of `MenuAlwaysNotShown` (`src/uimenu.c`, 0x0804F450 | 1). */
        public const val MENU_ALWAYS_NOT_SHOWN: Int = 0x0804F451
    }
}
