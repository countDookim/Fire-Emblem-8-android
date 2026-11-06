package fe8.game.savemenu

import fe8.game.GameControl
import fe8.game.bm.PlaySt
import fe8.game.save.BmSaveLib
import fe8.game.save.Fe8SramLayout as L

/**
 * The save menu's exit handler, decomp `src/savemenu.c`: what `PostSaveMenuHandler` does once the
 * player has chosen a main menu entry.
 *
 * The save menu itself is not ported, so [stand-in][standInSelection] picks the entry that
 * pressing A on the default item gives.
 */
public class PostSaveMenu(
    /** `gPlaySt`. */
    private val playSt: PlaySt,
    /** The save library [standInSelection] reads the slot states from. */
    private val lib: BmSaveLib,
    /** `SetNextGameActionId`. */
    private val setNextGameActionId: (Int) -> Unit,
    /** The calls of the handler that touch state other than `nextAction`. */
    public val effects: Effects = Effects(),
) {
    /** The fields of `struct SaveMenuProc` the handler reads. */
    public class Selection(
        /** `main_sel_bitfile`: the `MAIN_MENU_OPTION_*` bit chosen. */
        public var mainSelBitfile: Int = 0,
        /** `extra_sel_bitfile`: the extras entry chosen (when `mainSelBitfile` is extras). */
        public var extraSelBitfile: Int = 0,
        /** `sus_slot`: the game save slot chosen. */
        public var susSlot: Int = 0,
    )

    /** The side effects of `PostSaveMenuHandler`; the defaults do nothing. */
    public class Effects(
        /** `APProc_Delete(proc->approc)` and `Proc_End(proc->savedraw)`. */
        public var endMenuProcs: () -> Unit = {},
        /** `SetPrimaryHBlankHandler(0)`. */
        public var clearHBlankHandler: () -> Unit = {},
        /** `StartBgmVolumeChange(0xc0, 0x100, 0x10, 0)`. */
        public var startBgmVolumeChange: () -> Unit = {},
        /** `ReadSuspendSave(slot)`. */
        public var readSuspendSave: (Int) -> Unit = {},
        /** `ReadGameSave(slot)`. */
        public var readGameSave: (Int) -> Unit = {},
    )

    /**
     * Ends the menu's procs, then sets the game control `nextAction` from [sel]: extras (link
     * arena 6, or 0xC with the post game bit), exit (0xA with bit 0x80, else 5), resume (suspend
     * slot 3 loaded, 4), restart / new game slot (`susSlot + 1` after loading that game save) or
     * new game (event return, 0).
     *
     * Ported from src/savemenu.c: PostSaveMenuHandler
     */
    public fun postSaveMenuHandler(sel: Selection) {
        effects.endMenuProcs()
        effects.clearHBlankHandler()
        val main = sel.mainSelBitfile
        if (main == MAIN_MENU_OPTION_EXTRAS) {
            when (sel.extraSelBitfile) {
                1 -> setNextGameActionId(GameControl.GAME_ACTION_6)
                0x10 -> {
                    setNextGameActionId(GameControl.GAME_ACTION_C)
                    playSt.chapterStateBits =
                        playSt.chapterStateBits or GameControl.PLAY_FLAG_POSTGAME
                }
            }
        } else if (main and 0x40 != 0) {
            return
        } else if (main and MAIN_MENU_OPTION_EXIT != 0) {
            effects.startBgmVolumeChange()
            val exit = if (main and 0x80 != 0) GAME_ACTION_A else GameControl.GAME_ACTION_5
            setNextGameActionId(exit)
        } else if (main and MAIN_MENU_OPTION_RESUME != 0) {
            effects.readSuspendSave(L.SAVE_ID_SUSPEND)
            setNextGameActionId(GameControl.GAME_ACTION_4)
        } else if (main and (MAIN_MENU_OPTION_RESTART or 0x80) != 0) {
            effects.readGameSave(sel.susSlot)
            setNextGameActionId(sel.susSlot + 1)
        } else if (main and MAIN_MENU_OPTION_NEW_GAME != 0) {
            setNextGameActionId(GameControl.GAME_ACTION_EVENT_RETURN)
        }
    }

    /**
     * STAND-IN for the save menu: the selection after pressing A on the default item. A valid
     * suspend save ([BmSaveLib.isValidSuspendSave] of slot 3) gives resume; otherwise the last
     * game save slot ([BmSaveLib.readLastGameSaveId]) when it is valid gives restart; otherwise
     * new game. Replace with the real menu state when `savemenu.c` is ported.
     */
    public fun standInSelection(): Selection {
        if (lib.isValidSuspendSave(L.SAVE_ID_SUSPEND)) {
            return Selection(mainSelBitfile = MAIN_MENU_OPTION_RESUME)
        }
        val slot = lib.readLastGameSaveId()
        if (lib.isSaveValid(slot)) {
            return Selection(mainSelBitfile = MAIN_MENU_OPTION_RESTART, susSlot = slot)
        }
        return Selection(mainSelBitfile = MAIN_MENU_OPTION_NEW_GAME)
    }

    private companion object {
        const val MAIN_MENU_OPTION_RESUME = 1 shl 0
        const val MAIN_MENU_OPTION_RESTART = 1 shl 1
        const val MAIN_MENU_OPTION_NEW_GAME = 1 shl 4
        const val MAIN_MENU_OPTION_EXTRAS = 1 shl 5
        const val MAIN_MENU_OPTION_EXIT = 1 shl 8
        const val GAME_ACTION_A = 0xA
    }
}
