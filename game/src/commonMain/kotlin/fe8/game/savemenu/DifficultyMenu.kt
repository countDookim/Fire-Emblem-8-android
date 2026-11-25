package fe8.game.savemenu

import fe8.game.RomView
import fe8.game.bm.PlaySt
import fe8.game.ui.UiFrame
import gbafe.input.KeyStatus
import gbafe.proc.Proc
import gbafe.proc.ProcArgFunc
import gbafe.proc.ProcBoolFunc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.callArg
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.label
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.setEndCb
import gbafe.proc.ProcScripts.sleep
import gbafe.proc.ProcScripts.whileTrue
import gbafe.proc.ProcScripts.yield
import gbafe.render.ArchivedPaletteFade
import gbafe.render.BgHardware
import gbafe.render.BgTilemap
import gbafe.text.MsgStrings
import gbafe.text.Text
import gbafe.text.TextSystem

/**
 * The fields of `struct DifficultyMenuSpritesProc` (`include/savemenu.h`) that the difficulty
 * screen's lifecycle and key handler write. The sprite proc itself belongs to `PC-9c`; its state
 * object must be (or extend) this class so [DifficultyMenu.initDifficultySelectScreen] and
 * [DifficultyMenu.startDifficultyCursorMove] can update it.
 */
public class DifficultyMenuSpritesState {
    /**
     * `flags_0` (+0x29 bit 0): set by [DifficultyMenu.startDifficultyCursorMove] to start cursor
     * movement (`PC-9c`).
     */
    public var flags0: Boolean = false

    /** `flags_1` (+0x29 bit 1): set while the cursor moves (read by the key handler, `PC-9b`). */
    public var flags1: Boolean = false

    /** `current_selection` (+0x2B). */
    public var currentSelection: Int = 0

    /** `unk_2c` (+0x2C, `s16`): cursor X target, from `gUnknown_08A209FC[selection].a`. */
    public var unk2c: Int = 0

    /** `unk_2e` (+0x2E, `s16`): cursor Y target, from `gUnknown_08A209FC[selection].b`. */
    public var unk2e: Int = 0

    /** `unk_30` (+0x30, `u16`): move start X, set by [DifficultyMenu.startDifficultyCursorMove]. */
    public var unk30: Int = 0

    /** `unk_32` (+0x32, `u16`): move start Y, set by [DifficultyMenu.startDifficultyCursorMove]. */
    public var unk32: Int = 0

    /** `unk_34` (+0x34, `u16`): move target X set by [DifficultyMenu.startDifficultyCursorMove]. */
    public var unk34: Int = 0

    /** `unk_36` (+0x36, `u16`): move target Y set by [DifficultyMenu.startDifficultyCursorMove]. */
    public var unk36: Int = 0
}

/** The proc fields of `struct DifficultyMenuProc` (`include/savemenu.h`) past `PROC_HEADER`. */
public class DifficultyMenuState {
    /** `unk_2c` (+0x2C): frame counter of the (unused here) window animations. */
    public var unk2c: Int = 0

    /** `current_selection` (+0x30): 0 = first difficulty, 2 = last. */
    public var currentSelection: Int = 0

    /** `sprites_proc` (+0x34): the sprite child started by `StartDrawDifficultyMenuSprites`. */
    public var spritesProc: Proc? = null

    /** `unk_38[5]` (+0x38): the five text strips of the description. */
    public val texts: Array<Text> = Array(TEXT_LINES) { Text() }

    /** Layout constants of the description text. */
    public companion object {
        /** Number of description text strips. */
        public const val TEXT_LINES: Int = 5
    }
}

/**
 * The new-game difficulty select screen (`src/difficultymenu.c`, PC-9a/PC-9b): the blocking proc
 * `ProcScr_NewGameDifficultySelect` with its screen setup, description drawing, key handler, and
 * end callback. The sprite child (`PC-9c`), sound effects, and `SaveMenu_SetDifficultyChoice`
 * run through [hooks]. Not wired into the live New Game flow (`PC-9` WIRE).
 */
public class DifficultyMenu(
    /** The proc scheduler running the screen. */
    private val engine: ProcEngine,
    /** Display hardware (tilemaps, palette, VRAM, LCD). */
    private val hardware: BgHardware,
    /** Whole-ROM view supplying graphics, palettes and the small data tables. */
    private val rom: RomView,
    /** Persistent play state (`gPlaySt`, for the window frame colour). */
    private val playSt: PlaySt,
    /** Text renderer. */
    private val text: TextSystem,
    /** Message strings (`GetStringFromIndex`). */
    private val messages: MsgStrings,
    /** Archived palette fade (`NewFadeIn`, `NewFadeOut`, `FadeInExists`, `FadeOutExists`). */
    private val fade: ArchivedPaletteFade,
    /** Callbacks for the parts owned by other tasks. */
    public val hooks: Hooks = Hooks(),
    /** Key status (`gKeyStatusPtr`) read by [difficultySelectLoopKeyHandler]. */
    private val keys: KeyStatus = KeyStatus(),
) {
    /** Parts of the screen owned by other tasks or host wiring. */
    public class Hooks(
        /**
         * `DifficultySelect_Loop_KeyHandler` (`PC-9b`), the `PROC_REPEAT` of label 0. When null,
         * [script] runs [difficultySelectLoopKeyHandler]; callers or tests may override it.
         */
        public var keyHandler: ProcFunc? = null,
        /**
         * `StartDrawDifficultyMenuSprites(parent)` (`PC-9c`): starts the sprite child and returns
         * it; its [Proc.state] must be a [DifficultyMenuSpritesState]. Null: no sprites.
         */
        public var startSprites: (Proc) -> Proc? = { null },
        /** `PlaySoundEffect(songId)` called by [difficultySelectLoopKeyHandler]. */
        public var playSoundEffect: (songId: Int) -> Unit = {},
        /** `SaveMenu_SetDifficultyChoice(difficulty, b)` called on confirm or cancel. */
        public var setDifficultyChoice: (difficulty: Int, b: Int) -> Unit = { _, _ -> },
    )

    /**
     * `ProcScr_NewGameDifficultySelect` (`src/difficultymenu.c`). Command indices match the ROM
     * script (labels count as commands; the script has no `PROC_NAME`).
     */
    public val script: ProcScript = ProcScript(
        "ProcScr_NewGameDifficultySelect",
        listOf(
            setEndCb(ProcFunc { difficultySelectOnEnd(it) }),
            call(ProcFunc { disableAllGfx() }),
            yield(),
            call(ProcFunc { difficultySelectInit(it) }),
            sleep(1),
            call(ProcFunc { enableAllGfx() }),
            callArg(ProcArgFunc { speed, proc -> fade.newFadeIn(speed, proc); true }, 8),
            whileTrue(ProcBoolFunc { fade.exists() }),
            label(LABEL_LOOP),
            repeat(ProcFunc { proc ->
                val handler = hooks.keyHandler
                if (handler != null) handler.invoke(proc) else difficultySelectLoopKeyHandler(proc)
            }),
            label(LABEL_CONFIRM),
            sleep(10),
            label(LABEL_CANCEL),
            callArg(ProcArgFunc { speed, proc -> fade.newFadeOut(speed, proc); true }, 8),
            whileTrue(ProcBoolFunc { fade.fadeOutExists() }),
            call(ProcFunc { }),
            end(),
        ),
        newState = { DifficultyMenuState() },
    )

    /**
     * Starts [script] as a blocking child of [parent], the save menu proc.
     *
     * Transcribed from src/difficultymenu.c: NewNewGameDifficultySelect (not verified: the
     * capture shows the child's parent and the save menu's cursor holding at 23 while it runs,
     * but the test's parent is a stand-in, so the blocking is not compared)
     */
    public fun newNewGameDifficultySelect(parent: Proc): Proc? =
        engine.startBlocking(script, parent)

    /**
     * Ends the screen: tells the save menu's draw proc to resume (`savedraw->unk_29 = 1`).
     *
     * Ported from src/difficultymenu.c: DifficultySelect_OnEnd
     */
    public fun difficultySelectOnEnd(proc: Proc) {
        saveDrawOf(proc).unk29 = 1
    }

    /**
     * Resets the proc counters and builds the screen.
     *
     * Ported from src/difficultymenu.c: DifficultySelect_Init
     */
    public fun difficultySelectInit(proc: Proc) {
        val state = proc.state as DifficultyMenuState
        state.currentSelection = 0
        state.unk2c = 0
        initDifficultySelectScreen(proc)
    }

    /**
     * Loads the window frame, text strips, palettes, OBJ tiles and BG1 panel, clears the BG0/BG1
     * tilemaps, draws the description of selection 0, starts the sprite child and seeds its
     * cursor position.
     *
     * Partially ported from src/difficultymenu.c: InitDifficultySelectScreen (verified at step
     * 985 and f1020 of `ram_trace_newgame`: BG tilemaps, VRAM, palettes, `unk_29`, `unk_2c` and
     * the seeded sprite fields; not verified: [Hooks.startSprites] itself is `PC-9c`, and the
     * `windowColor` palette choice, as the capture has one colour; the `Pal_SaveScreenSprits` load,
     * which `Pal_DifficultyMenuObjs` overwrites in full)
     */
    public fun initDifficultySelectScreen(proc: Proc) {
        val state = proc.state as DifficultyMenuState

        text.resetTextFont()
        UiFrame.loadUiFrameGraphics(hardware, rom, playSt.windowColor)
        text.resetText()

        for (i in 0 until DifficultyMenuState.TEXT_LINES) {
            text.initText(state.texts[i], TEXT_TILE_WIDTH)
        }

        applyPalettes(SaveMenuScreen.PAL_SAVE_MENU_BG, 8, 8)
        applyPalettes(SaveMenuScreen.PAL_MAIN_MENU_BG_FOG, SaveMenuScreen.BGPAL_SAVEMENU_BGFOG, 1)
        applyPalettes(SaveMenuScreen.PAL_SAVE_SCREEN_SPRITES, 18, 8)
        applyPalettes(SaveMenuScreen.PAL_08A295B4, 2, 1)

        rom.decompressInto(IMG_DIFFICULTY_MENU_OBJS, hardware.vram, OBJ_TILES_OFFSET)
        applyPalettes(PAL_DIFFICULTY_MENU_OBJS, 17, 10)

        hardware.enablePaletteSync()

        saveDrawOf(proc).unk29 = 0

        hardware.fill(hardware.mapBuffer(0), 0)
        hardware.fill(hardware.mapBuffer(1), 0)

        val generic = ByteArray(GENERIC_PANEL_BYTES)
        rom.decompressInto(TSA_DIFFICULTY_PANEL, generic, 0)
        BgTilemap.applyTsa(hardware.mapBuffer(1), BG1_PANEL_OFFSET, generic, 0, BG1_PANEL_TILE_BASE)

        drawDifficultyModeText(state)
        putDifficultyModeText(state)

        hardware.enableBgSyncByMask(BG0_SYNC_BIT or BG1_SYNC_BIT)

        state.spritesProc = hooks.startSprites(proc)
        state.currentSelection = 0
        (state.spritesProc?.state as? DifficultyMenuSpritesState)?.let { sprites ->
            sprites.currentSelection = 0
            sprites.unk2c = cursorTarget(0, 0)
            sprites.unk2e = cursorTarget(0, 1)
        }
    }

    /**
     * Handles D-pad Up/Down selection changes (`repeatedKeys`), waits while the cursor animates
     * (`flags1`), and confirms (`A`/`Start` -> `LABEL_CONFIRM`) or cancels (`B` -> `LABEL_CANCEL`)
     * on `newKeys`.
     *
     * Transcribed from src/difficultymenu.c: DifficultySelect_Loop_KeyHandler (not verified:
     * `DPAD_UP`, `2 -> 0` wrap, `sprites.flags1 == true` block, `START_BUTTON`, selections `0`
     * and `2` confirm, `unk2c = 0` over already-zero `unk2c`, and `B_BUTTON` cancel are
     * unexercised in `ram_trace_newgame`; `DPAD_DOWN` `0 -> 1` at `f1030` and selection `1`
     * `A_BUTTON` confirm at `f1060` are verified)
     */
    public fun difficultySelectLoopKeyHandler(proc: Proc) {
        val state = proc.state as DifficultyMenuState
        var hasChanged = false

        if (keys.repeatedKeys and DPAD_UP != 0) {
            state.currentSelection =
                if (state.currentSelection == 0) MAX_SELECTION else state.currentSelection - 1
            hasChanged = true
        } else if (keys.repeatedKeys and DPAD_DOWN != 0) {
            state.currentSelection =
                if (state.currentSelection == MAX_SELECTION) 0 else state.currentSelection + 1
            hasChanged = true
        }

        val sprites = state.spritesProc?.state as? DifficultyMenuSpritesState
        if (hasChanged) {
            hooks.playSoundEffect(SONG_SE_SYS_CURSOR_UD1)
            if (sprites != null) {
                sprites.currentSelection = state.currentSelection
                startDifficultyCursorMove(
                    sprites,
                    cursorTarget(state.currentSelection, 0),
                    cursorTarget(state.currentSelection, 1),
                )
            }
            drawDifficultyModeText(state)
            putDifficultyModeText(state)
            return
        }

        if (sprites?.flags1 == true) return

        if (keys.newKeys and (A_BUTTON or KeyStatus.START_BUTTON) != 0) {
            state.unk2c = 0
            hooks.playSoundEffect(SONG_SE_SYS_WINDOW_SELECT1)
            when (state.currentSelection) {
                0, 1, MAX_SELECTION -> hooks.setDifficultyChoice(state.currentSelection, 0)
            }
            engine.goto(proc, LABEL_CONFIRM)
        }

        if (keys.newKeys and B_BUTTON != 0) {
            state.unk2c = 0
            hooks.playSoundEffect(SONG_SE_SYS_WINDOW_CANSEL1)
            hooks.setDifficultyChoice(DIFFICULTY_CANCEL, 0)
            engine.goto(proc, LABEL_CANCEL)
        }
    }

    /**
     * Begins a cursor move on [sprites] from its current `(unk2c, unk2e)` to `(x, y)`.
     *
     * Ported from src/difficultymenu.c: sub_80AC680
     */
    public fun startDifficultyCursorMove(sprites: DifficultyMenuSpritesState, x: Int, y: Int) {
        sprites.unk30 = sprites.unk2c and U16_MASK
        sprites.unk32 = sprites.unk2e and U16_MASK
        sprites.unk34 = x and U16_MASK
        sprites.unk36 = y and U16_MASK
        sprites.flags0 = true
    }

    /**
     * Draws the description text of the current selection into the five strips (one per `[NL]`).
     *
     * Partially ported from src/difficultymenu.c: DrawDifficultyModeText (owned by `PC-9b`;
     * verified: selection 0 full text VRAM `0x1000..0x2180` at `f1020`, and selection 1 text VRAM
     * `0x1280..0x2180` preserved at `f1100` after clearing and redrawing on `Down` at `f1030`;
     * not verified: selection 1 prefix `0x1000..0x1280` overwritten by `SaveMenuInitSubBoxText`
     * before `f1100`, and selection 2)
     */
    public fun drawDifficultyModeText(state: DifficultyMenuState) {
        for (i in 0 until DifficultyMenuState.TEXT_LINES) text.clearText(state.texts[i])

        val textId = rom.u16(TEXT_IDS_DIFFICULTY_DESCRIPTION + 2 * state.currentSelection)
        val str = messages.getStringFromIndex(textId)
        var line = 0
        var at = 0
        while (true) {
            val c = str[at].toInt() and 0xFF
            if (c == 0x00) return
            if (c == 0x01) {
                line++
                at++
                continue
            }
            at = text.drawCharacter(state.texts[line], str, at)
        }
    }

    /**
     * Clears the description box on BG0 and puts the five text strips on it.
     *
     * Partially ported from src/difficultymenu.c: sub_80AC034 (owned by `PC-9b`; verified: the
     * five `PutText` calls at init and `BG0_SYNC_BIT` at `f1030`; not verified: the box clear,
     * since BG0 is already `0` outside the columns overwritten by `PutText` both at init and at
     * `f1030`)
     */
    public fun putDifficultyModeText(state: DifficultyMenuState) {
        val bg0 = hardware.mapBuffer(0)
        // TileMap_FillRect counts both sizes inclusively: 15 columns by 11 rows.
        for (row in 0..BOX_HEIGHT) {
            for (col in 0..BOX_WIDTH) bg0[(BOX_Y + row) * BgTilemap.ROW + BOX_X + col] = 0
        }
        for (i in 0 until DifficultyMenuState.TEXT_LINES) {
            text.putText(state.texts[i], bg0, (TEXT_Y + i * 2) * BgTilemap.ROW + TEXT_X)
        }
        hardware.enableBgSyncByMask(BG0_SYNC_BIT)
    }

    /**
     * `gUnknown_08A209FC[selection]` field [field] (0 = `a`, 1 = `b`): `s8` values, 4 bytes
     * apart per entry in the ROM image.
     */
    private fun cursorTarget(selection: Int, field: Int): Int =
        rom.u8(CURSOR_TARGETS + selection * CURSOR_TARGET_STRIDE + field).toByte().toInt()

    private fun saveDrawOf(proc: Proc): SaveDrawProc {
        val menu = proc.parent!!.state as SaveMenuState
        return menu.savedraw!!.state as SaveDrawProc
    }

    private fun disableAllGfx() = hardware.lcd.setDispEnable(0, 0, 0, 0, 0)

    // Not verified: the fade-in enables the layers again before any compared step.
    private fun enableAllGfx() = hardware.lcd.setDispEnable(1, 1, 1, 1, 1)

    private fun applyPalettes(romAddr: Int, bankId: Int, bankCount: Int) {
        for (i in 0 until bankCount * PALETTE_BANK_COLORS) {
            hardware.palette[bankId * PALETTE_BANK_COLORS + i] = rom.u16(romAddr + 2 * i).toShort()
        }
        hardware.enablePaletteSync()
    }

    /** ROM addresses and layout constants of `src/difficultymenu.c`. */
    public companion object {
        /** `PL_` label 0 of `ProcScr_NewGameDifficultySelect`: the key loop. */
        public const val LABEL_LOOP: Int = 0

        /** Label 1: a difficulty was confirmed (10 frame pause, then fade out). */
        public const val LABEL_CONFIRM: Int = 1

        /** Label 2: cancelled (fade out at once). */
        public const val LABEL_CANCEL: Int = 2

        /** `Img_DifficultyMenuObjs` (`data/data_A21658.s`). */
        public const val IMG_DIFFICULTY_MENU_OBJS: Int = 0x08A28A0C

        /** `Pal_DifficultyMenuObjs` (`data/data_A21658.s`). */
        public const val PAL_DIFFICULTY_MENU_OBJS: Int = 0x08A29418

        /** `gUnknown_08A29558` (`data/data_A21658.s`): compressed BG1 panel TSA. */
        public const val TSA_DIFFICULTY_PANEL: Int = 0x08A29558

        /** `gUnknown_08A209FC`: three `{s8 a, s8 b}` cursor targets (4 bytes apart). */
        public const val CURSOR_TARGETS: Int = 0x08A209FC

        /** `gTextIds_DifficultyDescription`: three `u16` message ids. */
        public const val TEXT_IDS_DIFFICULTY_DESCRIPTION: Int = 0x08A20A08

        private const val A_BUTTON = 1 shl 0
        private const val B_BUTTON = 1 shl 1
        private const val DPAD_UP = 1 shl 6
        private const val DPAD_DOWN = 1 shl 7
        private const val SONG_SE_SYS_CURSOR_UD1 = 0x66
        private const val SONG_SE_SYS_WINDOW_SELECT1 = 0x6A
        private const val SONG_SE_SYS_WINDOW_CANSEL1 = 0x6B
        private const val MAX_SELECTION = 2
        private const val DIFFICULTY_CANCEL = 3
        private const val U16_MASK = 0xFFFF

        private const val CURSOR_TARGET_STRIDE = 4
        private const val OBJ_TILES_OFFSET = 0x10800
        private const val GENERIC_PANEL_BYTES = 0x2000
        private const val BG1_PANEL_OFFSET = 0xD1
        private const val BG1_PANEL_TILE_BASE = 0x1000
        private const val TEXT_TILE_WIDTH = 14
        private const val PALETTE_BANK_COLORS = 16
        private const val BG0_SYNC_BIT = 1
        private const val BG1_SYNC_BIT = 2
        private const val BOX_X = 7
        private const val BOX_Y = 7
        private const val BOX_WIDTH = 14
        private const val BOX_HEIGHT = 10
        private const val TEXT_X = 18
        private const val TEXT_Y = 7
    }
}
