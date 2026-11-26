package fe8.game.event

import fe8.game.event.EventConst.EVC_ADVANCE_CONTINUE
import fe8.game.ui.MenuOverrides

/**
 * The menu override event command of `src/eventscr.c`: 0x3D (`Event3D_MenuOverride`), which the
 * tutorial scripts use to hide menu entries (`EvtOverrideUnitMenu`).
 *
 * @param memory event script memory.
 * @param overrides the `sMenuOverrides` table the command writes; resolved on every execution
 *   because the menu that owns it can be created after the engine.
 */
public class EventMenuOverride(
    private val memory: ScriptMemory,
    private val overrides: () -> MenuOverrides?,
) {
    /** Registers 0x3D on [engine]. */
    public fun registerOn(engine: EventEngine) {
        engine.register(0x3D, event3dMenuOverride)
    }

    /**
     * Partially ported from src/eventscr.c: Event3D_MenuOverride
     *
     * Verified in `ram_trace_newgame_easy` (`EasyTutorialMenuOverrideDifferentialTest`):
     * sub-command `EVSUBCMD_DISABLEOPTIONS` with every `EVENT_MENUOVERRIDE_*` bit but Wait
     * (`0x7FFB`) rebuilds the table from a different one (entries for the other 14 unit menu
     * commands, in bit order, `MENU_OVERRIDE_ISAVAILABLE` with `MenuAlwaysNotShown`). Unverified:
     * sub-command
     * `EVSUBCMD_DISABLEWEAPONS` (weapon menu entries with `MenuAlwaysDisabled` and `Get8`, no
     * capture runs it; not ported, it only resets the table) and a mask with fewer bits.
     *
     * Resets the table, then adds one `MenuAlwaysNotShown` availability override for each bit set
     * in the first argument word, bit 0 being the first command of [UNIT_MENU_COMMANDS]. Always
     * continues.
     */
    public val event3dMenuOverride: EventHandler = EventHandler { proc ->
        val table = overrides()
        if (table != null) {
            val cur = proc.pEventCurrent
            val subcmd = memory.u16(cur) and 0xF
            val flags = memory.u16(cur + 2)
            table.resetMenuOverrides()
            if (subcmd == SUBCMD_DISABLE_OPTIONS) {
                for (i in UNIT_MENU_COMMANDS.indices) {
                    if (flags and (1 shl i) != 0) {
                        table.addMenuOverride(
                            UNIT_MENU_COMMANDS[i],
                            MenuOverrides.MENU_OVERRIDE_ISAVAILABLE,
                            MenuOverrides.MENU_ALWAYS_NOT_SHOWN,
                        )
                    }
                }
            }
        }
        EVC_ADVANCE_CONTINUE
    }

    /** Constants of `Event3D_MenuOverride`. */
    public companion object {
        /** `EVSUBCMD_DISABLEOPTIONS`: hide unit menu commands. */
        public const val SUBCMD_DISABLE_OPTIONS: Int = 0

        /**
         * `UnitMenuOverrideConf`: the unit menu item `overrideId` that `EVENT_MENUOVERRIDE_*`
         * bit `i` hides, from `EVENT_MENUOVERRIDE_ATTACK` (bit 0) to `EVENT_MENUOVERRIDE_END`.
         */
        public val UNIT_MENU_COMMANDS: IntArray = intArrayOf(
            0x4F, 0x51, 0x6B, 0x63, 0x64, 0x5C, 0x5A, 0x67, 0x37, 0x68, 0x69, 0x5B, 0x5F, 0x71,
            0x78,
        )
    }
}
