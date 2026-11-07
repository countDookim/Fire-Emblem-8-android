package fe8.game

import fe8.game.bm.BMapMain
import fe8.game.bm.BMapMain.Companion.SAVE_ID_SUSPEND
import fe8.game.bm.BattleMapNodes
import fe8.game.bm.BattleMapStart
import fe8.game.bm.BmMap
import fe8.game.bm.BmSt
import fe8.game.bm.BmMapRender
import fe8.game.bm.CamMove
import fe8.game.bm.ChapterData
import fe8.game.bm.ChapterMapGfx
import fe8.game.bm.MapAnimations
import fe8.game.bm.MapCursor
import fe8.game.bm.MapMusic
import fe8.game.bm.EntityBmMaps
import fe8.game.bm.PLAY_FLAG_HARD
import fe8.game.bm.PlayerPhase
import fe8.game.bm.ResetCursorPosition
import fe8.game.bm.UnitLoader
import fe8.game.bm.UnitSpriteHandles
import fe8.game.bm.UnitSprites
import fe8.game.bm.UnitSpritesOam
import fe8.game.bm.UnitTable
import fe8.game.event.EventConst
import fe8.game.event.EventEngine
import fe8.game.event.EventEngineHooks
import fe8.game.event.EventBgm
import fe8.game.event.EventFade
import fe8.game.event.EventHandler
import fe8.game.event.EventScrCore
import fe8.game.event.EventState
import fe8.game.event.EventUnitLoads
import fe8.game.event.EventChangeMap
import fe8.game.event.EventInfoHooks
import fe8.game.event.EventInfos
import fe8.game.event.ReleaseEventStubs
import fe8.game.event.ScriptWords
import fe8.game.flags.Fe8EventFlags
import fe8.game.opanim.EarlyStartUi
import fe8.game.opanim.OpAnim
import fe8.game.save.BmSaveLib
import fe8.game.save.BmSuspendSave
import fe8.game.save.NewGameRam
import fe8.game.save.SuspendRam
import fe8.game.title.TitleScreen
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
import gbafe.core.rng.RngService
import gbafe.core.time.GameClock
import gbafe.input.KeyStatus
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.render.HardwareSceneLoader
import gbafe.render.ScreenFade

/**
 * The boot sequence wired for a device: `StartGame` -> game control -> health & safety screen ->
 * Nintendo / Intelligent Systems logos ([EarlyStartUi]) -> op anim ([OpAnim]) -> title
 * ([TitleScreen]), driven by the [GameLoop] (one [frame] per GBA frame) and drawn from the
 * synced display state ([HardwareSceneLoader] -> [SoftwareRenderer], or the GPU renderer on
 * device). With [m4a], the boot's BGM and sound effects play through [SoundWrapper], mixed in
 * the VBlank exactly as traced (`Fe8BootSoundTest`); CGB (PSG) channels are still silent.
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
    /** `gKeyStatusPtr`. */
    public val keys: KeyStatus = KeyStatus()

    /** `gGameClock`. */
    public val clock: GameClock = GameClock()

    /** Proc trees. */
    public val engine: ProcEngine = ProcEngine()

    /** The early start screens and their display state. */
    public val ui: EarlyStartUi = EarlyStartUi(
        engine,
        EarlyStartUi.Env(
            ShortArray(512), ShortArray(512), gbafe.render.LcdControl(), keys,
            rom.pal(0x08B125F4, 16), rom.pal(0x08B10ABC, 16), rom.pal(0x08B11864, 48),
            gfx = EarlyStartUi.Gfx(
                rom.blob(0x08B11D0C), rom.blob(0x08B118C4), rom.blob(0x08B11C0C), rom.blob(0x08B10630),
                rom.blob(0x08B104D8), rom.blob(0x08B10CA4), rom.blob(0x08B10ADC),
            ),
            fadeOutBgm = { control.sound.fadeOutBgm(it) },
        ),
    )

    private val hw = ui.env.hardware

    /** The blend fades of `src/bmlib.c` (`StartMidFadeFromBlack`, the event fade command). */
    public val screenFade: ScreenFade = ScreenFade(engine, hw.lcd)
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

    /** The suspend save's RAM, shared by the write hook and the resume read (`actionData`). */
    private val suspendRam: SuspendRam by lazy {
        SuspendRam(control.playSt, loop.bmSt, rng, units, flags, clock, eventState)
    }

    private fun flushSram() {
        saveStorage?.let { sram.flushTo(it) }
    }

    /**
     * Stand-in for `StartSaveMenu` (the save menu is not ported). With a valid suspend save in
     * [sram] it takes the menu's "Resume" choice: reads the suspend save
     * ([BmSuspendSave.readSuspendSave]) and sets `nextAction` to `GAME_ACTION_4`, so the game
     * control proc continues at the resumed game. Otherwise it is the dev "New Game" choice: writes
     * a fresh game to slot 0 ([BmSaveLib.writeNewGameSave]) and leaves `nextAction` at event
     * return, so the game control proc continues to chapter 0.
     */
    private fun newGameSaveMenu() {
        if (saveLib.isValidSuspendSave(SAVE_ID_SUSPEND)) {
            BmSuspendSave(saveLib, rom).readSuspendSave(suspendRam, SAVE_ID_SUSPEND)
            control.setNextGameActionId(GameControl.GAME_ACTION_4)
            return
        }
        saveLib.writeNewGameSave(
            NewGameRam(control.playSt, flags, ByteArray(200)), 0, false, 0, -1,
        )
        flushSram()
        control.setNextGameActionId(GameControl.GAME_ACTION_EVENT_RETURN)
    }

    /** The units the battle map start path touches (raw-unit stand-in). */
    public val units: UnitTable by lazy { UnitTable(rom) }

    /** The event engine's globals, shared by [eventEngine] and the suspend save. */
    public val eventState: EventState = EventState()

    /** `gChapterFlagBits` and friends. */
    public val flags: EventFlags = Fe8EventFlags.create()

    /**
     * The event engine (`EventEngine`) with the ported command handlers of [EventScrCore]
     * registered; the other commands stay unknown. Hooks are the defaults (stand-ins).
     */
    public val eventEngine: EventEngine by lazy {
        val state = eventState
        val memory = ScriptWords({ rom.u16(it) })
        // `gKeyStatusPtr->newKeys & START_BUTTON`: the game's single KeyStatus.
        val hooks = object : EventEngineHooks {
            override fun startPressed(): Boolean = keys.newKeys and KeyStatus.START_BUTTON != 0
            override fun fadeExists(): Boolean = screenFade.fadeExists()
            override fun blendY(): Int = hw.lcd.blendY
        }
        EventEngine(engine, state, memory, hooks).also {
            EventScrCore(
                state, memory, flags = flags, rng = rng, playSt = control.playSt, keys = keys,
            ).registerAll(it)
            val loader = UnitLoader(
                units, rom,
                // Stand-in: only unk1C[0..1] are modelled in PlaySt.
                { type ->
                    when (type) {
                        0 -> control.playSt.unk1C0
                        1 -> control.playSt.unk1C1
                        else -> 0
                    }
                },
                rng, { control.playSt.chapterStateBits and PLAY_FLAG_HARD != 0 },
            ).apply {
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
            EventUnitLoads(
                state, memory, rom, units, loader, rng,
                { x, y -> battleBmMap.terrain[y, x] },
                { x, y -> battleBmMap.unit[y, x] },
                { battleBmMap.bmMapFill(battleBmMap.other, 0) },
            ).also { loads ->
                loads.registerOn(it)
                // Stand-in: the walking load (EventLoadUnitWithMovement, MU) is not ported, so a
                // load outside skip mode (e.g. the first update, before skipping can start) runs
                // the silent path as if faded in.
                it.register(0x2C, EventHandler { p ->
                    val bits = p.evStateBits
                    p.evStateBits = bits or EventConst.EV_STATE_FADEDIN
                    val r = loads.event2cLoadUnits.run(p)
                    p.evStateBits = (p.evStateBits and EventConst.EV_STATE_FADEDIN.inv()) or
                        (bits and EventConst.EV_STATE_FADEDIN)
                    r
                })
            }
            EventChangeMap(
                state, memory, control.playSt, loop.bmSt, hw,
                { battleMap.restartBattleMap() },
                { battleEntityMaps.refreshEntityBmMaps() },
                { bmapRender.renderBmMap() },
            ).registerOn(it)
            EventFade(memory, screenFade).registerOn(it)
            EventBgm(state, memory, control.sound).registerOn(it)
            ReleaseEventStubs.registerMissing(it)
        }
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

    private val battleChapters: ChapterData by lazy { ChapterData(rom) }

    /** The battle map's `gBmMap*` pools, shared by the battle map path and the unit loads. */
    private val battleBmMap: BmMap by lazy { BmMap(rom, battleChapters, loop.bmSt) }

    private val bmapRender: BmMapRender by lazy { BmMapRender(loop.bmSt, battleBmMap, hw) }

    private val battleEntityMaps: EntityBmMaps by lazy {
        EntityBmMaps(battleBmMap, units, control.playSt)
    }

    private val bmapMainParts: Pair<BMapMain, BattleMapStart> by lazy {
        val chapters = battleChapters
        val bm = loop.bmSt
        val map = battleBmMap
        val camMove = CamMove(engine, bm)
        val reset = ResetCursorPosition(
            engine, bm, control.playSt, units, camMove, NO_WORLD_MAP_NODES,
        )
        val main = BMapMain(
            engine, control.playSt, units, flags, chapters, rom, reset, camMove,
        )
        val render = bmapRender
        val music = MapMusic(
            control.playSt, chapters, units, flags, control.sound,
        ) { battleMap.hooks.getBattleMapKind() }
        val entityBmMaps = battleEntityMaps
        val phase = PlayerPhase(
            engine, bm, control.playSt, map, units, camMove,
            entityBmMaps, render, keys,
            eventEngine::eventEngineExists,
        ).also { pp -> pp.hooks.startMapSongBgm = ProcFunc { music.startMapSongBgm() } }
        main.hooks.playerPhase = phase.script
        main.hooks.startMapSongBgm = { music.startMapSongBgm() }
        main.hooks.startMidFadeFromBlack = { screenFade.startMidFadeFromBlack() }
        main.hooks.waitForFade = { !screenFade.fadeExists() }
        main.hooks.callEvent = { events, execType, _ ->
            eventEngine.callEvent(events, execType)
        }
        val eventInfos = EventInfos(eventEngine.memory, flags, control.playSt)
        eventInfos.hooks = object : EventInfoHooks {
            override fun callEvent(script: Int, execType: Int) {
                eventEngine.callEvent(script, execType)
            }

            override fun getBattleMapKind(): Int = battleMap.hooks.getBattleMapKind()

            override fun chapterEventGroup(chapter: Int): Int =
                chapters.getChapterEventDataPointer(chapter)

            override fun goalWindowDataType(chapter: Int): Int =
                rom.u8(chapters.getROMChapterStruct(chapter) + GOAL_WINDOW_DATA_TYPE_OFFSET)
        }
        main.hooks.runPhaseSwitchEvents = { eventInfos.runPhaseSwitchEvents() }
        val start = BattleMapStart(
            engine, control.playSt, bm, hw, chapters, map,
            ChapterMapGfx(rom, chapters, hw, control.playSt), render,
            flags, rng, clock, units,
        ).also {
            main.hooks.writeSuspendSave = suspendSaveHook(main, rom)
            main.hooks.getBattleMapKind = { it.hooks.getBattleMapKind() }
            it.hooks.bmapMainScript = main.script
            it.hooks.refreshEntityBmMaps = { entityBmMaps.refreshEntityBmMaps() }
            it.hooks.suspendPointType = {
                suspendRam.actionData[ACTION_SUSPEND_POINT_TYPE].toInt() and 0xFF
            }
        }
        wireUnitSprites(main, start, phase, map, rom)
        main to start
    }

    /**
     * Replaces the unit sprite stand-ins of [main] and [start] with the ported `bmudisp.c`
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
        val handles = UnitSpriteHandles(rom, sprites, units)
        val oam = UnitSpritesOam(
            rom, units, handles, bm, control.playSt, battleChapters, flags, loop.oam,
        ).also {
            it.clockSource = clock
            it.battleMapKind = { start.hooks.getBattleMapKind() }
            it.fogAt = { x, y -> map.fog[y, x] }
            it.terrainAt = { x, y -> map.terrain[y, x] }
        }
        // Stand-in: the trap pool is not ported (no ballistae or light runes).
        val traps = ByteArray(TRAP_POOL_SIZE)
        val mapUnitAt = { x: Int, y: Int -> map.unit[y, x] }
        val refresh = { handles.refreshUnitSprites(mapUnitAt, traps) }
        val anims = MapAnimations(rom, battleChapters, hw, control.playSt)
        start.hooks.initMapAnimations = { anims.initMapAnimations() }
        start.hooks.updateMapImgAnimations = { anims.updateMapImgAnimations() }
        start.hooks.updateMapPalAnimations = { anims.updateMapPalAnimations() }
        start.hooks.applyUnitSpritePalettes = { sprites.applyUnitSpritePalettes() }
        start.hooks.resetUnitSprites = { sprites.resetUnitSprites() }
        start.hooks.refreshUnitSprites = refresh
        start.hooks.syncUnitSpriteSheet = { sprites.syncUnitSpriteSheet() }
        start.hooks.putUnitSpritesOam = { oam.putUnitSpritesOam() }
        main.hooks.refreshUnitSprites = refresh
        val refreshProc = ProcFunc { refresh() }
        phase.hooks.refreshUnitSprites = refreshProc
        val cursor = MapCursor(rom, bm, loop.sprites).also { it.clockSource = clock }
        phase.hooks.putMapCursor = PlayerPhase.PutMapCursor { x, y, kind -> cursor.put(x, y, kind) }
    }

    /**
     * `WriteSuspendSave` for [BMapMain.Hooks.writeSuspendSave], on [saveLib].
     *
     * Stand-ins, until the state exists: `gActionData` is the [SuspendRam.actionData] buffer
     * (zeros apart from the RN state the save stores and `suspendPointType`, which is merged in
     * from [BMapMain.suspendPointType] at offset 0x16); the traps, convoy, menu override, world
     * map info and dungeon state are the zeroed buffers of [SuspendRam] (their owners are not
     * ported).
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

    private val driver: M4aDriver? = m4a?.driver(pcm)

    /** The op anim. */
    public val opAnim: OpAnim = BootRom.opAnim(rom, engine, hw, keys, control).also { opAnimRef = it }

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
            val n = opAnim.missedVBlanks + title.missedVBlanks
            opAnim.missedVBlanks = 0
            title.missedVBlanks = 0
            if (n == 0 && hw.modifiedBgs != 0 && engine.find(ui.script) != null) 1 else n
        },
        flush = {
            hw.lcd.flushTo(io)
            hw.flushBackgrounds()
        },
        soundVSync = { driver?.vSync() },
        soundMain = { driver?.main() },
    )

    private val state = RenderState()
    private val sheets = TileSheets()

    /** Packed per-frame data for the GPU backend, drawn from the same state as [screen]. */
    public val gpuFrame: GpuFrame = GpuFrame(state, sheets)

    /** Tile graphics the GPU backend uploads (sheet ids as in [gpuFrame]'s state). */
    public val tileSheets: TileSheets get() = sheets
    private val loader = HardwareSceneLoader()

    private val renderer = SoftwareRenderer(state, sheets, screen, colorMath)
    private val camera = Camera()

    init {
        opAnim.afterStep = loop::afterStep
        control.hooks.startBattleMap = ProcFunc { battleMap.startBattleMap(it) }
        control.hooks.startResumedGame = ProcFunc { battleMap.startResumedGame(it) }
        // The New Game stand-in leads into the battle map, which reads ROM data outside the boot
        // windows: with windows only (debug APK without an imported ROM) the save menu holds, as
        // before M5-58, instead of crashing on the first chapter read.
        if (rom.isWholeRom) control.hooks.startSaveMenu = ProcFunc { newGameSaveMenu() }
        control.sound.setDefaultMaxNumChannels() // AgbMain, after m4aSoundInit
        control.startGame()
    }

    /** Runs one GBA frame (VBlank + main step). */
    public fun frame() {
        loop.frame()
    }

    private val hblankIo = ByteArray(io.size)
    private val hblankLine: (Int, ByteArray) -> Unit = { v, regs -> hw.hblank.active?.onHBlank(v, regs) }

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

    private fun prepare() {
        loader.load(state, sheets, hw, loop.oam, io)
        if (hw.hblank.irqEnabled && hw.hblank.active != null) {
            HBlankLines.capture(io, state, hblankIo, hblankLine)
        }
    }
}

/** Offset of `suspendPointType` in `struct ActionData`. */
private const val ACTION_SUSPEND_POINT_TYPE = 0x16

/** `gTrapArray`: 0x40 entries of `struct Trap` (8 bytes). */
private const val TRAP_POOL_SIZE = 0x40 * 8

/** World map stand-in until the world map is ported: no node holds a chapter. */
private val NO_WORLD_MAP_NODES = object : BattleMapNodes {
    override fun chapterId(node: Int): Int = -1

    override fun isCleared(node: Int): Boolean = false

    override fun encounters(node: Int): Int = 0

    override fun placementFlag(node: Int): Int = 0
}

private const val GOAL_WINDOW_DATA_TYPE_OFFSET = 0x8C
