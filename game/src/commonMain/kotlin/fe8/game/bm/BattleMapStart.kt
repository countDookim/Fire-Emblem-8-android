package fe8.game.bm

import gbafe.core.flags.EventFlags
import gbafe.core.rng.RngService
import gbafe.core.time.GameClock
import gbafe.proc.Proc
import gbafe.proc.ProcCmd
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.endDuplicates
import gbafe.proc.ProcScripts.goto
import gbafe.proc.ProcScripts.label
import gbafe.proc.ProcScripts.mark
import gbafe.proc.ProcScripts.name
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.setEndCb
import gbafe.proc.ProcScripts.sleep
import gbafe.proc.ProcScripts.yield
import gbafe.render.BgHardware

/**
 * The battle map start path of `src/bmio.c`: [startBattleMap] (with [clearBattleMapState]), and
 * [startBMapMain] / [endBMapMain] with the procs they start (`gProc_BMapMain` on tree 2, the VBlank
 * proc `sProc_BMVSync` on tree 0 and `gProc_MapTask` on tree 4). `GameControl.Hooks.startBattleMap`
 * is wired to [startBattleMap] by the boot.
 *
 * Ported: everything [startBattleMap] does to `gPlaySt`, `gBmSt`, the chapter flags, the map pools
 * ([BmMap.initChapterMap]) and the BG/OBJ graphics ([ChapterMapGfx]), the proc starts of
 * [startBMapMain], and the `gProc_MapTask` script 1:1. `SetMainUpdateRoutine(OnMain)` and
 * `SetInterrupt_LCDVBlank(OnVBlank)` are what `GameLoop` always runs, so there is nothing to
 * switch. Stand-ins ([Hooks], all no-ops by default): the unit sprite and menu override resets,
 * the trap lists, temporary units, map obstacles, the sprite OAM / weather effect calls and
 * the map animation calls of the procs, and `GetBattleMapKind` (needs world map state). The
 * `gProc_BMapMain` script is [Hooks.bmapMainScript]: a placeholder here, [BMapMain.script] in
 * the boot.
 */
public class BattleMapStart(
    /** The proc engine the procs are started on. */
    private val engine: ProcEngine,
    /** `gPlaySt`. */
    private val playSt: PlaySt,
    /** `gBmSt`. */
    private val bmSt: BmSt,
    /** BG hardware state `SetupBackgrounds` writes. */
    private val hardware: BgHardware,
    /** The chapter table (`GetROMChapterStruct`). */
    private val chapterData: ChapterData,
    /** The chapter map model [startBattleMap] initialises. */
    private val map: BmMap,
    /** The chapter graphics loaders. */
    private val gfx: ChapterMapGfx,
    /** `UpdateBmMapDisplay`, run every frame by the map task. */
    private val render: BmMapRender,
    /** `gPermanentFlagBits` / `gChapterFlagBits` ([EventFlags.resetChapterFlags]). */
    private val flags: EventFlags,
    /** `NextRN_100` for the skirmish vision roll. */
    private val rng: RngService,
    /** `GetGameClock`. */
    private val clock: GameClock,
    /** The raw unit arrays the deploy loop of [startBattleMap] updates (null: no units). */
    private val units: UnitTable? = null,
    /** The stand-ins. */
    public val hooks: Hooks = Hooks(),
) {
    /** The calls that are not ported yet; every one does nothing by default. */
    public class Hooks {
        /** `ApplyUnitSpritePalettes`. Stand-in. */
        public var applyUnitSpritePalettes: () -> Unit = {}

        /** `ResetUnitSprites`. Stand-in. */
        public var resetUnitSprites: () -> Unit = {}

        /** `ResetMenuOverrides`. Stand-in. */
        public var resetMenuOverrides: () -> Unit = {}

        /** `ClearTraps`. Stand-in. */
        public var clearTraps: () -> Unit = {}

        /** `InitMapObstacles`. Stand-in. */
        public var initMapObstacles: () -> Unit = {}

        /** `ClearTemporaryUnits`. Stand-in. */
        public var clearTemporaryUnits: () -> Unit = {}

        /** `LoadChapterTraps`. Stand-in. */
        public var loadChapterTraps: () -> Unit = {}

        /**
         * `GetBattleMapKind` ([BattleMapKind]): needs the world map state, so the default answers
         * [BattleMapKind.STORY] for every chapter (no skirmish vision roll). Stand-in.
         */
        public var getBattleMapKind: () -> Int = { BattleMapKind.STORY }

        /**
         * The `gProc_BMapMain` script. Placeholder; [BMapMain.script] replaces it: it has the name,
         * `PROC_15` and the main mark of the real one and then only yields. A replacement must
         * use [BMapMainState] as its `newState`.
         */
        public var bmapMainScript: ProcScript = BMAPMAIN_PLACEHOLDER

        /** `PutUnitSpritesOam`, first call of every `gProc_MapTask` frame. Stand-in. */
        public var putUnitSpritesOam: () -> Unit = {}

        /** `WfxUpdate`, second call of every `gProc_MapTask` frame. Stand-in. */
        public var wfxUpdate: () -> Unit = {}

        /** `BMapVSync_InitMapAnimations` and `WfxInit` (run by `BMapVSync_Start`). Stand-in. */
        public var initMapAnimations: () -> Unit = {}

        /** `BMapVSync_UpdateMapImgAnimations`. Stand-in. */
        public var updateMapImgAnimations: () -> Unit = {}

        /** `BMapVSync_UpdateMapPalAnimations`. Stand-in. */
        public var updateMapPalAnimations: () -> Unit = {}

        /** `SyncUnitSpriteSheet`. Stand-in. */
        public var syncUnitSpriteSheet: () -> Unit = {}

        /** `WfxVSync`. Stand-in. */
        public var wfxVSync: () -> Unit = {}

        /** `BMapVSync_OnEnd` (clears the secondary HBlank handler). Stand-in. */
        public var bmapVSyncOnEnd: () -> Unit = {}
    }

    /** The state of the `gProc_BMapMain` proc (`struct BMapMainProc` as far as it is used). */
    public class BMapMainState {
        /** `gameCtrl`: the game control proc, whose `lockCnt` the proc holds. */
        public var gameCtrl: Proc? = null
    }

    /**
     * `sProc_BMVSync` (ROM 0x0859D8B8, 10 commands): the VBlank proc, started on tree 0. The calls
     * are [Hooks] stand-ins.
     */
    public val vSyncScript: ProcScript = ProcScript(
        "sProc_BMVSync",
        listOf(
            mark(PROC_MARK_DISP),
            setEndCb(ProcFunc { hooks.bmapVSyncOnEnd() }),
            sleep(0),
            label(0),
            call(ProcFunc { hooks.updateMapImgAnimations() }),
            call(ProcFunc { hooks.updateMapPalAnimations() }),
            call(ProcFunc { hooks.syncUnitSpriteSheet() }),
            call(ProcFunc { hooks.wfxVSync() }),
            repeat(ProcFunc { engine.goto(it, 0) }), // BMapVSync_OnLoop
            end(),
        ),
    )

    /**
     * `gProc_MapTask` (bmio.c:139, ROM 0x0859D908, 10 commands): the map task, started on tree 4;
     * every frame it runs `PutUnitSpritesOam` and `WfxUpdate` (stand-ins) and then the ported
     * [BmMapRender.updateBmMapDisplay].
     */
    public val mapTaskScript: ProcScript = ProcScript(
        "gProc_MapTask",
        listOf(
            name("MAPTASK"),
            endDuplicates(),
            mark(PROC_MARK_DISP),
            yield(),
            label(0),
            call(ProcFunc { hooks.putUnitSpritesOam() }),
            call(ProcFunc { hooks.wfxUpdate() }),
            call(ProcFunc { render.updateBmMapDisplay() }),
            yield(),
            goto(0),
        ),
    )

    /**
     * Ported from src/bmio.c: ClearBattleMapState
     *
     * Zeroes `gBmSt` except `lock`.
     */
    public fun clearBattleMapState() {
        val lock = bmSt.lock
        bmSt.decode(ZERO_BM_ST, 0)
        bmSt.lock = lock
    }

    /**
     * Ported from src/bmio.c: StartBattleMap
     *
     * Sets up the BG layers, clears `gBmSt`, loads the UI graphics, resets the chapter flags,
     * puts the play state at the start of the chapter (green phase, turn 0, vision and weather of
     * the chapter), initialises the chapter map, marks the units not deployed that have
     * `US_BIT21` (the raw [units]), and starts the battle map procs ([startBMapMain]) when
     * [gameCtrl] is not null. Ends with the backdrop and blend setup.
     */
    public fun startBattleMap(gameCtrl: Proc?) {
        hardware.setupBackgrounds(null, playSt.chapterWeatherId == ChapterMapGfx.WEATHER_CLOUDS)

        clearBattleMapState()
        gfx.sub80156D4()
        hooks.applyUnitSpritePalettes()
        flags.resetChapterFlags()
        hooks.resetUnitSprites()
        hooks.resetMenuOverrides()
        hooks.clearTraps()

        playSt.faction = FACTION_GREEN
        playSt.chapterTurnNumber = 0

        if (hooks.getBattleMapKind() == BattleMapKind.SKIRMISH) {
            if (playSt.chapterStateBits and PLAY_FLAG_PREPSCREEN == 0) {
                playSt.chapterVisionRange = 3 * (rng.next100() and 1)
            }
        } else {
            playSt.chapterVisionRange = chapterData.initialFogLevel(playSt.chapterIndex)
        }

        playSt.chapterWeatherId = chapterData.initialWeather(playSt.chapterIndex)

        gfx.initBmBgLayers()
        map.initChapterMap(playSt.chapterIndex)
        // `InitChapterMap` also unpacks the tileset graphics and palette (BmMap does not).
        gfx.unpackChapterMapGraphics(playSt.chapterIndex)
        hooks.initMapObstacles()

        playSt.timeChapterStarted = clock.get().toInt()
        playSt.chapterTotalSupportGain = 0

        playSt.unk48 = 0
        playSt.unk4A1 = 0
        playSt.tutorialCounter = 0
        playSt.tutorialExecType = 0

        val table = units
        if (table != null) {
            for (id in 1 until 0x40) {
                if (!table.isValid(id)) continue
                val state = table.state(id)
                val deployed = if (state and US_BIT21 != 0) {
                    state or US_NOT_DEPLOYED
                } else {
                    state and US_NOT_DEPLOYED.inv()
                }
                table.setState(id, deployed)
            }
        }

        hooks.clearTemporaryUnits()
        hooks.loadChapterTraps()

        if (gameCtrl != null) startBMapMain(gameCtrl)

        hardware.palette[0] = 0
        hardware.enablePaletteSync()

        val lcd = hardware.lcd
        lcd.setBlendTargetA(1, 1, 1, 1, 1)
        lcd.bldcnt = lcd.bldcnt or 0x20 // SetBlendBackdropA(TRUE)
        lcd.setBlendConfig(3, 0, 0, 0x10)
    }

    /**
     * Ported from src/bmio.c: StartBMapMain
     *
     * Starts `gProc_BMapMain` on tree 2 holding [gameCtrl]'s `lockCnt`, the VBlank proc
     * ([vSyncScript], tree 0, before the map task) and the map task ([mapTaskScript], tree 4).
     * Returns the BMapMain proc.
     */
    public fun startBMapMain(gameCtrl: Proc): Proc {
        val mapMain = engine.start(hooks.bmapMainScript, PROC_TREE_2)
        (mapMain.state as BMapMainState).gameCtrl = gameCtrl
        gameCtrl.lockCnt++

        // BMapVSync_Start
        engine.start(vSyncScript, PROC_TREE_VSYNC)
        hooks.initMapAnimations()
        bmSt.gameGfxSemaphore = 0

        engine.start(mapTaskScript, PROC_TREE_4)
        return mapMain
    }

    /**
     * Ported from src/bmio.c: EndBMapMain
     *
     * Ends every `PROC_MARK_DISP` proc, releases the game control lock and ends BMapMain.
     */
    public fun endBMapMain() {
        engine.endEachMarked(PROC_MARK_DISP)
        val mapMain = engine.find(hooks.bmapMainScript)!!
        (mapMain.state as BMapMainState).gameCtrl!!.lockCnt--
        engine.end(mapMain)
    }

    /** Constants. */
    public companion object {
        /** `PROC_MARK_DISP`. */
        public const val PROC_MARK_DISP: Int = 1

        /** `PROC_MARK_MAIN`. */
        public const val PROC_MARK_MAIN: Int = 2

        /** `PROC_TREE_VSYNC`. */
        public const val PROC_TREE_VSYNC: Int = 0

        /** `PROC_TREE_2`. */
        public const val PROC_TREE_2: Int = 2

        /** `PROC_TREE_4`. */
        public const val PROC_TREE_4: Int = 4

        /** `FACTION_GREEN` (`gPlaySt.faction` of the NPC phase). */
        public const val FACTION_GREEN: Int = 0x40

        /** `US_NOT_DEPLOYED` of `Unit.state`. */
        public const val US_NOT_DEPLOYED: Int = 1 shl 3

        /** `US_BIT21` of `Unit.state`. */
        public const val US_BIT21: Int = 1 shl 21

        /** `PLAY_FLAG_PREPSCREEN` of `gPlaySt.chapterStateBits`. */
        public const val PLAY_FLAG_PREPSCREEN: Int = 1 shl 4

        private val ZERO_BM_ST = ByteArray(0x40)

        /** Placeholder for `gProc_BMapMain` until M5-44. */
        private val BMAPMAIN_PLACEHOLDER = ProcScript(
            "gProc_BMapMain",
            listOf(
                sleep(0),
                name("E_BMAPMAIN"),
                ProcCmd(0x15), // PROC_15
                mark(PROC_MARK_MAIN),
                sleep(0),
                label(0),
                sleep(0),
                goto(0),
            ),
            newState = { BMapMainState() },
        )
    }
}
