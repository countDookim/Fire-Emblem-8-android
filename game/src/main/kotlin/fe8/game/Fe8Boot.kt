package fe8.game

import fe8.game.opanim.EarlyStartUi
import fe8.game.opanim.OpAnimHs
import gbafe.core.time.GameClock
import gbafe.input.KeyStatus
import gbafe.proc.ProcEngine
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts
import gbafe.render.Camera
import gbafe.render.ColorMath
import gbafe.render.FrameBuffer
import gbafe.render.HardwareSceneLoader
import gbafe.render.RenderState
import gbafe.render.SoftwareRenderer
import gbafe.render.TileSheets

/**
 * The boot sequence wired for a device: `StartGame` -> game control -> health & safety screen ->
 * Nintendo / Intelligent Systems logos ([EarlyStartUi]), driven by the [GameLoop] (one [frame] per
 * GBA frame) and drawn from the synced display state ([HardwareSceneLoader] -> [SoftwareRenderer]).
 *
 * Not wired yet: op anim and title (game control gets a blocking stand-in after the logos).
 */
public class Fe8Boot(
    /** Graphics and palettes of the early start screens. */
    assets: Assets,
    /** Raw pressed GBA keys (`REG_KEYINPUT`, active high) for the next step. */
    keyInput: () -> Int,
    /** Colour-effect arithmetic of [render]; hardware by default. */
    colorMath: ColorMath = ColorMath.Hardware,
    /** The picture [render] draws into. */
    public val screen: FrameBuffer = FrameBuffer(),
) {
    /** ROM data the boot screens load (from the ROM image or extracted blobs). */
    public class Assets(
        /** `Pal_OpAnimHealthSafetyScreen` (16 colours). */
        public val hsPalette: ShortArray,
        /** `Pal_GameIntroNintendo` (16 colours). */
        public val nintendoPalette: ShortArray,
        /** `Pal_IntelligentSystems` (48 colours). */
        public val isPalette: ShortArray,
        /** Compressed images and TSAs. */
        public val gfx: EarlyStartUi.Gfx,
    ) {
        /** Factory reading the data straight from a ROM image. */
        public companion object {
            /** Reads the boot assets from [rom] (addresses from decomp/fireemblem8.nm.txt). */
            public fun fromRom(rom: ByteArray): Assets {
                fun blob(at: Int) = OpAnimHs.Blob(rom, at - ROM_BASE)
                fun pal(at: Int, n: Int) = ShortArray(n) {
                    val o = at - ROM_BASE + 2 * it
                    ((rom[o].toInt() and 0xFF) or ((rom[o + 1].toInt() and 0xFF) shl 8)).toShort()
                }
                return Assets(
                    pal(0x08B125F4, 16), pal(0x08B10ABC, 16), pal(0x08B11864, 48),
                    EarlyStartUi.Gfx(
                        blob(0x08B11D0C), blob(0x08B118C4), blob(0x08B11C0C), blob(0x08B10630),
                        blob(0x08B104D8), blob(0x08B10CA4), blob(0x08B10ADC),
                    ),
                )
            }

            private const val ROM_BASE = 0x08000000
        }
    }

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
            assets.hsPalette, assets.nintendoPalette, assets.isPalette, gfx = assets.gfx,
        ),
    )

    private val hw = ui.env.hardware
    private val io = ByteArray(0x60)

    /** The game control proc's owner. */
    public val control: GameControl = GameControl(engine, keys, { ui.script }, { OP_ANIM_STAND_IN })

    /**
     * The main loop. A screen load overruns its frame on hardware (one missed VBlank), detected by
     * the BG sync request a load leaves.
     */
    public val loop: GameLoop = GameLoop(
        engine, keys, clock, keyInput,
        overran = { hw.modifiedBgs != 0 },
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

    private companion object {
        val OP_ANIM_STAND_IN = ProcScript(
            "ProcScr_OpAnim (not wired)",
            listOf(ProcScripts.block(), ProcScripts.end()),
        )
    }
}
