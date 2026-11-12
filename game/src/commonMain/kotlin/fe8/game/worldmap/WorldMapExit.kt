package fe8.game.worldmap

import fe8.game.RomView
import fe8.game.bm.ChapterData
import fe8.game.bm.ChapterMapGfx
import fe8.game.bm.PlaySt
import fe8.game.event.EventEngine
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcScript
import gbafe.render.BgHardware

/**
 * How the Prologue world map is left: the callbacks of `ProcScr_WorldMapMain` that run after the
 * beginning event (`WorldMap_PostBeginningEvent`, `WorldMap_CallIntroEvent`,
 * `WorldMap_WaitForChapterIntroEvents`, `WorldMap_HideEverything`, `ResetWorldMapScreen`), the
 * proc's end callback `WorldMap_Destruct` and `EndWM`, which `GameControl` calls once the blocking
 * [WorldMapMain.wrapperScript] is gone.
 *
 * D11-wm9 wires [postBeginningEvent], [callIntroEvent], [waitForChapterIntroEvents],
 * [hideEverything], [resetWorldMapScreen] and [destruct] into `ProcScr_WorldMapMain` (through
 * [WorldMapMain.MainHooks] and [WorldMapMainScript]) and [endWm] into
 * `GameControl.Hooks.endWorldMap`.
 *
 * @param mainScript `ProcScr_WorldMapMain`
 * @param fadeInScript `ProcScr_BmFadeIN`
 * @param faces the world map portraits (`HideWmFace`, `EndWMFaceCtrl`, `StartWMFaceCtrl`)
 * @param text the world map text (`HideWmText`)
 * @param muEntry the entry bands (`EndGmMuEntry`, `StartGmapMuEntry`)
 * @param rm the big map (`EndGmapRM`, `EndGmapRmUpdateExt`)
 * @param scroll `GetWMCenteredCameraPosition`
 * @param mainProc the `ProcScr_WorldMapMain` proc, parent of the portrait controller
 */
public class WorldMapExit(
    private val engine: ProcEngine,
    private val hardware: BgHardware,
    private val playSt: PlaySt,
    private val gmData: GmData,
    private val nodes: WmNodeData,
    private val rom: RomView,
    private val chapters: ChapterData,
    private val events: EventEngine,
    private val mainScript: ProcScript,
    private val fadeInScript: ProcScript,
    private val faces: WorldMapFace,
    private val text: WorldMapText,
    private val muEntry: WorldMapMuEntry,
    private val rm: WorldMapRm,
    private val scroll: WorldMapScroll,
    private val mainProc: () -> Proc?,
    private val hooks: Hooks = Hooks(),
) {
    /**
     * Ported from src/worldmap_main.c: WorldMap_PostBeginningEvent
     *
     * Runs once the beginning event is over (`PROC_REPEAT`: it returns while the event engine
     * exists). Ends the portrait, entry band and map procs, then either jumps to the chapter
     * intro event (label 15: the event skipped the world map or asked for no fade, as the
     * Prologue's does) or centres the camera on the leader and goes on (`Proc_Break`).
     */
    public fun postBeginningEvent(proc: Proc) {
        if (events.eventEngineExists()) return

        hooks.mapRoute80BC2DC()
        if (gmData.stateRaw and GM_STATE_NOFADE == 0) {
            hardware.lcd.setBlendConfig(0, 0, 0, 0)
            hardware.lcd.setBlendTargetA(0, 0, 0, 0, 0)
            hardware.lcd.setBlendTargetB(0, 0, 1, 0, 0)
            hardware.lcd.setBlendBackdropA(0)
            hardware.lcd.setBlendBackdropB(0)
        } else {
            hardware.lcd.setDispEnable(0, 0, 0, 0, 0)
        }

        faces.hideWmFace()
        text.hideWmText()
        faces.endWmFaceCtrl()
        muEntry.endGmMuEntry()
        rm.endGmapRm()
        rm.endGmapRmUpdateExt()

        val state = proc.state as WorldMapMainProc
        if (gmData.stateRaw and (GM_STATE_SKIPPED or GM_STATE_NOFADE) != 0) {
            hooks.fadeOutBgm(1)
            hooks.fadeOutSe(1)
            state.unk3e = gmData.unitLocation(0)
            engine.goto(proc, LABEL_INTRO_EVENT)
        } else {
            val x = nodes.x(gmData.unitLocation(0))
            val y = nodes.y(gmData.unitLocation(0))
            gmData.ix = x shl 8
            gmData.iy = y shl 8
            val (xCamera, yCamera) = scroll.getWmCenteredCameraPosition(x, y)
            gmData.xCamera = xCamera
            gmData.yCamera = yCamera
            engine.breakProc(proc)
        }
    }

    /**
     * Starts the chapter intro event (`Events_WM_ChapterIntro`) with the portrait controller and
     * the entry bands, when the chapter's `gmapEventId` has an entry.
     *
     * Ported from src/worldmap_main.c: CallChapterWMIntroEvents
     */
    public fun callChapterWmIntroEvents() {
        val id = rom.u8(chapters.getROMChapterStruct(playSt.chapterIndex) + OFF_GMAP_EVENT_ID)
        val event = rom.u32(EVENTS_WM_CHAPTER_INTRO + 4 * id)
        if (event == 0) return
        events.callEvent(event, 0)
        faces.startWmFaceCtrl(mainProc())
        muEntry.startGmapMuEntry()
    }

    /**
     * Ported from src/worldmap_main.c: WorldMap_CallIntroEvent
     *
     * Sets `gPlaySt.chapterIndex` to the chapter of the node the world map was left from (the
     * leader's own node when it stands on a dungeon), runs the chapter intro event and removes
     * the random monsters. `GmMu_80BE108` (clears flag 3 of `GMapMu` slot 0) and
     * `WmRemoveRandomMonsters` are hooks.
     */
    public fun callIntroEvent(proc: Proc) {
        val state = proc.state as WorldMapMainProc
        hooks.gmMu80BE108(0, false)
        val location = gmData.unitLocation(0)
        if (nodes.placementFlag(location) != PLACEMENT_DUNGEON) {
            playSt.chapterIndex = nodes.chapterId(state.unk3e, playSt.chapterModeIndex)
            gmData.stateRaw = gmData.stateRaw and GM_STATE_MONSTER_MERGED.inv()
        } else {
            playSt.chapterIndex = nodes.chapterId(location, playSt.chapterModeIndex)
        }
        callChapterWmIntroEvents()
        gmData.spriteDisp = 0
        hooks.wmRemoveRandomMonsters()
    }

    /**
     * Ported from src/worldmap_main.c: WorldMap_WaitForChapterIntroEvents
     *
     * `PROC_REPEAT`: waits for the intro event, then blanks the display and ends the portrait,
     * entry band and map procs.
     */
    public fun waitForChapterIntroEvents(proc: Proc) {
        if (events.eventEngineExists()) return

        hardware.lcd.setDispEnable(0, 0, 0, 0, 0)
        faces.hideWmFace()
        text.hideWmText()
        faces.endWmFaceCtrl()
        muEntry.endGmMuEntry()
        rm.endGmapRm()
        rm.endGmapRmUpdateExt()
        engine.breakProc(proc)
    }

    /**
     * Transcribed from src/worldmap_main.c: WorldMap_HideEverything (not verified: the display is
     * already off after `WorldMap_PostBeginningEvent`, so no dump shows its effect)
     */
    public fun hideEverything() {
        hardware.lcd.setDispEnable(0, 0, 0, 0, 0)
    }

    /**
     * Transcribed from src/worldmap_main.c: ResetWorldMapScreen (not verified: the battle map
     * start rewrites the registers before the next dump)
     *
     * Blanks the display, clears the blend targets (second target: backdrop and OBJ) and fades
     * the music and sound effects out.
     */
    public fun resetWorldMapScreen() {
        hardware.lcd.setDispEnable(0, 0, 0, 0, 0)
        hardware.lcd.setBlendConfig(0, 0, 0, 0)
        hardware.lcd.setBlendTargetA(0, 0, 0, 0, 0)
        hardware.lcd.setBlendTargetB(0, 0, 0, 1, 1)
        hooks.fadeOutBgm(1)
        hooks.fadeOutSe(1)
    }

    /**
     * Ported from src/worldmap_main.c: WorldMap_Destruct
     *
     * The end callback of `ProcScr_WorldMapMain`: ends the cursor, screen and node icon procs,
     * removes the HBlank handlers, ends the talk, resets the unit sprites, sets the blend to
     * black and clears the `skip_wm`, `no_fade` and `state_0` bits of `gGMData`.
     */
    public fun destruct(@Suppress("UNUSED_PARAMETER") proc: Proc) {
        hooks.endScreenProcs()
        hooks.clearHblank()
        hooks.endTalk()
        hooks.clearTalkText()
        hooks.resetUnitSprites()
        hardware.lcd.setBlendConfig(3, 0, 0, 0x10)
        hooks.endPlayerInterface()
        hooks.endMinimap()
        gmData.stateRaw = gmData.stateRaw and EXIT_CLEARED_BITS.inv()
    }

    /**
     * Transcribed from src/worldmap_main.c: EndWM (not verified: the main proc has ended on its own
     * and the dialogue screen is already reset when it runs; the backgrounds are not dumped)
     *
     * Ends the fade-in and the main proc (which runs [destruct]), resets the dialogue screen
     * (bubble, faces: `ResetFaces` restores the default portrait layout), removes every animation
     * proc and sets the backgrounds up again.
     */
    public fun endWm(@Suppress("UNUSED_PARAMETER") proc: Proc) {
        engine.end(engine.find(fadeInScript))
        engine.end(engine.find(mainScript))
        hooks.resetDialogueScreen()
        hooks.apProcDeleteAll()
        hardware.setupBackgrounds(null, playSt.chapterWeatherId == ChapterMapGfx.WEATHER_CLOUDS)
    }

    /**
     * The callees that are not ported or live outside the world map code. All default to nothing;
     * the defaults are stand-ins until D11-wm9 replaces them.
     */
    public class Hooks(
        /** `MapRoute_80BC2DC(gm_screen->gmroute)` (stand-in until D11-wm9). */
        public val mapRoute80BC2DC: () -> Unit = {},
        /** `Sound_FadeOutBGM(speed)` (stand-in until D11-wm9). */
        public val fadeOutBgm: (Int) -> Unit = {},
        /** `Sound_FadeOutSE(speed)` (stand-in until D11-wm9). */
        public val fadeOutSe: (Int) -> Unit = {},
        /** `GmMu_80BE108(proc->gm_mu, index, flag)` (stand-in until D11-wm9). */
        public val gmMu80BE108: (Int, Boolean) -> Unit = { _, _ -> },
        /** `WmRemoveRandomMonsters()` (stand-in until D11-wm9). */
        public val wmRemoveRandomMonsters: () -> Unit = {},
        /**
         * `Proc_End` of `gm_cursor`, `gm_screen` and `gm_icon` (stand-in until D11-wm9: the
         * cursor and screen procs are not ported as scripts yet).
         */
        public val endScreenProcs: () -> Unit = {},
        /**
         * `SetSecondaryHBlankHandler(NULL)`, `ClearWmHblank()`, `SetPrimaryHBlankHandler(NULL)`
         * ([WorldMapHblank.clearWmHblank] in the boot; nothing by default).
         */
        public val clearHblank: () -> Unit = {},
        /** `EndTalk()` (stand-in until D11-wm9). */
        public val endTalk: () -> Unit = {},
        /** `ClearTalkText()` (stand-in until D11-wm9). */
        public val clearTalkText: () -> Unit = {},
        /** `ResetUnitSprites()` (stand-in until D11-wm9). */
        public val resetUnitSprites: () -> Unit = {},
        /** `sub_80BF15C()`: ends the player interface (stand-in until D11-wm9). */
        public val endPlayerInterface: () -> Unit = {},
        /** `EndWorldmapMinimap()` (stand-in until D11-wm9). */
        public val endMinimap: () -> Unit = {},
        /**
         * `ResetDialogueScreen()`: `ClearTalkBubble`, `Proc_EndEach(gProcScr_E_FACE)`,
         * `ResetFaces` (the default portrait layout), `ClearTalkFaceRefs` (stand-in until
         * D11-wm9: `EventTextTalkHost.clearTalkBubble` and `resetAllFaces`).
         */
        public val resetDialogueScreen: () -> Unit = {},
        /** `APProc_DeleteAll()` (stand-in until D11-wm9). */
        public val apProcDeleteAll: () -> Unit = {},
    )

    private companion object {
        /** `GM_STATE_MONSGEN` (`monster_merged`). */
        const val GM_STATE_MONSTER_MERGED = 0x02

        /** `GM_STATE_SKIPPED` (`skip_wm`). */
        const val GM_STATE_SKIPPED = 0x40

        /** `GM_STATE_NOFADE` (`no_fade`). */
        const val GM_STATE_NOFADE = 0x80

        /** `state_0`, `skip_wm` and `no_fade`. */
        const val EXIT_CLEARED_BITS = 0x01 or GM_STATE_SKIPPED or GM_STATE_NOFADE

        /** Label 15 of `ProcScr_WorldMapMain`: `WorldMap_CallIntroEvent`. */
        const val LABEL_INTRO_EVENT = 15

        /** `GMAP_NODE_PLACEMENT_DUNGEON`. */
        const val PLACEMENT_DUNGEON = 3

        /** `gmapEventId` in `struct ROMChapterData`. */
        const val OFF_GMAP_EVENT_ID = 0x75

        /** GBA address of `Events_WM_ChapterIntro`. */
        const val EVENTS_WM_CHAPTER_INTRO = 0x088B3AD8
    }
}
