package fe8.game.savemenu

import fe8.game.GameControl
import fe8.game.bm.PlaySt
import fe8.game.save.BmSaveLib
import fe8.game.save.Fe8SramLayout

/**
 * Post-exit handler for the save / main menu, ported from `src/savemenu.c: PostSaveMenuHandler`.
 *
 * Once the player confirms a menu choice, [postSaveMenuHandler] terminates active menu helper
 * procs, clears HBlank callbacks, and sets [GameControl.nextAction] to transition game flow:
 * resuming a suspended game (`GAME_ACTION_4`), restarting the current chapter from a save slot
 * (`susSlot + 1`), launching New Game opening events (`GAME_ACTION_EVENT_RETURN`), entering
 * Extras menus (Link Arena `GAME_ACTION_6` or Creature Campaign postgame `GAME_ACTION_C`), or
 * returning to the title screen (`GAME_ACTION_5` or `GAME_ACTION_A`).
 *
 * The interactive save menu UI is not yet ported, so [standInSelection] automatically picks the
 * default action that pressing 'A' on the save menu would select in vanilla FE8.
 */
public class PostSaveMenu(
    /** The active battle map and game state (`gPlaySt`). */
    private val playSt: PlaySt,
    /** The save library that [standInSelection] queries for valid save slot states. */
    private val lib: BmSaveLib,
    /** Callback setting `gGameControl.nextAction` to drive state transitions. */
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
        /** Terminates active menu procs (`APProc_Delete(proc->approc)` and `Proc_End(proc->savedraw)`). */
        public var endMenuProcs: () -> Unit = {},
        /** Clears the primary HBlank interrupt handler (`SetPrimaryHBlankHandler(0)`). */
        public var clearHBlankHandler: () -> Unit = {},
        /** Restores BGM volume (`StartBgmVolumeChange(0xc0, 0x100, 0x10, 0)`). */
        public var startBgmVolumeChange: () -> Unit = {},
        /** Reads a suspend save file into memory (`ReadSuspendSave(slot)`). */
        public var readSuspendSave: (Int) -> Unit = {},
        /** Reads a chapter game save file into memory (`ReadGameSave(slot)`). */
        public var readGameSave: (Int) -> Unit = {},
    )

    /**
     * Ends active menu procs and HBlank handlers, then dispatches the next game action based on
     * [sel]: Extras (Link Arena or Creature Campaign postgame), Exit to title, Resume suspend
     * save, Restart chapter from slot, or New Game.
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
            // Invalid selection / trial map warning dialog active; stay in menu
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
     * Stand-in save menu selection logic simulating pressing 'A' on the default menu item.
     *
     * Follows vanilla precedence:
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
        /** `MAIN_MENU_OPTION_RESUME` (1 << 0): resumes a suspended chapter. */
        const val MAIN_MENU_OPTION_RESUME = 1 shl 0

        /** `MAIN_MENU_OPTION_RESTART` (1 << 1): restarts the chapter from a saved slot. */
        const val MAIN_MENU_OPTION_RESTART = 1 shl 1

        /** `MAIN_MENU_OPTION_NEW_GAME` (1 << 4): starts a new campaign from the prologue. */
        const val MAIN_MENU_OPTION_NEW_GAME = 1 shl 4

        /** `MAIN_MENU_OPTION_EXTRAS` (1 << 5): opens the Extras submenu. */
        const val MAIN_MENU_OPTION_EXTRAS = 1 shl 5

        /** `MAIN_MENU_OPTION_INVALID` (1 << 6): invalid option flag (e.g. trial map error). */
        const val MAIN_MENU_OPTION_INVALID = 1 shl 6

        /** `MAIN_MENU_OPTION_7` (1 << 7): alternative menu slot/exit flag in decomp. */
        const val MAIN_MENU_OPTION_7 = 1 shl 7

        /** `MAIN_MENU_OPTION_EXIT` (1 << 8): returns to the title screen. */
        const val MAIN_MENU_OPTION_EXIT = 1 shl 8

        /** `EXTRA_MENU_OPTION_LINK_ARENA` (1 << 0): Link Arena multiplayer battle. */
        const val EXTRA_MENU_OPTION_LINK_ARENA = 1

        /** `EXTRA_MENU_OPTION_MAP` (1 << 4): Creature Campaign extra map postgame mode. */
        const val EXTRA_MENU_OPTION_MAP = 0x10

        /** `GAME_ACTION_A`: secondary exit game action (e.g. soft reset to title). */
        const val GAME_ACTION_A = 0xA
    }
}
