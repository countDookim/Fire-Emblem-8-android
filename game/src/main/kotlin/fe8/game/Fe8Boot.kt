package fe8.game

import fe8.game.opanim.EarlyStartUi
import fe8.game.opanim.OpAnim
import fe8.game.title.TitleScreen
import gbafe.core.time.GameClock
import gbafe.input.KeyStatus
import gbafe.proc.ProcEngine
import gbafe.render.Camera
import gbafe.render.ColorMath
import gbafe.render.FrameBuffer
import gbafe.render.HardwareSceneLoader
import gbafe.render.RenderState
import gbafe.render.SoftwareRenderer
import gbafe.render.TileSheets

/**
 * The boot sequence wired for a device: `StartGame` -> game control -> health & safety screen ->
 * Nintendo / Intelligent Systems logos ([EarlyStartUi]) -> op anim ([OpAnim]) -> title
 * ([TitleScreen]), driven by the [GameLoop] (one [frame] per GBA frame) and drawn from the
 * synced display state ([HardwareSceneLoader] -> [SoftwareRenderer]).
 */
public class Fe8Boot(
    /** ROM data of the boot screens (whole ROM, or the extracted [RomView.BOOT_WINDOWS]). */
    rom: RomView,
    /** Raw pressed GBA keys (`REG_KEYINPUT`, active high) for the next step. */
    keyInput: () -> Int,
    /** Colour-effect arithmetic of [render]; hardware by default. */
    colorMath: ColorMath = ColorMath.Hardware,
    /** The picture [render] draws into. */
    public val screen: FrameBuffer = FrameBuffer(),
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
        ),
    )

    private val hw = ui.env.hardware
    private val io = ByteArray(0x60)

    private lateinit var opAnimRef: OpAnim
    private lateinit var titleRef: TitleScreen

    /** The game control proc's owner. */
    public val control: GameControl =
        GameControl(engine, keys, { ui.script }, { opAnimRef.script }, { titleRef })

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
    )

    private val state = RenderState()
    private val sheets = TileSheets()
    private val loader = HardwareSceneLoader()

    private val renderer = SoftwareRenderer(state, sheets, screen, colorMath)
    private val camera = Camera()

    init {
        control.startGame()
    }

    /** Runs one GBA frame (VBlank + main step). */
    public fun frame() {
        loop.frame()
    }

    /** Draws the display state synced by the last VBlank into [screen]. */
    public fun render() {
        loader.load(state, sheets, hw, loop.oam, io)
        renderer.render(camera)
    }
}
