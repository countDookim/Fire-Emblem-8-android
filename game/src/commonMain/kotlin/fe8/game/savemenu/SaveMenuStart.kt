package fe8.game.savemenu

import fe8.game.RomView
import fe8.game.bm.PlaySt
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcScript
import gbafe.render.BgHardware

/**
 * The setup callbacks of the title save menu (`ProcScr_SaveMenu`, `src/savemenu.c`, `PC-11a`):
 * `StartSaveMenu`, `SaveMenu_Init`, `SaveMenu_LoadExtraMenuGraphics` and `SaveMenu_JumpToTarget`.
 * [SaveMenuAssembly] or the title menu wiring (`PC-11`) puts them in
 * [SaveMenuNewGame.Hooks.funcs] under their C names.
 *
 * @param script `ProcScr_SaveMenu`, [SaveMenuNewGame.script]
 */
public class SaveMenuStart(
    private val engine: ProcEngine,
    private val hardware: BgHardware,
    private val rom: RomView,
    private val playSt: PlaySt,
    private val script: ProcScript,
    private val menu: SaveMenuPostChapter,
    private val choice: SaveMenuChoice,
    /** Callbacks for the parts owned by other tasks. */
    public val hooks: Hooks = Hooks(),
) {
    /** Parts of the setup owned by other tasks. */
    public class Hooks(
        /**
         * `SaveMenuGetValidMenuAmt(MAIN_MENU_OPTION_EXTRAS, proc)`, the number of valid Extras
         * entries; a stand-in (returns 0) until the Extras return path is ported.
         */
        public var getValidMenuAmt: (SaveMenuState) -> Int = { 0 },
    )

    /**
     * Starts `ProcScr_SaveMenu` blocking [parent], sets `main_sel_bitfile` to `0x100` (not a
     * menu option), clears `extra_sel_bitfile` and sets the text speed to 2.
     *
     * Ported from src/savemenu.c: StartSaveMenu (verified: the blocked parent, `main_sel_bitfile`,
     * `extra_sel_bitfile` and the text speed at f921 of `ram_trace_title_continue`)
     */
    public fun startSaveMenu(parent: Proc): Proc? {
        val proc = engine.startBlocking(script, parent) ?: return null
        val state = proc.state as SaveMenuState
        state.mainSelBitfile = START_SEL_BITFILE
        state.extraSelBitfile = 0
        playSt.textSpeed = TEXT_SPEED
        return proc
    }

    /**
     * Rebuilds the screen state: resets the help text state, sets up the backgrounds from
     * `gBgConfig_SaveMenu`, hides every layer, mode 0, BG priorities 0..3 and the blend setup.
     *
     * Ported from src/savemenu.c: SaveMenu_Init
     */
    public fun saveMenuInit() {
        menu.setupSaveMenuScreenLcd()
    }

    /**
     * Loads the Extras / main choice object graphics, builds the option lists and picks the
     * entry the menu starts on: the Extras entry when `main_sel_bitfile` is the Extras option,
     * else the first main option at the main loop. `unk_2f` follows the target label.
     *
     * Partially ported from src/savemenu.c: SaveMenu_LoadExtraMenuGraphics (verified: the
     * fresh-start branch (`main_sel_bitfile` 0x100) and the main loop label at f943 of the title
     * runs; not verified: the Extras return branch, whose valid-amount query is a [Hooks]
     * stand-in, and the slot select label's `unk_2f`)
     */
    public fun saveMenuLoadExtraMenuGraphics(proc: Proc) {
        val state = proc.state as SaveMenuState
        rom.decompressInto(
            IMG_GAME_MAIN_MENU_OBJS, hardware.vram, OBJ_VRAM_OFFSET + CHOICE_STR_OFFSET,
        )
        choice.initSaveMenuChoice(state)

        if (state.mainSelBitfile == PostSaveMenu.MAIN_MENU_OPTION_EXTRAS) {
            state.mainSelect = hooks.getValidMenuAmt(state)
        } else {
            state.jumpLabel = SaveMenuNewGame.PL_MAIN_LOOP
            state.susSlot = 0
            state.mainSelect = 0
            state.extraSelect = 0
            state.unk46 = 0
            state.mainSelBitfile =
                SaveMenuChoice.saveMenuGetBitfile(state.mainOptions, state.mainSelect)
        }

        if (state.jumpLabel == SaveMenuNewGame.PL_MAIN_LOOP) state.unk2f = 0
        if (state.jumpLabel == SaveMenuNewGame.PL_SAVE_SLOT_SEL) state.unk2f = MENU_X_OFFSET
    }

    /**
     * Jumps the script to the label in `jump_label`.
     *
     * Ported from src/savemenu.c: SaveMenu_JumpToTarget
     */
    public fun saveMenuJumpToTarget(proc: Proc) {
        engine.goto(proc, (proc.state as SaveMenuState).jumpLabel)
    }

    private companion object {
        /** `proc->main_sel_bitfile` after `StartSaveMenu`. */
        const val START_SEL_BITFILE = 0x100
        const val TEXT_SPEED = 2

        /** `Img_GameMainMenuObjs` (ROM). */
        const val IMG_GAME_MAIN_MENU_OBJS = 0x08A2812C
        const val OBJ_VRAM_OFFSET = 0x10000

        /** `OBJCHR_SAVEMENU_MAINCHOICE_STR` (0x200 tiles of 0x20 bytes). */
        const val CHOICE_STR_OFFSET = 0x200 * 0x20
        const val MENU_X_OFFSET = 0xDC
    }
}
