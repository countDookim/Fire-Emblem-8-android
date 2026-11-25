package fe8.game

import fe8.game.bm.ActiveUnit
import fe8.game.bm.BattleDeamonFlag
import fe8.game.bm.AiBattleSimulation
import fe8.game.bm.AiIntStep
import fe8.game.bm.AiStep
import fe8.game.bm.AiTry
import fe8.game.bm.BMapMain
import fe8.game.bm.CallDelayedArg
import fe8.game.bm.MovementMap
import fe8.game.bm.PathArrow
import fe8.game.bm.BattleGenerate
import fe8.game.bm.BmBattle
import fe8.game.bm.BmContainer
import fe8.game.bm.ItemInfo
import fe8.game.bm.PartyGoldValue
import fe8.game.bm.BMapMain.Companion.SAVE_ID_SUSPEND
import fe8.game.bm.BattleMapNodes
import fe8.game.bm.BattleMapStart
import fe8.game.bm.BmMap
import fe8.game.bm.BmSt
import fe8.game.bm.BmMapRender
import fe8.game.bm.CamMove
import fe8.game.bm.ChapterData
import fe8.game.bm.ChapterIntro
import fe8.game.bm.ChapterMapGfx
import fe8.game.bm.MapAnimScanline
import fe8.game.bm.CpEquip
import fe8.game.bm.BmxFade
import fe8.game.bm.CpPerform
import fe8.game.bm.CpPhase
import fe8.game.bm.MapAnimations
import fe8.game.bm.Mu
import fe8.game.bm.MuCtrl
import fe8.game.bm.MuCtrlHooks
import fe8.game.bm.Reda
import fe8.game.bm.MuConfig
import fe8.game.bm.MuHooks
import fe8.game.bm.MuProc
import fe8.game.bm.MuUnit
import fe8.game.bm.MuSpriteAnim
import fe8.game.bm.MapCursor
import fe8.game.bm.MapMusic
import fe8.game.bm.GameOverScreen
import fe8.game.bm.MapTraps
import fe8.game.bm.SupportReliance
import fe8.game.bm.EntityBmMaps
import fe8.game.bm.PLAY_FLAG_HARD
import fe8.game.bm.PhaseIntro
import fe8.game.bm.PhaseStartEnd
import fe8.game.bm.PhaseTargets
import fe8.game.bm.PlayerPhase
import fe8.game.bm.ResetCursorPosition
import fe8.game.bm.SelectTargets
import fe8.game.bm.UnitDeployState
import fe8.game.bm.UnitDeployStateHooks
import fe8.game.bm.UnitLoader
import fe8.game.ui.GotItemPopup
import fe8.game.ui.UiFrame
import fe8.game.ui.ItemIcon
import fe8.game.ui.PopupScript
import fe8.game.bm.UnitSpriteHandles
import fe8.game.bm.UnitSprites
import fe8.game.bm.UnitSpritesOam
import fe8.game.bm.UnitTable
import fe8.game.event.ActiveEventRegistry
import fe8.game.event.EventConst
import fe8.game.event.EventEngine
import fe8.game.event.EventEngineHooks
import fe8.game.event.EventBgm
import fe8.game.event.EventChangeAi
import fe8.game.event.EventChangeAiHooks
import fe8.game.event.EventAsmCall
import fe8.game.event.EventGiveItem
import fe8.game.event.EventScrHooks
import fe8.game.event.bmGuideTextSetAllGreen
import fe8.game.event.EventFade
import fe8.game.event.EventHandler
import fe8.game.event.EventScrCore
import fe8.game.event.EventScriptBattle
import fe8.game.event.EventScriptBattleHost
import fe8.game.event.EventUnitState
import fe8.game.event.EventUnitStateHooks
import fe8.game.event.EventState
import fe8.game.event.EventText
import fe8.game.event.EventTextBg
import fe8.game.event.EventTextBgHost
import fe8.game.event.EventTextTalkHost
import fe8.game.event.EventCursorCamera
import fe8.game.event.EventCursorCameraHooks
import fe8.game.event.MapCursorProc
import fe8.game.event.createProcScrEventDisplayCursor
import fe8.game.event.EventEngineProc
import fe8.game.event.EventUnitLoads
import fe8.game.event.EventChangeMap
import fe8.game.event.EventInfoHooks
import fe8.game.event.GameOverCaller
import fe8.game.event.GameOverStart
import fe8.game.event.EventInfos
import fe8.game.event.EventMoveToChapter
import fe8.game.event.EventMoveUnit
import fe8.game.event.EventMoveUnitHost
import fe8.game.event.EventUnitLoadsHost
import fe8.game.event.EventPopup
import fe8.game.event.ReleaseEventStubs
import fe8.game.scene.BrownTextBox
import fe8.game.event.ScriptMemory
import fe8.game.event.ScriptWords
import fe8.game.flags.Fe8EventFlags
import fe8.game.opanim.EarlyStartUi
import fe8.game.opanim.OpAnim
import fe8.game.opanim.OpSubtitle
import fe8.game.save.BmSaveLib
import fe8.game.save.BmSuspendSave
import fe8.game.save.ChapterRankings
import fe8.game.save.ChapterStats
import fe8.game.save.NewGameRam
import fe8.game.save.PidStats
import fe8.game.save.SuspendRam
import fe8.game.scene.IntConsumer
import fe8.game.scene.IntSupplier
import fe8.game.scene.TalkScene
import fe8.game.title.TitleScreen
import fe8.game.worldmap.GmData
import fe8.game.worldmap.WmNodeData
import fe8.game.worldmap.WmPathData
import fe8.game.worldmap.WorldMapPath
import fe8.game.worldmap.WorldMapNodes
import gba.audio.M4aDriver
import gba.audio.M4aSound
import gba.audio.PcmStream
import gba.core.sram.CartSram
import gba.core.sram.SaveStorage
import gba.core.sram.flushTo
import gba.core.sram.loadFrom
import gba.render.Camera
import gba.render.ColorMath
import gba.render.FrameBuffer
import gba.render.GpuFrame
import gba.render.HBlankLines
import gba.render.RenderState
import gba.render.SoftwareRenderer
import gba.render.TileSheets
import gbafe.core.flags.EventFlags
import gbafe.core.mechanics.Mechanics
import gbafe.core.rng.RngService
import gbafe.core.time.GameClock
import gbafe.input.KeyStatus
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts
import gbafe.render.ArchivedPaletteFade
import gbafe.render.BgTilemap
import gbafe.render.ColorFade
import gbafe.render.FadeCore
import gbafe.render.HardwareSceneLoader
import gbafe.render.PaletteFade
import gbafe.render.ScreenFade
import gbafe.text.Font
import gbafe.text.MsgHuffman
import gbafe.text.MsgStrings
import fe8.game.bm.ChapterTitle
import fe8.game.mapanim.ApProcs
import fe8.game.save.BmGameSave
import fe8.game.save.GameSaveRam
import fe8.game.savemenu.SaveMenuAssembly
import fe8.game.worldmap.WorldMapApHost
import gbafe.render.BgVerticalScroll
import gbafe.render.SpriteAnimPlayer

/**
 * The boot sequence wired for a device: `StartGame` -> game control -> health & safety screen ->
 * Nintendo / Intelligent Systems logos ([EarlyStartUi]) -> op anim ([OpAnim]) -> title
 * ([TitleScreen]), driven by the [GameLoop] (one [frame] per GBA frame) and drawn from the
 * synced display state ([HardwareSceneLoader] -> [SoftwareRenderer], or the GPU renderer on
 * device). With [m4a], the boot's BGM and sound effects play through [SoundWrapper], mixed in
 * the VBlank exactly as traced (`Fe8BootSoundTest`); CGB (PSG) channels are still silent.
 *
 * Past the title, New Game goes through the world map and chapter intro to the Prologue battle
 * map, with the player phase and the enemy phase ([wirePlayerPhase]) running on the same procs.
 */
public class Fe8Boot(
    /** ROM data of the boot screens (whole ROM, or the extracted [RomView.BOOT_WINDOWS]). */
    private val rom: RomView,
    /** Raw pressed GBA keys (`REG_KEYINPUT`, active high) for the next step. */
    keyInput: () -> Int,
    /** The game's RNG ([Fe8Game.rng]); the battle map start path draws from it. */
    public val rng: RngService,
    /** Colour-effect arithmetic of [render]; hardware by default. */
    colorMath: ColorMath = ColorMath.Hardware,
    /** The picture [render] draws into. */
    public val screen: FrameBuffer = FrameBuffer(),
    /** The game's m4a state ([Fe8Sound.create], already `m4aSoundInit`ed), or null: silent. */
    public val m4a: M4aSound? = null,
    /** Receives each frame's mixed samples (null: mix only). */
    pcm: PcmStream? = null,
    /**
     * Where [sram] is loaded from at boot and flushed to after each save write; null: kept in
     * memory only.
     */
    private val saveStorage: SaveStorage? = null,
) {
    /** Test hook: told the command code of every event command that runs a no-op release stub. */
    public var onEventStub: ((code: Int) -> Unit)? = null

    /** `gKeyStatusPtr`. */
    public val keys: KeyStatus = KeyStatus()

    /** `gGameClock`. */
    public val clock: GameClock = GameClock()

    /** `gMapCursor` sprite renderer (`PutMapCursor`). */
    public val mapCursor: MapCursor by lazy {
        MapCursor(rom, loop.bmSt, loop.sprites).also { it.clockSource = clock }
    }

    /** Proc trees. */
    public val engine: ProcEngine = ProcEngine()

    /** The early start screens and their display state. */
    public val ui: EarlyStartUi = EarlyStartUi(
        engine,
        EarlyStartUi.Env(
            ShortArray(512), ShortArray(512), gbafe.render.LcdControl(), keys,
            rom.pal(0x08B125F4, 16), rom.pal(0x08B10ABC, 16), rom.pal(0x08B11864, 48),
            gfx = EarlyStartUi.Gfx(
                rom.blob(0x08B11D0C),
                rom.blob(0x08B118C4),
                rom.blob(0x08B11C0C),
                rom.blob(0x08B10630),
                rom.blob(0x08B104D8), rom.blob(0x08B10CA4), rom.blob(0x08B10ADC),
            ),
            fadeOutBgm = { control.sound.fadeOutBgm(it) },
        ),
    )

    private val hw = ui.env.hardware

    /** The blend fades of `src/bmlib.c` (`StartMidFadeFromBlack`, the event fade command). */
    public val screenFade: ScreenFade = ScreenFade(engine, hw.lcd)

    /** The LCD I/O register image (offset 0 = 0x04000000) that [loop] flushes each frame. */
    private val io = ByteArray(0x60)

    private lateinit var opAnimRef: OpAnim
    private lateinit var titleRef: TitleScreen

    /** The game control proc's owner. */
    public val control: GameControl =
        GameControl(engine, keys, { ui.script }, { opAnimRef.script }, { titleRef }, m4a)

    /** The cartridge save RAM of this boot (empty until a save is written). */
    public val sram: CartSram = CartSram().also { s -> saveStorage?.let { s.loadFrom(it) } }

    /** The save routines on [sram]. */
    public val saveLib: BmSaveLib by lazy { BmSaveLib(sram).also { it.sramInit() } }

    /** The per-unit battle statistics (`gPidStatsData`). */
    public val pidStats: PidStats by lazy { PidStats(rom, saveLib.pidStatsData) }

    /** The per-chapter completion records (`gChapterStats`). */
    public val chapterStats: ChapterStats by lazy { ChapterStats(saveLib.chapterStatsData) }

    /** The suspend save's RAM, shared by the write hook and the resume read (`actionData`). */
    private val suspendRam: SuspendRam by lazy {
        SuspendRam(
            control.playSt, loop.bmSt, rng, units, flags, clock, eventState, gmData = gmData,
        )
    }

    /** Flushes the cartridge SRAM contents to [saveStorage] if configured. */
    private fun flushSram() {
        saveStorage?.let { sram.flushTo(it) }
    }

    /**
     * Stand-in until M6-E (PC-9 for the New Game path) for `StartSaveMenu` (the save menu is not
     * ported). With a valid suspend save in [sram] it takes the menu's "Resume" choice: reads the
     * suspend save ([BmSuspendSave.readSuspendSave]) and sets `nextAction` to `GAME_ACTION_4`,
     * so the game control proc continues at the resumed game. Otherwise it is the dev "New Game"
     * choice: writes a fresh game to slot 0 ([BmSaveLib.writeNewGameSave], the save menu's
     * difficulty 1 "Normal": `SaveMenuWriteNewGame` passes mode 1, isTutorial 1, not hard;
     * ram_trace_prologue has `config.controller` = 1, so `CHECK_TUTORIAL` (`Event19_Checks`) is
     * false) and leaves `nextAction` at event return, so the game control proc continues to
     * chapter 0.
     */
    private fun newGameSaveMenu() {
        if (saveLib.isValidSuspendSave(SAVE_ID_SUSPEND)) {
            BmSuspendSave(saveLib, rom).readSuspendSave(suspendRam, SAVE_ID_SUSPEND)
            control.setNextGameActionId(GameControl.GAME_ACTION_4)
            return
        }
        saveLib.writeNewGameSave(
            NewGameRam(
                control.playSt, flags, ByteArray(200), initUnits = { units.initUnits() },
            ),
            0, false, 1, 1,
        )
        flushSram()
        control.setNextGameActionId(GameControl.GAME_ACTION_EVENT_RETURN)
    }

    /**
     * The post-chapter save menu (PC-4) with every `wire*` of [SaveMenuPostChapter] applied
     * ([SaveMenuAssembly]); `WriteGameSave` is [BmGameSave.writeGameSave] on this boot's RAM.
     * Built on first use; needs the whole ROM (text, messages).
     */
    public val postChapterSaveMenu: SaveMenuAssembly.Result by lazy {
        val scene = talkScene
        val nodes = WmNodeData(rom)
        val gameSave = BmGameSave(saveLib, statsUnitLoader, rom)
        val ram = GameSaveRam(control.playSt, loop.bmSt, units, flags, clock, gmData = gmData)
        SaveMenuAssembly.assemble(
            SaveMenuAssembly.Deps(
                engine = engine,
                hardware = hw,
                rom = rom,
                oam = loop.oam,
                layers = loop.sprites,
                scroll = BgVerticalScroll(),
                apProcs = ApProcs(
                    engine,
                    SpriteAnimPlayer(
                        { rom.u16(it) }, loop.sprites, WorldMapApHost(rom, hw, loop.oam),
                    ),
                ),
                playSt = control.playSt,
                bmSt = loop.bmSt,
                gmData = gmData,
                saveLib = saveLib,
                archivedFade = ArchivedPaletteFade(PaletteFade(hw, engine), hw, engine),
                chapterTitle = ChapterTitle(
                    rom, hw, battleChapters,
                    getPlayChapterId = { chapter ->
                        (0 until WM_NODE_COUNT).firstOrNull { node ->
                            nodes.chapterId(node, control.playSt.chapterModeIndex) == chapter
                        } ?: -1
                    },
                    getNextUnclearedNode = {
                        WorldMapPath(gmData, WmPathData(rom)).getNextUnclearedNode()
                    },
                ),
                text = scene.text,
                messages = scene.messages,
                lcg = rng.lcg,
                loadObjUiGfx = {
                    ChapterMapGfx(rom, battleChapters, hw, control.playSt).loadObjUiGfx()
                },
                clock = { clock.frames.toInt() },
                setNextGameActionId = { control.setNextGameActionId(it) },
                writeGameSave = { slot ->
                    gameSave.writeGameSave(ram, slot)
                    flushSram()
                },
                newKeys = { keys.newKeys },
                playSoundEffect = { control.sound.playSoundEffect(it) },
            ),
        )
    }

    /** `gActiveEventRegistry` of the chapter event lists, set when the event lists are wired. */
    private var activeEventRegistry: ActiveEventRegistry? = null

    /** The units the battle map start path touches (raw unit bytes). */
    public val units: UnitTable by lazy { UnitTable(rom) }

    /**
     * Unit stats/item-rules helper shared by player phase, battle, AI, and chapter cleanup; loads
     * no units itself.
     */
    private val statsUnitLoader: UnitLoader by lazy {
        val playSt = control.playSt
        UnitLoader(
            units, rom,
            // `gPlaySt.unk1C[weaponType]`, the character that unseals a weapon type (types 0, 1).
            { type -> if (type == 0) playSt.unk1C0 else if (type == 1) playSt.unk1C1 else 0 },
            rng, { playSt.chapterStateBits and PLAY_FLAG_HARD != 0 },
        )
    }

    /** `sub_80141B0` of the event skip (only its [FadeCore.darkenEnd] is used). */
    private val skipFade: FadeCore by lazy { FadeCore(engine, hw) }

    /** The event engine's globals, shared by [eventEngine] and the suspend save. */
    public val eventState: EventState = EventState()

    /** `gChapterFlagBits` and friends. */
    public val flags: EventFlags = Fe8EventFlags.create()

    /**
     * `gGMData` and its [GmData.init] (`GmDataInit`). Fe8Boot has no whole-IWRAM image yet, so
     * the block lives in a private 32 KiB IWRAM-sized array indexed like the real one.
     */
    private val gmData = GmData(ByteArray(GM_DATA_IWRAM_SIZE), rng)

    /**
     * The event engine (`EventEngine`) with every ported command handler registered (core,
     * unit state and loads, cursor and camera, map change, battle, fade, BGM, give item, popup,
     * and with the whole ROM the talk, text background and ASM-call commands). The commands
     * still unported are registered as no-op release stubs ([ReleaseEventStubs], reported to
     * [onEventStub]). The engine hooks are wired to the screen fade, talk scene, world map,
     * game lock and sound of this boot.
     */
    public val eventEngine: EventEngine by lazy {
        val state = eventState
        val memory = ScriptWords({ rom.u16(it) })
        // `gKeyStatusPtr->newKeys & START_BUTTON`: the game's single KeyStatus.
        val hooks = object : EventEngineHooks {
            override fun startPressed(): Boolean = keys.newKeys and KeyStatus.START_BUTTON != 0
            override fun fadeExists(): Boolean = screenFade.fadeExists()
            override fun isBattleDeamonActive(): Boolean = battleDeamon.isBattleDeamonActive()
            // `WM_Exists`; a boot that never built the world map has none.
            override fun worldMapExists(): Boolean =
                worldMapBootDelegate.isInitialized() && worldMapBoot.exists()

            // `sub_800D488`: `sub_80141B0` (instant black, layers off) and end the talk bubbles
            // opening; used by the world map skip and by a skip after the scene faded out.
            override fun skipFadedIn(proc: EventEngineProc) {
                skipFade.darkenEnd()
                if (talkSceneDelegate.isInitialized()) engine.endEach(talkScene.bubble.openScript)
            }

            // `sub_80BA424`: `sub_8002AC8` fades the BGM out (`Sound_FadeOutBGM(1)` stands in for
            // its wait-music proc end and two `m4aMPlayFadeOut` calls); `MapRoute_80BC2DC` and
            // the route flags are not run: the Prologue draws no route.
            override fun worldMapSkip(proc: EventEngineProc) {
                control.sound.fadeOutBgm(1)
            }
            override fun startLockingFadeToBlack(speed: Int, parent: Proc) {
                screenFade.startLockingFadeToBlack(speed, parent)
            }
            override fun blendY(): Int = hw.lcd.blendY

            // `SetTextFont(NULL)`, `InitSystemTextFont`, `LoadUiFrameGraphics`: without it the
            // talk font of a battle quote stays active and the map battle names use its palette
            // bank (D-22).
            override fun resetTextFont() {
                if (!rom.isWholeRom) return
                talkScene.text.setTextFont(null)
                talkScene.text.initSystemTextFont()
                UiFrame.loadUiFrameGraphics(hw, rom, control.playSt.windowColor)
            }
            // `EndAllMus`, `EndBMapMain` (`EV_STATE_CHANGEGM`: the chapter ends; releasing the
            // game control lock lets `GameControl` run its post-chapter label).
            override fun endMapForChangeGm() {
                mu.endAllMus()
                battleMap.endBMapMain()
            }
            // `gPlaySt.chapterIndex`, stored in the engine proc of a cutscene or gameplay event.
            override fun chapterIndex(): Int = control.playSt.chapterIndex
            override fun lockGame() { loop.lockGame() }
            override fun freezeMenu() { playerWiring?.menu?.freezeMenu() }
            // `UnlockGame`, `ResumeMenu`, `ResetBkselPalette` (`ClearCutsceneUnits` is EP-11h
            // territory and not ported).
            override fun resumeGameplay() {
                loop.unlockGame()
                playerWiring?.let {
                    it.menu.resumeMenu()
                    if (it.isForecastReady) it.forecast.resetBkselPalette()
                }
            }
            override fun changeUnitSpritePalette(palId: Int) {
                unitSprites?.changeUnitSpritePalette(palId)
            }
            // `ReadGameSaveCoreGfx`, `UnpackChapterMapPalette`, `ChangeUnitSpritePalette`; the
            // graphics need the whole ROM (text, icons), boot windows keep the no-op.
            override fun restoreAfterEvent5(mapSpritePalIdOverride: Int) {
                if (!rom.isWholeRom) return
                val gfx = battleMapGfx(battleChapters)
                gfx.readGameSaveCoreGfx()
                gfx.unpackChapterMapPalette()
                unitSprites?.changeUnitSpritePalette(mapSpritePalIdOverride)
            }
            // The talk needs the whole ROM (messages, portraits); with boot windows only the
            // chapter data of the events is missing as well, so the default no-op stays.
            override fun endEventTalk() {
                if (!rom.isWholeRom) return
                eventTextHost.endTalk()
                eventTextHost.endCgText()
                eventTextHost.endBoxDialogue()
            }
            override fun eventEndCleanup(proc: EventEngineProc) {
                if (rom.isWholeRom) eventText.endTextFaces(proc)
            }
        }
        EventEngine(engine, state, memory, hooks).also {
            EventScrCore(
                state, memory, flags = flags, rng = rng, playSt = control.playSt, keys = keys,
                hooks = object : EventScrHooks {
                    override fun tutorialEvent(chapterIndex: Int, index: Int): Int {
                        if (!rom.isWholeRom) return 0
                        val group = battleChapters.getChapterEventDataPointer(chapterIndex)
                        val list = rom.u32(group + TUTORIAL_EVENTS_OFFSET)
                        return if (list == 0) 0 else rom.u32(list + 4 * index)
                    }

                    override fun countRedUnits(): Int = units.countRedUnits()

                    override fun countGreenUnits(): Int = units.countGreenUnits()

                    override fun battleMapKind(): Int = battleMap.hooks.getBattleMapKind()

                    // `GetEventTriggerId`: the list flag that started the script.
                    override fun eventTriggerId(script: Int): Int {
                        val registry = activeEventRegistry ?: return 0
                        for (i in 0 until minOf(registry.idx, ActiveEventRegistry.CAPACITY)) {
                            if (registry.scripts[i] == script) return registry.flags[i]
                        }
                        return 0
                    }
                },
            ).registerAll(it)
            val loader = UnitLoader(
                units, rom,
                // Only unk1C[0..1] exist in PlaySt (`u8 unk1C[2]` in the C struct); other types
                // read 0.
                { type ->
                    when (type) {
                        0 -> control.playSt.unk1C0
                        1 -> control.playSt.unk1C1
                        else -> 0
                    }
                },
                rng, { control.playSt.chapterStateBits and PLAY_FLAG_HARD != 0 },
            ).apply {
                // `UnitLoadSub`'s level adjustment of red units (character number >= 0x3C): the
                // u16 at +0x14 of the chapter struct holds `easyModeLevelMalus` (bits 0-3),
                // `difficultModeLevelBonus` (4-7) and `normalModeLevelMalus` (8-11);
                // `config.controller` 0 in normal mode is the easy branch.
                chapterLevelBonus = {
                    val ps = control.playSt
                    val levels = rom.u16(
                        battleChapters.getROMChapterStruct(ps.chapterIndex) + 0x14,
                    )
                    val hard = ps.chapterStateBits and PLAY_FLAG_HARD != 0
                    when {
                        ps.controller == 0 && !hard -> -(levels and 0xF)
                        hard -> levels ushr 4 and 0xF
                        else -> -(levels ushr 8 and 0xF)
                    }
                }
                bmMap = battleBmMap
                weatherId = { control.playSt.chapterWeatherId }
            }
            // Cursor / camera commands: the camera moves and the cursor proc runs;
            // SetSomeRealCamPos and RenderBmMap keep the default no-op stand-ins (M6-B).
            val unitLookup = EventUnitState(
                state, memory, units, loader, object : EventUnitStateHooks {},
            )
            val cursorScript = createProcScrEventDisplayCursor(
                putMapCursor = { x, y, kind -> mapCursor.put(x, y, kind) },
            )
            EventCursorCamera(
                state, memory, loop.bmSt,
                object : EventCursorCameraHooks {
                    override fun unitPosition(pid: Int): Int {
                        val id = unitLookup.getUnitStructFromEventParameter(pid)
                        if (id < 0) return -1
                        return (units.xPos(id) shl 16) or (units.yPos(id) and 0xFFFF)
                    }

                    override fun startDisplayCursor(x: Int, y: Int, subcmd: Int) {
                        val proc = engine.start(cursorScript, 3)
                        val s = proc.state as MapCursorProc
                        s.x = x
                        s.y = y
                        s.subcmd = subcmd
                        s.timer = 0
                    }

                    override fun endDisplayCursor() {
                        engine.endEach(cursorScript)
                    }

                    override fun ensureCameraOntoPosition(proc: EventEngineProc, x: Int, y: Int) {
                        camMove.ensureCameraOntoPosition(proc.proc, x, y)
                    }

                    override fun cameraOntoPositionCentred(proc: EventEngineProc, x: Int, y: Int) {
                        camMove.startCameraOntoPositionCentred(
                            proc.proc, x, y, battleBmMap.sizeX, battleBmMap.sizeY,
                        )
                    }
                },
            ).registerOn(it)
            EventUnitLoads(
                state, memory, rom, units, loader, rng,
                { x, y -> battleBmMap.terrain[y, x] },
                { x, y -> battleBmMap.unit[y, x] },
                { battleBmMap.bmMapFill(battleBmMap.other, 0) },
                object : EventUnitLoadsHost {
                    override fun camMoveExists(): Boolean =
                        this@Fe8Boot.engine.find(camMove.script) != null

                    override fun ensureCameraOntoPosition(
                        proc: EventEngineProc,
                        x: Int,
                        y: Int,
                    ) = camMove.ensureCameraOntoPosition(proc.proc, x, y)

                    // An index loop: `any` makes a 32 B iterator each frame a waiting load retries.
                    override fun canStartMu(): Boolean {
                        val configs = mu.configs
                        for (i in 0 until configs.size) if (configs[i].slot == 0) return true
                        return false
                    }
                },
            ).also { loads ->
                loads.registerOn(it)
            }
            EventChangeMap(
                state, memory, control.playSt, loop.bmSt, hw,
                { battleMap.restartBattleMap() },
                { battleEntityMaps.refreshEntityBmMaps() },
                { bmapRender.renderBmMap() },
                resumeMapDisplay = {
                    if (rom.isWholeRom) {
                        battleMap.bMapDispResume()
                        engine.unblockEachMarked(Mu.PROC_MARK_MU)
                    }
                },
                refreshUnitSprites = { refreshSprites() },
                refreshBMapGraphics = { if (rom.isWholeRom) refreshMapGraphics() },
                changeUnitSpritePalette = { unitSprites?.changeUnitSpritePalette(it) },
            ).registerOn(it)
            val unitState = EventUnitState(
                state, memory, units, loader,
                object : EventUnitStateHooks {
                    override fun unitAt(x: Int, y: Int): Int = battleBmMap.unit[y, x]

                    override fun refreshMapAndSprites() {
                        battleEntityMaps.refreshEntityBmMaps()
                        refreshSprites()
                        bmapRender.renderBmMap()
                    }

                    // `KILL`: `HideUnitSprite`, `StartMu`, `SetAutoMuDefaultFacing` and
                    // `MU_StartDeathFade` (the unit state part is in the handler).
                    override fun startUnitDeathFade(unitId: Int) {
                        spriteHandles?.hideUnitSprite(unitId)
                        val muProc = mu.startMu(
                            MuUnit(
                                address = UnitTable.BLUE_ADDRESS + units.unitOffset(unitId),
                                xPos = units.xPos(unitId),
                                yPos = units.yPos(unitId),
                                classId = units.classId(unitId),
                                state = units.state(unitId),
                                spritePalette = spriteHandles?.getUnitSpritePalette(unitId) ?: 0,
                            ),
                        ) ?: return
                        mu.setAutoMuDefaultFacing()
                        mu.muStartDeathFade(muProc.state as MuProc)
                    }

                    override fun muDeathFadeExists(): Boolean = mu.muDeathFadeExists()
                },
            ).also { us -> us.registerOn(it) }
            EventChangeAi(
                state, memory, units,
                object : EventChangeAiHooks {
                    override fun unitAt(x: Int, y: Int): Int = battleBmMap.unit[y, x]
                },
            ).registerOn(it)
            EventScriptBattle(
                engine, state, memory, units,
                BattleGenerate(
                    BmBattle(rom, units, loader, control.playSt, loop.bmSt) { y, x ->
                        battleBmMap.terrain[y, x]
                    },
                    rom, loader, control.playSt, rng, Mechanics(),
                ).also {
                    it.bmMap = battleBmMap
                    it.unitTable = units
                },
                object : EventScriptBattleHost {
                    override fun getUnitStructFromEventParameter(pid: Int): Int =
                        unitState.getUnitStructFromEventParameter(pid)

                    override fun gameLock(): Int = loop.gameLock()

                    override fun refreshEntityBmMaps() {
                        battleEntityMaps.refreshEntityBmMaps()
                    }
                },
            ).registerOn(it)
            EventFade(memory, screenFade).registerOn(it)
            EventBgm(state, memory, control.sound).registerOn(it)
            EventMoveToChapter(
                state,
                memory,
                control.playSt,
                control.sound,
                setNextGameActionId = { id -> control.setNextGameActionId(id) },
                setNextChapterId = { id -> control.setNextChapterId(id) },
                gotoChapterWithoutSave = { id -> bmapMain.gotoChapterWithoutSave(id) },
            ).registerOn(it)
            registerMoveUnit(it, state, memory, loader) { pid ->
                unitState.getUnitStructFromEventParameter(pid)
            }
            val itemInfo = ItemInfo(rom)
            val giveItemAdd: (Int, Int) -> Boolean =
                { u, item -> loader.unitAddItem(units.unitOffset(u), item) }
            EventGiveItem(
                state, memory, { pid -> unitState.getUnitStructFromEventParameter(pid) },
                giveItemAdd,
                itemInfo::makeNewItem,
                { control.playSt.partyGoldAmount }, { control.playSt.partyGoldAmount = it },
                // The popup machinery is built on the first GIVEITEMTO of an item.
                { p, u, item ->
                    gotItemPopup(giveItemAdd, itemInfo::makeNewItem).start(p, u, item)
                },
            ).registerOn(it)
            EventPopup(
                state, memory,
                BrownTextBox(
                    engine, hw, loop.sprites, rom,
                    textWidth = { id -> if (rom.isWholeRom) brownTextBoxWidth(id) else 0 },
                    drawText = { id, chr, pal, centerWidth ->
                        if (rom.isWholeRom) brownTextBoxDraw(id, chr, pal, centerWidth)
                    },
                ),
            ).registerOn(it)
            if (rom.isWholeRom) {
                eventText.registerOn(it)
                EventAsmCall(
                    memory,
                    mapOf(
                        0x080CF460 to { _ -> bmGuideTextSetAllGreen(memory, flags) },
                        // `EventCallGameOverExt`: the ASMC of `EventScr_GameOver` (PC-8).
                        0x08085374 to { p ->
                            gameOverCaller.eventCallGameOverExt(checkNotNull(p.proc))
                        },
                    ),
                ).registerOn(it)
                eventTextBg.registerOn(it)
            }
            ReleaseEventStubs.registerMissing(it) { code -> onEventStub?.invoke(code) }
        }
    }

    /** The game over screen (`src/gameoverbgfx.c`) on the game's BG hardware, clock and keys. */
    private val gameOverScreen: GameOverScreen by lazy {
        GameOverScreen(
            engine, hw, ColorFade(hw.palette), rom,
            object : GameOverScreen.Hooks {
                override fun bMapDispSuspend() { battleMap.bMapDispSuspend() }
                override fun startGameOverBgm() { control.sound.startBgm(GameOverStart.SONG_GAME_OVER, 0) }
                override fun endAllMus() { mu.endAllMus() }
                override fun gameClock(): Int = clock.get().toInt()
                override fun newKeys(): Int = keys.newKeys
                override fun fadeOutBgm(speed: Int) { control.sound.fadeOutBgm(speed) }
            },
        )
    }

    /** `EventCallGameOverExt` and `ProcScr_BmGameOver`, on the real fade, MUs and event engines. */
    private val gameOverCaller: GameOverCaller by lazy {
        GameOverCaller(
            engine, control.playSt,
            fadeOutBgm = { control.sound.fadeOutBgm(it) },
            startSlowFadeToBlack = { screenFade.startSlowFadeToBlack() },
            endAllMus = { mu.endAllMus() },
            startGameOverScreen = { gameOverScreen.startGameOverScreen(it) },
            setNextGameActionId = { control.setNextGameActionId(it) },
            endBMapMain = { battleMap.endBMapMain() },
            deleteEventEngines = { eventEngine.deleteEventEngines() },
        )
    }

    /** `sub_80837B0`: the start of the game over. */
    private val gameOverStart: GameOverStart by lazy {
        GameOverStart(
            flags, control.playSt,
            startBgm = { control.sound.startBgm(it, 0) },
            callGameOverEvent = { eventEngine.callGameOverEvent() },
        )
    }

    /**
     * `GetStringTextLen(GetStringFromIndex(msg))` for the brown text box: the width in pixels
     * with the system glyphs (`TextGlyphs_System`, English ASCII font), measured on a scratch
     * font so the talk font stays untouched.
     */
    internal fun brownTextBoxWidth(msg: Int): Int {
        val scene = talkScene
        val text = scene.text
        val saved = text.activeFont
        val font = Font().also { it.lang = 1 }
        text.setTextFont(font)
        text.setTextFontGlyphs(0)
        try {
            return text.getStringTextLen(scene.messages.getStringFromIndex(msg))
        } finally {
            text.setTextFont(saved)
        }
    }

    /** The sprite text calls of `StartBrownTextBoxCore` after the `Decompress`. */
    internal fun brownTextBoxDraw(msg: Int, chr: Int, pal: Int, centerWidth: Int) {
        val scene = talkScene
        val text = scene.text
        val font = Font()
        val t = gbafe.text.Text()
        val str = scene.messages.getStringFromIndex(msg)
        text.initSpriteTextFont(font, 0x06010400 + chr, pal + 0x12)
        text.initSpriteText(t)
        text.spriteTextDrawBackgroundExt(t, 0)
        text.setTextFontGlyphs(0)
        text.textInsertDrawString(t, text.getStringTextCenteredPos(centerWidth, str), 0, str)
        text.setTextFont(null)
    }

    /**
     * The talk window on the game's BG buffers, VRAM, fonts, procs, keys and the ROM messages
     * (see [TalkScene] for its stand-ins, M6-E). Built on first use; needs the whole ROM.
     */
    private val talkSceneDelegate = lazy {
        val bytes = rom.wholeRomBytes()
        val root = rom.u32(MSG_HUFFMAN_ROOT) - RomView.ROM_BASE
        val huffman = MsgHuffman(bytes, MSG_HUFFMAN_TABLE - RomView.ROM_BASE, root)
        TalkScene(
            rom, bytes, hw, engine, loop.sprites, keys, { clock.frames.toInt() },
            MsgStrings(bytes, huffman, MSG_TABLE - RomView.ROM_BASE),
            { song -> control.sound.playSoundEffect(song) },
            { control.playSt.textSpeed },
            // SetDialogueSkipEvBit: EV_STATE_0008 on the standard event engine.
            {
                (eventEngine.findStd()?.state as? EventEngineProc)?.let { p ->
                    p.evStateBits = p.evStateBits or EventConst.EV_STATE_0008
                }
            },
            rng.lcg,
            battleDeamon,
        )
    }

    /** `gBattleDeamonActive`: the one flag the talk and the event engine read. */
    private val battleDeamon = BattleDeamonFlag()

    /** `gPopupItem`. */
    private var popupItem = 0

    /** `gPopupNumber`. */
    private var popupNumber = 0

    /**
     * `DrawnIconLookupTable`: per icon, how many places draw it and its graphics slot
     * ([ItemIcon.MAX_ICON_COUNT] entries of [ItemIcon.ICON_ENTRY_SIZE] bytes).
     */
    private val drawnIconLookupTable = ByteArray(0x380)

    /** `IconGFXIDLookupTable`: per icon graphics slot, the icon index plus one (0 = free). */
    private val iconGfxIdLookupTable = ByteArray(0x100)

    /** The [gotItemPopup] result, kept so the popup machinery is built once. */
    private var gotItemPopupInstance: GotItemPopup? = null

    /**
     * The "got item" popup (`NewPopup_ItemGot`) on the game's proc engine, built on first use
     * (needs the whole ROM through [talkScene]). Stand-ins (D09-3): `gpPopupUnit` is not
     * tracked, so the unit-name text id is 0 (the got-item and pilfered scripts do not use the
     * unit name), and [addItem] with a full inventory (the item-drop menu) throws
     * [NotImplementedError].
     * `LoadIconPalette(slot, palId)` maps to [ItemIcon.loadIconPalette] (icon palette `slot` into
     * palette bank `palId`).
     */
    private fun gotItemPopup(
        addItem: (unit: Int, item: Int) -> Boolean,
        makeNewItem: (Int) -> Int,
    ): GotItemPopup {
        gotItemPopupInstance?.let { return it }
        val scene = talkScene
        val popupIconObject = rom.objN(0x08590F4C) // gObject_16x16
        val popupScript = PopupScript(
            engine,
            PopupScript.Dependencies(
                rom = rom,
                messages = scene.messages,
                text = scene.text,
                hardware = hw,
                drawnIconLookupTable = drawnIconLookupTable,
                iconGfxIdLookupTable = iconGfxIdLookupTable,
                popupItem = { popupItem },
                popupNumber = { popupNumber },
                unitNameTextId = { 0 },
                loadIconPalette = { slot, palId -> ItemIcon.loadIconPalette(hw, rom, slot, palId) },
                startBgmVolumeChange = { proc, v, e, t ->
                    control.sound.startBgmVolumeChange(v, e, t, proc)
                },
                playSoundEffect = { song -> control.sound.playSoundEffect(song) },
                newKeys = { keys.newKeys },
                pushToSecondaryOam = { x, y, _, oam2 ->
                    loop.oam.putHi(x, y, popupIconObject.data, oam2)
                },
            ),
        )
        val popup = GotItemPopup(
            engine,
            GotItemPopup.Dependencies(
                newPopupSimple = { parent, def, clock, style ->
                    popupScript.start(parent, def, clock, style)
                },
                setPopupItem = { popupItem = it },
                unitFaction = { u -> units.index(u) and 0xC0 },
                setUnitDropItem = { u ->
                    units.setState(u, units.state(u) or UnitLoader.US_DROP_ITEM)
                },
                makeNewItem = makeNewItem,
                handleNewItemGetFromDrop = { u, item, _ ->
                    if (!addItem(u, item)) {
                        throw NotImplementedError(
                            "GIVEITEMTO with a full inventory (item-drop menu) is not ported",
                        )
                    }
                },
            ),
        )
        gotItemPopupInstance = popup
        return popup
    }

    /** The talk scene, created on first use. */
    public val talkScene: TalkScene by talkSceneDelegate

    /** Host of the talk commands: the [talkScene] parts, text speed and map display hooks. */
    private val eventTextHost: EventTextTalkHost by lazy {
        val scene = talkScene
        EventTextTalkHost(
            engine, scene.talk, scene.bubble, scene.face, scene, scene,
            readTextSpeed = { control.playSt.textSpeed },
            writeTextSpeed = { control.playSt.textSpeed = it },
            stringFromIndex = scene::stringFromIndex,
            refreshMapGraphics = { if (rom.isWholeRom) refreshMapGraphics() },
            suspendMapDisp = { if (rom.isWholeRom) battleMap.bMapDispSuspend() },
            resumeMapDisp = { if (rom.isWholeRom) battleMap.bMapDispResume() },
            changeSpritePalette = { unitSprites?.changeUnitSpritePalette(it) },
        )
    }

    /**
     * Restores the available parts of `RefreshBMapGraphics` after a conversation background.
     * Text, UI frame, face and icon resets, weather particles and system font setup are
     * stand-ins (M6-E).
     */
    private fun refreshMapGraphics() {
        val gfx = ChapterMapGfx(rom, battleChapters, hw, control.playSt)
        hw.setupBackgrounds(
            cloudsWeather = control.playSt.chapterWeatherId == ChapterMapGfx.WEATHER_CLOUDS,
        )
        gfx.loadObjUiGfx()
        gfx.unpackChapterMapGraphics(control.playSt.chapterIndex)
        bmapRender.renderBmMap()
        refreshSprites()
        unitSprites?.applyUnitSpritePalettes()
        unitSprites?.forceSyncUnitSpriteSheet()
    }

    /** The talk event commands 0x1A to 0x1D (`Event1A_TEXTSTART` ...) over [eventTextHost]. */
    private val eventText: EventText by lazy {
        EventText(eventState, ScriptWords({ rom.u16(it) }), eventTextHost)
    }

    /**
     * The text background command 0x21 (`Event21_TextBg`). Real: BG positions, the random
     * background, the BG3 load of subcode 0; the fade procs and `DisplayCGfx` are no-op
     * stand-ins (M6-E).
     */
    private val eventTextBg: EventTextBg by lazy {
        EventTextBg(
            eventState, ScriptWords({ rom.u16(it) }),
            object : EventTextBgHost {
                override fun resetBgPositions() {
                    for (bg in 0..3) hw.lcd.setBgPosition(bg, 0, 0)
                }

                override fun randomBackground(): Int = rng.nextN(BG_BLANK)

                override fun loadConvoBackground(bgIndex: Int) {
                    val entry = CONVO_BACKGROUND_DATA + 12 * bgIndex
                    rom.decompressInto(rom.u32(entry), hw.vram, hw.tileDataOffset(3))
                    // The tsa is stored raw (header: width - 1, height - 1) and read in place.
                    val tsaAt = rom.u32(entry + 4)
                    val tsaSize = 2 + 2 * (rom.u8(tsaAt) + 1) * (rom.u8(tsaAt + 1) + 1)
                    val tsa = ByteArray(tsaSize) { rom.u8(tsaAt + it).toByte() }
                    BgTilemap.applyTsa(hw.mapBuffer(3), 0, tsa, 0, 0x8000)
                    rom.pal(rom.u32(entry + 8), 16 * 8)
                        .copyInto(hw.palette, 8 * 16)
                    hw.enableBgSyncByMask(1 shl 3)
                    hw.enablePaletteSync()
                    hw.palette[0] = 0
                }

                override fun displayCg(bgIndex: Int) {}

                override fun startBackgroundFade(
                    fadeType: Int,
                    unkType: Int,
                    bgIndex: Int,
                    fadeSpeed: Int,
                    proc: EventEngineProc,
                ) {}

                override fun clearScreen(proc: EventEngineProc): Int =
                    eventText.event22ClearScreen.run(proc)

                override fun colorFadeIn(speed: Int, color: Int, proc: EventEngineProc) {}

                override fun colorFadeOut(speed: Int, color: Int, proc: EventEngineProc) {}
            },
        )
    }

    /**
     * The battle map start path (`StartBattleMap`, `StartBMapMain`), built when the game control
     * proc first reaches it (it reads chapter data from the ROM, which the boot windows may lack).
     */
    public val battleMap: BattleMapStart by lazy {
        bmapMainParts.second
    }

    /**
     * The `BMapMain` of [battleMap] with its player phase and event hooks wired (player phase
     * script, `EventEngine.callEvent`).
     */
    public val bmapMain: BMapMain by lazy { bmapMainParts.first }

    /** The player phase script wired into [bmapMain]. */
    public val playerPhaseScript: ProcScript get() = bmapMain.hooks.playerPhase

    /** `gSMSHandleArray` of the battle map, set by [wireUnitSprites]. */
    private var spriteHandles: UnitSpriteHandles? = null

    /**
     * `gTrapArray`. Stand-in until M6-H: the trap pool is not ported (no ballistae, light runes
     * or obstacles), so it stays empty; the sprite refresh and the attack target list read it.
     */
    private val trapPool = ByteArray(TRAP_POOL_SIZE)

    /** `gTrapArray` / `sTrapPool` of the battle map, set by [wirePhases]. */
    public var mapTraps: MapTraps? = null
        private set

    /** Support system helper of the battle map, set by [wirePhases]. */
    public var supportReliance: SupportReliance? = null
        private set

    /**
     * The unit sprite sheet state (`gUnitSpriteSlots`, index counters), set by
     * [wireUnitSprites].
     */
    public var unitSprites: UnitSprites? = null

    /** The player phase wiring (menu and battle forecast), set by the battle map wiring. */
    internal var playerWiring: PlayerPhaseWiring? = null
        private set

    /** `RefreshUnitSprites` of the battle map, set by [wireUnitSprites]. */
    private var refreshSprites: () -> Unit = {}

    /** The palette fade of the MU hit flash (`StartPalFade`) on the shared palette. */
    private val muPalFade: PaletteFade by lazy { PaletteFade(hw, engine) }

    /** The target palette of a MOVEUNIT palette fade per bank, reused (the fade keeps it). */
    private val fadeTargets = Array(0x40) { ShortArray(16) }

    /** Delayed call procs (`src/bmlib.c`). */
    public val delayedCalls: CallDelayedArg = CallDelayedArg(engine)

    private fun startDelayedCall(func: (Int) -> Unit, arg: Int, delay: Int) {
        delayedCalls.callDelayedArg(func, arg, delay)
    }

    /** MU animation owner retained to flush its registered tile moves during VBlank. */
    private var muSpriteAnim: MuSpriteAnim? = null

    /**
     * How many MU sprite objects have been parsed from the ROM so far. Each first show of a MU
     * sprite frame parses one (48 B) and caches it; a test uses the growth to tell that one-time
     * fill from a per-frame allocation.
     */
    public val muSpriteObjectsParsed: Int get() = muSpriteAnim?.objectCount ?: 0

    /** `PlaySeSpacial` over the game's [SoundWrapper] (the MU step sounds). */
    private val spacialSe: SpacialSe by lazy { SpacialSe(control.sound) }

    /**
     * The MU system (`mu.c`) with class lookups from the ROM; walking MUs are drawn by the ap.c
     * sprite animation player ([MuSpriteAnim]) and step on the game's sound effects
     * ([SpacialSe]); the standing-MU facing, fog bump and played-through hooks keep their no-op
     * defaults.
     */
    public val mu: Mu by lazy {
        val spriteAnim = MuSpriteAnim(rom, hw, loop.oam, loop.sprites)
        muSpriteAnim = spriteAnim
        lateinit var self: Mu
        self = Mu(
            engine, loop.bmSt, control.playSt, keys, camMove.script,
            object : MuHooks {
                private fun classU8(jid: Int, o: Int) =
                    rom.u8(UnitLoader.CLASS_DATA + (jid - 1) * UnitLoader.CLASS_DATA_SIZE + o)

                override fun classAttributes(jid: Int): Int =
                    rom.u32(UnitLoader.CLASS_DATA + (jid - 1) * UnitLoader.CLASS_DATA_SIZE + 0x28)

                override fun classSlowWalking(jid: Int): Int = classU8(jid, 0x07)

                override fun createMuAnim(proc: MuProc, config: MuConfig): Int =
                    spriteAnim.create(self, proc, config)

                override fun switchMuAnim(proc: MuProc, facing: Int) =
                    spriteAnim.switchAnimation(proc.spriteAnim, facing)

                override fun apUpdate(anim: Int, x: Int, y: Int) = spriteAnim.update(anim, x, y)

                override fun resetSpriteAnimClock(anim: Int) = spriteAnim.resetClock(anim)

                override fun spriteAnimObjLayer(anim: Int): Int = spriteAnim.objLayer(anim)

                override fun deleteMuAnim(proc: MuProc) = spriteAnim.delete(proc.spriteAnim)

                override fun freezeSpriteAnim(anim: Int) = spriteAnim.freeze(anim)

                override fun setSpriteAnimClock(anim: Int, interval: Int) =
                    spriteAnim.setClock(anim, interval)

                override fun setSpriteAnimTileBase(anim: Int, tileBase: Int) =
                    spriteAnim.setTileBase(anim, tileBase)

                override fun setSpriteAnimObjLayer(anim: Int, layer: Int) =
                    spriteAnim.setObjLayer(anim, layer)

                override fun applyPalette(src: Int, bank: Int) {
                    for (i in 0 until 16) hw.palette[bank * 16 + i] = rom.u16(src + 2 * i).toShort()
                    hw.enablePaletteSync()
                }

                override fun startPalFade(targetBank: Int, bank: Int, duration: Int, parent: Proc) {
                    val target = fadeTargets[bank and 0x3F]
                    hw.palette.copyInto(target, 0, targetBank * 16, targetBank * 16 + 16)
                    muPalFade.start(target, bank, duration, parent)
                }

                override fun setBlendConfig(effect: Int, coeffA: Int, coeffB: Int, blendY: Int) =
                    hw.lcd.setBlendConfig(effect, coeffA, coeffB, blendY)

                override fun playSoundEffect(song: Int) = control.sound.playSoundEffect(song)

                override fun callDelayedArg(func: (Int) -> Unit, arg: Int, delay: Int) =
                    startDelayedCall(func, arg, delay)

                override fun playSeSpacial(song: Int, x: Int) =
                    spacialSe.playSeSpacial(song, x)
            },
            rom,
        )
        self
    }

    /**
     * Registers the event commands 0x2F (MOVEUNIT) and 0x30 (ENUN) on [engine]: the units walk
     * with [MuCtrl] and the MU sprite animation player.
     */
    private fun registerMoveUnit(
        engine: EventEngine,
        state: EventState,
        memory: ScriptMemory,
        loader: UnitLoader,
        unitFromParameter: (Int) -> Int,
    ) {
        val muCtrl = MuCtrl(
            this.engine, mu, units, battleBmMap, loader, rng, keys,
            object : MuCtrlHooks {
                override fun hideUnitSprite(id: Int) { spriteHandles?.hideUnitSprite(id) }

                override fun showUnitSprite(id: Int) { spriteHandles?.showUnitSprite(id) }

                override fun refreshEntityBmMaps() { battleEntityMaps.refreshEntityBmMaps() }

                override fun refreshUnitSprites() { refreshSprites() }

                override fun spritePalette(id: Int): Int =
                    spriteHandles?.getUnitSpritePalette(id) ?: 0
            },
        )
        loader.startDefinedMove = { id, redas, count, flags ->
            val list = List(count) {
                Reda.fromWords(rom.u32(redas + 8 * it), rom.u32(redas + 8 * it + 4))
            }
            muCtrl.startDefinedMove(id, list, count, flags)
        }
        EventMoveUnit(
            state, memory, units, battleBmMap, mu, muCtrl, unitFromParameter,
            object : EventMoveUnitHost {
                override fun camMoveExists(): Boolean =
                    this@Fe8Boot.engine.find(camMove.script) != null

                override fun ensureCameraOntoPosition(proc: EventEngineProc, x: Int, y: Int) =
                    camMove.ensureCameraOntoPosition(proc.proc, x, y)

                override fun refreshEntityBmMaps() { battleEntityMaps.refreshEntityBmMaps() }

                override fun refreshUnitSprites() { refreshSprites() }

                override fun renderBmMap() { bmapRender.renderBmMap() }
            },
        ).registerOn(engine)
    }

    /** The camera mover (`gProcScr_CamMove`) of the battle map. */
    private val camMove: CamMove by lazy { CamMove(engine, loop.bmSt) }

    /** The ROM chapter table (`GetROMChapterStruct`, event pointers). */
    private val battleChapters: ChapterData by lazy { ChapterData(rom) }

    /** The battle map's `gBmMap*` pools, shared by the battle map path and the unit loads. */
    public val battleBmMap: BmMap by lazy { BmMap(rom, battleChapters, loop.bmSt) }

    /** `RenderBmMap` of the battle map. */
    private val bmapRender: BmMapRender by lazy { BmMapRender(loop.bmSt, battleBmMap, hw) }

    /** `RefreshEntityBmMaps`: the unit and hidden-unit maps of the battle map. */
    private val battleEntityMaps: EntityBmMaps by lazy {
        EntityBmMaps(battleBmMap, units, control.playSt)
    }

    /**
     * Builds the battle map: [BMapMain] (first) with the player phase, phase intro, events, map
     * music and chapter intro hooked up, and its [BattleMapStart] (second).
     */
    private val bmapMainParts: Pair<BMapMain, BattleMapStart> by lazy {
        val chapters = battleChapters
        val bm = loop.bmSt
        val map = battleBmMap
        val camMove = this.camMove
        val reset = ResetCursorPosition(
            engine, bm, control.playSt, units, camMove,
            if (rom.isWholeRom) {
                WorldMapNodes(WmNodeData(rom), gmData, control.playSt)
            } else {
                NO_WORLD_MAP_NODES
            },
        )
        val main = BMapMain(
            engine, control.playSt, units, flags, chapters, rom, reset, camMove,
        )
        val render = bmapRender
        val music = MapMusic(
            control.playSt, chapters, units, flags, control.sound,
        ) { battleMap.hooks.getBattleMapKind() }
        val entityBmMaps = battleEntityMaps
        val active = ActiveUnit()
        val moves = MovementMap(rom, map, units, control.playSt)
        val arrow = PathArrow(
            map, units, moves, active, bm, rng = rng, sprites = loop.sprites, hardware = hw,
            rom = rom,
        )
        val phase = PlayerPhase(
            engine, bm, control.playSt, map, units, camMove,
            entityBmMaps, render, keys,
            eventEngine::eventEngineExists,
            active = active, movementMap = moves, pathArrow = arrow,
        ).also { pp -> pp.hooks.startMapSongBgm = ProcFunc { music.startMapSongBgm() } }
        main.hooks.playerPhase = phase.script
        main.hooks.phaseIntro = PhaseIntro(
            engine, bm, control.playSt, units, rom, hw, loop.oam, loop.sprites,
        ).also {
            it.hooks.currentBgmSong = { control.sound.songId }
            it.hooks.currentMapMusicIndex = { music.getCurrentMapMusicIndex() }
            it.hooks.fadeOutBgm = { speed -> control.sound.fadeOutBgm(speed) }
            it.hooks.playSoundEffect = { song -> control.sound.playSoundEffect(song) }
            it.hooks.startMapSongBgm = { music.startMapSongBgm() }
        }.script
        main.hooks.startMapSongBgm = { music.startMapSongBgm() }
        main.hooks.startMidFadeFromBlack = { screenFade.startMidFadeFromBlack() }
        main.hooks.waitForFade = { !screenFade.fadeExists() }
        main.hooks.callEvent = { events, execType, _ ->
            eventEngine.callEvent(events, execType)
        }
        val eventInfos = EventInfos(eventEngine.memory, flags, control.playSt)
        activeEventRegistry = eventInfos.registry
        eventInfos.hooks = object : EventInfoHooks {
            override fun callEvent(script: Int, execType: Int) {
                eventEngine.callEvent(script, execType)
            }

            override fun callBattleQuoteEventInBattle(msg: Int) {
                eventEngine.callBattleQuoteEventInBattle(msg)
            }

            override fun createBattle(event: Int) {
                eventEngine.createBattle(event)
            }

            override fun unitActionType(): Int = active.action.unitActionType

            override fun protectCharacterIndex(chapter: Int): Int =
                rom.u8(chapters.getROMChapterStruct(chapter) + OFF_PROTECT_CHARACTER)

            override fun unitFaction(pid: Int): Int {
                val u = units.getUnitFromCharId(pid)
                return if (u >= 0) u and 0xC0 else 0x80
            }

            override fun startBgm(song: Int) {
                control.sound.startBgm(song, 0)
            }

            override fun startGameOver() {
                gameOverStart.startGameOver()
            }

            override fun getBattleMapKind(): Int = battleMap.hooks.getBattleMapKind()

            override fun chapterEventGroup(chapter: Int): Int =
                chapters.getChapterEventDataPointer(chapter)

            override fun goalWindowDataType(chapter: Int): Int =
                rom.u8(chapters.getROMChapterStruct(chapter) + GOAL_WINDOW_DATA_TYPE_OFFSET)

            override fun areAnyEnemyUnitDead(): Boolean = units.areAnyEnemyUnitAlive()

            override fun countAvailableBlueUnits(): Int = units.countAvailableBlueUnits()

            override fun refreshAllies() {
                units.refreshAllies(
                    refreshEntityBmMaps = { battleEntityMaps.refreshEntityBmMaps() },
                    refreshUnitSprites = { refreshSprites() },
                )
            }
        }
        main.hooks.runPhaseSwitchEvents = { eventInfos.runPhaseSwitchEvents() }
        val start = BattleMapStart(
            engine, control.playSt, bm, hw, chapters, map,
            battleMapGfx(chapters), render,
            flags, rng, clock, units,
        ).also {
            main.hooks.writeSuspendSave = suspendSaveHook(main, rom)
            it.hooks.readGameSaveCoreGfx = { readGameSaveCoreGfx(chapters) }
            main.hooks.getBattleMapKind = { it.hooks.getBattleMapKind() }
            it.hooks.bmapMainScript = main.script
            it.hooks.refreshEntityBmMaps = { entityBmMaps.refreshEntityBmMaps() }
            it.hooks.suspendPointType = {
                suspendRam.actionData[ACTION_SUSPEND_POINT_TYPE].toInt() and 0xFF
            }
        }
        wireUnitSprites(main, start, phase, map, rom)
        // The player-phase menus and windows read text, item and UI data outside the windowed ROM
        // views some tests boot with; the app always has the whole ROM.
        if (rom.isWholeRom) wirePlayerPhase(main, start, phase, active, eventInfos)
        if (rom.isWholeRom) main.hooks.chapterIntro = chapterIntro(start).script
        main to start
    }

    /**
     * The `ChapterMapGfx` of the battle map start with the text, UI frame and icon resets of
     * `sub_80156D4` wired (the menus and side windows need them). Stand-in until M6-F:
     * `ResetFaces` (the stat screen faces are not ported).
     */
    private fun battleMapGfx(chapters: ChapterData): ChapterMapGfx =
        ChapterMapGfx(rom, chapters, hw, control.playSt).also {
            it.resetText = { resetText() }
            it.loadLegacyUiFrameGraphics = { loadLegacyUiFrame() }
            it.loadUiFrameGraphics = {
                UiFrame.loadUiFrameGraphics(hw, rom, control.playSt.windowColor)
            }
            it.resetIconGraphics = { resetIcons() }
            it.loadIconPalettes = { palId -> loadIconPalettes(palId) }
        }

    /** `ResetText`: the default font at VRAM + 0x1000, tile 0x80. */
    private fun resetText() {
        talkScene.text.resetText()
    }

    /**
     * `ResetIconGraphics_`: clears both icon lookup tables.
     *
     * Transcribed from src/icon.c: ResetIconGraphics_ (not verified: the debug wrapper of
     * `ResetIconGraphics`; both tables are already empty at f3720 and f3721 of
     * `ram_trace_prologue_clear`, where the event end runs it)
     */
    private fun resetIcons() {
        ItemIcon.resetIconGraphics(drawnIconLookupTable, iconGfxIdLookupTable)
    }

    /** `LoadIconPalettes(palId)`: icon palettes 0 and 1 into palette banks `palId` and +1. */
    private fun loadIconPalettes(palId: Int) {
        ItemIcon.loadIconPalettes(hw, rom, palId)
    }

    /**
     * `ReadGameSaveCoreGfx` (bm.c) for the resumed game and the end of an event:
     * [ChapterMapGfx.readGameSaveCoreGfx] with the hooks of [battleMapGfx]. Stand-in until M6-F:
     * `ResetFaces`.
     */
    private fun readGameSaveCoreGfx(chapters: ChapterData) {
        battleMapGfx(chapters).readGameSaveCoreGfx()
    }

    /**
     * Stand-in until M6-F for `LoadLegacyUiFrameGraphics` (uiutils.c): the window frame image
     * of `gPlaySt.config.windowColor` to BG tile 0 and its palette to palette 1, from the ROM
     * lookup tables (not verified against a capture).
     */
    private fun loadLegacyUiFrame() {
        val color = control.playSt.windowColor
        rom.decompressInto(rom.u32(LEGACY_FRAME_IMAGES + 4 * color), hw.vram, 0)
        rom.pal(rom.u32(LEGACY_FRAME_PALETTES + 4 * color), 16).copyInto(hw.palette, 16)
        hw.enablePaletteSync()
    }

    /**
     * Connects the player phase hooks ([PlayerPhaseWiring]), then builds the enemy phase
     * ([CpPhase]) and the phase start and end housekeeping ([PhaseStartEnd]) into the hooks of
     * [main]; the unported parts of the enemy phase are listed at the [CpPhase] construction.
     */
    private fun wirePlayerPhase(
        main: BMapMain,
        start: BattleMapStart,
        phase: PlayerPhase,
        active: ActiveUnit,
        eventInfos: EventInfos,
    ) {
        val playSt = control.playSt
        val loader = statsUnitLoader.apply { bmMap = battleBmMap }
        val handles = checkNotNull(spriteHandles)
        val battle = BmBattle(rom, units, loader, playSt, loop.bmSt) { y, x ->
            battleBmMap.terrain[y, x]
        }.also {
            it.pidStatsAddExpGained = { pid, exp -> pidStats.pidStatsAddExpGained(pid, exp) }
            it.pidStatsRecordDefeatInfo = { pid, killerPid ->
                pidStats.pidStatsRecordDefeatInfo(
                    pid, killerPid, PidStats.DEFEAT_CAUSE_COMBAT,
                    playSt.chapterIndex, playSt.chapterTurnNumber,
                )
            }
        }
        val battleGenerate = BattleGenerate(battle, rom, loader, playSt, rng, Mechanics()).also {
            it.bmMap = battleBmMap
            it.unitTable = units
            it.pidStatsRecordBattleRes = {
                pidStats.pidStatsRecordBattleRes(battle.actor, battle.target, playSt)
            }
            it.pidStatsAddBattleAmt = { src, offset ->
                pidStats.pidStatsAddBattleAmt(src, offset)
            }
        }
        PlayerPhaseWiring(
            engine, rom, hw, loop.bmSt, playSt, battleBmMap, units, flags, keys,
            IntSupplier { clock.get().toInt() }, control.sound, loop, camMove, bmapRender,
            battleEntityMaps, talkScene, handles,
            ChapterMapGfx(rom, battleChapters, hw, control.playSt),
            MapAnimScanline.PaletteLineWriter { line, index, color ->
                state.lines.writePalette(line, index, color)
            },
            mu, active, loader,
            { start.hooks.getBattleMapKind() }, trapPool,
            { resetIcons() }, IntConsumer { palId -> loadIconPalettes(palId) },
            drawnIconLookupTable, iconGfxIdLookupTable, mapCursor, battle,
            battleGenerate,
            { eventEngine.eventEngineExists() },
            { eventEngine.battleEventEngineExists() },
            pidStats,
        ).also { playerWiring = it }.wire(phase, main, eventInfos)
        // The enemy phase (EP-2a, EP-3a/b): `AiPhaseInit`, the unit order and the decision loop.
        // The script interpreter (EP-4), the attack search (EP-5), the score (EP-6a) and the reach
        // and movement maps (EP-7) run, so an enemy in reach decides its attack. The perform proc
        // `gProcScr_CpPerform` walks the unit (EP-8). Left null: its action half (EP-9, EP-9b),
        // the four EP-6b score components and tile position components, and the EP-12 branches
        // the Prologue never enters. So nothing is performed: End turn runs the enemy banner,
        // walks each deciding enemy to its tile, marks every enemy as moved and goes on to the
        // next player phase.
        val cp = CpPhase(
            engine, rom, playSt, units, loader, battleBmMap, battleEntityMaps, active, rng,
        )
        cp.battle?.battleUnits = battle
        cp.battle?.hooks?.battleGenerateSimulation = AiBattleSimulation { a, t, x, y, slot ->
            battleGenerate.battleGenerateSimulation(
                units.bytes, units.unitOffset(a), units.unitOffset(t), x, y, slot,
            )
        }
        cp.decide.hooks.renderBmMap = AiStep { bmapRender.renderBmMap() }
        cp.decide.hooks.refreshUnitSprites = AiStep { main.hooks.refreshUnitSprites() }
        cp.decide.hooks.writeSuspendSave = AiIntStep { slot ->
            main.suspendPointType = active.action.suspendPointType
            main.hooks.writeSuspendSave(slot)
        }
        // The perform proc's movement half (EP-8): the camera, the unit's walk (an MU along the
        // best path), the target cursor and the action (EP-9: an attack through the player
        // phase's `ApplyUnitAction` and the map combat, or no action); `CpPerform_EquipBest` is
        // EP-9b and the fade EP-9c.
        val bmxFade = BmxFade(engine, loop.bmSt, units, hw, playSt)
        val perform = CpPerform(
            engine, loop.bmSt, playSt, units, ItemInfo(rom), battleEntityMaps, active,
            cp.decide.decision, battleBmMap,
            MovementMap(rom, battleBmMap, units, playSt), camMove, mu, phase, keys, rng,
            phase.workingMovementScript,
            CpEquip(rom, units, active, cp.decide.decision, battleBmMap, loader, battle,
                checkNotNull(cp.utility)),
        )
        perform.hooks.musicProc3Exists = AiTry {
            engine.find(control.sound.delayedSongScript) != null
        }
        perform.hooks.startMapSongBgm = AiStep { main.hooks.startMapSongBgm() }
        perform.hooks.hideUnitSprite = AiIntStep { id -> handles.hideUnitSprite(id) }
        perform.hooks.startMu = AiIntStep { id -> phase.hover.hooks.startMu(id) }
        perform.hooks.endAllMus = AiStep { mu.endAllMus() }
        perform.hooks.updateAllPhaseHealingAIStatus = AiStep {
            cp.utility?.updateAllPhaseHealingAiStatus()
        }
        perform.hooks.renderBmMap = AiStep { bmapRender.renderBmMap() }
        perform.hooks.renderBmMapOnBg2 = AiStep { bmapRender.renderBmMapOnBg2() }
        perform.hooks.newBmxFade = AiIntStep { flag -> bmxFade.newBmxFade(flag != 0) }
        perform.hooks.showUnitSprite = AiIntStep { id -> handles.showUnitSprite(id) }
        perform.hooks.refreshUnitSprites = AiStep { main.hooks.refreshUnitSprites() }
        perform.hooks.putMapCursor = PlayerPhase.PutMapCursor { x, y, kind ->
            mapCursor.put(x, y, kind)
        }
        cp.decide.hooks.startCpPerform = perform.start
        main.hooks.cpPhase = cp.phaseScript
        main.hooks.berserkCpPhase = cp.berserkPhaseScript
        // `gSelectTargets` of the C: the trap damage targets (bmtrick.c) and the phase lists and
        // `sub_8026414` (bmtarget.c) fill and read the one list. The player phase keeps its own.
        val phaseTargetList = SelectTargets()
        val trapsObj = MapTraps(
            trapPool, battleBmMap, units, playSt, flags, phaseTargetList,
            engine = engine, action = active.action,
        ).also {
            it.hooks.refreshEntityBmMaps = { battleEntityMaps.refreshEntityBmMaps() }
            it.hooks.callGameOverEvent = { eventEngine.callGameOverEvent() }
        }
        // Phase start and end housekeeping (EP-1, EP-1b), over the list shared with the traps;
        // EP-w shares it with the player phase.
        val phaseTargets = PhaseTargets(
            rom, units, phaseTargetList, battleBmMap, trapsObj, rng,
            { id -> loader.getUnitMaxHp(units.unitOffset(id)) },
        ).also {
            it.hooks.recordDefeatInfo = { pid, cause ->
                pidStats.pidStatsRecordDefeatInfo(
                    pid, 0, cause, playSt.chapterIndex, playSt.chapterTurnNumber,
                )
            }
            it.hooks.recordLoseData = { pid -> pidStats.pidStatsRecordLoseData(pid) }
        }
        trapsObj.hooks.prepareTrapDefeats = { phaseTargets.sub8026414(3) }
        val phaseStartEnd = PhaseStartEnd(
            engine, units, playSt, phaseTargetList, phaseTargets,
        ).also {
            it.hooks.pidStatsSubFavval08 = PhaseStartEnd.PidAction { pid ->
                pidStats.pidStatsSubFavval08(pid)
            }
        }
        main.hooks.clearActiveFactionGrayedStates =
            { phaseStartEnd.clearActiveFactionGrayedStates() }
        main.hooks.tickActiveFactionTurn = { phaseStartEnd.tickActiveFactionTurn() }
        // `UpdatePrevDeployStates` (BMapMain label 1, before the beginning events).
        val deployState = UnitDeployState(
            units, statsUnitLoader, control.playSt,
            object : UnitDeployStateHooks {
                override fun refreshEntityBmMaps() { battleEntityMaps.refreshEntityBmMaps() }

                override fun endAllMus() { mu.endAllMus() }
            },
        )
        main.hooks.updatePrevDeployStates = { deployState.updatePrevDeployStates() }
        main.hooks.processTurnSupportExp = { battle.supportReliance.processTurnSupportExp() }
        supportReliance = battle.supportReliance
        main.hooks.statusDecayDisplay = phaseStartEnd.statusDecayDisplay
        main.hooks.terrainHealDisplay = phaseStartEnd.terrainHealDisplay
        main.hooks.poisonDamageDisplay = phaseStartEnd.poisonDamageDisplay
        main.hooks.gorgonEggHatchDisplay = phaseStartEnd.gorgonEggHatchDisplay
        mapTraps = trapsObj
        battleEntityMaps.traps = trapsObj
        // `GetTrapAt(x, y)->type` for RideCommandUsability; -1 when no trap is there.
        playerWiring?.unitMenu?.hooks?.trapTypeAt = { x, y ->
            trapsObj.getTrapAt(x, y).let { if (it < 0) -1 else trapsObj.type(it) }
        }
        // `gGMData.state.bits.state_0` and `GetNextUnclearedChapter` for HasConvoyAccess.
        playerWiring?.unitMenu?.convoy?.hooks?.let { convoyHooks ->
            convoyHooks.isGmState0 = { gmData.stateRaw and 1 != 0 }
            convoyHooks.getNextUnclearedChapter = {
                if (!rom.isWholeRom) {
                    -1
                } else {
                    val node = WorldMapPath(gmData, WmPathData(rom)).getNextUnclearedNode()
                    if (node < 0) {
                        -1
                    } else {
                        WorldMapNodes(WmNodeData(rom), gmData, control.playSt).chapterId(node)
                    }
                }
            }
        }
        phase.hooks.getPickTrapType = { unit -> trapsObj.getPickTrapType(unit) }
        battleGenerate.disableAllLightRunes = { trapsObj.disableAllLightRunes() }
        battleGenerate.enableAllLightRunes = { trapsObj.enableAllLightRunes() }
        main.hooks.updateTraps = trapsObj.updateTrapsScript
        main.hooks.decayTraps = { trapsObj.decayTraps() }
    }

    /**
     * The chapter intro (`gProcScr_ChapterIntro`, D11-ci5) over the battle map state, the real
     * screen fades, the live key status ([keys], so A/B/Start skip it) and the game's
     * [SoundWrapper]. Needs the whole ROM (title graphics).
     */
    private fun chapterIntro(start: BattleMapStart): ChapterIntro {
        val bm = loop.bmSt
        val path = WorldMapPath(gmData, WmPathData(rom))
        val nodes = WmNodeData(rom)
        val hooks = ChapterIntro.Hooks(
            newKeys = { keys.newKeys },
            bMapDispSuspend = { start.bMapDispSuspend() },
            bMapDispResume = { start.bMapDispResume() },
        ).also {
            it.getBattleMapKind = { start.hooks.getBattleMapKind() }
            it.refreshBMapGraphics = { refreshMapGraphics() }
            it.getPlayChapterId = { chapter ->
                (0 until WM_NODE_COUNT).firstOrNull { node ->
                    nodes.chapterId(node, control.playSt.chapterModeIndex) == chapter
                } ?: -1
            }
            it.getNextUnclearedNode = { path.getNextUnclearedNode() }
            it.gameClock = ChapterIntro.GameClockSource { clock.get().toInt() }
        }
        return ChapterIntro(
            engine, hw, ColorFade(hw.palette), loop.oam, rom, control.sound, bm, control.playSt,
            battleChapters, ChapterMapGfx(rom, battleChapters, hw, control.playSt),
            MapAnimations(rom, battleChapters, hw, control.playSt, engine, bm),
            checkNotNull(unitSprites), battleEntityMaps, bmapRender, hooks, io,
        )
    }

    /**
     * Replaces the default unit sprite hooks of [main] and [start] with the ported `bmudisp.c`
     * loaders and the `PutUnitSpritesOam` of the map task, on the shared state.
     */
    private fun wireUnitSprites(
        main: BMapMain,
        start: BattleMapStart,
        phase: PlayerPhase,
        map: BmMap,
        rom: RomView,
    ) {
        val bm = loop.bmSt
        val sprites = UnitSprites(rom, hw, bm).also { it.clockSource = clock }
        unitSprites = sprites
        val handles = UnitSpriteHandles(rom, sprites, units)
        val oam = UnitSpritesOam(
            rom, units, handles, bm, control.playSt, battleChapters, flags, loop.oam,
        ).also {
            it.clockSource = clock
            it.battleMapKind = { start.hooks.getBattleMapKind() }
            it.fogAt = { x, y -> map.fog[y, x] }
            it.terrainAt = { x, y -> map.terrain[y, x] }
        }
        val traps = trapPool
        val mapUnitAt = UnitSpriteHandles.MapUnitAt { x, y -> map.unit[y, x] }
        val refresh = { handles.refreshUnitSprites(mapUnitAt, traps) }
        spriteHandles = handles
        refreshSprites = refresh
        val anims = MapAnimations(rom, battleChapters, hw, control.playSt)
        start.hooks.initMapAnimations = { anims.initMapAnimations() }
        start.hooks.updateMapImgAnimations = { anims.updateMapImgAnimations() }
        start.hooks.updateMapPalAnimations = { anims.updateMapPalAnimations() }
        start.hooks.applyUnitSpritePalettes = { sprites.applyUnitSpritePalettes() }
        // `ResetMenuOverrides` (`sMenuOverrides`, owned by the player phase menu) and
        // `ClearTemporaryUnits` (then `RefreshEntityBmMaps`, `RefreshUnitSprites`).
        start.hooks.resetMenuOverrides = { playerWiring?.menu?.overrides?.clear() }
        start.hooks.clearTemporaryUnits = {
            units.clearTemporaryUnits()
            battleEntityMaps.refreshEntityBmMaps()
            refresh()
        }
        start.hooks.resetUnitSprites = { sprites.resetUnitSprites() }
        start.hooks.refreshUnitSprites = refresh
        start.hooks.syncUnitSpriteSheet = { sprites.syncUnitSpriteSheet() }
        start.hooks.putUnitSpritesOam = { oam.putUnitSpritesOam() }
        main.hooks.refreshUnitSprites = refresh
        val refreshProc = ProcFunc { refresh() }
        phase.hooks.refreshUnitSprites = refreshProc
        phase.hooks.putMapCursor = PlayerPhase.PutMapCursor {
            x, y, kind -> mapCursor.put(x, y, kind)
        }
        phase.hover.hooks.hideUnitSprite = { id -> handles.hideUnitSprite(id) }
        phase.hover.hooks.showUnitSprite = { id -> handles.showUnitSprite(id) }
        phase.hover.hooks.endAllMus = { mu.endAllMus() }
        // One snapshot refilled per hover start: the idle loop must not allocate.
        val hoverMuUnit = MuUnit(address = 0, xPos = 0, yPos = 0, classId = 0, state = 0)
        phase.hover.hooks.startMu = { id ->
            hoverMuUnit.address = UnitTable.BLUE_ADDRESS + units.unitOffset(id)
            hoverMuUnit.xPos = units.xPos(id)
            hoverMuUnit.yPos = units.yPos(id)
            hoverMuUnit.classId = units.classId(id)
            hoverMuUnit.state = units.state(id)
            hoverMuUnit.spritePalette = handles.getUnitSpritePalette(id)
            mu.startMu(hoverMuUnit)
        }
    }

    /**
     * `WriteSuspendSave` for [BMapMain.Hooks.writeSuspendSave], on [saveLib].
     *
     * Stand-ins, until the state exists (M6-B: `gActionData`, menu override; M6-H: traps,
     * convoy; M6-I: dungeon state): `gActionData` is the [SuspendRam.actionData] buffer (zeros
     * apart from the RN state the save stores and `suspendPointType`, which is merged in from
     * [BMapMain.suspendPointType] at offset 0x16); the traps, convoy, menu override and dungeon
     * state are the zeroed buffers of [SuspendRam] (their owners are not ported); `wmStuff` is
     * serialized from [gmData] via [BmSaveGmap.writeWorldMapStuff].
     */
    private fun suspendSaveHook(main: BMapMain, rom: RomView): (Int) -> Unit {
        val save = BmSuspendSave(saveLib, rom)
        return { slot ->
            val ram = suspendRam
            ram.actionData[ACTION_SUSPEND_POINT_TYPE] = main.suspendPointType.toByte()
            save.writeSuspendSave(ram, slot)
            flushSram()
        }
    }

    /** The m4a driver whose VBlank and main steps [loop] runs; null: silent. */
    private val driver: M4aDriver? = m4a?.driver(pcm)

    /** The opening animation (`OpAnim`) shown before the title. */
    public val opAnim: OpAnim =
        BootRom.opAnim(rom, engine, hw, keys, control).also { opAnimRef = it }

    /**
     * The opening monologue (`StartIntroMonologue`, `src/opsubtitle.c`) with the real fades, the
     * live key status and the game's [SoundWrapper]. Needs the whole ROM (scene graphics).
     */
    public val opSubtitle: OpSubtitle by lazy {
        val archivedFade = ArchivedPaletteFade(PaletteFade(hw, engine), hw, engine)
        OpSubtitle(
            engine, hw, rom, control.sound,
            OpSubtitle.Hooks(
                newFadeIn = { speed, proc -> archivedFade.newFadeIn(speed, proc) },
                fadeInExists = { archivedFade.exists() },
                startSlowFadeToBlack = { screenFade.startSlowFadeToBlack() },
                startFastFadeToBlack = { screenFade.startFastFadeToBlack() },
                waitForFade = { screenFade.waitForFade(it) },
                newKeys = { keys.newKeys },
            ),
        )
    }

    /** The Prologue world map ([WorldMapBoot]); needs the whole ROM, built on first use. */
    private val worldMapBootDelegate = lazy {
        bmapMain // builds the unit sprites the world map shares
        WorldMapBoot(
            engine, hw, rom, control.playSt, gmData, loop.bmSt, keys, loop.sprites, loop.oam,
            units, eventEngine, eventState, flags, eventTextHost, eventText, screenFade,
            control.sound, talkScene, mu, { unitSprites }, battleChapters, state.lines,
        ).also { it.wire() }
    }
    private val worldMapBoot: WorldMapBoot by worldMapBootDelegate

    /** The title screen. */
    public val title: TitleScreen =
        BootRom.titleScreen(rom, engine, hw, opAnim.sprites, keys, control).also { titleRef = it }

    /**
     * The main loop. A screen load overruns its frame on hardware (one missed VBlank), detected by
     * the BG sync request a load leaves.
     */
    public val loop: GameLoop = GameLoop(
        engine, keys, clock, keyInput,
        sprites = opAnim.sprites,
        overran = {
            val missedVBlanks = opAnim.missedVBlanks + title.missedVBlanks
            opAnim.missedVBlanks = 0
            title.missedVBlanks = 0
            if (missedVBlanks == 0 && hw.modifiedBgs != 0 && engine.find(ui.script) != null) {
                1
            } else {
                missedVBlanks
            }
        },
        flush = {
            hw.lcd.flushTo(io)
            hw.flushBackgrounds()
            muSpriteAnim?.flushTileMoves()
            if (talkSceneDelegate.isInitialized()) talkScene.flushTileMoves()
        },
        soundVSync = { driver?.vSync() },
        soundMain = { driver?.main() },
    )

    /** Display state the renderers draw from, filled by [prepare]. */
    private val state = RenderState()

    /** Tile graphics of [state]. */
    private val sheets = TileSheets()

    /** Packed per-frame data for the GPU backend, drawn from the same state as [screen]. */
    public val gpuFrame: GpuFrame = GpuFrame(state, sheets)

    /** Tile graphics the GPU backend uploads (sheet ids as in [gpuFrame]'s state). */
    public val tileSheets: TileSheets get() = sheets

    /** Copies the synced hardware state into [state] and [sheets]. */
    private val loader = HardwareSceneLoader()

    private val renderer = SoftwareRenderer(state, sheets, screen, colorMath)
    private val camera = Camera()

    init {
        opAnim.afterStep = loop::afterStep
        control.hooks.startBattleMap = ProcFunc { battleMap.startBattleMap(it) }
        control.hooks.startResumedGame = ProcFunc { battleMap.startResumedGame(it) }
        // The New Game stand-in (M6-E) leads into the battle map, which reads ROM data outside
        // the boot windows: with windows only (debug APK without an imported ROM) the save menu
        // holds, instead of crashing on the first chapter read.
        if (rom.isWholeRom) control.hooks.startSaveMenu = ProcFunc { newGameSaveMenu() }
        if (rom.isWholeRom) {
            control.hooks.startIntroMonologue = ProcFunc { opSubtitle.startBlocking(it) }
            control.hooks.makeSaveMenuPostChapter =
                ProcFunc { SaveMenuAssembly.makeHook(postChapterSaveMenu)(it) }
            control.hooks.worldMapWrapperProvider = { worldMapBoot.wrapperScript }
            control.hooks.endWorldMap = ProcFunc { worldMapBoot.endWm(it) }
        }
        control.rng = rng
        control.hooks.endAllMus = ProcFunc { mu.endAllMus() }
        control.hooks.clearTemporaryUnits = ProcFunc { battleMap.hooks.clearTemporaryUnits() }
        control.hooks.winFlagSet = { flags.checkFlag(EVFLAG_WIN) }
        control.hooks.chapterSwitch = ProcFunc { control.chapterSwitch(it) }
        control.hooks.callPostChapterSaveMenu = ProcFunc { control.callPostChapterSaveMenu(it) }
        if (rom.isWholeRom) {
            control.hooks.startPostChapterSaveMenu =
                ProcFunc { SaveMenuAssembly.makeHook(postChapterSaveMenu)(it) }
        }
        control.sound.setDefaultMaxNumChannels() // AgbMain, after m4aSoundInit
        control.hooks.gmDataInit = ProcFunc { gmData.init() }
        control.hooks.registerChapterTimeAndTurnCount = ProcFunc {
            chapterStats.registerChapterTimeAndTurnCount(control.playSt, clock.get().toInt())
        }
        if (rom.isWholeRom) {
            val itemInfo = ItemInfo(rom)
            val rankings = ChapterRankings(
                control.playSt, chapterStats, pidStats, units, battleChapters,
                getPartyTotalGoldValue = {
                    val convoy = playerWiring?.unitMenu?.convoy ?: BmContainer()
                    PartyGoldValue(itemInfo, units, convoy, control.playSt).getPartyTotalGoldValue()
                },
            )
            control.hooks.computeChapterRankings = ProcFunc { rankings.computeChapterRankings() }
            control.hooks.chapterChangeUnitCleanup = ProcFunc {
                units.chapterChangeUnitCleanup(control.playSt) { id ->
                    statsUnitLoader.getUnitMaxHp(units.unitOffset(id))
                }
            }
        }
        control.hooks.blendSetup = ProcFunc {
            hw.lcd.setBlendConfig(3, 0, 0, 16)
            hw.lcd.setBlendTargetA(1, 1, 1, 1, 1)
            hw.lcd.setBlendBackdropA(1)
        }
        gmData.init() // AgbMain: GmDataInit() after SetInterrupt_LCDVBlank, before StartGame
        control.startGame()
    }

    /** Runs one GBA frame (VBlank + main step). */
    public fun frame() {
        loop.frame()
    }

    /** Scratch I/O image the HBlank handler writes per line during capture. */
    private val hblankIo = ByteArray(io.size)

    /** Runs the active HBlank handler for one line against the scratch register image. */
    private val hblankLine = HBlankLines.LineHandler { lineIndex, regs ->
        hw.hblank.active?.onHBlank(lineIndex, regs)
    }

    /**
     * Draws the display state synced by the last VBlank into [screen], with the active HBlank
     * handler's per-line register writes (run here, once per drawn frame).
     */
    public fun render() {
        prepare()
        renderer.render(camera)
    }

    /** Like [render], but packs [gpuFrame] for the GPU backend instead of drawing [screen]. */
    public fun pack() {
        prepare()
        gpuFrame.pack()
    }

    /** Prepares the hardware state and captures HBlank line effects before rendering. */
    private fun prepare() {
        loader.load(state, sheets, hw, loop.oam, io)
        if (hw.hblank.irqEnabled && hw.hblank.active != null) {
            HBlankLines.capture(io, state, hblankIo, hblankLine)
        }
    }
}

/** `EVFLAG_WIN`: the chapter's win flag, read by `GameControl_ChapterSwitch`. */
private const val EVFLAG_WIN = 3

/** World map nodes tried by `GetPlayChapterId`. */
private const val WM_NODE_COUNT = 0x1D

/** Offset of `suspendPointType` in `struct ActionData`. */
private const val ACTION_SUSPEND_POINT_TYPE = 0x16

/** `gTrapArray`: 0x40 entries of `struct Trap` (8 bytes). */
private const val TRAP_POOL_SIZE = 0x40 * 8

/** Size of the IWRAM image that holds `gGMData`. */
private const val GM_DATA_IWRAM_SIZE = 0x8000

/** Fallback for a ROM without the world map data (windows only): no node holds a chapter. */
private val NO_WORLD_MAP_NODES = object : BattleMapNodes {
    override fun chapterId(node: Int): Int = -1

    override fun isCleared(node: Int): Boolean = false

    override fun encounters(node: Int): Int = 0

    override fun placementFlag(node: Int): Int = 0
}

/** Offset of `goalWindowDataType` in `struct ChapterData` (the ROM chapter struct). */
private const val GOAL_WINDOW_DATA_TYPE_OFFSET = 0x8C

/** Offset of `tutorialEvents` in `ChapterEventGroup`. */
private const val TUTORIAL_EVENTS_OFFSET = 0x1C

/** Offset of `protectCharacterIndex` in `struct ChapterData`. */
private const val OFF_PROTECT_CHARACTER = 0x8E

/** `gConvoBackgroundData`: gfx, tsa, pal pointers per background. */
private const val CONVO_BACKGROUND_DATA = 0x0895DD1C

/** `BG_BLANK`: the random text background is `NextRN_N(BG_BLANK)`, below the blank one. */
private const val BG_BLANK = 0x35

/** `gMsgHuffmanTable`, `gMsgHuffmanTableRoot` and `gMsgTable` of FE8U. */
private const val MSG_HUFFMAN_TABLE = 0x0815A72C
private const val MSG_HUFFMAN_ROOT = 0x0815D488
private const val MSG_TABLE = 0x0815D48C

/** `sLegacyUiFrameImageLookup`. */
private const val LEGACY_FRAME_IMAGES = 0x085B6460

/** `sLegacyUiFramePaletteLookup`. */
private const val LEGACY_FRAME_PALETTES = 0x085B6440
