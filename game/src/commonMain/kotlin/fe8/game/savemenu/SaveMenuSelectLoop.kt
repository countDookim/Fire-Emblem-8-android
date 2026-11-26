package fe8.game.savemenu

import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.render.BgHardware
import gbafe.text.Font
import gbafe.text.MsgStrings
import gbafe.text.Text
import gbafe.text.TextSystem

/**
 * Save slot selection and confirm loop of the save menu (`PC-4e1`, `src/savemenu.c` and
 * `src/savedraw.c`): reads the key status, moves the slot cursor, opens and closes the confirm
 * sub-box on BG1 and dispatches the chosen option. Everything the loop reaches that other tasks
 * own (slot cursor movement, help box, other write / copy / erase actions, `SaveMenu_NewGame`) is
 * called through [hooks].
 *
 * @param engine proc scheduler, for `Proc_Goto`
 * @param hardware display hardware (`gBG1TilemapBuffer`, BG sync)
 * @param text text renderer drawing into [subBoxText]
 * @param messages message table (`GetStringFromIndex`)
 * @param subBoxFont `gSaveMenuSubBoxFont` (see [SaveMenuScreen.subBoxFont])
 * @param subBoxText `gSaveMenuSubBoxText` (see [SaveMenuScreen.subBoxText])
 * @param bitfileToIndex `BitfileToIndex` (see [SaveDrawSlots.bitfileToIndex])
 */
public class SaveMenuSelectLoop(
    private val engine: ProcEngine,
    private val hardware: BgHardware,
    private val text: TextSystem,
    private val messages: MsgStrings,
    private val subBoxFont: Font,
    private val subBoxText: Text,
    private val bitfileToIndex: (Int) -> Int,
    /** Callbacks for the parts of the loop that later tasks own. */
    public val hooks: Hooks = Hooks(),
) {
    /** External callbacks of the loop. Defaults are no-ops until their owning tasks are ported. */
    public class Hooks(
        /** `gKeyStatusPtr->newKeys`. */
        public var newKeys: () -> Int = { 0 },
        /** `PlaySoundEffect(songId)`. */
        public var playSoundEffect: (songId: Int) -> Unit = {},
        /** `CloseHelpBox`. */
        public var closeHelpBox: () -> Unit = {},
        /**
         * `LoadHelpBoxGfx` + `StartHelpBoxExt_NoHelpSprite(48, 48, MSG_882)`, the "suspended data
         * will be lost" box.
         */
        public var startSuspendLostHelpBox: () -> Unit = {},
        /** `LoadHelpBoxGfx` + `StartItemHelpBox(0x50, y, -2)`, the slot info box at [y]. */
        public var startSlotInfoHelpBox: (y: Int) -> Unit = {},
        /** `LoadSaveMenuInfo(slot)`; 0 when the slot is not valid. Stand-in (not ported). */
        public var loadSaveMenuInfo: (slot: Int) -> Int = { 0 },
        /** `SaveMenuTryMoveSaveSlotCursor(proc, position)`; true if the cursor moved. */
        public var tryMoveSaveSlotCursor: (state: SaveMenuState, position: Int) -> Boolean =
            { _, _ -> false },
        /** `SaveMenu_NewGame(proc)`. Stand-in (not ported). */
        public var newGame: (Proc) -> Unit = {},
        /** `SaveMenuWriteNewGame(proc)`. Stand-in (not ported). */
        public var writeNewGame: (Proc) -> Unit = {},
        /** `CopyGameSave(from, to)`. */
        public var copyGameSave: (from: Int, to: Int) -> Unit = { _, _ -> },
        /** `InvalidateGameSave(slot)`. */
        public var invalidateGameSave: (slot: Int) -> Unit = {},
        /** `ReadGameSave(slot)`. */
        public var readGameSave: (slot: Int) -> Unit = {},
        /** `WriteGameSave(slot)`, wired to [fe8.game.save.BmGameSave.writeGameSave]. */
        public var writeGameSave: (slot: Int) -> Unit = {},
    )

    /**
     * Frame function of the slot select step (`gProcScr_SaveMenuPostChapter` repeat command):
     * ignores input while the help box animates, moves the slot cursor (cursor_config 0) or
     * the sub-box selection (otherwise), and on `A` / `B` runs [execSaveMenuMiscOption] or
     * cancels.
     *
     * Transcribed from src/savemenu.c: SaveMenu_SaveSlotSelectLoop (not verified:
     * `ram_trace_prologue_end` presses only `A` with `MAIN_MENU_OPTION_INVALID`, so the D-pad,
     * `B`, and the other main option branches are not exercised)
     */
    public fun saveMenuSaveSlotSelectLoop(proc: Proc) {
        val state = proc.state as SaveMenuState
        state.jumpLabel = SaveMenuPostChapter.PL_SAVEMENU_SAVE_SLOT_SEL

        if (saveMenuWaitHelpBoxAnim(state)) return

        val keys = hooks.newKeys()
        if (state.cursorConfig == 0) {
            if (keys and DPAD_UP != 0) {
                if (hooks.tryMoveSaveSlotCursor(state, -1)) {
                    hooks.playSoundEffect(SONG_SE_SYS_CURSOR_UD1)
                }
            } else if (keys and DPAD_DOWN != 0) {
                if (hooks.tryMoveSaveSlotCursor(state, 1)) {
                    hooks.playSoundEffect(SONG_SE_SYS_CURSOR_UD1)
                }
            }
        } else if (keys and DPAD_LEFT != 0) {
            if (state.cursorConfig != 1) {
                state.cursorConfig = 1
                hooks.playSoundEffect(SONG_SE_SYS_CURSOR_LR1)
                saveMenuHandleHelpBox(state)
            }
        } else if (keys and DPAD_RIGHT != 0) {
            if (state.cursorConfig != 2) {
                state.cursorConfig = 2
                hooks.playSoundEffect(SONG_SE_SYS_CURSOR_LR1)
                saveMenuHandleHelpBox(state)
            }
        }

        if (keys and A_BUTTON != 0) {
            state.scrollCnt = 0
            when (state.mainSelBitfile) {
                PostSaveMenu.MAIN_MENU_OPTION_RESTART -> {
                    if (state.susSlotCur != NO_SLOT) {
                        execSaveMenuMiscOption(proc)
                        return
                    }
                    hooks.playSoundEffect(SONG_SE_SYS_WINDOW_SELECT1)
                    hooks.newGame(proc)
                    return
                }
                PostSaveMenu.MAIN_MENU_OPTION_7 -> {
                    if (state.susSlotCur != NO_SLOT) state.unk44 = 0xF0
                    hooks.playSoundEffect(SONG_SE_SYS_WINDOW_SELECT1)
                    hooks.newGame(proc)
                    return
                }
                PostSaveMenu.MAIN_MENU_OPTION_RESUME -> {
                    hooks.playSoundEffect(SONG_SE_SYS_WINDOW_SELECT1)
                    hooks.newGame(proc)
                    return
                }
                PostSaveMenu.MAIN_MENU_OPTION_NEW_GAME -> {
                    if (state.susSlotCur != NO_SLOT) {
                        hooks.playSoundEffect(SONG_SE_SYS_WINDOW_SELECT1)
                        execSaveMenuMiscOption(proc)
                        return
                    }
                }
                MAIN_MENU_OPTION_COPY, MAIN_MENU_OPTION_ERASE,
                PostSaveMenu.MAIN_MENU_OPTION_INVALID -> {
                    execSaveMenuMiscOption(proc)
                    return
                }
                else -> return
            }

            hooks.writeNewGame(proc)
            engine.goto(proc, SaveMenuPostChapter.PL_SAVEMENU_SLOT_SELECTED)
            hooks.playSoundEffect(SONG_60)
        } else if (keys and B_BUTTON != 0) {
            state.scrollCnt = 0
            hooks.playSoundEffect(SONG_SE_SYS_WINDOW_CANSEL1)

            if (state.cursorConfig != 0) {
                saveMenuDrawSubSelBox(state, false)
                saveMenuHandleHelpBox(state)
                return
            }

            if (state.cursorSlot != NO_SLOT) {
                state.susSlot = state.cursorSlot
                state.cursorSlot = NO_SLOT
                return
            }

            if (state.mainSelBitfile and
                (PostSaveMenu.MAIN_MENU_OPTION_7 or PostSaveMenu.MAIN_MENU_OPTION_INVALID) != 0
            ) {
                engine.goto(proc, SaveMenuPostChapter.PL_SAVEMENU_EXIT_FADE)
                state.mainSelBitfile = state.mainSelBitfile or PostSaveMenu.MAIN_MENU_OPTION_EXIT
                return
            }

            engine.goto(proc, PL_SAVEMENU_BACK_TO_MAIN)
        }
    }

    /**
     * Confirms or opens the sub-box for the chosen main option. With the sub-box closed
     * (`cursor_config == 0`) it opens it (`cursor_config` 1 for `MAIN_MENU_OPTION_INVALID`, else
     * 2); with it open, `cursor_config == 1` runs the option (for
     * `MAIN_MENU_OPTION_INVALID`, `WriteGameSave` of `sus_slot`, then the jump to
     * `PL_SAVEMENU_SLOT_SELECTED`) and any other value cancels.
     *
     * Transcribed from src/savemenu.c: ExecSaveMenuMiscOption (not verified:
     * `ram_trace_prologue_end` runs only the `MAIN_MENU_OPTION_INVALID` branches, with
     * `cursor_config` 0 -> 1 -> write; copy, erase, restart, extras, new game and the cancel
     * branch are not exercised)
     */
    public fun execSaveMenuMiscOption(proc: Proc) {
        val state = proc.state as SaveMenuState
        if (state.cursorConfig == 0) {
            hooks.playSoundEffect(SONG_SE_SYS_WINDOW_SELECT1)

            when (state.mainSelBitfile) {
                MAIN_MENU_OPTION_COPY -> {
                    if (state.cursorSlot == NO_SLOT) {
                        state.cursorSlot = state.susSlot
                        hooks.tryMoveSaveSlotCursor(state, 1)
                        return
                    }
                    hooks.copyGameSave(state.cursorSlot, state.susSlot)
                    engine.goto(proc, SaveMenuPostChapter.PL_SAVEMENU_SLOT_SELECTED)
                    return
                }
                MAIN_MENU_OPTION_ERASE -> {
                    state.cursorConfig = 2
                    saveMenuDrawSubSelBox(state, true)
                }
                PostSaveMenu.MAIN_MENU_OPTION_INVALID -> {
                    state.cursorConfig = 1
                    saveMenuDrawSubSelBox(state, true)
                }
                PostSaveMenu.MAIN_MENU_OPTION_RESTART,
                PostSaveMenu.MAIN_MENU_OPTION_EXTRAS,
                PostSaveMenu.MAIN_MENU_OPTION_NEW_GAME -> {
                    state.cursorConfig = 2
                    saveMenuDrawSubSelBox(state, true)
                }
            }

            saveMenuHandleHelpBox(state)
            return
        }

        when (state.mainSelBitfile) {
            PostSaveMenu.MAIN_MENU_OPTION_EXTRAS -> {
                if (state.cursorConfig == 1) {
                    state.unk44 = 0xF0
                    hooks.readGameSave(state.susSlot)
                    hooks.playSoundEffect(SONG_SE_SYS_WINDOW_SELECT1)
                    if (state.extraSelBitfile != PostSaveMenu.EXTRA_MENU_OPTION_MAP) {
                        if (state.extraSelBitfile == EXTRA_MENU_OPTION_BONUS_CLAIM) {
                            engine.goto(proc, PL_SAVEMENU_EXEC_EXTRA_MISC_OPTION)
                        }
                    } else {
                        hooks.newGame(proc)
                    }
                } else {
                    hooks.playSoundEffect(SONG_SE_SYS_WINDOW_CANSEL1)
                }
            }
            PostSaveMenu.MAIN_MENU_OPTION_RESTART -> {
                if (state.cursorConfig == 1) {
                    state.unk44 = 0xF0
                    hooks.playSoundEffect(SONG_SE_SYS_WINDOW_SELECT1)
                    hooks.newGame(proc)
                } else {
                    hooks.playSoundEffect(SONG_SE_SYS_WINDOW_CANSEL1)
                }
            }
            PostSaveMenu.MAIN_MENU_OPTION_NEW_GAME -> {
                if (state.cursorConfig == 1) {
                    hooks.writeNewGame(proc)
                    engine.goto(proc, SaveMenuPostChapter.PL_SAVEMENU_SLOT_SELECTED)
                    hooks.playSoundEffect(SONG_60)
                } else {
                    hooks.playSoundEffect(SONG_SE_SYS_WINDOW_CANSEL1)
                }
            }
            MAIN_MENU_OPTION_ERASE -> {
                if (state.cursorConfig == 1) {
                    hooks.invalidateGameSave(state.susSlot)
                    engine.goto(proc, SaveMenuPostChapter.PL_SAVEMENU_SLOT_SELECTED)
                    hooks.playSoundEffect(SONG_SE_SYS_WINDOW_SELECT1)
                } else {
                    hooks.playSoundEffect(SONG_SE_SYS_WINDOW_CANSEL1)
                }
            }
            PostSaveMenu.MAIN_MENU_OPTION_INVALID -> {
                if (state.cursorConfig == 1) {
                    hooks.writeGameSave(state.susSlot)
                    engine.goto(proc, SaveMenuPostChapter.PL_SAVEMENU_SLOT_SELECTED)
                    hooks.playSoundEffect(SONG_60)
                } else {
                    engine.goto(proc, SaveMenuPostChapter.PL_SAVEMENU_EXIT_FADE)
                    state.mainSelBitfile =
                        state.mainSelBitfile or PostSaveMenu.MAIN_MENU_OPTION_EXIT
                    hooks.playSoundEffect(SONG_SE_SYS_WINDOW_CANSEL1)
                }
            }
        }

        saveMenuDrawSubSelBox(state, false)
        saveMenuHandleHelpBox(state)
    }

    /**
     * Returns true while the help box opens or closes, during which the buttons are ignored
     * (`ctrl_timer` counts down from 8). `B`, `R` or a D-pad press with the box open closes it;
     * `R` otherwise opens the slot info box.
     *
     * Transcribed from src/savemenu.c: SaveMenuWaitHelpBoxAnim (not verified:
     * `ram_trace_prologue_end` only runs it with `ctrl_timer == 0` and no `R` / `B` / D-pad press)
     */
    public fun saveMenuWaitHelpBoxAnim(state: SaveMenuState): Boolean {
        val keys = hooks.newKeys()
        if (state.ctrlTimer == CTRL_TIMER_MAX) {
            if (keys and (B_BUTTON or R_BUTTON or DPAD_ANY) != 0) {
                hooks.closeHelpBox()
                state.ctrlTimer = CTRL_TIMER_MAX - 1
            }
        } else if (keys and R_BUTTON != 0) {
            when (hooks.loadSaveMenuInfo(state.susSlot)) {
                0 -> hooks.playSoundEffect(SONG_6C)
                1, 2 -> {
                    hooks.startSlotInfoHelpBox(state.susSlot * 0x20 + 0x2C)
                    state.ctrlTimer = CTRL_TIMER_MAX
                }
            }
        }

        if (state.ctrlTimer == 0) return false
        if (state.ctrlTimer < CTRL_TIMER_MAX) state.ctrlTimer--
        return state.ctrlTimer != 0
    }

    /**
     * Opens the "suspended data will be lost" help box for the restart, new game and extras
     * options while the confirm sub-box is open, and closes the help box when there is no
     * suspend save or no sub-box.
     *
     * Transcribed from src/savemenu.c: SaveMenuHandleHelpBox (not verified:
     * `ram_trace_prologue_end` only runs the close branch, `sus_slot_cur == 0xFF`)
     */
    public fun saveMenuHandleHelpBox(state: SaveMenuState) {
        if (state.susSlotCur == NO_SLOT || state.cursorConfig == 0) {
            hooks.closeHelpBox()
            state.hbEn = false
            return
        }

        when (state.mainSelBitfile) {
            PostSaveMenu.MAIN_MENU_OPTION_RESTART,
            PostSaveMenu.MAIN_MENU_OPTION_NEW_GAME,
            PostSaveMenu.MAIN_MENU_OPTION_EXTRAS -> {
                if (!state.hbEn) {
                    hooks.startSuspendLostHelpBox()
                    state.hbEn = true
                }
            }
        }
    }

    /**
     * Draws the confirm sub-box on BG1 (row 17, column 7): the option label from
     * `SaveMenuSubSelBoxTexts` of the chosen main option and "Cancel" ([draw] true), or clears
     * it ([draw] false, which also resets `cursor_config`).
     *
     * Partially ported from src/savedraw.c: SaveMenuDrawSubSelBox. Verified (`PC-11h`, title
     * continue run): the Restart label draw (`f1093` to `f1154`) and the clear that resets
     * `cursor_config` (`f1175` to `f1176`), plus `MAIN_MENU_OPTION_INVALID` (`PC-cap1`).
     * Unverified: the labels of the other main options.
     */
    public fun saveMenuDrawSubSelBox(state: SaveMenuState, draw: Boolean) {
        val index = bitfileToIndex(state.mainSelBitfile and 0xFF)
        saveMenuDrawSubSelBoxExt(SUB_SEL_BOX_TEXTS.getOrElse(index) { 0 }, draw)
        if (!draw) state.cursorConfig = 0
    }

    /**
     * Draws message [msgId] and the "Cancel" label ([draw] true) into [subBoxText] and puts it at
     * BG1 tile (7, 17), or clears that 12x2 tile strip ([draw] false); then syncs BG1.
     *
     * Ported from src/savedraw.c: SaveMenuDrawSubSelBoxExt
     */
    public fun saveMenuDrawSubSelBoxExt(msgId: Int, draw: Boolean) {
        val tm = hardware.mapBuffer(1)
        if (draw) {
            val str = messages.getStringFromIndex(msgId)
            text.setTextFont(subBoxFont)
            text.clearText(subBoxText)
            text.setCursor(subBoxText, 0)
            text.setColor(subBoxText, 0)
            text.drawString(subBoxText, str)
            text.setCursor(subBoxText, 0x2C)
            text.drawString(subBoxText, messages.getStringFromIndex(MSG_CANCEL))
            text.putText(subBoxText, tm, SUB_BOX_ROW * TILEMAP_WIDTH + SUB_BOX_COLUMN)
        } else {
            // TileMap_FillRect(dest, 0xb, 1, 0) fills width + 1 columns by height + 1 rows.
            for (row in 0 until 2) {
                for (col in 0 until SUB_BOX_WIDTH) {
                    tm[(SUB_BOX_ROW + row) * TILEMAP_WIDTH + SUB_BOX_COLUMN + col] = 0
                }
            }
        }
        hardware.enableBgSyncByMask(BG1_SYNC_BIT)
    }

    private companion object {
        const val A_BUTTON = 1 shl 0
        const val B_BUTTON = 1 shl 1
        const val R_BUTTON = 1 shl 8
        const val DPAD_RIGHT = 1 shl 4
        const val DPAD_LEFT = 1 shl 5
        const val DPAD_UP = 1 shl 6
        const val DPAD_DOWN = 1 shl 7
        const val DPAD_ANY = 0xF0

        const val SONG_60 = 0x60
        const val SONG_SE_SYS_CURSOR_UD1 = 0x66
        const val SONG_SE_SYS_CURSOR_LR1 = 0x67
        const val SONG_SE_SYS_WINDOW_SELECT1 = 0x6A
        const val SONG_SE_SYS_WINDOW_CANSEL1 = 0x6B
        const val SONG_6C = 0x6C

        /** `MAIN_MENU_OPTION_COPY` (1 << 2) and `MAIN_MENU_OPTION_ERASE` (1 << 3). */
        const val MAIN_MENU_OPTION_COPY = 1 shl 2
        const val MAIN_MENU_OPTION_ERASE = 1 shl 3
        const val EXTRA_MENU_OPTION_BONUS_CLAIM = 1 shl 5

        const val PL_SAVEMENU_BACK_TO_MAIN = 4
        const val PL_SAVEMENU_EXEC_EXTRA_MISC_OPTION = 14
        const val CTRL_TIMER_MAX = 8
        const val NO_SLOT = 0xFF
        const val BG1_SYNC_BIT = 1 shl 1

        const val MSG_CANCEL = 0x142
        const val SUB_BOX_ROW = 17
        const val SUB_BOX_COLUMN = 7
        const val SUB_BOX_WIDTH = 0xC
        const val TILEMAP_WIDTH = 32

        /** `SaveMenuSubSelBoxTexts` message ids, indexed by `BitfileToIndex`. */
        val SUB_SEL_BOX_TEXTS = intArrayOf(0, 0x143, 0x144, 0x140, 0x143, 0x143, 0x145)
    }
}
