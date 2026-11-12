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
import gbafe.render.ApHandle
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
 * The fields of `struct WorldMapMainProc` ported so far: the state of the proc that runs the
 * whole world map screen (`ProcScr_WorldMapMain`, `src/worldmap_main.c`). The rest of the
 * struct and the whole `ProcScr_WorldMapMain` script are not ported yet.
 */
public class WorldMapMainProc {
    /** `gm_icon` (+0x48): the state of the `ProcScr_GmNodeIconDisplay` proc (node icons). */
    public var gmIcon: GmNodeIconDisplayProc = GmNodeIconDisplayProc()

    /** `gm_screen` (+0x44): the `ProcScr_GMapScreen` proc (set by `WorldMap_Init`). */
    public var gmScreen: Proc? = null

    /** `gm_unitc` (+0x4C): the `ProcScr_GmapUnitContainer` proc (the unit sprites). */
    public var gmUnitc: Proc? = null

    /** `gm_cursor` (+0x50): the `gProcScr_GmapCursor` proc. */
    public var gmCursor: Proc? = null

    /** `gm_mu` (+0x54): the `ProcScr_GMapMu` proc (the map units, `GM_MU`). */
    public var gmMu: Proc? = null

    /** The `ProcScr_GmNodeIconDisplay` proc whose state is [gmIcon] (not a struct field). */
    public var gmIconProc: Proc? = null

    /** `delay_timer` (+0x2A, `s8`), cleared by `WorldMap_Init`. */
    public var delayTimer: Int = 0

    /** `unk_3a` (+0x3A, `u16`), cleared by `sub_80B8E60`; meaning unknown. */
    public var unk3a: Int = 0

    /** `flags_1` (bit 1 of the byte at +0x29), cleared by `NewWorldMap`; meaning unknown. */
    public var flags1: Int = 0

    /**
     * `unk_3e` (+0x3E, `u8`): the node whose chapter `WorldMap_CallIntroEvent` starts
     * (`WMLoc_GetChapterId(unk_3e)`); set to the leader's node (`gGMData.units[0].location`) or to
     * a story node the leader reaches.
     */
    public var unk3e: Int = 0

    /**
     * `timer` (+0x2C): set by `NewWorldMap` (4 after the postgame flag, 12 once the monsters are
     * merged, else 16) and passed on to `sub_80BA008`; meaning unknown.
     */
    public var timer: Int = 0

    /**
     * `scrolling` (+0x30): bit 0 is set while no [WorldMapScroll] scroll runs
     * (`CheckGmScrolling` returns `!(scrolling & 1)`).
     */
    public val scrolling: GmScrollFlags = GmScrollFlags()
}

/**
 * The fields of `struct GmNodeIconDisplayProc` (`ProcScr_GmNodeIconDisplay`, see
 * [WorldMapNodeIcons]): the proc that draws the icon of every node whose state bit 0 is set on
 * the world map, and the flag sprite of the next node.
 */
public class GmNodeIconDisplayProc {
    /** `chr` (+0x2C): VRAM byte offset of the node icon tiles (divided by `CHR_SIZE` to draw). */
    public var chr: Int = 0

    /** `pal` (+0x30): the OBJ palette bank. */
    public var pal: Int = 0

    /**
     * `unk_31` (+0x31): the `unk` argument of `StartGmNodeIconDisplay`; the decomp never reads
     * it, meaning unknown.
     */
    public var unk31: Int = 0

    /**
     * `skip` (bit 0 of the byte at +0x32): the loop draws only while this is set (the decomp
     * name is the opposite of what the loop does; `GmapScreen2_Init` sets it).
     */
    public var skip: Boolean = false

    /** `unk_34` (+0x34): one bit per node id; a set bit draws that node's icon translucent. */
    public var unk34: Int = 0

    /**
     * `ap` (+0x38): the sprite drawn at node [nodeId]'s icon flag origin while [mergeNextNode] is
     * set.
     */
    public var ap: ApHandle? = null

    /** `pScreenProc` (+0x3C): the screen proc whose scroll position the icons are drawn against. */
    public var screen: GmScreenProc? = null

    /** `merge_next_node` (bit 1 of the byte at +0x32): draw the [ap] sprite at [nodeId]. */
    public var mergeNextNode: Boolean = false

    /** `nodeId` (+0x33): the node [ap] is drawn at (the next uncleared node). */
    public var nodeId: Int = 0
}

/**
 * The world map entry wrapper: [wrapperScript] (`ProcScr_WorldMapWrapper`) starts [mainScript]
 * through [newWorldMap], waits until the world map proc ends, and then ends any fade-in. Also
 * holds the first steps of the world map main script (`ProcScr_WorldMapMain`) and a few of its
 * helpers.
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
     *
     * Waits a frame, starts the world map, and holds until `ProcScr_WorldMapMain` no longer
     * exists.
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
     * postgame flag (`PLAY_FLAG_POSTGAME`), else 12 once the monsters are merged
     * (`monster_merged`), else 16.
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
     * First command of the world map main script: sets `gGMData.state.bits.state_0` (bit 0) and
     * clears `state_3` (bit 3); the decomp does not say what the bits mean. The trace has
     * `state_3` already 0 before, so only the `state_0` write is verified.
     */
    public fun sub80B8FD4() {
        gmData.stateRaw = (gmData.stateRaw or 0x01) and 0x08.inv()
    }

    /**
     * Ported from src/worldmap_main.c: sub_80B8FEC
     *
     * Points the node icon's flag sprite at the next uncleared node and enables merging, or
     * disables merging if there is none. The prologue trace only covers the no-node branch
     * (node 0 is cleared but not valid there), so `nodeId` assignment is not verified.
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
     * Scrolls the world map camera `gGMData.xCamera/yCamera` so the cursor `ix/iy >> 8` stays
     * inside the 0x20..0xD0 (x) and 0x20..0x80 (y) margins of the screen, clamped to 0..0xF0
     * and 0..0xA0.
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
     * Fades out the music, then (unless monsters are merged or the postgame flag is set) looks
     * up the chapter the leader's node leads to and runs that chapter's world map beginning
     * event; finally starts the face controller and the text box bands. Real callees come from
     * [WorldMapBeginning] (`Sound_FadeOutBGM`, `Events_WM_Beginning` lookup, `ResetGmStoryNode`,
     * `merge_next_node`, `CallEvent`, and `StartWMFaceCtrl` / `StartGmapMuEntry` when given
     * their ports). Still a stand-in: `sub_80BA008` (monster-merged/postgame branch, M6-I).
     *
     * @param proc the world map main proc
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
     * The prefix runs the init, fades the music, runs the beginning event and then repeats the
     * post-beginning-event step.
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

    /** Callees of `WorldMap_CallBeginningEvent` that are not ported here. */
    public class BeginningHooks(
        /** `Sound_FadeOutBGM(speed)`. */
        public val fadeOutBgm: (Int) -> Unit = {},
        /** `sub_80BA008(timer)`: the monster-merged/postgame branch. */
        public val sub80BA008: (Int) -> Unit = {},
        /** `Events_WM_Beginning[GetROMChapterStruct(chIndex)->gmapEventId]`, null if NULL. */
        public val beginningEvent: (Int) -> Int? = { null },
        /** `ResetGmStoryNode()`. */
        public val resetGmStoryNode: () -> Unit = {},
        /** `proc->gm_icon->merge_next_node = false`: stops drawing the next-node flag. */
        public val clearMergeNextNode: () -> Unit = {},
        /** `CallEvent(Events_WM_Beginning[...], 0)` with the event id from [beginningEvent]. */
        public val callEvent: (Int) -> Unit = {},
        /** `StartWMFaceCtrl(proc)`: the world map face controller ([WorldMapFace]). */
        public val startWmFaceCtrl: () -> Unit = {},
        /** `StartGmapMuEntry(NULL)`: the shaded bands behind world map text. */
        public val startGmapMuEntry: () -> Unit = {},
    )
}

/**
 * The graphics setup that opens `WorldMap_Init` (`src/worldmap_main.c`):
 * `SetupGraphicSystemsForWorldMap` and `sub_80B8E60` (with `SetupGmapNodeGfx`): the BG, palette,
 * tile, window and blend state of the world map screen. `WorldMapBoot.worldMapInit` calls both.
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
     * Loads the node palettes (banks 0x13-0x14), the node tiles at OBJ VRAM `+0x1000`
     * (`0x06011000`) and the castle node tiles (0xC x 4 tiles) at `+0x280` (`0x06010280`).
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
     * HBlank state of the world map. The path image goes to BG VRAM `0x06005000`, which
     * `StartGMapRoute(..., 0x5000, 0xe)` (`chr` 0x5000, palette 0xE) draws from.
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

    /** `ApplyPalettes(src, palId, count)`: copies [count] 16-colour palettes into the buffer. */
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
        /** `LoadLegacyUiFrameGraphics`: stand-in until M6-B (shared with `ChapterMapGfx`). */
        public val loadLegacyUiFrameGraphics: () -> Unit = {},
        /** `ResetText`: stand-in until M6-B (not ported in `TextSystem`). */
        public val resetText: () -> Unit = {},
        /** `ResetTextFont`: stand-in until M6-B (not ported in `TextSystem`). */
        public val resetTextFont: () -> Unit = {},
        /** `ResetFaces`: ported as `TalkFace.resetFaces`; `WorldMapBoot` connects it. */
        public val resetFaces: () -> Unit = {},
        /** `SetupFaceGfxData`: ported as `TalkFace.setupFaceGfxData`; tile offsets, palette ids. */
        public val setupFaceGfxData: (IntArray, IntArray) -> Unit = { _, _ -> },
        /** `ResetUnitSprites`: ported as `UnitSprites.resetUnitSprites`. */
        public val resetUnitSprites: () -> Unit = {},
        /** `InitMus`: ported as `Mu.initMus`. */
        public val initMus: () -> Unit = {},
        /** `ApplyUnitSpritePalettes`: ported as `UnitSprites.applyUnitSpritePalettes`. */
        public val applyUnitSpritePalettes: () -> Unit = {},
        /** `SetWorldMapHblank`: [WorldMapHblank.setWorldMapHblank] in the boot; default nothing. */
        public val setWorldMapHblank: () -> Unit = {},
    )

    /** ROM addresses and layout constants. */
    public companion object {
        /** Size of `gGenericBuffer` used here. */
        private const val GENERIC_BUFFER_SIZE = 0x2000

        /** `FACE_SLOT_COUNT`. */
        private const val FACE_SLOTS = 4

        /** `CHR_SIZE`: bytes of one 4bpp tile. */
        private const val CHR_SIZE = 0x20

        /** `0x06010000` as a VRAM offset (OBJ tiles). */
        private const val OBJ_VRAM = 0x10000

        /** `WEATHER_CLOUDS` of `include/types.h`. */
        private const val WEATHER_CLOUDS = 7

        /** Bit index of `wincnt.win0_enableBlend` in the window control word. */
        private const val WIN0_ENABLE_BLEND = 5

        /** Bit index of `wincnt.win1_enableBlend`. */
        private const val WIN1_ENABLE_BLEND = 13

        /** Bit index of `wincnt.wout_enableBlend`. */
        private const val WOUT_ENABLE_BLEND = 21

        /** `gUnknown_08A3D728`: the world map face config (tile offset, palette id) x 4. */
        public const val FACE_CONFIG: Int = 0x08A3D728

        /** `gUnknown_08A97A40`: the node icon palettes (two banks). */
        public const val PAL_NODES: Int = 0x08A97A40

        /** `Img_GmapNodes`: the node icon tiles. */
        public const val IMG_GMAP_NODES: Int = 0x08A96308

        /** `Img_GmapCastleNodes`: the castle node tiles. */
        public const val IMG_GMAP_CASTLE_NODES: Int = 0x08A97410

        /** `gPal_MiscUiGraphics`: the misc UI palettes. */
        public const val PAL_MISC_UI: Int = 0x0859ED70

        /** `gUnknown_08AA11D0`: the world map's misc UI image. */
        public const val GFX_MISC_UI_WM: Int = 0x08AA11D0

        /** `gUnknown_08A97FA4`: the path palette. */
        public const val PAL_PATH: Int = 0x08A97FA4

        /** `Img_GmapPath`: the path tiles. */
        public const val IMG_GMAP_PATH: Int = 0x08A97ED8
    }
}
