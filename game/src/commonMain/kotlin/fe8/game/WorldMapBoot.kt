package fe8.game

import fe8.game.bm.BmSt
import fe8.game.bm.ChapterData
import fe8.game.bm.Mu
import fe8.game.bm.PlaySt
import fe8.game.bm.UnitLoader
import fe8.game.bm.UnitSprites
import fe8.game.bm.UnitTable
import fe8.game.event.EventBgm
import fe8.game.event.EventEngine
import fe8.game.event.EventState
import fe8.game.event.EventText
import fe8.game.event.EventTextTalkHost
import fe8.game.event.ScriptMemory
import fe8.game.scene.TalkScene
import fe8.game.worldmap.GmData
import fe8.game.worldmap.GmRouteProc
import fe8.game.worldmap.GmScreenProc
import fe8.game.worldmap.WmBigMapHooks
import fe8.game.worldmap.WmNodeData
import fe8.game.worldmap.WmPathData
import fe8.game.worldmap.WmTextFadeHooks
import fe8.game.worldmap.WmTextHost
import fe8.game.worldmap.WmUnitHooks
import fe8.game.worldmap.WorldMapApHost
import fe8.game.worldmap.WorldMapAutoMu
import fe8.game.worldmap.WorldMapDot
import fe8.game.worldmap.WorldMapDotEvents
import fe8.game.worldmap.WorldMapUnitEvents
import fe8.game.worldmap.WorldMapBeginning
import fe8.game.worldmap.WorldMapBigMapEvents
import fe8.game.worldmap.WorldMapCursor
import fe8.game.worldmap.WorldMapExit
import fe8.game.worldmap.WorldMapFace
import fe8.game.worldmap.WorldMapFaceEvents
import fe8.game.worldmap.WorldMapGfxSetup
import fe8.game.worldmap.WorldMapHblank
import fe8.game.worldmap.WorldMapLineFade
import fe8.game.worldmap.WorldMapMain
import fe8.game.worldmap.WorldMapMainProc
import fe8.game.worldmap.WorldMapMainScript
import fe8.game.worldmap.WorldMapMiscEvents
import fe8.game.worldmap.WorldMapMu
import fe8.game.worldmap.WorldMapMuEntry
import fe8.game.worldmap.WorldMapNodeIcons
import fe8.game.worldmap.WorldMapPath
import fe8.game.worldmap.WorldMapRm
import fe8.game.worldmap.WorldMapRmBaPalAnim
import fe8.game.worldmap.WorldMapRmBorder
import fe8.game.worldmap.WorldMapScreen
import fe8.game.worldmap.WorldMapScroll
import fe8.game.worldmap.WorldMapSpawnEvents
import fe8.game.worldmap.WorldMapText
import fe8.game.worldmap.WorldMapTextEvents
import fe8.game.worldmap.WorldMapTextFadeEvents
import fe8.game.worldmap.SetStandingMuFacing
import fe8.game.worldmap.SmsDisplayOne
import fe8.game.worldmap.WorldMapSms
import fe8.game.worldmap.WorldMapUnit
import gba.render.LineTable
import gbafe.core.flags.EventFlags
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcArgFunc
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.end
import gbafe.render.ArchivedPaletteFade
import gbafe.render.BgHardware
import gbafe.render.OamBuffer
import gbafe.render.PaletteFade
import gbafe.render.ScreenFade
import gbafe.render.SpriteAnimPlayer
import gbafe.render.SpriteLayers

/**
 * The Prologue world map wired into the boot (D11-wm9): `ProcScr_WorldMapWrapper` and
 * `ProcScr_WorldMapMain` with the real `WorldMap_Init`, the beginning event through the event VM
 * with the world map commands registered, the exit ([WorldMapExit]) and `EndWM`. Wiring only: no
 * port claims. Every callee that is not ported stays a stand-in (see the `Hooks` of the classes
 * used and docs/plans/D11-worldmap.md section 8g).
 *
 * Needs the whole ROM. Built lazily by [Fe8Boot].
 */
internal class WorldMapBoot(
    private val engine: ProcEngine,
    private val hw: BgHardware,
    private val rom: RomView,
    private val playSt: PlaySt,
    private val gmData: GmData,
    private val bmSt: BmSt,
    private val keys: gbafe.input.KeyStatus,
    private val layers: SpriteLayers,
    private val oam: OamBuffer,
    private val units: UnitTable,
    private val events: EventEngine,
    private val eventState: EventState,
    private val flags: EventFlags,
    private val eventTextHost: EventTextTalkHost,
    @Suppress("unused") private val eventText: EventText,
    private val screenFade: ScreenFade,
    private val sound: SoundWrapper,
    private val talkScene: TalkScene,
    private val mu: Mu,
    private val unitSprites: () -> UnitSprites?,
    private val chapters: ChapterData,
    private val lines: LineTable,
) {
    private val nodes = WmNodeData(rom)
    private val memory = ScriptMemory { a -> rom.u16(a) }
    private val hblank = WorldMapHblank { line, index, color ->
        lines.writePalette(line, index, color)
    }
    private val apHost = WorldMapApHost(rom, hw, oam)
    private val player = SpriteAnimPlayer({ rom.u16(it) }, layers, apHost).also { it.clearAll() }
    private val sms: WorldMapSms by lazy { WorldMapSms(rom, layers, checkNotNull(unitSprites())) }
    private val archivedFade = ArchivedPaletteFade(PaletteFade(hw, engine), hw, engine)
    private val path = WorldMapPath(gmData, WmPathData(rom))

    private val fadeIn: ProcScript get() = archivedFade.script
    private val builder = WorldMapMain(
        engine, playSt, gmData, ProcScript("ProcScr_WorldMapMain (prefix)", listOf(end())), fadeIn,
    )
    private val main: WorldMapMain by lazy {
        WorldMapMain(engine, playSt, gmData, mainScript, fadeIn)
    }
    private val exit: WorldMapExit by lazy {
        WorldMapExit(
            engine, hw, playSt, gmData, nodes, rom, chapters, events, mainScript, fadeIn,
            faces, wmText, muEntry, rm, scroll, { mainProc() },
            WorldMapExit.Hooks(
                fadeOutBgm = { sound.fadeOutBgm(it) },
                gmMu80BE108 = { i, f -> mainState()?.gmMu?.let { wmMu.gmMu80BE108(it, i, f) } },
                endScreenProcs = {
                    mainState()?.let { s ->
                        engine.end(s.gmCursor)
                        engine.end(s.gmScreen)
                        engine.end(s.gmIconProc)
                    }
                },
                clearHblank = {
                    hw.hblank.setSecondary(null)
                    hblank.clearWmHblank(hw.hblank)
                },
                endTalk = { eventTextHost.endTalk() },
                clearTalkText = { talkScene.talk.clearTalkText() },
                resetUnitSprites = { unitSprites()?.resetUnitSprites() },
                resetDialogueScreen = {
                    eventTextHost.clearTalkBubble()
                    eventTextHost.resetAllFaces()
                },
            ),
        )
    }

    private val unitHost = WorldMapUnit(
        engine, rom, hw, player, gmData,
        WorldMapUnit.Hooks(
            rmPosition = {
                val position = rm.gmapRmGetPosition()
                if (position == null) {
                    cachedPair(0, 0)
                } else {
                    cachedPair(position.first, position.second)
                }
            },
            screenPosition = { p -> (p.state as GmScreenProc).let { cachedPair(it.x, it.y) } },
            classSmsId = { sms.classSmsId(it) },
            startWorldMapSms = { id, frame, slot -> sms.startWorldMapSms(id, frame, slot) },
            setStandingMuFacingWm = SetStandingMuFacing { frame, dst ->
                sms.setStandingMuFacing(frame, dst)
            },
            smsDisplayOne = SmsDisplayOne { classId, layer, x, y, oam2, blend ->
                sms.smsDisplayOne(classId, layer, x, y, oam2, blend)
            },
            decompressMuGfx = { img, index ->
                rom.decompressInto(img, apHost.muGfx, index * MU_GFX_SIZE)
            },
        ),
    )
    private val wmMu: WorldMapMu = WorldMapMu(
        engine, unitHost, gmData, nodes,
        WorldMapMu.Hooks(
            unitAt = { i ->
                if (units.isValid(i)) units.charId(i) to units.classId(i) else null
            },
            defaultClass = { pid ->
                rom.u8(UnitLoader.CHARACTER_DATA + (pid - 1) * UnitLoader.CHARACTER_DATA_SIZE + 5)
            },
            centeredCamera = { x, y -> scroll.getWmCenteredCameraPosition(x, y) },
            heldKeys = { keys.heldKeys },
        ),
    )
    private val scroll = WorldMapScroll(engine, gmData, nodes)
    private val screens = WorldMapScreen(engine, hw, rom, gmData)
    private val icons = WorldMapNodeIcons(engine, player, gmData)
    private val cursors = WorldMapCursor(engine, hw, rom, gmData, layers)
    private val faces = WorldMapFace(
        engine, hw, gmData, rom, talkScene.face, talkScene, talkScene,
    )
    private val wmText = WorldMapText(
        engine, hw, hblank, layers,
        object : WmTextHost {
            override fun endTalk() = eventTextHost.endTalk()

            override fun initSpriteTalk(chr: Int, pal: Int, lines: Int) =
                talkScene.talk.initSpriteTalk(chr, lines, pal)

            override fun startTalkMsg(x: Int, y: Int, msg: Int) =
                eventTextHost.startTalkMsg(x, y, msg)

            override fun setTalkPrintDelay(delay: Int) = talkScene.talk.setTalkPrintDelay(delay)

            override fun setTalkFlag(flag: Int) = talkScene.talk.setTalkFlag(flag)
        },
    )
    private val muEntry = WorldMapMuEntry(engine, hw, rom, hblank)
    private val rm: WorldMapRm = WorldMapRm(
        engine, hw, rom, gmData,
        WorldMapRm.Hooks(
            updateRouteFlags = { clear, set ->
                routeProc()?.let { it.flags = (it.flags and clear.inv()) or set }
            },
            skipNodeIcon = { mainState()?.let { icons.skipGmNodeIconDisplay(it.gmIcon) } },
            unskipNodeIcon = { mainState()?.let { icons.unskipGmNodeIconDisplay(it.gmIcon) } },
            setupGmapNodeGfx = { gfx.setupGmapNodeGfx() },
            loadObjUiGfx = { talkScene.loadObjUiGfx() },
            loadLegacyUiFrameGraphics = { loadLegacyUiFrameGraphics() },
            removeMuUnit = { i -> mainProc()?.gmMuProc()?.let { wmMu.gmMuRemoveUnit(it, i) } },
            hideLeaderMu = { mainProc()?.gmMuProc()?.let { wmMu.gmHideMuUnit(it, 0) } },
            showLeaderMu = { mainProc()?.gmMuProc()?.let { wmMu.gmShowMuUnit(it, 0) } },
        ),
    )
    private val gfx = WorldMapGfxSetup(
        hw, rom, playSt, bmSt,
        WorldMapGfxSetup.Hooks(
            loadLegacyUiFrameGraphics = { loadLegacyUiFrameGraphics() },
            setWorldMapHblank = { hblank.setWorldMapHblank(hw.hblank) },
            resetFaces = { talkScene.face.resetFaces() },
            setupFaceGfxData = { tiles, pals -> talkScene.face.setupFaceGfxData(tiles, pals) },
            resetUnitSprites = { unitSprites()?.resetUnitSprites() },
            initMus = { mu.initMus() },
            applyUnitSpritePalettes = { unitSprites()?.applyUnitSpritePalettes() },
        ),
    )

    /** The last position pair handed to a unit: positions repeat, so no pair per frame. */
    private var lastPair: Pair<Int, Int> = 0 to 0

    private fun cachedPair(x: Int, y: Int): Pair<Int, Int> {
        if (lastPair.first != x || lastPair.second != y) lastPair = x to y
        return lastPair
    }

    private fun loadLegacyUiFrameGraphics() {
        // Stand-in until M6-B: `LoadLegacyUiFrameGraphics` (BG UI frame tiles) is not ported.
    }

    private fun mainProc(): Proc? = engine.find(mainScript)

    private fun mainState(): WorldMapMainProc? = mainProc()?.state as WorldMapMainProc?

    private fun Proc.gmMuProc(): Proc? = (state as WorldMapMainProc).gmMu

    private fun routeProc(): GmRouteProc? =
        ((engine.find(screens.screenScript)?.state as GmScreenProc?)?.gmRoute?.state
            as GmRouteProc?)

    private val border: WorldMapRmBorder by lazy {
        WorldMapRmBorder(
            engine, hw, rom, player,
            WorldMapRmBaPalAnim(engine, hw.lcd) { faces.getWmFaceBlendAmt() },
        ) { rm.gmapRmGetPosition() }
    }
    private fun gmMu(): Proc = checkNotNull(mainState()?.gmMu) { "no GMapMu proc" }

    private val automu = WorldMapAutoMu(
        engine, gmData,
        WorldMapAutoMu.Hooks(
            muMoving = { wmMu.sub80BE12C(gmMu(), it) },
            muStop = { wmMu.sub80BE330(gmMu(), it) },
            endUnitFade = unitHost::endGmapUnitFade,
            muSetPosition = { i, x, y -> wmMu.gmMuSetPosition(gmMu(), i, x, y) },
            hideUnit = { wmMu.gmHideMuUnit(gmMu(), it) },
            showUnit = { wmMu.gmShowMuUnit(gmMu(), it) },
            muFadeIn = { i, d -> wmMu.gmMuStartFadeIn(gmMu(), i, d) },
            muFadeOut = { i, d -> wmMu.gmMuStartFadeOut(gmMu(), i, d) },
            unitFadeExists = wmMu::gmUnitFadeExists,
            muMoveNodes = { wmMu.sub80BDEB4(gmMu(), it) },
            muMovePositions = { wmMu.sub80BDFA4(gmMu(), it) },
        ),
    )
    private val dots = WorldMapDot(engine, hw, rom, layers, { rm.gmapRmGetPosition() })

    private val lineFade = WorldMapLineFade(engine, hw.lcd, hblank) {
        archivedFade.newFadeIn(it, null)
    }

    /** The `ProcScr_WorldMapMain` script: prefix, `WorldMap_Init` and the tail. */
    val mainScript: ProcScript by lazy {
        val tail = WorldMapMainScript()
        tail.funcs["WorldMap_CallIntroEvent"] = ProcFunc { exit.callIntroEvent(it) }
        tail.funcs["WorldMap_WaitForChapterIntroEvents"] =
            ProcFunc { exit.waitForChapterIntroEvents(it) }
        tail.funcs["WorldMap_HideEverything"] = ProcFunc { exit.hideEverything() }
        tail.funcs["ResetWorldMapScreen"] = ProcFunc { exit.resetWorldMapScreen() }
        tail.funcs["NULL_080B9F08"] = ProcFunc { }
        tail.argFuncs["NewFadeOut"] = ProcArgFunc { _, _ -> true }
        val beginning = WorldMapBeginning(rom, chapters, path, events) { sound.fadeOutBgm(it) }
        val mainHooks = WorldMapMain.MainHooks(
            destruct = { exit.destruct(it) },
            init = { worldMapInit(it) },
            fadeBgmOut = { sound.fadeOutBgm(it) },
            postBeginningEvent = { exit.postBeginningEvent(it) },
        )
        val prefix = builder.mainScriptPrefix(
            nodes, { flags.checkFlag(it) },
            WorldMapMain.BeginningHooks(),
            mainHooks,
        )
        // The beginning hooks need the main proc's state: built when the command runs.
        val cmds = prefix.toMutableList()
        cmds[CALL_BEGINNING] = gbafe.proc.ProcScripts.call(
            ProcFunc { proc ->
                val state = proc.state as WorldMapMainProc
                builder.callBeginningEvent(
                    state, nodes, { flags.checkFlag(it) },
                    beginning.hooks(state, faces, muEntry) { mainProc() },
                )
            },
        )
        ProcScript("ProcScr_WorldMapMain", cmds + tail.commands()) { WorldMapMainProc() }
    }

    /**
     * Transcribed from src/worldmap_main.c: WorldMap_Init (not verified: wired, then checked
     * through the proc tree of the boot test only)
     */
    private fun worldMapInit(proc: Proc) {
        val state = proc.state as WorldMapMainProc
        gfx.setupGraphicSystemsForWorldMap()
        gfx.sub80B8E60(state)
        builder.deployEveryUnit(units)
        val screen = screens.newMapScreen(TREE_5)
        state.gmScreen = screen
        val icon = icons.startGmNodeIconDisplay(
            TREE_5, 0, 3, 10, screen.state as GmScreenProc,
        )
        state.gmIconProc = icon
        state.gmIcon = icon.state as fe8.game.worldmap.GmNodeIconDisplayProc
        state.gmUnitc = unitHost.newGmapUnitContainer(screen, 0x280, 0xC)
        state.gmCursor = cursors.newGmapCursor(
            TREE_5, 0x12C0, 4, screen.state as GmScreenProc,
        )
        state.gmMu = wmMu.startGmMu(proc)
        path.refreshGmNodeLinks(fe8.game.worldmap.GmNodeLinks())
        wmText.startWmTextHandler(proc)
        builder.sub80B8FEC(state) { path.getNextUnclearedNode() }
        sub80B90CC(state)
        routeProc()?.let { it.flags = it.flags or 3 }
        if (playSt.chapterStateBits and GameControl.PLAY_FLAG_POSTGAME != 0) {
            path.resetGmStoryNode()
            state.gmIcon.mergeNextNode = false
        }
        state.delayTimer = 0
    }

    /**
     * Transcribed from src/worldmap_main.c: sub_80B9028, sub_80B90CC (not verified: wired
     * only): puts the units of gGMData on the map, dropping the three monster slots first unless
     * the monsters are merged.
     */
    private fun sub80B90CC(state: WorldMapMainProc) {
        val mu = state.gmMu ?: return
        if (!gmData.monsterMerged()) {
            for (i in 4 until 7) {
                wmMu.gmMuRemoveUnit(mu, i)
                gmData.setUnitId(i, 0)
                gmData.setUnitState(i, gmData.unitState(i) and 3.inv())
            }
        }
        for (i in 0 until 7) {
            if (gmData.unitId(i) == 0) continue
            val node = gmData.unitLocation(i)
            if (gmData.unitState(i) and 2 != 0) {
                wmMu.mapMuSetUnitClass(mu, i, gmData.unitId(i), -1, node)
            } else {
                wmMu.mapMuSetUnitChar(mu, i, gmData.unitId(i), -1, node)
            }
            wmMu.gmMuSetPosition(mu, i, nodes.x(node), nodes.y(node))
            wmMu.gmShowMuUnit(mu, i)
        }
    }

    /** `ProcScr_WorldMapWrapper`, to be started as the blocking child of the game control. */
    val wrapperScript: ProcScript get() = main.wrapperScript

    /** `EndWM`, for the game control (`PROC_CALL(EndWM)`). */
    fun endWm(proc: Proc) = exit.endWm(proc)

    /** Registers the world map commands on [events]. */
    fun wire() {
        registerEvents()
    }

    private fun registerEvents() {
        // `MUSCFAST(0x7FFF)` (0x13) is the only fade-in the Prologue runs; the other chapters'
        // world map events keep the release stub, so it is registered here, not in `EventBgm`.
        events.register(0x13, EventBgm(eventState, memory, sound).event13BgmFadeIn)
        val textEvents = WorldMapTextEvents(memory, wmText)
        events.register(0xC6, textEvents.eventC6WmDisplayText)
        events.register(0xC7, textEvents.eventC7)
        val textFade = WorldMapTextFadeEvents(
            memory, screenFade,
            WmTextFadeHooks(
                endWm = { exit.endWm(it.proc!!) },
                gmMuEntryStartShow = { s, b -> muEntry.gmMuEntryStartShow(s, b) },
                endGmMuEntry = muEntry::endGmMuEntry,
                gmMuEntryFlag = muEntry::getGmMuEntryFlag,
                startGmapLineFade = { lineFade.startGmapLineFade(it) },
                isGmapLineFadeActive = lineFade::isGmapLineFadeActive,
            ),
        )
        textFade.registerOn(events)
        WorldMapBigMapEvents(memory, WmBigMapHooks.of(rm, border)).registerOn(events)
        WorldMapFaceEvents(memory, faces).registerOn(events)
        val camera = scroll.CameraEvents(memory)
        events.register(0x83, camera.event83SetCamera)
        events.register(0x84, camera.event84SetCameraOntoNode)
        events.register(0x85, camera.event85SetCameraOntoUnit)
        events.register(0x86, camera.event86ScrollCamera)
        events.register(0x87, camera.event87ScrollCameraOntoNode)
        events.register(0x88, camera.event88ScrollCameraOntoUnit)
        events.register(0x89, camera.event89ScrollWait)
        val spawn = WorldMapSpawnEvents(memory, gmData) { id, pid, allegiance, node ->
            wmMu.setGmCharUnit(id, pid, allegiance, node)
        }
        events.register(0xC2, spawn.eventC2WmNoFade)
        events.register(0xC3, spawn.eventC3SpawnLord)
        val unitEvents = WorldMapUnitEvents(
            memory,
            WmUnitHooks(
                setClassUnit = { id, jid, al, node ->
                    wmMu.mapMuSetUnitClass(gmMu(), id, jid, al, node)
                },
                removeUnit = { wmMu.gmMuRemoveUnit(gmMu(), it) },
                showUnit = { wmMu.gmShowMuUnit(gmMu(), it) },
                hideUnit = { wmMu.gmHideMuUnit(gmMu(), it) },
                fadeIn = { i, d -> wmMu.gmMuStartFadeIn(gmMu(), i, d) },
                fadeOut = { i, d -> wmMu.gmMuStartFadeOut(gmMu(), i, d) },
                endUnitFade = unitHost::endGmapUnitFade,
                unitFadeExists = wmMu::gmUnitFadeExists,
                setPosition = { i, x, y -> wmMu.gmMuSetPosition(gmMu(), i, x, y) },
                startMoveFree = { input, flag -> automu.startType1(input, flag, null) },
                startMovePaths = { input, flag -> automu.startType0(input, flag, null) },
                autoMuActive = automu::isActiveFor,
                endAutoMu = automu::endFor,
            ),
        )
        events.register(0x9E, unitEvents.event9EWmSetClassUnit)
        events.register(0x9F, unitEvents.event9FWmSetCharUnit)
        events.register(0xA0, unitEvents.eventA0WmRemoveUnit)
        events.register(0xA1, unitEvents.eventA1WmShowUnit)
        events.register(0xA2, unitEvents.eventA2WmHideUnit)
        events.register(0xA3, unitEvents.eventA3WmShowUnitFaded)
        events.register(0xA4, unitEvents.eventA4WmHideUnitFaded)
        events.register(0xA5, unitEvents.eventA5WmUnitFadeWait)
        events.register(0xA6, unitEvents.eventA6WmUnitSetOnNode)
        events.register(0xA7, unitEvents.eventA7WmUnitSetPosition)
        events.register(0xA8, unitEvents.eventA8WmUnitMoveFree)
        events.register(0xA9, unitEvents.eventA9WmUnitMovePaths)
        events.register(0xAA, unitEvents.eventAAWmUnitPauseMove)
        events.register(0xAB, unitEvents.eventABWmUnitResumeMove)
        events.register(0xAC, unitEvents.eventACWmUnitMoveWait)
        val dotEvents = WorldMapDotEvents(memory, dots, nodes)
        events.register(0xBB, dotEvents.eventBB)
        events.register(0xBC, dotEvents.eventBCMarkPoint)
        events.register(0xBD, dotEvents.eventBD)
        events.register(0xBE, dotEvents.eventBE)
        WorldMapMiscEvents(gmData) { wmText.hideWmText() }.registerOn(events)
    }

    private companion object {
        const val TREE_5 = 5

        /** One decompressed map sprite sheet (`GetMapUnitMMSGfxBuffer` stride). */
        const val MU_GFX_SIZE = 0x2200

        /** Index of `PROC_CALL(WorldMap_CallBeginningEvent)` in `ProcScr_WorldMapMain`. */
        const val CALL_BEGINNING = 5
    }
}
