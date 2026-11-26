package fe8.game.savemenu

import fe8.game.RomView
import fe8.game.bm.BmSt
import fe8.game.bm.ChapterTitle
import fe8.game.bm.PlaySt
import fe8.game.mapanim.ApProcs
import fe8.game.save.BmSaveLib
import fe8.game.save.NewGameRam
import fe8.game.save.SaveExtras
import fe8.game.ui.HelpBox
import fe8.game.ui.HelpPromptSprite
import fe8.game.ui.UiHand
import fe8.game.worldmap.GmData
import gbafe.core.rng.LcgRng
import gbafe.input.KeyStatus
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.render.ArchivedPaletteFade
import gbafe.render.BgHardware
import gbafe.render.BgVerticalScroll
import gbafe.render.OamBuffer
import gbafe.render.SpriteLayers
import gbafe.text.MsgStrings
import gbafe.text.TextSystem

/**
 * Production assembly of the post-chapter save menu (PC-4): builds every part of
 * `gProcScr_SaveMenuPostChapter` and calls each `wire*` function of [SaveMenuPostChapter]
 * (`wireScreen` with the lifecycle, post-save handler, slots and effects; `wireSlotFlip`;
 * `wireSelectLoop`), with `WriteGameSave` supplied by the caller from
 * [fe8.game.save.BmGameSave.writeGameSave].
 */
public object SaveMenuAssembly {
    /** The shared game objects the save menu runs on. */
    public class Deps(
        /** Proc engine the menu procs run on. */
        public val engine: ProcEngine,
        /** BG/palette hardware buffers. */
        public val hardware: BgHardware,
        /** ROM view for graphics and tables. */
        public val rom: RomView,
        /** OAM buffer. */
        public val oam: OamBuffer,
        /** Sprite layers. */
        public val layers: SpriteLayers,
        /** BG vertical scroll procs. */
        public val scroll: BgVerticalScroll,
        /** AP (sprite animation) procs. */
        public val apProcs: ApProcs,
        /** `gPlaySt`. */
        public val playSt: PlaySt,
        /** `gBmSt`. */
        public val bmSt: BmSt,
        /** World map data (`gGMData`). */
        public val gmData: GmData,
        /** SRAM save helpers. */
        public val saveLib: BmSaveLib,
        /** Archived palette fade procs. */
        public val archivedFade: ArchivedPaletteFade,
        /** Chapter title drawing. */
        public val chapterTitle: ChapterTitle,
        /** Text system. */
        public val text: TextSystem,
        /** Message strings. */
        public val messages: MsgStrings,
        /** LCG random generator. */
        public val lcg: LcgRng,
        /** `LoadObjUIGfx`. */
        public val loadObjUiGfx: () -> Unit,
        /** `gGameClock` frame count (for the slot box animation). */
        public val clock: () -> Int,
        /** `SetNextGameActionId`. */
        public val setNextGameActionId: (Int) -> Unit,
        /** `WriteGameSave(slot)`, i.e. `BmGameSave.writeGameSave(ram, slot)`. */
        public val writeGameSave: (slot: Int) -> Unit,
        /** `gKeyStatusPtr->newKeys`. */
        public val newKeys: () -> Int,
        /** `PlaySoundEffect`. */
        public val playSoundEffect: (Int) -> Unit,
    )

    /** What [assemble] built, so callers (and tests) can reach the wired parts. */
    public class Result(
        /** The `ProcScr_SaveMenuPostChapter` menu. */
        public val menu: SaveMenuPostChapter,
        /** The save draw lifecycle procs. */
        public val lifecycle: SaveDrawLifecycle,
        /** Slot box drawing. */
        public val slots: SaveDrawSlots,
        /** Save draw effects. */
        public val effects: SaveDrawEffects,
        /** Post-write slot flip. */
        public val flip: SaveMenuSlotFlip,
        /** Slot select and confirm loop. */
        public val selectLoop: SaveMenuSelectLoop,
        /** Per-slot display data. */
        public val slotData: SaveSlotData,
    )

    /** Builds and fully wires the post-chapter save menu on [d]. */
    public fun assemble(d: Deps): Result {
        val cursor = SaveDrawCursor(d.engine, d.layers, d.rom)
        val lifecycle =
            SaveDrawLifecycle(d.engine, d.hardware, d.scroll, d.oam, cursor, d.apProcs, d.rom)
        val slotData = SaveSlotData(d.saveLib, d.chapterTitle, d.hardware, d.rom)
        val uiHand = UiHand(d.rom, d.layers)
        val slots = SaveDrawSlots(d.layers, d.rom, cursor, uiHand, d.apProcs, d.clock)
        val effects = SaveDrawEffects(
            d.hardware, d.oam, d.layers, slotData, d.lcg, lifecycle.particles, d.rom,
        )
        val menu = SaveMenuPostChapter(
            d.engine, d.hardware, d.playSt, d.bmSt, d.gmData, d.saveLib, d.archivedFade,
        )
        val postSaveMenu = PostSaveMenu(d.playSt, d.saveLib, d.setNextGameActionId)
        val screen = SaveMenuScreen(
            d.rom, d.hardware, d.oam, d.playSt, d.text, d.chapterTitle, slotData,
            menu::startSaveDraw, d.loadObjUiGfx,
        )
        menu.wireScreen(screen, lifecycle, postSaveMenu, slots, effects)
        val flip = SaveMenuSlotFlip(
            d.engine, d.rom, d.oam, d.chapterTitle, slotData,
            SaveMenuChoice(extras = SaveExtras(d.saveLib)),
        ).also {
            it.hooks.playSoundEffect = d.playSoundEffect
            it.hooks.modifySaveSlot = { slot, valid, position ->
                SaveMenuMainLoop.modifySaveSlot(d.saveLib, slot, valid, position)
            }
        }
        menu.wireSlotFlip(flip)
        val loop = SaveMenuSelectLoop(
            d.engine, d.hardware, d.text, d.messages, screen.subBoxFont, screen.subBoxText,
            bitfileToIndex = slots::bitfileToIndex,
        ).also {
            it.hooks.newKeys = d.newKeys
            it.hooks.playSoundEffect = d.playSoundEffect
            it.hooks.writeGameSave = d.writeGameSave
        }
        menu.wireSelectLoop(loop)
        return Result(menu, lifecycle, slots, effects, flip, loop, slotData)
    }

    /** What [wireNewGame] built. */
    public class NewGameWiring(
        /** The `ProcScr_SaveMenu` bridge (script, `SaveMenu_NewGame`, `SaveMenuWriteNewGame`). */
        public val saveMenu: SaveMenuNewGame,
        /** The `ProcScr_NewGameDifficultySelect` screen. */
        public val difficulty: DifficultyMenu,
        /** The difficulty screen's sprite child. */
        public val sprites: DifficultyMenuSprites,
        /** The main menu loop, slot scroll, slot-select sprite and slot search (`PC-11c`). */
        public val mainLoop: SaveMenuMainLoop,
    )

    /**
     * Wires the New Game path of the save menu onto [result] (`PC-9`): the select loop's
     * `SaveMenu_NewGame` and `SaveMenuWriteNewGame` hooks become [SaveMenuNewGame.saveMenuNewGame]
     * and [SaveMenuNewGame.saveMenuWriteNewGame] (the write is `BmSaveLib.writeNewGameSave` on
     * [ram], then [afterWrite]); `ProcScr_SaveMenu` becomes the script
     * [SaveMenuPostChapter.saveMenuSetDifficultyChoice] searches; the difficulty select is a real
     * [DifficultyMenu] with [DifficultyMenuSprites] reading [keys], whose choice is written back
     * through [SaveMenuPostChapter.saveMenuSetDifficultyChoice]. `SameMenu_CtrlLoop`,
     * `SaveMenuScrollSlot`, `SaveMenuSlotSelDrawSprite` and `SaveMenuModifySaveSlot` are wired
     * through [SaveMenuMainLoop] (`PC-11c`); the remaining setup/graphics callbacks
     * (`SaveMenu_Init`, `SaveMenu_ReloadScreenFormDifficulty` graphics reload, ...) stay in
     * [SaveMenuNewGame.Hooks] for the remaining `PC-11` tasks.
     *
     * @param d the deps [assemble] was called with
     * @param ram `gPlaySt`, flags and convoy that `WriteNewGameSave` initialises
     * @param keys key status the difficulty select reads
     * @param startBgmVolumeChange `StartBgmVolumeChange`
     * @param afterWrite called after the new game is written (e.g. flush SRAM to storage)
     */
    public fun wireNewGame(
        result: Result,
        d: Deps,
        ram: NewGameRam,
        keys: KeyStatus,
        startBgmVolumeChange: (Int, Int, Int, Int) -> Unit = { _, _, _, _ -> },
        afterWrite: () -> Unit = {},
    ): NewGameWiring {
        val bridge = SaveMenuNewGame(d.engine, d.hardware, d.saveLib, ram, d.archivedFade)
        bridge.hooks.startBgmVolumeChange = startBgmVolumeChange
        bridge.hooks.postSaveMenuHandler = result.menu.hooks.postSaveMenuHandler
        bridge.hooks.saveSlotSelectLoop = result.menu.hooks.saveSlotSelectLoop
        val difficulty = DifficultyMenu(
            d.engine, d.hardware, d.rom, d.playSt, d.text, d.messages, d.archivedFade,
            keys = keys, layers = d.layers,
        )
        difficulty.hooks.playSoundEffect = d.playSoundEffect
        difficulty.hooks.setDifficultyChoice = { choice, b ->
            result.menu.saveMenuSetDifficultyChoice(choice, b)
        }
        val sprites = DifficultyMenuSprites(d.engine, d.hardware, d.layers, d.rom)
        difficulty.wireSprites(sprites)
        bridge.hooks.newNewGameDifficultySelect =
            ProcFunc { difficulty.newNewGameDifficultySelect(it) }
        val mainLoop = SaveMenuMainLoop(d.engine, keys, d.saveLib)
        mainLoop.hooks.playSoundEffect = d.playSoundEffect
        mainLoop.hooks.startBgmVolumeChange = startBgmVolumeChange
        val helpPrompt = HelpPromptSprite(d.engine, d.layers, d.hardware, d.rom)
        mainLoop.hooks.startHelpPromptSprite = { x, y, z, parent ->
            helpPrompt.startHelpPromptSprite(x, y, z, parent)
        }
        val helpBox = HelpBox(
            d.engine, d.layers, d.hardware, d.rom, d.text, helpPrompt, d.messages,
            textSpeed = { d.playSt.textSpeed },
        )
        helpBox.hooks.playSoundEffect = d.playSoundEffect
        result.selectLoop.hooks.closeHelpBox = helpBox::closeHelpBox
        result.selectLoop.hooks.startSuspendLostHelpBox = {
            val chr = HelpBox.OBJCHR_SAVEMENU_SLOTSEL_HELPBOX
            helpBox.loadHelpBoxGfx(
                HelpBox.OBJ_VRAM0 + chr * HelpBox.TILE_SIZE_4BPP,
                HelpBox.OBJPAL_SAVEMENU_SLOTSEL_HELPBOX,
            )
            helpBox.startHelpBoxExtNoHelpSprite(48, 48, MSG_882)
        }
        bridge.hooks.funcs["SameMenu_CtrlLoop"] = ProcFunc { mainLoop.sameMenuCtrlLoop(it) }
        bridge.hooks.funcs["SaveMenuScrollSlot"] = ProcFunc { mainLoop.saveMenuScrollSlot(it) }
        bridge.hooks.funcs["SaveMenuSlotSelDrawSprite"] =
            ProcFunc { mainLoop.saveMenuSlotSelDrawSprite(it) }
        result.flip.hooks.modifySaveSlot = mainLoop::saveMenuModifySaveSlot
        result.menu.hooks.saveMenuProcScript = bridge.script
        result.selectLoop.hooks.newGame = { bridge.saveMenuNewGame(it) }
        result.selectLoop.hooks.writeNewGame = {
            bridge.saveMenuWriteNewGame(it)
            afterWrite()
        }
        return NewGameWiring(bridge, difficulty, sprites, mainLoop)
    }

    /** `MSG_882`, the "suspended data will be lost" message of the confirm help box. */
    private const val MSG_882 = 0x882

    /** `Make6C_SaveMenuPostChapter` for [GameControl.Hooks.makeSaveMenuPostChapter]. */
    public fun makeHook(result: Result): (Proc) -> Unit = { parent ->
        result.menu.make6cSaveMenuPostChapter(parent)
    }
}
