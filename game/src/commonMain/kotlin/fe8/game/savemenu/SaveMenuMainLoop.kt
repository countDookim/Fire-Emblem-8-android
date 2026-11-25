package fe8.game.savemenu

import fe8.game.save.BmSaveLib
import gbafe.input.KeyStatus
import gbafe.proc.Proc
import gbafe.proc.ProcEngine

/**
 * The main menu loop of the title save menu (`src/savemenu.c`, `PC-11c`): `SameMenu_CtrlLoop`,
 * `SaveMenuScrollSlot`, `SaveMenuSlotSelDrawSprite` and `SaveMenuModifySaveSlot`
 * (`src/savedraw.c`). [SaveMenuNewGame.Hooks.funcs] takes the first three by their C names;
 * [SaveMenuSlotFlip.Hooks.modifySaveSlot] takes the last.
 */
public class SaveMenuMainLoop(
    private val engine: ProcEngine,
    private val keys: KeyStatus,
    private val saveLib: BmSaveLib,
    /** Callbacks for the parts owned by other tasks. */
    public val hooks: Hooks = Hooks(),
) {
    /** Parts owned by other tasks. */
    public class Hooks(
        /** `PlaySoundEffect(songId)`. */
        public var playSoundEffect: (songId: Int) -> Unit = {},
        /** `StartBgmVolumeChange(volume, end, time, 0)`, run when New Game is chosen. */
        public var startBgmVolumeChange: (Int, Int, Int, Int) -> Unit = { _, _, _, _ -> },
        /**
         * `StartHelpPromptSprite(x, y, z, proc)` (`src/statscreen.c`); a stand-in (`PC-11d`: does
         * nothing until the prompt sprite is ported).
         */
        public var startHelpPromptSprite: (x: Int, y: Int, z: Int, parent: Proc) -> Unit =
            { _, _, _, _ -> },
    )

    /**
     * Walks the main list with UP / DOWN (`main_select` clamps, or wraps on a fresh press) and, on
     * A, stores the chosen option in `main_sel_bitfile` and starts the slot scroll (Resume,
     * Restart, Copy, Erase), the difficulty select (New Game) or the Extras list; B exits.
     *
     * Partially ported from src/savemenu.c: SameMenu_CtrlLoop (verified: UP and DOWN in the
     * middle of the list, A on Resume + goto `PL_SCROLL_SLOT`, and A on Restart +
     * `SaveMenuModifySaveSlot` + goto `PL_SCROLL_SLOT` in the title runs; not verified:
     * wrap-around presses, B, and A on Copy, Erase, New Game and Extras; sound effect ids are
     * not in the dumps)
     */
    public fun sameMenuCtrlLoop(proc: Proc) {
        val state = proc.state as SaveMenuState
        state.jumpLabel = SaveMenuNewGame.PL_MAIN_LOOP

        if (keys.repeatedKeys and DPAD_UP != 0) {
            if (state.mainSelect != 0) {
                state.mainSelect--
                hooks.playSoundEffect(SONG_SE_SYS_CURSOR_UD1)
            } else if (keys.newKeys and DPAD_UP != 0) {
                state.mainSelect = (state.unk31 - 1) and 0xFF
                hooks.playSoundEffect(SONG_SE_SYS_CURSOR_UD1)
            }
        } else if (keys.repeatedKeys and DPAD_DOWN != 0) {
            if (state.mainSelect < state.unk31 - 1) {
                state.mainSelect++
                hooks.playSoundEffect(SONG_SE_SYS_CURSOR_UD1)
            } else if (keys.newKeys and DPAD_DOWN != 0) {
                state.mainSelect = 0
                hooks.playSoundEffect(SONG_SE_SYS_CURSOR_UD1)
            }
        }

        if (keys.newKeys and A_BUTTON != 0) {
            state.mainSelBitfile =
                SaveMenuChoice.saveMenuGetBitfile(state.mainOptions, state.mainSelect)
            hooks.playSoundEffect(SONG_SE_SYS_WINDOW_SELECT1)
            state.scrollCnt = 0

            when (state.mainSelBitfile) {
                PostSaveMenu.MAIN_MENU_OPTION_RESUME -> {
                    state.susSlot = state.susSlotCur
                    engine.goto(proc, SaveMenuNewGame.PL_SCROLL_SLOT)
                }
                PostSaveMenu.MAIN_MENU_OPTION_RESTART,
                SaveMenuChoice.MAIN_MENU_OPTION_COPY,
                SaveMenuChoice.MAIN_MENU_OPTION_ERASE,
                -> {
                    state.susSlot = saveMenuModifySaveSlot(saveLib.readLastGameSaveId(), true, 1)
                    engine.goto(proc, SaveMenuNewGame.PL_SCROLL_SLOT)
                }
                PostSaveMenu.MAIN_MENU_OPTION_NEW_GAME -> {
                    state.susSlot = saveMenuModifySaveSlot(state.susSlot, false, 1)
                    engine.goto(proc, SaveMenuNewGame.PL_DIFFICULTY_SEL)
                    hooks.startBgmVolumeChange(BGM_VOLUME, BGM_END_VOLUME, BGM_DURATION, 0)
                }
                PostSaveMenu.MAIN_MENU_OPTION_EXTRAS -> {
                    if (state.extraSelect >= state.maxChoice) state.extraSelect = 0
                    engine.goto(proc, SaveMenuNewGame.PL_8)
                }
                else -> return
            }
        } else if (keys.newKeys and B_BUTTON != 0) {
            hooks.playSoundEffect(SONG_SE_SYS_WINDOW_CANSEL1)
            engine.goto(proc, SaveMenuNewGame.PL_NEW_GAME)
            state.mainSelBitfile = PostSaveMenu.MAIN_MENU_OPTION_EXIT
        }
    }

    /**
     * One frame of the slot scroll after A on the main list: sets the jump label, advances
     * `scroll_cnt` and moves the menu offset `unk_2f` left on a quadratic ease, ending the
     * repeat at frame 14.
     *
     * Ported from src/savemenu.c: SaveMenuScrollSlot
     */
    public fun saveMenuScrollSlot(proc: Proc) {
        val state = proc.state as SaveMenuState
        state.jumpLabel = SaveMenuNewGame.PL_SCROLL_SLOT
        state.scrollCnt = (state.scrollCnt + 1) and 0xFF

        val unk = SCROLL_FRAMES - state.scrollCnt
        state.unk2f = (-MENU_BASE_OFFSET - unk * SCROLL_DISTANCE * unk / SCROLL_DIVISOR) and 0xFF

        if (state.scrollCnt == SCROLL_FRAMES) engine.breakProc(proc)
    }

    /**
     * Starts the help prompt sprite on the slot select unless the New Game option is chosen.
     *
     * Partially ported from src/savemenu.c: SaveMenuSlotSelDrawSprite (verified: invokes the
     * prompt sprite hook for Resume and Restart in the title runs and skips it for New Game in
     * `ram_trace_newgame`; not verified: the sprite arguments, since the prompt sprite itself is
     * the [Hooks.startHelpPromptSprite] stand-in)
     */
    public fun saveMenuSlotSelDrawSprite(proc: Proc) {
        val state = proc.state as SaveMenuState
        if (state.mainSelBitfile and PostSaveMenu.MAIN_MENU_OPTION_NEW_GAME == 0) {
            hooks.startHelpPromptSprite(PROMPT_X, PROMPT_Y, PROMPT_Z, proc)
        }
    }

    /**
     * The first slot at or after [slot] (going up when [position] > 0, else down), wrapping over
     * the three slots, whose validity equals [valid]; `0xFF` when none.
     *
     * Partially ported from src/savedraw.c: SaveMenuModifySaveSlot (verified: first-iteration
     * valid-slot hit on slot 0 from `ReadLastGameSaveId` in `ram_trace_title_continue`; not
     * verified: slot wrap-around, downward search, invalid-slot search and `0xFF` result)
     */
    public fun saveMenuModifySaveSlot(slot: Int, valid: Boolean, position: Int): Int =
        modifySaveSlot(saveLib, slot, valid, position)

    /** Shared helpers and hardware / audio constants for the title main menu loop. */
    public companion object {
        /**
         * First save slot `0..2` at or after [slot] (ascending when [position] > 0, else
         * descending) whose [BmSaveLib.isSaveValid] matches [valid], or `0xFF` when none match.
         */
        public fun modifySaveSlot(
            saveLib: BmSaveLib,
            slot: Int,
            valid: Boolean,
            position: Int,
        ): Int {
            var current = slot
            val step = if (position > 0) 1 else -1
            for (i in 0 until SLOT_COUNT) {
                if (saveLib.isSaveValid(current) == valid) return current
                current = (current + step + SLOT_COUNT) % SLOT_COUNT
            }
            return 0xFF
        }

        private const val A_BUTTON = 1 shl 0
        private const val B_BUTTON = 1 shl 1
        private const val DPAD_UP = 1 shl 6
        private const val DPAD_DOWN = 1 shl 7
        private const val SONG_SE_SYS_CURSOR_UD1 = 0x66
        private const val SONG_SE_SYS_WINDOW_SELECT1 = 0x6A
        private const val SONG_SE_SYS_WINDOW_CANSEL1 = 0x6B
        private const val SLOT_COUNT = 3
        private const val BGM_VOLUME = 0xC0
        private const val BGM_END_VOLUME = 0x100
        private const val BGM_DURATION = 0x10
        private const val SCROLL_FRAMES = 0xE
        private const val SCROLL_DISTANCE = 0xDC
        private const val SCROLL_DIVISOR = 0xC4
        private const val MENU_BASE_OFFSET = 0x24
        private const val PROMPT_X = 0xC0
        private const val PROMPT_Y = 8
        private const val PROMPT_Z = 8
    }
}
