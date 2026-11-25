package fe8.game.savemenu

import fe8.game.save.BmSaveLib
import fe8.game.save.NewGameRam
import gbafe.proc.Proc
import gbafe.proc.ProcArgFunc
import gbafe.proc.ProcBoolFunc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.block
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.callArg
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.goto
import gbafe.proc.ProcScripts.label
import gbafe.proc.ProcScripts.name
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.sleep
import gbafe.proc.ProcScripts.whileTrue
import gbafe.proc.ProcScripts.yield
import gbafe.render.ArchivedPaletteFade
import gbafe.render.BgHardware

/**
 * The new-game bridge of the save menu (`src/savemenu.c`, `PC-9d`): `ProcScr_SaveMenu` and the
 * callbacks that run a new game's difficulty select, slot write and exit. The other callbacks of
 * the script (`SaveMenu_Init`, the main menu loop, extra screens, ...) belong to other tasks and
 * run through [Hooks.funcs] / [Hooks.conds] by their C name. [SaveMenuAssembly.wireNewGame] wires
 * the difficulty select, select loop and write; the title menu (`PC-11`) does not start it yet.
 */
public class SaveMenuNewGame(
    private val engine: ProcEngine,
    private val hardware: BgHardware,
    private val saveLib: BmSaveLib,
    private val ram: NewGameRam,
    private val fade: ArchivedPaletteFade,
    /** Callbacks for the parts owned by other tasks. */
    public val hooks: Hooks = Hooks(),
) {
    /** Parts of the save menu owned by other tasks or host wiring. */
    public class Hooks(
        /** `StartBgmVolumeChange(volume, 0, duration, 0)` called by [saveMenuNewGame]. */
        public var startBgmVolumeChange: (Int, Int, Int, Int) -> Unit = { _, _, _, _ -> },
        /**
         * `NewNewGameDifficultySelect(proc)` (`PC-9a`); wire
         * [DifficultyMenu.newNewGameDifficultySelect].
         */
        public var newNewGameDifficultySelect: ProcFunc = ProcFunc { },
        /**
         * Graphics reload of `SaveMenu_ReloadScreenFormDifficulty` (`ResetTextFont` through
         * `SaveMenuInitSlotPalette`, not verified here).
         */
        public var reloadScreenGraphics: ProcFunc = ProcFunc { },
        /** `PostSaveMenuHandler(proc)`; wire [SaveMenuPostChapter.hooks]' handler. */
        public var postSaveMenuHandler: ProcFunc = ProcFunc { },
        /** `SaveMenu_SaveSlotSelectLoop(proc)`; wire [SaveMenuSelectLoop]. */
        public var saveSlotSelectLoop: ProcFunc = ProcFunc { },
        /** Other `PROC_CALL` / `PROC_REPEAT` functions of the script, by C name; default no-op. */
        public val funcs: MutableMap<String, ProcFunc> = HashMap(),
        /** `PROC_WHILE` conditions of the script by C name; default false. */
        public val conds: MutableMap<String, ProcBoolFunc> = HashMap(),
    )

    private fun f(cName: String) = ProcFunc { p -> hooks.funcs[cName]?.invoke(p) }

    private fun c(cName: String) = ProcBoolFunc { p -> hooks.conds[cName]?.invoke(p) ?: false }

    private fun fadeIn(speed: Int) = callArg(
        ProcArgFunc { s, proc -> fade.newFadeIn(s, proc); true },
        speed,
    )

    private fun fadeOut(speed: Int) = callArg(
        ProcArgFunc { s, proc -> fade.newFadeOut(s, proc); true },
        speed,
    )

    /**
     * `ProcScr_SaveMenu` (`src/savemenu.c`). Command indices match the ROM script (labels and
     * `PROC_NAME` count as commands). Callbacks that other tasks own go through [Hooks].
     */
    public val script: ProcScript = ProcScript(
        "ProcScr_SaveMenu",
        listOf(
            name("savemenu"),
            label(PL_INIT),
            yield(),
            call(f("SaveMenu_Init")),
            yield(),
            call(f("SaveMenu_InitScreen")),
            call(f("SaveMenu_LoadExtraMenuGraphics")),
            yield(),
            fadeIn(8),
            whileTrue(ProcBoolFunc { fade.exists() }),
            yield(),
            whileTrue(c("MusicProc4Exists")),
            call(f("SaveMenu_JumpToTarget")),
            label(PL_MAIN_LOOP),
            repeat(f("SameMenu_CtrlLoop")),
            goto(PL_EXIT),
            label(PL_DIFFICULTY_SEL),
            call(ProcFunc { saveMenuResetLcdFormDifficulty(it) }),
            fadeOut(8),
            whileTrue(ProcBoolFunc { fade.fadeOutExists() }),
            call(ProcFunc { hardware.lcd.setDispEnable(0, 0, 0, 0, 0) }),
            call(ProcFunc { hooks.newNewGameDifficultySelect.invoke(it) }),
            yield(),
            call(ProcFunc { saveMenuReloadScreenFormDifficulty(it) }),
            call(ProcFunc { saveMenuResetLcdFormDifficulty(it) }),
            fadeIn(8),
            whileTrue(ProcBoolFunc { fade.exists() }),
            call(ProcFunc { saveMenuPostDifficultHandler(it) }),
            label(PL_SAVE_SLOT_SEL),
            call(f("SaveMenuSlotSelDrawSprite")),
            yield(),
            repeat(ProcFunc { hooks.saveSlotSelectLoop.invoke(it) }),
            goto(PL_EXIT),
            label(PL_EXEC_MISC_OPTION),
            sleep(5),
            call(f("_ExecSaveMenuMiscOption")),
            goto(PL_SAVE_SLOT_SEL),
            label(PL_SLOT_SELECTED),
            sleep(1),
            call(f("SaveMenuRegisterSlotSelected")),
            sleep(1),
            repeat(f("SaveMenuWaitSlotBoxScrolling")),
            goto(PL_SAVE_SLOT_SEL),
            label(PL_SCROLL_SLOT),
            repeat(f("SaveMenuScrollSlot")),
            goto(PL_SAVE_SLOT_SEL),
            label(PL_BACK_TO_MAIN),
            call(f("SaveMenu_EndHelpPromptSprite")),
            repeat(f("SaveMenuScrollBackToMain")),
            goto(PL_MAIN_LOOP),
            label(PL_8),
            repeat(f("sub_80A9A68")),
            label(PL_9),
            repeat(f("sub_80A9AB0")),
            label(PL_12),
            call(f("sub_80A9A08")),
            repeat(f("sub_80A9AF4")),
            label(PL_13),
            repeat(f("sub_80A9B44")),
            label(PL_10),
            repeat(f("sub_80A9B90")),
            label(PL_POST_BONUS_CLAIM),
            call(f("SaveMenuStartBonusClaim")),
            yield(),
            repeat(f("sub_80A9E1C")),
            label(PL_EXEC_EXTRA_MISC_OPTION),
            fadeOut(8),
            whileTrue(ProcBoolFunc { fade.fadeOutExists() }),
            whileTrue(c("IsMusicProc2Running")),
            call(f("SaveMenuStartExtraMiscScreen")),
            yield(),
            call(f("SaveMenu_Init")),
            yield(),
            call(f("SaveMenu_InitScreen")),
            call(f("SaveMenu_LoadExtraMenuGraphics")),
            yield(),
            fadeIn(8),
            whileTrue(ProcBoolFunc { fade.exists() }),
            whileTrue(c("IsMusicProc2Running")),
            call(f("SaveMenuPostExtraMiscScreen")),
            label(PL_BLOCKING),
            block(),
            label(PL_NEW_GAME),
            fadeOut(4),
            whileTrue(ProcBoolFunc { fade.fadeOutExists() }),
            goto(PL_EXIT),
            label(PL_EXIT_FADE),
            fadeOut(8),
            whileTrue(ProcBoolFunc { fade.fadeOutExists() }),
            label(PL_EXIT),
            yield(),
            call(ProcFunc { hooks.postSaveMenuHandler.invoke(it) }),
            yield(),
            end(),
        ),
        newState = { SaveMenuState() },
    )

    /**
     * Jumps the save menu to its new game exit (fade out, then `PostSaveMenuHandler`) and fades
     * the music out.
     *
     * Ported from src/savemenu.c: SaveMenu_NewGame (verified: the jump to the label at f1174; not
     * verified: the `StartBgmVolumeChange` arguments, music is not in the dumps)
     */
    public fun saveMenuNewGame(proc: Proc) {
        engine.goto(proc, PL_NEW_GAME)
        hooks.startBgmVolumeChange(BGM_VOLUME, 0, BGM_DURATION, 0)
    }

    /**
     * Writes a new game to the selected slot: difficulty 0 is the normal mode without tutorial,
     * 1 is normal with tutorial, 2 is hard with tutorial. A cancelled choice (3) leaves the
     * C locals uninitialised; the menu never writes then, so nothing is written here.
     *
     * Partially ported from src/savemenu.c: SaveMenuWriteNewGame (verified: difficulty 1 at
     * f1129..f1132, SRAM and `gPlaySt`; not verified: difficulties 0, 2 and 3, unexercised)
     */
    public fun saveMenuWriteNewGame(proc: Proc) {
        val state = proc.state as SaveMenuState
        val isTutorial: Int
        val isDifficult: Boolean
        when (state.difficulty) {
            0 -> { isTutorial = 0; isDifficult = false }
            1 -> { isTutorial = 1; isDifficult = false }
            2 -> { isTutorial = 1; isDifficult = true }
            else -> return
        }
        saveLib.writeNewGameSave(ram, state.susSlot, isDifficult, 1, isTutorial)
    }

    /**
     * Clears the scroll counter, turns the WIN0, WIN1 and object windows off and sets WIN0 to
     * show every layer and the outside to show none.
     *
     * Ported from src/savemenu.c: SaveMenu_ResetLcdFormDifficulty
     */
    public fun saveMenuResetLcdFormDifficulty(proc: Proc) {
        (proc.state as SaveMenuState).scrollCnt = 0
        hardware.lcd.setWinEnable(0, 0, 0)
        hardware.lcd.setWin0Layers(1, 1, 1, 1, 1)
        hardware.lcd.setWOutLayers(0, 0, 0, 0, 0)
    }

    /**
     * Rebuilds the save menu screen after the difficulty select: clears BG0 and BG1, reloads the
     * graphics, unblocks the save draw procs, and (unless the choice was cancelled) points the
     * menu at the slot select with its menu offset.
     *
     * Transcribed from src/savemenu.c: SaveMenu_ReloadScreenFormDifficulty (not verified: the
     * graphics reload is the [Hooks.reloadScreenGraphics] hook; only the surrounding logic is
     * tested)
     */
    public fun saveMenuReloadScreenFormDifficulty(proc: Proc) {
        val state = proc.state as SaveMenuState
        hardware.fill(hardware.mapBuffer(0), 0)
        hardware.fill(hardware.mapBuffer(1), 0)
        hooks.reloadScreenGraphics.invoke(proc)
        engine.unblockEachMarked(SaveDrawLifecycle.PROC_MARK_SAVEDRAW)
        engine.unblockEachMarked(PROC_MARK_D)
        hardware.enableBgSyncByMask(BG_SYNC_BG0_BG1)
        if (state.difficulty != DIFFICULTY_CANCEL) {
            state.jumpLabel = PL_SAVE_SLOT_SEL
            state.unk2f = MENU_X_OFFSET
        }
        hardware.enablePaletteSync()
    }

    /**
     * Goes to the main loop (label 2) when the difficulty select was cancelled, else to the
     * slot select (label 5).
     *
     * Ported from src/savemenu.c: SaveMenu_PostDifficultHandler
     */
    public fun saveMenuPostDifficultHandler(proc: Proc) {
        val state = proc.state as SaveMenuState
        val label = if (state.difficulty == DIFFICULTY_CANCEL) PL_MAIN_LOOP else PL_SAVE_SLOT_SEL
        engine.goto(proc, label)
    }

    /** `PL_SAVEMENU_*` labels (`include/savemenu.h`) and constants of the bridge. */
    public companion object {
        /** `PL_SAVEMENU_INIT`. */
        public const val PL_INIT: Int = 0

        /** `PL_SAVEMENU_DIFFICULTY_SEL`. */
        public const val PL_DIFFICULTY_SEL: Int = 1

        /** `PL_SAVEMENU_MAIN_LOOP`. */
        public const val PL_MAIN_LOOP: Int = 2

        /** `PL_SAVEMENU_SCROLL_SLOT`. */
        public const val PL_SCROLL_SLOT: Int = 3

        /** `PL_SAVEMENU_BACK_TO_MAIN`. */
        public const val PL_BACK_TO_MAIN: Int = 4

        /** `PL_SAVEMENU_SAVE_SLOT_SEL`. */
        public const val PL_SAVE_SLOT_SEL: Int = 5

        /** `PL_SAVEMENU_SLOT_SELECTED`. */
        public const val PL_SLOT_SELECTED: Int = 6

        /** `PL_SAVEMENU_EXEC_MISC_OPTION`. */
        public const val PL_EXEC_MISC_OPTION: Int = 7

        /** `PL_SAVEMENU_8`. */
        public const val PL_8: Int = 8

        /** `PL_SAVEMENU_9`. */
        public const val PL_9: Int = 9

        /** `PL_SAVEMENU_10`. */
        public const val PL_10: Int = 10

        /** `PL_SAVEMENU_POST_BONUS_CLAIM`. */
        public const val PL_POST_BONUS_CLAIM: Int = 11

        /** `PL_SAVEMENU_12`. */
        public const val PL_12: Int = 12

        /** `PL_SAVEMENU_13`. */
        public const val PL_13: Int = 13

        /** `PL_SAVEMENU_EXEC_EXTRA_MISC_OPTION`. */
        public const val PL_EXEC_EXTRA_MISC_OPTION: Int = 14

        /** `PL_SAVEMENU_EXIT`. */
        public const val PL_EXIT: Int = 15

        /** `PL_SAVEMENU_EXIT_FADE`. */
        public const val PL_EXIT_FADE: Int = 17

        /** `PL_SAVEMENU_NEW_GAME`. */
        public const val PL_NEW_GAME: Int = 18

        /** `PL_SAVEMENU_BLOCKING`. */
        public const val PL_BLOCKING: Int = 21

        /** `PROC_MARK_D` (`include/proc.h`). */
        private const val PROC_MARK_D = 13

        private const val DIFFICULTY_CANCEL = 3
        private const val MENU_X_OFFSET = 0xDC
        private const val BG_SYNC_BG0_BG1 = 3
        private const val BGM_VOLUME = 0xC0
        private const val BGM_DURATION = 0x10
    }
}
