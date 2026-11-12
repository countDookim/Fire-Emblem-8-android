package gbafe.render

import gbafe.core.math.Interpolate
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.mark
import gbafe.proc.ProcScripts.repeat

/**
 * Per-palette-bank fades of `src/bmlib.c` on the shared palette buffer: `StartPalFade` (one
 * 16-colour bank moves linearly to a target palette over a number of frames) and the palette
 * archive (`sPalFadeSt[32]`, `ArchiveCurrentPalettes`, `WriteFadedPaletteFromArchive`) that
 * scales a copy of the palette per colour channel.
 */
public class PaletteFade(private val hardware: BgHardware, private val engine: ProcEngine) {
    /** One `struct PalFadeSt` (`include/bmlib.h`): the state of one bank (0..31). */
    private class State {
        /** `from_colors`: the bank's colours when the fade started (or were archived). */
        val sourceColors = ShortArray(16)

        /** `to_colors`: the colours the fade moves to. */
        var targetColors = ShortArray(16)

        /** The bank `pal` points at (`gPaletteBuffer + bank * 16`). */
        var bank = 0

        /** `clock`: frames elapsed. */
        var elapsedFrames = 0

        /**
         * `clock_end`: fade length in frames. The archive functions reuse banks 0..2's field to
         * hold the red, green and blue scale last given to [writeFadedPaletteFromArchive].
         */
        var durationFrames = 0

        /** `clock_stop`: the fade ends when `clock` reaches it (`duration + 1` by default). */
        var stopFrame = 0
    }

    /** `struct PalFadeProc`: ties a fade proc to its bank's state (`st`, +0x2C). */
    private class ProcState { lateinit var fade: State }
    private val states = Array(32) { State() }
    private val script = ProcScript(
        "ProcScr_PalFade",
        listOf(mark(PROC_MARK_EFX_BGCOL), repeat(ProcFunc { onLoop(it) }), end()),
    ) { ProcState() }

    /**
     * Starts a fade to [colors] for [bank], as a child of [parent].
     *
     * Transcribed from src/bmlib.c: StartPalFade (not verified: arbitrary nonblack targets from
     * nonblack sources, other durations, custom early stops and generic proc behavior).
     * Portrait fades to and from black at duration 12 are exercised.
     */
    public fun start(colors: ShortArray, bank: Int, duration: Int, parent: Proc) {
        val state = states[bank]
        val proc = engine.start(script, parent)
        hardware.palette.copyInto(state.sourceColors, 0, bank * 16, bank * 16 + 16)
        state.targetColors = colors
        state.bank = bank
        state.elapsedFrames = 0
        state.durationFrames = duration
        state.stopFrame = duration + 1
        (proc.state as ProcState).fade = state
    }

    /**
     * One frame of the fade: ends the proc when `clock` reaches `clock_stop` or passes
     * `clock_end`, else sets each of the bank's 16 colours to the per-channel linear interpolation
     * of source and target at `clock` / `clock_end`, requests a palette sync and counts the frame.
     *
     * Transcribed from src/bmlib.c: PalFade_OnLoop (not verified: arbitrary nonblack targets from
     * nonblack sources, other durations, custom early stops and generic proc behavior).
     * Portrait fades to and from black at duration 12 are exercised.
     */
    private fun onLoop(proc: Proc) {
        val state = (proc.state as ProcState).fade
        if (state.elapsedFrames == state.stopFrame || state.elapsedFrames > state.durationFrames) {
            engine.end(proc)
            return
        }
        for (i in 0 until 16) {
            val sourceColor = state.sourceColors[i].toInt()
            val targetColor = state.targetColors[i].toInt()
            val red = interpolate(sourceColor, targetColor, 0x1F, state)
            val green = interpolate(sourceColor, targetColor, 0x3E0, state)
            val blue = interpolate(sourceColor, targetColor, 0x7C00, state)
            hardware.palette[state.bank * 16 + i] =
                ((red and 0x1F) or (green and 0x3E0) or (blue and 0x7C00)).toShort()
        }
        hardware.enablePaletteSync()
        state.elapsedFrames++
    }

    /**
     * Copies every palette bank (0..31) of the hardware palette into the fade archive and sets
     * the clock end of banks 0..2 to 0x100 (identity scale).
     *
     * Ported from src/bmlib.c: ArchiveCurrentPalettes
     */
    public fun archiveCurrentPalettes() {
        for (i in 0 until 32) {
            hardware.palette.copyInto(states[i].sourceColors, 0, i * 16, i * 16 + 16)
        }
        for (i in 0 until 3) states[i].durationFrames = 0x100
    }

    /**
     * Writes the archived palette, scaled per channel by [red], [green] and [blue] (0x100 is
     * identity), into the hardware palette for banks selected by [mask] (bit i = bank i).
     * The three arguments are also stored as the clock end of archive banks 0..2. Like the
     * original, it ends with a palette-sync request.
     *
     * Ported from src/bmlib.c: WriteFadedPaletteFromArchive. The white (argument above 0x100)
     * branches and partial masks are transcribed but unverified; only the darkening branch with
     * mask 0xFFFFFFFF is checked against the prologue capture.
     */
    public fun writeFadedPaletteFromArchive(red: Int, green: Int, blue: Int, mask: Int) {
        states[0].durationFrames = red and 0xFFFF
        states[1].durationFrames = green and 0xFFFF
        states[2].durationFrames = blue and 0xFFFF
        val buffer = hardware.palette
        for (i in 0 until 32) {
            if ((1 shl i) and mask == 0) continue
            val src = states[i].sourceColors
            for (j in 0 until 16) {
                // Each channel is scaled in place: <= 0x100 darkens (x * scale / 0x100), above
                // 0x100 moves towards the channel maximum (0x1F, 0x3E0, 0x7C00 for r, g, b).
                val c = src[j].toInt() and 0xFFFF
                val r = c and 0x1F
                val rr = if (red > 0x100) {
                    r + (((0x1F - r) * (red - 0x100)) shr 8)
                } else {
                    (r * red) shr 8
                }
                val g = c and 0x3E0
                val gg = if (green > 0x100) {
                    g + (((0x3E0 - g) * (green - 0x100)) shr 8)
                } else {
                    (g * green) shr 8
                }
                val b = c and 0x7C00
                val bb = if (blue > 0x100) {
                    b + (((0x7C00 - b) * (blue - 0x100)) shr 8)
                } else {
                    (b * blue) shr 8
                }
                buffer[16 * i + j] =
                    ((rr and 0x1F) or (gg and 0x3E0) or (bb and 0x7C00)).toShort()
            }
        }
        hardware.enablePaletteSync()
    }

    /** Returns the archived color [index] (0..15) of palette [bank] (0..31) as an unsigned u16. */
    public fun archivedColor(bank: Int, index: Int): Int =
        states[bank].sourceColors[index].toInt() and 0xFFFF

    /** Returns the stored clock end of archive [bank] (0..31) as an unsigned u16. */
    public fun clockEnd(bank: Int): Int = states[bank].durationFrames and 0xFFFF

    /**
     * Interpolates one colour channel (selected by [mask]: 0x1F red, 0x3E0 green, 0x7C00 blue) with
     * `Interpolate(INTERPOLATE_LINEAR, ...)` over the fade's clock, as `PalFade_OnLoop` does.
     */
    private fun interpolate(from: Int, to: Int, mask: Int, state: State): Int =
        Interpolate.interpolate(
            Interpolate.LINEAR, from and mask, to and mask, state.elapsedFrames,
            state.durationFrames,
        )

    private companion object {
        /** `PROC_MARK_EFX_BGCOL`: the proc mark of fades on the BG colours. */
        const val PROC_MARK_EFX_BGCOL = 10
    }
}
