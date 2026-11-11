package fe8.game.worldmap

import fe8.game.GameControl
import fe8.game.RomView
import fe8.game.bm.BMapMain.Companion.FACTION_BLUE
import fe8.game.bm.BMapMain.Companion.FACTION_GREEN
import fe8.game.bm.BattleMapStart
import fe8.game.bm.BmSt
import fe8.game.bm.PlaySt
import fe8.game.bm.UnitTable
import fe8.game.ui.UiFrame
import gbafe.render.BgHardware
import gbafe.proc.Proc
import gbafe.proc.ProcArgFunc
import gbafe.proc.ProcCmd
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.callArg
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.endEach
import gbafe.proc.ProcScripts.label
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.setEndCb
import gbafe.proc.ProcScripts.sleep
import gbafe.proc.ProcScripts.whileExists
import gbafe.proc.ProcScripts.yield

/**
 * The fields of `struct WorldMapMainProc` ported so far. The rest of the struct and the whole
 * `ProcScr_WorldMapMain` script are not ported yet.
 */
public class WorldMapMainProc {
    /** `gm_icon` (+0x48): the `ProcScr_GmNodeIconDisplay` proc. */
    public var gmIcon: GmNodeIconDisplayProc = GmNodeIconDisplayProc()

    /** `unk_3a` (+0x3A), cleared by `sub_80B8E60`. */
    public var unk3a: Int = 0

    /** `flags_1` (bit 1 of the byte at +0x29). */
    public var flags1: Int = 0

    /** `timer` (+0x2C). */
    public var timer: Int = 0

    /** `scrolling` (+0x30): bit 0 is set while no [WorldMapScroll] scroll runs. */
    public val scrolling: GmScrollFlags = GmScrollFlags()
}

/** The fields of `struct GmNodeIconDisplayProc` that `src/worldmap_main.c` writes. */
public class GmNodeIconDisplayProc {
    /** `merge_next_node` (bit 1 of the byte at +0x32). */
    public var mergeNextNode: Boolean = false

    /** `nodeId` (+0x33). */
    public var nodeId: Int = 0
}

/**
 * The world map entry wrapper: [wrapperScript] starts [mainScript] through [newWorldMap].
 *
 * @param engine the proc scheduler
 * @param playSt `gPlaySt`
 * @param gmData `gGMData`
 * @param mainScript `ProcScr_WorldMapMain`; its `newState` must create a [WorldMapMainProc]
 * @param fadeInScript `ProcScr_BmFadeIN`
 */
public class WorldMapMain(
    private val engine: ProcEngine,
    private val playSt: PlaySt,
    private val gmData: GmData,
    private val mainScript: ProcScript,
    private val fadeInScript: ProcScript,
) {
    /**
     * Transcribed from src/worldmap_main.c: ProcScr_WorldMapWrapper (not verified: script order not
     * yet checked against a reference run).
     */
    public val wrapperScript: ProcScript = ProcScript(
        "ProcScr_WorldMapWrapper",
        listOf(
            sleep(1),
            call(ProcFunc { newWorldMap() }),
            yield(),
            whileExists { mainScript },
            endEach { fadeInScript },
            yield(),
            end(),
        ),
    )

    /**
     * Starts [mainScript] in proc tree 3 and sets its `flags_1` and `timer`: 4 after the
     * postgame flag, else 12 once the monsters are merged, else 16.
     *
     * Ported from src/worldmap_main.c: NewWorldMap
     */
    public fun newWorldMap(): Proc {
        val proc = engine.start(mainScript, 3)
        val state = proc.state as WorldMapMainProc
        state.flags1 = 0
        state.timer = when {
            playSt.chapterStateBits and GameControl.PLAY_FLAG_POSTGAME != 0 -> 4
            gmData.monsterMerged() -> 12
            else -> 16
        }
        return proc
    }

    /**
     * Transcribed from src/worldmap_main.c: DeployEveryUnit (not verified: no unit has
     * `US_NOT_DEPLOYED` set in the prologue trace, so it cannot tell this from a no-op)
     *
     * Clears `US_NOT_DEPLOYED` of every valid unit with id in `FACTION_BLUE + 1 until
     * FACTION_GREEN`.
     */
    public fun deployEveryUnit(units: UnitTable) {
        for (id in FACTION_BLUE + 1 until FACTION_GREEN) {
            if (!units.isValid(id)) continue
            units.setState(id, units.state(id) and BattleMapStart.US_NOT_DEPLOYED.inv())
        }
    }

    /**
     * Ported from src/worldmap_main.c: sub_80B8FD4
     *
     * Sets `gGMData.state.bits.state_0` (bit 0) and clears `state_3` (bit 3). The trace has
     * `state_3` already 0 before, so only the `state_0` write is verified.
     */
    public fun sub80B8FD4() {
        gmData.stateRaw = (gmData.stateRaw or 0x01) and 0x08.inv()
    }

    /**
     * Ported from src/worldmap_main.c: sub_80B8FEC
     *
     * Points the node icon at the next uncleared node and enables merging, or disables merging
     * if there is none. The prologue trace only covers the no-node branch (node 0 is cleared but
     * not valid there), so `nodeId` assignment is not verified.
     *
     * @param getNextUnclearedNode `GetNextUnclearedNode(&gGMData)`
     */
    public fun sub80B8FEC(proc: WorldMapMainProc, getNextUnclearedNode: () -> Int) {
        val id = getNextUnclearedNode()
        if (id >= 0) {
            proc.gmIcon.nodeId = id
            proc.gmIcon.mergeNextNode = true
        } else {
            proc.gmIcon.mergeNextNode = false
        }
    }

    /**
     * Transcribed from src/worldmap_main.c: WmMain_MoveCamera (not verified: `gGMData.ix/iy` stay
     * 0 and the camera constant in every prologue dump, so no trace covers any branch)
     *
     * Scrolls `gGMData.xCamera/yCamera` so the cursor `ix/iy >> 8` stays inside the
     * 0x20..0xD0 (x) and 0x20..0x80 (y) margins, clamped to 0..0xF0 and 0..0xA0.
     */
    public fun wmMainMoveCamera() {
        val a = (gmData.ix shr 8).toShort().toInt()
        val b = (gmData.iy shr 8).toShort().toInt()
        var c = gmData.xCamera
        var d = gmData.yCamera
        var delta = (a - c).toShort().toInt()
        if (delta < 0x20) {
            c = if (a >= 0x20) a - 0x20 else 0
        } else if (delta > 0xD0) {
            c = if (a < 0x1C0) a - 0xD0 else 0xF0
        }
        delta = (b - d).toShort().toInt()
        if (delta < 0x20) {
            d = if (b >= 0x20) b - 0x20 else 0
        } else if (delta > 0x80) {
            d = if (b < 0x120) b - 0x80 else 0xA0
        }
        gmData.xCamera = c
        gmData.yCamera = d
    }

    /**
     * Ported from src/worldmap_main.c: WorldMap_CallBeginningEvent
     *
     * Real callees come from [WorldMapBeginning] (`Sound_FadeOutBGM`, `Events_WM_Beginning`
     * lookup, `ResetGmStoryNode`, `merge_next_node`, `CallEvent`). Still stand-ins: `sub_80BA008`
     * (monster-merged/postgame branch, M6-I), `StartWMFaceCtrl` (D11-wm8d) and
     * `StartGmapMuEntry` (D11-wm3e).
     *
     * @param nodes `gWMNodeData`
     * @param checkFlag `CheckFlag`
     * @param hooks the unported callees
     */
    public fun callBeginningEvent(
        proc: WorldMapMainProc,
        nodes: WmNodeData,
        checkFlag: (Int) -> Boolean,
        hooks: BeginningHooks,
    ) {
        hooks.fadeOutBgm(4)
        val postgame = playSt.chapterStateBits and GameControl.PLAY_FLAG_POSTGAME != 0
        if (gmData.monsterMerged() || postgame) {
            hooks.sub80BA008(proc.timer)
        } else {
            val loc = gmData.unitLocation(0)
            gmData.currentNode = loc
            val nodeNext = nodes.nextLocId(loc, playSt.chapterModeIndex, checkFlag)
            if (nodeNext > -1) {
                val chIndex = nodes.chapterId(nodeNext, playSt.chapterModeIndex)
                playSt.chapterIndex = chIndex
                val event = hooks.beginningEvent(chIndex) ?: return
                hooks.resetGmStoryNode()
                hooks.clearMergeNextNode()
                hooks.callEvent(event)
            }
        }
        hooks.startWmFaceCtrl()
        hooks.startGmapMuEntry()
    }

    /**
     * Transcribed from src/worldmap_main.c: ProcScr_WorldMapMain (not verified: prefix, up to and
     * including `PROC_REPEAT(WorldMap_PostBeginningEvent)`; the rest is not transcribed yet).
     *
     * @param nodes `gWMNodeData`
     * @param checkFlag `CheckFlag`
     * @param hooks the unported callees of `WorldMap_CallBeginningEvent`
     * @param mainHooks the unported commands of the script
     */
    public fun mainScriptPrefix(
        nodes: WmNodeData,
        checkFlag: (Int) -> Boolean,
        hooks: BeginningHooks = BeginningHooks(),
        mainHooks: MainHooks = MainHooks(),
    ): List<ProcCmd> = listOf(
        setEndCb(ProcFunc { mainHooks.destruct(it) }),
        call(ProcFunc { sub80B8FD4() }),
        call(ProcFunc { mainHooks.init(it) }),
        callArg(
            ProcArgFunc { arg, _ ->
                mainHooks.fadeBgmOut(arg)
                true
            },
            1,
        ),
        sleep(4),
        call(
            ProcFunc { callBeginningEvent(it.state as WorldMapMainProc, nodes, checkFlag, hooks) },
        ),
        sleep(1),
        label(0),
        label(1),
        repeat(ProcFunc { mainHooks.postBeginningEvent(it) }),
    )

    /** Script commands of `ProcScr_WorldMapMain` whose callees are not ported yet. */
    public class MainHooks(
        /** `WorldMap_Destruct` (`PROC_SET_END_CB`). */
        public val destruct: (Proc) -> Unit = {},
        /** `WorldMap_Init`. */
        public val init: (Proc) -> Unit = {},
        /** `_FadeBgmOut(speed)`. */
        public val fadeBgmOut: (Int) -> Unit = {},
        /** `WorldMap_PostBeginningEvent` (`PROC_REPEAT`; the engine ends the repeat itself). */
        public val postBeginningEvent: (Proc) -> Unit = {},
    )

    /** Callees of `WorldMap_CallBeginningEvent` that are not ported yet. */
    public class BeginningHooks(
        /** `Sound_FadeOutBGM(speed)`. */
        public val fadeOutBgm: (Int) -> Unit = {},
        /** `sub_80BA008(timer)`: the monster-merged/postgame branch. */
        public val sub80BA008: (Int) -> Unit = {},
        /** `Events_WM_Beginning[GetROMChapterStruct(chIndex)->gmapEventId]`, null if NULL. */
        public val beginningEvent: (Int) -> Int? = { null },
        /** `ResetGmStoryNode()`. */
        public val resetGmStoryNode: () -> Unit = {},
        /** `proc->gm_icon->merge_next_node = false`. */
        public val clearMergeNextNode: () -> Unit = {},
        /** `CallEvent(Events_WM_Beginning[...], 0)` with the event id from [beginningEvent]. */
        public val callEvent: (Int) -> Unit = {},
        /** `StartWMFaceCtrl(proc)`. */
        public val startWmFaceCtrl: () -> Unit = {},
        /** `StartGmapMuEntry(NULL)`. */
        public val startGmapMuEntry: () -> Unit = {},
    )
}

/**
 * The graphics setup that opens `WorldMap_Init`: `SetupGraphicSystemsForWorldMap` and
 * `sub_80B8E60` (with `SetupGmapNodeGfx`). `WorldMap_Init` itself is still the
 * [WorldMapMain.MainHooks] stand-in until D11-wm9; nothing calls these two yet.
 *
 * `GetSaveWriteAddr(SAVE_ID_XMAP)` only returns an address and has no side effect, so it is not
 * ported. The `for` loop over `gGMData.nodes` that writes `gm_icon` belongs to `sub_80B9114`
 * (later in `WorldMap_Init`, after `gm_icon` exists), not to `sub_80B8E60`.
 *
 * @param hardware BG buffers, VRAM, palette buffer and `gLCDControlBuffer`
 * @param rom the ROM the graphics are read from
 * @param playSt `gPlaySt` (`chapterWeatherId`, `windowColor`)
 * @param bmSt `gBmSt` (camera)
 * @param hooks callees that live elsewhere or are not ported
 */
public class WorldMapGfxSetup(
    private val hardware: BgHardware,
    private val rom: RomView,
    private val playSt: PlaySt,
    private val bmSt: BmSt,
    private val hooks: Hooks = Hooks(),
) {
    /** `gGenericBuffer`: scratch for the decompressed images. */
    private val genericBuffer = ByteArray(GENERIC_BUFFER_SIZE)

    /**
     * Ported from src/worldmap_main.c: SetupGraphicSystemsForWorldMap
     *
     * Resets the BGs (priorities 0, 1, 3, 3), clears the four tilemap buffers, turns the display
     * layers off and resets text, faces, unit sprites, MUs and the camera.
     */
    public fun setupGraphicSystemsForWorldMap() {
        hardware.setupBackgrounds(null, playSt.chapterWeatherId == WEATHER_CLOUDS)
        hardware.lcd.setDispEnable(0, 0, 0, 0, 0)
        fillTilemaps()
        val bgCnt = hardware.lcd.bgCnt
        for ((bg, priority) in intArrayOf(0, 1, 3, 3).withIndex()) {
            bgCnt[bg] = (bgCnt[bg] and 3.inv()) or priority
        }
        // GetSaveWriteAddr(SAVE_ID_XMAP) only computes an address: no side effect.
        hooks.loadLegacyUiFrameGraphics()
        hooks.resetText()
        hooks.resetFaces()
        val tileOffsets = IntArray(FACE_SLOTS) { rom.u32(FACE_CONFIG + 8 * it) }
        val paletteIds = IntArray(FACE_SLOTS) { rom.u32(FACE_CONFIG + 8 * it + 4) }
        hooks.setupFaceGfxData(tileOffsets, paletteIds)
        hooks.resetUnitSprites()
        hooks.initMus()
        hooks.applyUnitSpritePalettes()
        bmSt.cameraX = 0
        bmSt.cameraY = 0
    }

    /**
     * Ported from src/worldmap_main.c: SetupGmapNodeGfx
     *
     * Loads the node palettes (banks 0x13-0x14), the node tiles at OBJ VRAM `+0x1000` and the
     * castle node tiles (0xC x 4 tiles) at `+0x280`.
     */
    public fun setupGmapNodeGfx() {
        applyPalettes(PAL_NODES, 0x13, 2)
        rom.decompressInto(IMG_GMAP_NODES, hardware.vram, OBJ_VRAM + 0x1000)
        rom.decompressInto(IMG_GMAP_CASTLE_NODES, genericBuffer, 0)
        copy2dChr(genericBuffer, OBJ_VRAM + 0x280, 0xC, 4)
    }

    /**
     * Ported from src/worldmap_main.c: sub_80B8E60
     *
     * Clears the tilemaps, loads the UI, node and path graphics and sets the window, blend and
     * HBlank state of the world map.
     */
    public fun sub80B8E60(proc: WorldMapMainProc) {
        proc.unk3a = 0
        val lcd = hardware.lcd
        lcd.setBlendConfig(0, 0x10, 0, 0) // SetDefaultColorEffects
        fillTilemaps()
        hardware.enableBgSyncByMask(0xF)
        applyPalettes(PAL_MISC_UI, 0x10, 2)
        applyPalettes(PAL_MISC_UI, 0x1B, 1)
        rom.decompressInto(GFX_MISC_UI_WM, genericBuffer, 0)
        copy2dChr(genericBuffer, OBJ_VRAM + 0x5300, 8, 2)
        setupGmapNodeGfx()
        applyPalettes(PAL_PATH, 0xE, 1)
        rom.decompressInto(IMG_GMAP_PATH, hardware.vram, 0x5000)
        UiFrame.loadUiFrameGraphics(hardware, rom, playSt.windowColor)
        hooks.resetText()
        hooks.resetTextFont()
        lcd.setWinEnable(0, 0, 0)
        lcd.setWOutLayers(1, 1, 1, 1, 1)
        lcd.setWincntBit(WIN0_ENABLE_BLEND, true)
        lcd.setWincntBit(WIN1_ENABLE_BLEND, true)
        lcd.setWincntBit(WOUT_ENABLE_BLEND, true)
        // SetBlankBgColor(0, 0, 0)
        hardware.palette[0] = 0
        hardware.enablePaletteSync()
        lcd.setBlendConfig(0, 0, 0, 0)
        lcd.setBlendTargetA(0, 0, 0, 0, 0)
        lcd.setBlendTargetB(0, 0, 0, 1, 1)
        hooks.setWorldMapHblank()
    }

    /** `BG_Fill(gBGnTilemapBuffer, 0)` for the four BGs. */
    private fun fillTilemaps() {
        for (bg in 0..3) hardware.fill(hardware.mapBuffer(bg), 0)
    }

    private fun applyPalettes(src: Int, palId: Int, count: Int) {
        rom.pal(src, 16 * count).copyInto(hardware.palette, palId * 16)
        hardware.enablePaletteSync()
    }

    /** `Copy2dChr`: rows of [widthInTiles] tiles from packed [source] to rows 0x20 tiles apart. */
    private fun copy2dChr(
        source: ByteArray,
        destOffset: Int,
        widthInTiles: Int,
        heightInTiles: Int,
    ) {
        val lineSize = widthInTiles * CHR_SIZE
        for (row in 0 until heightInTiles) {
            source.copyInto(
                hardware.vram, destOffset + row * CHR_SIZE * 0x20, row * lineSize,
                (row + 1) * lineSize,
            )
        }
    }

    /**
     * Callees of the two functions that are ported elsewhere or not at all. Each is a no-op by
     * default.
     */
    public class Hooks(
        /** `LoadLegacyUiFrameGraphics`: stand-in until D11-wm9 (world-map wiring). */
        public val loadLegacyUiFrameGraphics: () -> Unit = {},
        /** `ResetText`: stand-in until D11-wm9 (world-map wiring). */
        public val resetText: () -> Unit = {},
        /** `ResetTextFont`: stand-in until D11-wm9 (world-map wiring). */
        public val resetTextFont: () -> Unit = {},
        /** `ResetFaces`: stand-in until D11-wm9 (world-map wiring). */
        public val resetFaces: () -> Unit = {},
        /** `SetupFaceGfxData`: ported as `TalkFace.setupFaceGfxData`; tile offsets, palette ids. */
        public val setupFaceGfxData: (IntArray, IntArray) -> Unit = { _, _ -> },
        /** `ResetUnitSprites`: ported as `UnitSprites.resetUnitSprites`. */
        public val resetUnitSprites: () -> Unit = {},
        /** `InitMus`: ported as `Mu.initMus`. */
        public val initMus: () -> Unit = {},
        /** `ApplyUnitSpritePalettes`: ported as `UnitSprites.applyUnitSpritePalettes`. */
        public val applyUnitSpritePalettes: () -> Unit = {},
        /** `SetWorldMapHblank`: stand-in until D11-wm9 (world map HBlank, worldmap_hb.c). */
        public val setWorldMapHblank: () -> Unit = {},
    )

    /** ROM addresses and layout constants. */
    public companion object {
        private const val GENERIC_BUFFER_SIZE = 0x2000
        private const val FACE_SLOTS = 4
        private const val CHR_SIZE = 0x20
        private const val OBJ_VRAM = 0x10000
        private const val WEATHER_CLOUDS = 7
        private const val WIN0_ENABLE_BLEND = 5
        private const val WIN1_ENABLE_BLEND = 13
        private const val WOUT_ENABLE_BLEND = 21

        /** `gUnknown_08A3D728`: the world map face config (tile offset, palette id) x 4. */
        public const val FACE_CONFIG: Int = 0x08A3D728

        /** `gUnknown_08A97A40`: node palettes. */
        public const val PAL_NODES: Int = 0x08A97A40

        /** `Img_GmapNodes`. */
        public const val IMG_GMAP_NODES: Int = 0x08A96308

        /** `Img_GmapCastleNodes`. */
        public const val IMG_GMAP_CASTLE_NODES: Int = 0x08A97410

        /** `gPal_MiscUiGraphics`. */
        public const val PAL_MISC_UI: Int = 0x0859ED70

        /** `gUnknown_08AA11D0`: the world map's misc UI image. */
        public const val GFX_MISC_UI_WM: Int = 0x08AA11D0

        /** `gUnknown_08A97FA4`: path palette. */
        public const val PAL_PATH: Int = 0x08A97FA4

        /** `Img_GmapPath`. */
        public const val IMG_GMAP_PATH: Int = 0x08A97ED8
    }
}
