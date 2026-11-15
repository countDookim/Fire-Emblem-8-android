package fe8.game.worldmap

import gbafe.core.math.Spline
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.mark
import gbafe.proc.ProcScripts.name
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.setEndCb
import gbafe.proc.ProcScripts.yield
import gbafe.render.LcdControl

/**
 * Per-proc state of the world map's line fade `ProcScr_GmapLineFade`: the fields of
 * `struct GmapLineFadeProc` (src/worldmap_line_fade.c) past `PROC_HEADER`. The decomp names
 * (`unk_29`, `unk_2a`, `unk_2c`) are kept because the host test reads them.
 */
public class GmapLineFadeProc {
    /** `unk_29` (+0x29, `u8`): the `unk` argument of `StartGmapLineFade`; 2 selects 100 frames. */
    public var unk29: Int = 0

    /** `unk_2a` (+0x2A, `s16`): frames run so far (the sweep's progress). */
    public var unk2a: Int = 0

    /** `unk_2c` (+0x2C, `s16`): length of the sweep in frames (50, or 100 for argument 2). */
    public var unk2c: Int = 0
}

/**
 * The scanline fade of the world map (`src/worldmap_line_fade.c`, the "fancy fade" of the event
 * commands `EventB2_WmFancyFade` / `EventB3_WmFancyFadeWait`): a darken blend (`BLDY`, effect
 * `BLEND_EFFECT_DARKEN`) whose per-scanline coefficient table (0x10 = black, 0 = clear) is
 * rewritten every frame, so the screen is revealed from its middle outward to the top and
 * bottom edges, with a soft edge 0x30 lines wide, while the generic fade-in runs. The table is
 * copied into `BLDY` line by line by [WorldMapHblank].
 *
 * Only what the Prologue runs is written: `StartGmapLineFade(0, NULL)`, whose argument selects
 * the 50-frame variant (`NewFadeIn(2, 0)`); the 100-frame variant (argument 2), the blocking
 * start and `EndGmapLineFade` are not run by any capture and not written.
 *
 * @param engine the proc scheduler
 * @param lcd the blend and display registers
 * @param hblank the scanline-effect state
 * @param newFadeIn `NewFadeIn(speed, 0)`
 */
public class WorldMapLineFade(
    private val engine: ProcEngine,
    private val lcd: LcdControl,
    private val hblank: WorldMapHblank,
    private val newFadeIn: (speed: Int) -> Unit,
) {
    /**
     * `ProcScr_GmapLineFade`: init, yield once, then [gmapLineFadeLoop] each frame.
     *
     * Transcribed from src/worldmap_line_fade.c: ProcScr_GmapLineFade (not verified: the script
     * position is checked per step in WorldMapEventHostTest, which cannot tag a script).
     */
    public val script: ProcScript = ProcScript(
        "ProcScr_GmapLineFade",
        listOf(
            name("Gmap Line Fade"),
            mark(PROC_MARK_WMSTUFF),
            setEndCb(ProcFunc { gmapLineFadeOnEnd() }),
            call(ProcFunc { gmapLineFadeInit(it.state as GmapLineFadeProc) }),
            yield(),
            repeat(ProcFunc { gmapLineFadeLoop(it) }),
            end(),
        ),
    ) { GmapLineFadeProc() }

    /**
     * Ported from src/worldmap_line_fade.c: GmapLineFade_OnEnd
     *
     * Clears the scanline-copy enable bit (`gWmHblankStatus` bit 2) and resets the blend:
     * effect none, no first target, BG2 as the only second target, no backdrop target.
     */
    public fun gmapLineFadeOnEnd() {
        hblank.status = hblank.status and HBLANK_COPY_ENABLE.inv()
        lcd.setBlendConfig(BLEND_EFFECT_NONE, 0, 0, 0)
        lcd.setBlendTargetA(0, 0, 0, 0, 0)
        lcd.setBlendTargetB(0, 0, 1, 0, 0)
        lcd.setBlendBackdropA(0)
        lcd.setBlendBackdropB(0)
    }

    /**
     * Ported from src/worldmap_line_fade.c: GmapLineFade_80C0358
     *
     * Fills the off-screen page's `BLDY` line for this frame, for the top half of the screen
     * (scanlines 0..0x4F): the sweep progress (`unk_2a` of `unk_2c` frames) moves a clear edge
     * (`a` in the decomp) from scanline 0x80 (below the half) up to 0. Scanlines at or below it
     * get 0 (no darkening), the 0x30 above it ramp from 0 up to 0x10, and those further above
     * stay 0x10 (black). The first 0x50 entries are then mirrored (reversed) into the bottom
     * half (entries 0x50..0x9F) and the pages are swapped.
     */
    public fun gmapLineFade80C0358(proc: GmapLineFadeProc) {
        val buf = hblank.lineBufferIndex(0, 1)
        var coeff = Spline.divArm2(proc.unk2a, proc.unk2c, 0)
        val clearEdge = (Spline.divArm(0x1000, coeff * -0x80) + 0x80).toShort().toInt()
        val rampStart = (Spline.divArm(0x1000, coeff * -0x80) + 0x50).toShort().toInt()
        for (i in 0 until 0x50) {
            if (i >= clearEdge) {
                hblank.buffer[buf + i] = 0
                continue
            }
            if (i >= rampStart) {
                val tmp = (i - rampStart).toShort().toInt()
                coeff = Spline.divArm2(tmp, 0x30, 0)
                coeff = 0x1000 - coeff
                hblank.buffer[buf + i] = Spline.divArm(0x1000, coeff * 0x10).toShort()
                continue
            }
            hblank.buffer[buf + i] = 0x10
        }
        var bufA = buf
        var bufB = buf + 0x9F
        for (i in 0 until 0x50) hblank.buffer[bufB--] = hblank.buffer[bufA++]
        hblank.swapPages()
    }

    /**
     * Transcribed from src/worldmap_line_fade.c: GmapLineFade_Init (not verified: the BLDCNT
     * effect, BLDALPHA, BLDY, the DISPCNT layer enables and the fill value of line 0 are
     * overwritten by `NewFadeIn` and the first scanline page before the first dump at step
     * 4950; the frame count, scanline register, blend targets and status bit are checked)
     *
     * Only the `unk_29 != 2` branch (the 50-frame fade) is run by the Prologue. Starts the
     * generic fade-in (`NewFadeIn(2, 0)`), fills line 0 of the page (the `BLDY` line) with 0x10
     * (black), points register slot 0 at `BLDY`, sets the darken blend with BG0-BG3 and OBJ as
     * the first target, BG1-BG3 and OBJ enabled and BG0 off (`SetDispEnable(0, 1, 1, 1, 1)`),
     * and turns the scanline copy on (`gWmHblankStatus` bit 2).
     */
    public fun gmapLineFadeInit(proc: GmapLineFadeProc) {
        check(proc.unk29 != 2) { "not verified: no capture starts the line fade with 2" }
        newFadeIn(2)
        proc.unk2c = 50
        proc.unk2a = 0
        val line0 = hblank.lineBufferIndex(0, 0)
        hblank.buffer.fill(0x10, line0, line0 + LINE_FILL)
        hblank.setLineRegister(0, BLDY_REGISTER)
        hblank.maskLineFlags(0)
        lcd.setBlendConfig(BLEND_EFFECT_DARKEN, 16, 16, 16)
        lcd.setDispEnable(0, 1, 1, 1, 1)
        lcd.setBlendTargetA(1, 1, 1, 1, 1)
        lcd.setBlendTargetB(0, 0, 0, 0, 0)
        lcd.setBlendBackdropA(0)
        lcd.setBlendBackdropB(0)
        hblank.status = hblank.status or HBLANK_COPY_ENABLE
    }

    /**
     * Ported from src/worldmap_line_fade.c: GmapLineFade_Loop
     *
     * `PROC_REPEAT` callback: counts a frame and, while fewer than `unk_2c` have run, fills the
     * scanline table for it ([gmapLineFade80C0358]); otherwise breaks (the end callback then
     * switches the effect off).
     */
    public fun gmapLineFadeLoop(proc: Proc) {
        val state = proc.state as GmapLineFadeProc
        state.unk2a++
        if (state.unk2a < state.unk2c) gmapLineFade80C0358(state) else engine.breakProc(proc)
    }

    /**
     * Ported from src/worldmap_line_fade.c: StartGmapLineFade
     *
     * Called by `EventB2_WmFancyFade` with the command's 32-bit argument. Only the
     * `parent == NULL` branch (a tree 3 root) is run by the Prologue.
     *
     * @param unk the `unk` argument: 2 selects the 100-frame sweep, anything else the 50-frame
     *   one; 0 in the Prologue
     */
    public fun startGmapLineFade(unk: Int): Proc {
        val proc = engine.start(script, ROOT_TREE)
        (proc.state as GmapLineFadeProc).unk29 = unk and 0xFF
        return proc
    }

    /**
     * Ported from src/worldmap_line_fade.c: IsGmapLineFadeActive
     *
     * Whether a line fade proc exists; `EventB3_WmFancyFadeWait` waits until it is gone.
     */
    public fun isGmapLineFadeActive(): Boolean = engine.find(script) != null

    private companion object {
        /** `PROC_MARK_WMSTUFF`. */
        const val PROC_MARK_WMSTUFF = 8

        /** `PROC_TREE_3`. */
        const val ROOT_TREE = 3

        /** `gWmHblankStatus` bit 2 (`|= 4`): [WorldMapHblank] copies the scanline table. */
        const val HBLANK_COPY_ENABLE = 4

        /** The `CpuSet` of 0x140 bytes: one table line, a halfword per scanline (160). */
        const val LINE_FILL = 160

        /** `BLDY`, the register line 0 is copied to. */
        const val BLDY_REGISTER = 0x04000054

        /** `BLEND_EFFECT_NONE`. */
        const val BLEND_EFFECT_NONE = 0

        /** `BLEND_EFFECT_DARKEN` (include/gba/io_reg.h:549): `BLDCNT` darken mode. */
        const val BLEND_EFFECT_DARKEN = 3
    }
}
