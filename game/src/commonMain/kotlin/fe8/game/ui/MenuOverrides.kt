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

    /** Clears all [MENU_OVERRIDE_MAX] override slots (`kind = MENU_OVERRIDE_NONE`). */
    public fun clear() {
        for (i in 0 until MENU_OVERRIDE_MAX) {
            cmdIds[i] = 0
            kinds[i] = MENU_OVERRIDE_NONE
            funcs[i] = 0
        }
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
