package fe8.game.savemenu

import fe8.game.GameControl
import fe8.game.bm.BmSt
import fe8.game.bm.PlaySt
import fe8.game.save.BmSaveLib
import fe8.game.worldmap.GmData
import gbafe.proc.Proc
import gbafe.proc.ProcArgFunc
import gbafe.proc.ProcBoolFunc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
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
 * Help-text state (`gSaveMenuRTextData`, `struct SaveMenuRTextData` in `include/savemenu.h`)
 * initialized by [SaveMenuPostChapter.initSaveMenuHelpTextSt].
 */
public class SaveMenuRTextData(
    /** `pid` (`u8`): leader character id of the inspected save slot, or 0 when cleared. */
    public var pid: Int = 0,
    /** `level` (`s8`): leader level of the inspected save slot, or -1 when cleared. */
    public var level: Int = 0,
    /** `nodeId` (`s8`): world map node id of the inspected save slot, or -1 when cleared. */
    public var nodeId: Int = 0,
)

/**
 * The `struct SaveMenuProc` fields (`include/savemenu.h`) that `SaveMenuInit` writes, plus the
 * slot metadata and child proc handles shared with [SaveDrawLifecycle] and [PostSaveMenu].
 * [selection] stores `susSlot`, `extraSelBitfile`, and `mainSelBitfile` so
 * [PostSaveMenu.postSaveMenuHandler] can read them without extra allocations.
 */
public class SaveMenuState : SaveMenuParent {
    /** Selection fields shared with [PostSaveMenu.postSaveMenuHandler]. */
    public val selection: PostSaveMenu.Selection = PostSaveMenu.Selection()

    /** `scroll_cnt` (u8, 0x29): scroll frame counter cleared in `SaveMenu_InitScreen`. */
    public var scrollCnt: Int = 0

    /** `main_select` (u8, 0x2B): current index in the main menu list. */
    public var mainSelect: Int = 0

    /** `sus_slot` (u8, 0x2C): selected save slot index (0..2). */
    public override var susSlot: Int
        get() = selection.susSlot
        set(value) {
            selection.susSlot = value
        }

    /** `cursor_slot` (u8, 0x2D): static cursor slot index, or `0xFF` when hidden. */
    public var cursorSlot: Int = 0

    /** `jump_label` (u8, 0x2E): target proc label (`PL_SAVEMENU_*`). */
    public var jumpLabel: Int = 0

    /** `unk_2f` (u8, 0x2F): horizontal menu offset (`0xDC` for post-chapter slot select). */
    public var unk2f: Int = 0

    /** `main_options` (u8, 0x30): bitfield of active main menu options (`MAIN_MENU_OPTION_*`). */
    public var mainOptions: Int = 0

    /** `unk_31` (u8, 0x31): number of visible main menu options. */
    public var unk31: Int = 0

    /** `extra_select` (u8, 0x34): current index in the Extras menu. */
    public var extraSelect: Int = 0

    /** `extra_sel_bitfile` (u8, 0x35): bitmask of the selected Extras option. */
    public override var extraSelBitfile: Int
        get() = selection.extraSelBitfile
        set(value) {
            selection.extraSelBitfile = value
        }

    /**
     * `cursor_config` (u8, 0x36): confirm sub-box selection state, cleared in
     * `SaveMenu_InitScreen`.
     */
    public var cursorConfig: Int = 0

    /** `unk_3d` (u8, 0x3D): unknown byte cleared in `SaveMenu_InitScreen`. */
    public var unk3d: Int = 0

    /** `hb_en` (bool, 0x3E): whether help box mode is active. */
    public var hbEn: Boolean = false

    /** `ctrl_timer` (u8, 0x40): help box animation timer cleared in `SaveMenu_InitScreen`. */
    public var ctrlTimer: Int = 0

    /** `main_sel_bitfile` (u16, 0x42): bitmask of the chosen main option (`MAIN_MENU_OPTION_*`). */
    public override var mainSelBitfile: Int
        get() = selection.mainSelBitfile
        set(value) {
            selection.mainSelBitfile = value
        }

    /** `unk_46` (u16, 0x46): unknown halfword cleared in `SaveMenuInit`. */
    public var unk46: Int = 0

    /** Per-slot metadata (`chapter_idx`, `unk_3a`, `sus_slot_cur`, `unk_44`, `played_time`). */
    public val slots: SaveSlotData.Slots = SaveSlotData.Slots()

    /** `sus_slot_cur` (u8, 0x3F): game slot of the active suspend save, or `0xFF` if none. */
    public override var susSlotCur: Int
        get() = slots.susSlotCur
        set(value) {
            slots.susSlotCur = value
        }

    /** `savedraw` (0x58): child `ProcScr_savedraw` proc started by `SaveMenu_InitScreen`. */
    public var savedraw: Proc? = null

    /** `approc` (0x60): suspend bookmark `ProcScr_ApProc` proc started by `SaveDraw_Init`. */
    public override var approc: Proc? = null
}

/**
 * Post-chapter save menu (`gProcScr_SaveMenuPostChapter`, `src/savemenu.c`) and its entry / LCD
 * setup callbacks. The script keeps every original command; the steps owned by later tasks run
 * through [hooks].
 */
public class SaveMenuPostChapter(
    /** The proc scheduler running `gProcScr_SaveMenuPostChapter`. */
    private val engine: ProcEngine,
    /** Display hardware (`gLCDControlBuffer`, BG config, tilemaps). */
    private val hardware: BgHardware,
    /** Persistent play state (`gPlaySt`). */
    private val playSt: PlaySt,
    /** Battle map state (`gBmSt`, checked for `BM_FLAG_PREPSCREEN`). */
    private val bmSt: BmSt,
    /** World map state (`gGMData`, checked by `SaveMenu_SetLcdChapterIdx`). */
    private val gmData: GmData,
    /** Save RAM layer queried by `SaveMenuInit` for `ReadLastGameSaveId`. */
    private val saveLib: BmSaveLib,
    /** Generic archived palette fade-in (`NewFadeIn`, `FadeInExists`). */
    private val archivedFade: ArchivedPaletteFade,
    /** Help-text data (`gSaveMenuRTextData`) reset by [initSaveMenuHelpTextSt]. */
    public val rTextData: SaveMenuRTextData = SaveMenuRTextData(),
    /** Callbacks for screen init, slot loop, fade-out, and world-map/flag queries. */
    public val hooks: Hooks = Hooks(),
) {
    /**
     * External callbacks invoked by [script] and [saveMenuSetLcdChapterIdx]. Defaults are no-ops
     * or neutral fallbacks until their owning tasks are ported.
     */
    public class Hooks(
        /** `SaveMenu_InitScreen` (PC-4a2); callers can wire [SaveMenuScreen.saveMenuInitScreen]. */
        public var initScreen: ProcFunc = ProcFunc { },
        /** `SaveMenu_SaveSlotSelectLoop`. Stand-in (PC-4e1) until slot selection loop is ported. */
        public var saveSlotSelectLoop: ProcFunc = ProcFunc { },
        /** `SaveMenuRegisterSlotSelected`. Stand-in (PC-4e2) until slot confirm is ported. */
        public var registerSlotSelected: ProcFunc = ProcFunc { },
        /** `SaveMenuWaitSlotBoxScrolling`. Stand-in (PC-4e2) until slot box scroll is ported. */
        public var waitSlotBoxScrolling: ProcFunc = ProcFunc { },
        /** `NewFadeOut`. Stand-in (PC-4e2) until archived fade-out is ported. */
        public var newFadeOut: (speed: Int, parent: Proc?) -> Unit = { _, _ -> },
        /** `FadeOutExists`. Stand-in (PC-4e2) until archived fade-out is ported. */
        public var fadeOutExists: () -> Boolean = { false },
        /** `PostSaveMenuHandler` (PC-4g); callers can wire [PostSaveMenu.postSaveMenuHandler]. */
        public var postSaveMenuHandler: ProcFunc = ProcFunc { },
        /** `CheckFlag` query for `EVFLAG_136` in [saveMenuSetLcdChapterIdx]. */
        public var checkFlag: (Int) -> Boolean = { false },
        /** `GetNextUnclearedNode(&gGMData)` query in [saveMenuSetLcdChapterIdx]. */
        public var getNextUnclearedNode: () -> Int = { 0 },
        /** `WMLoc_GetChapterId(node)` query in [saveMenuSetLcdChapterIdx]. */
        public var wmLocGetChapterId: (Int) -> Int = { 0 },
        /** `GetChapterIndexOnWmNode(&gGMData)`. Stand-in (PC-4f) until ported. */
        public var getChapterIndexOnWmNode: () -> Int = { 0 },
        /**
         * `StartSaveDraw(proc)` called from `SaveMenu_InitScreen`; wired by [wireSaveDraw].
         */
        public var startSaveDraw: (Proc) -> Proc? = { null },
    )

    /**
     * The post-chapter save menu proc script `gProcScr_SaveMenuPostChapter`
     * (`src/savemenu.c:1689`). Every command index matches the ROM script 1:1.
     */
    public val script: ProcScript = ProcScript(
        "gProcScr_SaveMenuPostChapter",
        listOf(
            name("savemenu"),
            yield(),
            call(ProcFunc { saveMenuInit(it) }),
            call(ProcFunc { saveMenuSetLcdChapterIdx() }),
            yield(),
            call(ProcFunc { hooks.initScreen.invoke(it) }),
            yield(),
            call(ProcFunc { saveMenuDirectlySelectSlotOnPrepScreen(it) }),
            callArg(
                ProcArgFunc { speed, proc ->
                    archivedFade.newFadeIn(speed, proc)
                    true
                },
                8,
            ),
            whileTrue(ProcBoolFunc { archivedFade.exists() }),
            goto(PL_SAVEMENU_SAVE_SLOT_SEL),
            label(PL_SAVEMENU_SAVE_SLOT_SEL_FADEIN),
            callArg(
                ProcArgFunc { speed, proc ->
                    archivedFade.newFadeIn(speed, proc)
                    true
                },
                4,
            ),
            whileTrue(ProcBoolFunc { archivedFade.exists() }),
            label(PL_SAVEMENU_SAVE_SLOT_SEL),
            repeat(ProcFunc { hooks.saveSlotSelectLoop.invoke(it) }),
            goto(PL_SAVEMENU_EXIT),
            label(PL_SAVEMENU_SLOT_SELECTED),
            sleep(1),
            call(ProcFunc { hooks.registerSlotSelected.invoke(it) }),
            sleep(1),
            repeat(ProcFunc { hooks.waitSlotBoxScrolling.invoke(it) }),
            goto(PL_SAVEMENU_SAVE_SLOT_SEL),
            label(PL_SAVEMENU_18),
            callArg(
                ProcArgFunc { speed, proc ->
                    hooks.newFadeOut(speed, proc)
                    true
                },
                4,
            ),
            whileTrue(ProcBoolFunc { hooks.fadeOutExists() }),
            goto(PL_SAVEMENU_EXIT),
            label(PL_SAVEMENU_EXIT_FADE),
            callArg(
                ProcArgFunc { speed, proc ->
                    hooks.newFadeOut(speed, proc)
                    true
                },
                8,
            ),
            whileTrue(ProcBoolFunc { hooks.fadeOutExists() }),
            label(PL_SAVEMENU_EXIT),
            yield(),
            call(ProcFunc { hooks.postSaveMenuHandler.invoke(it) }),
            end(),
        ),
        newState = { SaveMenuState() },
    )

    /**
     * Starts `gProcScr_SaveMenuPostChapter` as a blocking child of [parent].
     *
     * Ported from src/savemenu.c: Make6C_SaveMenuPostChapter
     */
    public fun make6cSaveMenuPostChapter(parent: Proc): Proc? =
        engine.startBlocking(script, parent)

    /**
     * Initializes [SaveMenuState] for post-chapter slot selection: targets label
     * [PL_SAVEMENU_SAVE_SLOT_SEL], selects the last saved slot via [BmSaveLib.readLastGameSaveId],
     * sets `mainOptions` and `mainSelBitfile` to [PostSaveMenu.MAIN_MENU_OPTION_INVALID], clears
     * selection indices, and sets `unk2f` to `0xDC`.
     *
     * Ported from src/savemenu.c: SaveMenuInit
     */
    public fun saveMenuInit(proc: Proc) {
        val state = proc.state as SaveMenuState
        state.jumpLabel = PL_SAVEMENU_SAVE_SLOT_SEL
        state.susSlot = saveLib.readLastGameSaveId()
        state.mainSelect = 0
        state.extraSelect = 0
        state.unk46 = 0
        state.mainOptions = PostSaveMenu.MAIN_MENU_OPTION_INVALID
        state.mainSelBitfile = PostSaveMenu.MAIN_MENU_OPTION_INVALID
        state.unk31 = 0
        state.unk2f = POST_CHAPTER_MENU_X_OFFSET
    }

    /**
     * Optionally updates [PlaySt.chapterIndex] from world-map / Frelia progress, resets
     * [rTextData], and configures the save menu BG layout, disabled display, BG priorities 0..3,
     * and alpha blend (`SetBlendTargetA(0,0,1,0,0)`, `SetBlendTargetB(0,0,0,1,0)`,
     * `SetBlendConfig(1, 6, 16, 0)`).
     *
     * Transcribed from src/savemenu.c: SaveMenu_SetLcdChapterIdx (not verified: world-map,
     * EVFLAG_136, and non-save_menu_type-2 chapter-override branches are not exercised in
     * ram_trace_prologue_end; LCD, blend, and help-text setup for chapter 1 / save_menu_type 2
     * are verified at f09444).
     */
    public fun saveMenuSetLcdChapterIdx() {
        if (playSt.chapterStateBits and GameControl.PLAY_FLAG_COMPLETE == 0) {
            val chapterId = playSt.chapterIndex.toUInt()
            if ((gmData.stateRaw and 3) == 3) {
                if (
                    chapterId > CHAPTER_L_1.toUInt() &&
                    chapterId != CHAPTER_CASTLE_FRELIA.toUInt()
                ) {
                    var node = hooks.getNextUnclearedNode()
                    if (node < 0) node = 0
                    playSt.chapterIndex = hooks.wmLocGetChapterId(node)
                }
            } else if (playSt.chapterIndex == CHAPTER_L_5 && hooks.checkFlag(EVFLAG_136)) {
                playSt.chapterIndex = CHAPTER_CASTLE_FRELIA
            } else if (
                chapterId != CHAPTER_L_1.toUInt() &&
                chapterId != CHAPTER_E_9.toUInt() &&
                chapterId != CHAPTER_I_9.toUInt()
            ) {
                if (
                    playSt.saveMenuType != 2 &&
                    (bmSt.gameStateBits and BM_FLAG_PREPSCREEN) == 0
                ) {
                    playSt.chapterIndex = hooks.getChapterIndexOnWmNode()
                }
            }
        }

        initSaveMenuHelpTextSt()

        hardware.setupBackgrounds(BG_CONFIG_SAVE_MENU)
        hardware.lcd.setDispEnable(0, 0, 0, 0, 0)
        hardware.lcd.dispcnt = hardware.lcd.dispcnt and DISPCNT_MODE_MASK.inv()
        for (bg in 0..3) {
            hardware.lcd.bgCnt[bg] = (hardware.lcd.bgCnt[bg] and BG_PRIORITY_MASK.inv()) or bg
        }
        hardware.lcd.setBlendTargetA(0, 0, 1, 0, 0)
        hardware.lcd.setBlendTargetB(0, 0, 0, 1, 0)
        hardware.lcd.setBlendBackdropA(0)
        hardware.lcd.setBlendBackdropB(0)
        hardware.lcd.setBlendConfig(1, 6, 0x10, 0)
    }

    /**
     * Skips the slower fade-in when not entered from the preparations screen by jumping directly
     * to [PL_SAVEMENU_SAVE_SLOT_SEL_FADEIN] (`NewFadeIn(4)`).
     *
     * Ported from src/savemenu.c: SaveMenuDirectlySelectSlotOnPrepScreen (the prep-screen branch,
     * which falls through to `NewFadeIn(8)`, is not observed).
     */
    public fun saveMenuDirectlySelectSlotOnPrepScreen(proc: Proc) {
        if (bmSt.gameStateBits and BM_FLAG_PREPSCREEN == 0) {
            engine.goto(proc, PL_SAVEMENU_SAVE_SLOT_SEL_FADEIN)
        }
    }

    /**
     * Resets [rTextData] (`gSaveMenuRTextData`) to `(pid = 0, level = -1, nodeId = -1)`.
     *
     * Ported from src/savemenu.c: InitSaveMenuHelpTextSt
     */
    public fun initSaveMenuHelpTextSt() {
        rTextData.pid = 0
        rTextData.level = -1
        rTextData.nodeId = -1
    }

    /**
     * Starts `ProcScr_savedraw` via [Hooks.startSaveDraw] and records it on
     * [SaveMenuState.savedraw].
     */
    public fun startSaveDraw(proc: Proc): Proc? =
        hooks.startSaveDraw(proc).also { (proc.state as SaveMenuState).savedraw = it }

    /**
     * Ends the suspend bookmark `ProcScr_ApProc` (when non-null) and the child `ProcScr_savedraw`
     * proc (`APProc_Delete(proc->approc); Proc_End(proc->savedraw);` in `PostSaveMenuHandler`).
     */
    public fun endMenuProcs(proc: Proc) {
        val state = proc.state as SaveMenuState
        state.approc?.let { engine.end(it) }
        engine.end(state.savedraw)
    }

    /**
     * Clears the primary HBlank interrupt handler (`SetPrimaryHBlankHandler(0)` in
     * `PostSaveMenuHandler`).
     */
    public fun clearHBlankHandler() {
        hardware.hblank.setPrimary(null)
    }

    /**
     * Wires [lifecycle]'s `StartSaveDraw` into [Hooks.startSaveDraw] and, when [postSaveMenu] is
     * provided, wires [endMenuProcs], [clearHBlankHandler], and [PostSaveMenu.postSaveMenuHandler]
     * into [Hooks.postSaveMenuHandler].
     */
    public fun wireSaveDraw(
        lifecycle: SaveDrawLifecycle,
        postSaveMenu: PostSaveMenu? = null,
    ) {
        hooks.startSaveDraw = { parent -> lifecycle.startSaveDraw(parent) }
        if (postSaveMenu != null) {
            postSaveMenu.effects.clearHBlankHandler = ::clearHBlankHandler
            hooks.postSaveMenuHandler = ProcFunc { proc ->
                val state = proc.state as SaveMenuState
                postSaveMenu.effects.endMenuProcs = { endMenuProcs(proc) }
                postSaveMenu.postSaveMenuHandler(state.selection)
            }
        }
    }

    /**
     * Wires [screen] (`SaveMenu_InitScreen`), [lifecycle] (`StartSaveDraw` / `SaveDraw_OnEnd`),
     * and optionally [postSaveMenu] (`PostSaveMenuHandler`) into [hooks].
     */
    public fun wireScreen(
        screen: SaveMenuScreen,
        lifecycle: SaveDrawLifecycle,
        postSaveMenu: PostSaveMenu? = null,
    ) {
        wireSaveDraw(lifecycle, postSaveMenu)
        hooks.initScreen = ProcFunc { proc -> screen.saveMenuInitScreen(proc) }
    }

    /** Proc labels and chapter / hardware constants from `savemenu.c` and `savemenu.h`. */
    public companion object {
        /** `PL_SAVEMENU_SAVE_SLOT_SEL` (`include/savemenu.h`). */
        public const val PL_SAVEMENU_SAVE_SLOT_SEL: Int = 5

        /** `PL_SAVEMENU_SLOT_SELECTED` (`include/savemenu.h`). */
        public const val PL_SAVEMENU_SLOT_SELECTED: Int = 6

        /** `PL_SAVEMENU_EXIT` (`include/savemenu.h`). */
        public const val PL_SAVEMENU_EXIT: Int = 15

        /** `PL_SAVEMENU_EXIT_FADE` (`include/savemenu.h`). */
        public const val PL_SAVEMENU_EXIT_FADE: Int = 17

        /** Label 18 (`gProcScr_SaveMenuPostChapter` fast exit fade). */
        public const val PL_SAVEMENU_18: Int = 18

        /** `PL_SAVEMENU_SAVE_SLOT_SEL_FADEIN` (`include/savemenu.h`). */
        public const val PL_SAVEMENU_SAVE_SLOT_SEL_FADEIN: Int = 20

        /** `gBgConfig_SaveMenu` (`src/savemenu.c:232`). */
        private val BG_CONFIG_SAVE_MENU = intArrayOf(
            0x0000, 0x6000, 0x0000,
            0x0000, 0x6800, 0x0000,
            0x8000, 0x7000, 0x0000,
            0x8000, 0x7800, 0x0000,
        )

        /** `BM_FLAG_PREPSCREEN` (`include/types.h`). */
        private const val BM_FLAG_PREPSCREEN = 1 shl 4

        /** `CHAPTER_L_1` (`include/constants/chapters.h`). */
        private const val CHAPTER_L_1 = 0x01

        /** `CHAPTER_L_5` (`include/constants/chapters.h`). */
        private const val CHAPTER_L_5 = 0x06

        /** `CHAPTER_E_9` (`include/constants/chapters.h`). */
        private const val CHAPTER_E_9 = 0x0A

        /** `CHAPTER_I_9` (`include/constants/chapters.h`). */
        private const val CHAPTER_I_9 = 0x17

        /** `CHAPTER_CASTLE_FRELIA` (`include/constants/chapters.h`). */
        private const val CHAPTER_CASTLE_FRELIA = 0x38

        /** `EVFLAG_136` (`include/constants/event-flags.h`). */
        private const val EVFLAG_136 = 136

        /** Horizontal menu offset `unk_2f` set by `SaveMenuInit`. */
        private const val POST_CHAPTER_MENU_X_OFFSET = 0xDC

        /** Mask for `DISPCNT` video mode bits 0..2. */
        private const val DISPCNT_MODE_MASK = 7

        /** Mask for `BGxCNT` priority bits 0..1. */
        private const val BG_PRIORITY_MASK = 3
    }
}
