package fe8.game.title

import fe8.game.opanim.OpAnimHs
import gbafe.data.Decompress
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.block
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.goto
import gbafe.proc.ProcScripts.label
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.yield
import gbafe.render.BgHardware

/**
 * The title screen `gProcScr_TitleScreen` (decomp `src/titlescreen.c`). Work in progress (task
 * M4-10): only its start (init and main graphics) exists; stand-in `PROC_BLOCK`s hold the rest.
 * Not yet started by [fe8.game.GameControl] (it still uses a stand-in proc).
 */
public class TitleScreen(
    /** Proc engine. */
    public val engine: ProcEngine,
    /** Display hardware. */
    public val hardware: BgHardware,
    /** ROM graphics. */
    public val gfx: Gfx,
) {
    /** ROM data of the title screen. */
    public class Gfx(
        /** `gGfx_TitleMainBackground_1`, `_2`, `gTsa_TitleMainBackground` (compressed). */
        public val background: List<OpAnimHs.Blob>,
        /** `gPal_TitleMainBackground` (16 colours). */
        public val backgroundPal: ShortArray,
        /** `gGfx_TitleDragonForeground`, `gTsa_TitleDragonForeground` (compressed). */
        public val dragon: Pair<OpAnimHs.Blob, OpAnimHs.Blob>,
        /** `gPal_TitleDragonForeground` (16 colours). */
        public val dragonPal: ShortArray,
        /** `gGfx_FireEmblemLogo` (compressed). */
        public val logo: OpAnimHs.Blob,
        /** `gGfx_SubtitlePressStart` (compressed). */
        public val pressStart: OpAnimHs.Blob,
        /** `gPal_PressStart` (4 palettes). */
        public val pressStartPal: ShortArray,
    )

    /** `struct TitleScreenProc` fields. */
    public class State {
        /** `mode`: 1 after the op anim played through (runs the intro fx), else 0. */
        public var mode: Int = 0

        /** `timer`. */
        public var timer: Int = 0

        /** `timer_idle`. */
        public var timerIdle: Int = 0
    }

    private val mapBytes = ByteArray(0x800)

    /** `gProcScr_TitleScreen` (partial, see the class doc). */
    public val script: ProcScript = ProcScript(
        "gProcScr_TitleScreen",
        listOf(
            label(4),
            yield(),
            call(ProcFunc { init(it) }),
            repeat(ProcFunc { setupMainGraphics(it) }),
            call(ProcFunc { tryJumpIntroAnim(it) }),
            call(ProcFunc { showMain() }),
            block(), // not ported: FadeInBlackSpeed20
            yield(),
            goto(1),
            label(0),
            yield(),
            block(), // not ported: the intro fx (Title_StartSkipFxListener ...) and the rest
            label(1),
        ),
        newState = { State() },
    )

    /**
     * Starts the title screen as a blocking child of [parent] with [mode] (1 after the op anim
     * played through). `StartTitleScreen_WithMusic` / `_FlagFalse` use mode 0 (BGM not modelled).
     *
     * Ported from src/titlescreen.c: StartTitleScreen_FlagTrue
     */
    public fun start(parent: Proc, mode: Int): Proc? =
        engine.startBlocking(script, parent)?.also { (it.state as State).mode = mode }

    /**
     * Resets the display: mode 0, all layers and windows off, BG offsets/sizes/priorities, maps
     * cleared, default colour effects.
     *
     * Ported from src/titlescreen.c: Title_Init
     */
    public fun init(proc: Proc) {
        val hw = hardware
        val lcd = hw.lcd
        lcd.dispcnt = lcd.dispcnt and (0x7 or 0x1F00 or 0xE000).inv()
        hw.setTileDataOffset(0, 0)
        hw.setTileDataOffset(1, 0)
        hw.setTileDataOffset(2, 0x8000)
        hw.setTileDataOffset(3, 0xC000)
        hw.setMapDataOffset(0, 0x6800)
        hw.setMapDataOffset(1, 0x7000)
        hw.setMapDataOffset(2, 0xB000)
        hw.setMapDataOffset(3, 0x7800)
        for (bg in 0..3) {
            hw.setScreenSize(bg, 0)
            lcd.bgCnt[bg] = (lcd.bgCnt[bg] and 3.inv()) or bg
            lcd.setBgPosition(bg, 0, 0)
            hw.fill(hw.mapBuffer(bg), 0)
        }
        lcd.bldcnt = lcd.bldcnt and (0x20 or 0x2000).inv() // SetBlendBackdropA/B(0)
        lcd.setBlendConfig(0, 0x10, 0, 0) // SetDefaultColorEffects
        hw.enableBgSyncByMask(0xF)
        val st = proc.state as State
        st.timer = 0
        st.timerIdle = 0
    }

    /**
     * Loads the main background, dragon, logo and "Press Start" graphics over 5 steps; then
     * breaks.
     *
     * Ported from src/titlescreen.c: Title_SetupMainGraphics
     */
    public fun setupMainGraphics(proc: Proc) {
        val st = proc.state as State
        val hw = hardware
        val g = gfx
        when (st.timer) {
            0 -> Decompress.into(g.background[0].data, g.background[0].offset, hw.vram, 0)
            1 -> {
                Decompress.into(g.background[1].data, g.background[1].offset, hw.vram, 0x3000)
                loadMap(g.background[2], 1, 0xE000)
                applyPalette(g.backgroundPal, 0xE, 1)
                hw.palette[0] = if (st.mode != 0) 0x7FFF else 0
            }
            2 -> {
                Decompress.into(g.dragon.first.data, g.dragon.first.offset, hw.vram, 0x5000)
                loadMap(g.dragon.second, 0, 0xF280)
                applyPalette(g.dragonPal, 0xF, 1)
                hw.enableBgSyncByMask(1)
            }
            3 -> Decompress.into(g.logo.data, g.logo.offset, hw.vram, 0x10000)
            4 -> {
                Decompress.into(g.pressStart.data, g.pressStart.offset, hw.vram, 0x13000)
                applyPalette(g.pressStartPal, 0x10, 4)
                st.timer = 0
                engine.breakProc(proc)
                return
            }
        }
        st.timer++
    }

    /**
     * Mode 1 jumps to the intro fx (label 0); otherwise starts the title sprites proc (not
     * ported yet).
     *
     * Partially ported from src/titlescreen.c: TitleScreenTryJumpIntroAnim
     */
    public fun tryJumpIntroAnim(proc: Proc) {
        if ((proc.state as State).mode != 0) engine.goto(proc, 0)
    }

    /**
     * Shows BG0, BG1 and OBJ with an alpha blend of BG0 over BG0/BG1.
     *
     * Ported from src/titlescreen.c: sub_80C5870 (not verified yet)
     */
    public fun showMain() {
        val lcd = hardware.lcd
        hardware.enableBgSyncByMask(0xF)
        lcd.dispcnt = (lcd.dispcnt and 0x1F00.inv()) or 0x0100 or 0x0200 or 0x1000
        lcd.setBlendConfig(1, 8, 8, 0)
        lcd.setBlendTargetA(0, 0, 0, 0, 0)
        lcd.setBlendTargetB(1, 1, 0, 0, 0)
    }

    /** Decompresses a 0x280-entry map into BG [bg]'s buffer and adds [add] to every entry. */
    private fun loadMap(blob: OpAnimHs.Blob, bg: Int, add: Int) {
        val n = Decompress.into(blob.data, blob.offset, mapBytes, 0)
        val map = hardware.mapBuffer(bg)
        for (i in 0 until minOf(n / 2, map.size)) {
            map[i] = ((mapBytes[2 * i].toInt() and 0xFF) or ((mapBytes[2 * i + 1].toInt() and 0xFF) shl 8)).toShort()
        }
        for (i in 0 until 0x280) map[i] = (map[i] + add).toShort()
    }

    /** `ApplyPalettes(src, bank, count)`: copies [count] palettes into the palette buffer. */
    private fun applyPalette(src: ShortArray, bank: Int, count: Int) {
        src.copyInto(hardware.palette, bank * 16, 0, 16 * count)
        hardware.enablePaletteSync()
    }
}
