package fe8.game

import fe8.game.bm.BMapMain
import fe8.game.bm.BattleMapNodes
import fe8.game.bm.BattleMapStart
import fe8.game.bm.BmMap
import fe8.game.bm.BmMapRender
import fe8.game.bm.CamMove
import fe8.game.bm.ChapterData
import fe8.game.bm.ChapterMapGfx
import fe8.game.bm.ResetCursorPosition
import fe8.game.bm.UnitTable
import fe8.game.event.EventState
import fe8.game.flags.Fe8EventFlags
import fe8.game.opanim.EarlyStartUi
import fe8.game.opanim.OpAnim
import fe8.game.save.BmSaveLib
import fe8.game.save.BmSuspendSave
import fe8.game.save.SuspendRam
import fe8.game.title.TitleScreen
import gba.audio.M4aDriver
import gba.audio.M4aSound
import gba.audio.PcmStream
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
import gbafe.render.HardwareSceneLoader

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
    rom: RomView,
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
     * The save RAM routines (the owner of the cartridge SRAM), or null while the game has no SRAM
     * attached: the battle map's suspend save hook then does nothing.
     */
    public val saveLib: BmSaveLib? = null,
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
    private val io = ByteArray(0x60)

    private lateinit var opAnimRef: OpAnim
    private lateinit var titleRef: TitleScreen

    /** The game control proc's owner. */
    public val control: GameControl =
        GameControl(engine, keys, { ui.script }, { opAnimRef.script }, { titleRef }, m4a)

    /** The units the battle map start path touches (raw-unit stand-in). */
    public val units: UnitTable by lazy { UnitTable(rom) }

    /** The event engine's globals (`gEventSlotCounter` is read by the suspend save). */
    public val eventState: EventState = EventState()

    /** `gChapterFlagBits` and friends. */
    public val flags: EventFlags = Fe8EventFlags.create()

    /**
     * The battle map start path (`StartBattleMap`, `StartBMapMain`), built when the game control
     * proc first reaches it (it reads chapter data from the ROM, which the boot windows may lack).
     */
    public val battleMap: BattleMapStart by lazy {
        val chapters = ChapterData(rom)
        val bm = loop.bmSt
        val map = BmMap(rom, chapters, bm)
        val camMove = CamMove(engine, bm)
        val reset = ResetCursorPosition(
            engine, bm, control.playSt, units, camMove, NO_WORLD_MAP_NODES,
        )
        val main = BMapMain(
            engine, control.playSt, units, flags, chapters, rom, reset, camMove,
        )
        BattleMapStart(
            engine, control.playSt, bm, hw, chapters, map,
            ChapterMapGfx(rom, chapters, hw, control.playSt), BmMapRender(bm, map, hw),
            flags, rng, clock, units,
        ).also {
            main.hooks.writeSuspendSave = suspendSaveHook(main, bm, rom)
            main.hooks.getBattleMapKind = { it.hooks.getBattleMapKind() }
            it.hooks.bmapMainScript = main.script
        }
    }

    /**
     * `WriteSuspendSave` for [BMapMain.Hooks.writeSuspendSave], or a no-op without a [saveLib].
     *
     * Stand-ins, until the state exists: `gActionData` is the [SuspendRam.actionData] buffer
     * (zeros apart from the RN state the save stores and `suspendPointType`, which is merged in
     * from [BMapMain.suspendPointType] at offset 0x16); the traps, convoy, menu override, world
     * map info and dungeon state are the zeroed buffers of [SuspendRam] (their owners are not
     * ported).
     */
    private fun suspendSaveHook(main: BMapMain, bm: fe8.game.bm.BmSt, rom: RomView): (Int) -> Unit {
        val lib = saveLib ?: return {}
        val save = BmSuspendSave(lib, rom)
        val ram = SuspendRam(control.playSt, bm, rng, units, flags, clock, eventState)
        return { slot ->
            ram.actionData[ACTION_SUSPEND_POINT_TYPE] = main.suspendPointType.toByte()
            save.writeSuspendSave(ram, slot)
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

/** World map stand-in until the world map is ported: no node holds a chapter. */
private val NO_WORLD_MAP_NODES = object : BattleMapNodes {
    override fun chapterId(node: Int): Int = -1

    override fun isCleared(node: Int): Boolean = false

    override fun encounters(node: Int): Int = 0

    override fun placementFlag(node: Int): Int = 0
}
