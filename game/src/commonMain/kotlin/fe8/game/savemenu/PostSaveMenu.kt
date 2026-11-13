package fe8.game.savemenu

import fe8.game.GameControl
import fe8.game.bm.PlaySt
import fe8.game.save.BmSaveLib
import fe8.game.save.Fe8SramLayout

/**
 * Post-exit handler for the save / main menu, ported from `src/savemenu.c: PostSaveMenuHandler`.
 *
 * Once the save menu proc reaches its exit label, [postSaveMenuHandler] ends the menu's helper
 * procs, clears the primary HBlank handler and picks the next `GameCtrlProc.nextAction` from the
 * menu result: resume a suspend save (`GAME_ACTION_4`), load a chapter save slot (action number
 * `susSlot + 1`, i.e. `GAME_ACTION_CLASS_REEL` .. `GAME_ACTION_PLAYED_THROUGH` for slots 0..2,
 * which `GameControl.switchPostSaveMenu` sends to the battle map), New Game
 * (`GAME_ACTION_EVENT_RETURN`; the game control proc then runs `GmDataInit` and goes to the
 * battle map), the Extras entries (Link Arena `GAME_ACTION_6`, the Map entry
 * `GAME_ACTION_C` plus `PLAY_FLAG_POSTGAME`), or back to the title screen (`GAME_ACTION_5`, or
 * `GAME_ACTION_A` when the `MAIN_MENU_OPTION_7` bit is set).
 *
 * The interactive save menu is not ported yet, so [standInSelection] picks a selection in its
 * place (see its KDoc).
 */
public class PostSaveMenu(
    /** The persistent play state (`gPlaySt`); the Extras Map entry sets its postgame flag. */
    private val playSt: PlaySt,
    /** The save library that [standInSelection] queries for valid save slot states. */
    private val lib: BmSaveLib,
    /** Stores the next action number in the game control proc (`SetNextGameActionId`). */
    private val setNextGameActionId: (Int) -> Unit,
    /** Side effect hooks for hardware and proc cleanup invoked by the handler. */
    public val effects: Effects = Effects(),
) {
    /** The selection fields of `struct SaveMenuProc` read by [postSaveMenuHandler]. */
    public class Selection(
        /** `main_sel_bitfile`: bitmask of the chosen main menu option (`MAIN_MENU_OPTION_*`). */
        public var mainSelBitfile: Int = 0,
        /** `extra_sel_bitfile`: bitmask of the chosen Extras submenu item (`EXTRA_MENU_OPTION_*`). */
        public var extraSelBitfile: Int = 0,
        /** `sus_slot`: selected game save slot index (0, 1, or 2). */
        public var susSlot: Int = 0,
    )

    /** Side effects performed by `PostSaveMenuHandler`; default implementations do nothing. */
    public class Effects(
        /**
         * Ends the menu's sprite proc (`APProc_Delete(proc->approc)`, only when it is non-null)
         * and its background proc (`Proc_End(proc->savedraw)`).
         */
        public var endMenuProcs: () -> Unit = {},
        /** Clears the primary HBlank interrupt handler (`SetPrimaryHBlankHandler(0)`). */
        public var clearHBlankHandler: () -> Unit = {},
        /**
         * Fades the BGM volume from 0xC0 up to 0x100 over a duration of 0x10
         * (`StartBgmVolumeChange(0xc0, 0x100, 0x10, 0)`, no parent proc); called only on the
         * leave-to-title path. The duration unit is not checked here.
         */
        public var startBgmVolumeChange: () -> Unit = {},
        /** Reads a suspend save file into memory (`ReadSuspendSave(slot)`). */
        public var readSuspendSave: (Int) -> Unit = {},
        /** Reads a chapter game save file into memory (`ReadGameSave(slot)`). */
        public var readGameSave: (Int) -> Unit = {},
    )

    /**
     * Ends the menu procs and the HBlank handler, then picks the next game action from
     * [sel]. Checked in this order, as in the decomp: Extras (exactly the Extras bit, then
     * Link Arena or the Map entry; any other Extras item sets no action), the Invalid bit
     * (returns with no action), Exit, Resume, Restart / option 7, New Game.
     *
     * Ported from src/savemenu.c: PostSaveMenuHandler
     */
    public fun postSaveMenuHandler(sel: Selection) {
        effects.endMenuProcs()
        effects.clearHBlankHandler()
        val mainOption = sel.mainSelBitfile
        if (mainOption == MAIN_MENU_OPTION_EXTRAS) {
            when (sel.extraSelBitfile) {
                EXTRA_MENU_OPTION_LINK_ARENA -> setNextGameActionId(GameControl.GAME_ACTION_6)
                EXTRA_MENU_OPTION_MAP -> {
                    setNextGameActionId(GameControl.GAME_ACTION_C)
                    playSt.chapterStateBits =
                        playSt.chapterStateBits or GameControl.PLAY_FLAG_POSTGAME
                }
            }
        } else if (mainOption and MAIN_MENU_OPTION_INVALID != 0) {
            // The bit is still the menu's initial value (the decomp's SaveMenuInit sets it), so
            // no choice was made: set no next action. Not a trial-map warning (checked in
            // src/savemenu.c: SaveMenuInit, PostSaveMenuHandler).
            return
        } else if (mainOption and MAIN_MENU_OPTION_EXIT != 0) {
            effects.startBgmVolumeChange()
            val exitAction = if (mainOption and MAIN_MENU_OPTION_7 != 0) {
                GAME_ACTION_A
            } else {
                GameControl.GAME_ACTION_5
            }
            setNextGameActionId(exitAction)
        } else if (mainOption and MAIN_MENU_OPTION_RESUME != 0) {
            effects.readSuspendSave(Fe8SramLayout.SAVE_ID_SUSPEND)
            setNextGameActionId(GameControl.GAME_ACTION_4)
        } else if (mainOption and (MAIN_MENU_OPTION_RESTART or MAIN_MENU_OPTION_7) != 0) {
            effects.readGameSave(sel.susSlot)
            setNextGameActionId(sel.susSlot + 1)
        } else if (mainOption and MAIN_MENU_OPTION_NEW_GAME != 0) {
            setNextGameActionId(GameControl.GAME_ACTION_EVENT_RETURN)
        }
    }

    /**
     * Stand-in until M6-E for the player's choice on the save menu: picks the first usable
     * entry in this order (not checked against the interactive menu code):
     * 1. If a valid suspend save exists in slot 3 ([Fe8SramLayout.SAVE_ID_SUSPEND]), selects Resume.
     * 2. Else if the last played save slot ([BmSaveLib.readLastGameSaveId]) is valid, selects Restart.
     * 3. Otherwise selects New Game.
     */
    public fun standInSelection(): Selection {
        if (lib.isValidSuspendSave(Fe8SramLayout.SAVE_ID_SUSPEND)) {
            return Selection(mainSelBitfile = MAIN_MENU_OPTION_RESUME)
        }
        val slot = lib.readLastGameSaveId()
        if (lib.isSaveValid(slot)) {
            return Selection(mainSelBitfile = MAIN_MENU_OPTION_RESTART, susSlot = slot)
        }
        return Selection(mainSelBitfile = MAIN_MENU_OPTION_NEW_GAME)
    }

    private companion object {
        /** `MAIN_MENU_OPTION_RESUME` (1 << 0): continue the suspend save. */
        const val MAIN_MENU_OPTION_RESUME = 1 shl 0

        /** `MAIN_MENU_OPTION_RESTART` (1 << 1): loads the selected chapter save slot. */
        const val MAIN_MENU_OPTION_RESTART = 1 shl 1

        /** `MAIN_MENU_OPTION_NEW_GAME` (1 << 4): starts a new game. */
        const val MAIN_MENU_OPTION_NEW_GAME = 1 shl 4

        /** `MAIN_MENU_OPTION_EXTRAS` (1 << 5): opens the Extras submenu. */
        const val MAIN_MENU_OPTION_EXTRAS = 1 shl 5

        /**
         * `MAIN_MENU_OPTION_INVALID` (1 << 6): the menu's initial `main_sel_bitfile` value
         * (src/savemenu.c: SaveMenuInit); while it is set, no choice has been made.
         */
        const val MAIN_MENU_OPTION_INVALID = 1 shl 6

        /**
         * `MAIN_MENU_OPTION_7` (1 << 7): meaning unknown. Only the unused `SaveMenuInitUnused`
         * starts with it; the exit path ORs `MAIN_MENU_OPTION_EXIT` onto it, which makes this
         * handler choose `GAME_ACTION_A` instead of `GAME_ACTION_5`.
         */
        const val MAIN_MENU_OPTION_7 = 1 shl 7

        /** `MAIN_MENU_OPTION_EXIT` (1 << 8): returns to the title screen. */
        const val MAIN_MENU_OPTION_EXIT = 1 shl 8

        /** `EXTRA_MENU_OPTION_LINK_ARENA` (1 << 0): Link Arena multiplayer battle. */
        const val EXTRA_MENU_OPTION_LINK_ARENA = 1

        /**
         * `EXTRA_MENU_OPTION_MAP` (1 << 4): the Extras Map entry; the menu requires a save slot
         * with bit 4 of `unk_3a` set (cleared-save check, not checked further here).
         */
        const val EXTRA_MENU_OPTION_MAP = 0x10

        /**
         * `GAME_ACTION_A`: set on exit when `MAIN_MENU_OPTION_7` is set. The decomp's
         * `GameControl_SwitchPostSaveMenu` has no case for it (it does nothing), so what it is
         * for is unknown.
         */
        const val GAME_ACTION_A = 0xA
    }
}
