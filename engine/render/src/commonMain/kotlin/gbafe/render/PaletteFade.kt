package gbafe.render

import gbafe.core.math.Interpolate
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.mark
import gbafe.proc.ProcScripts.repeat

/** Palette-bank fades backed by the shared hardware palette buffer. */
public class PaletteFade(private val hardware: BgHardware, private val engine: ProcEngine) {
    /** Per-bank snapshot and frame counters shared by fades targeting that bank. */
    private class State {
        val sourceColors = ShortArray(16)
        var targetColors = ShortArray(16)
        var bank = 0
        var elapsedFrames = 0
        var durationFrames = 0
        var stopFrame = 0
    }

    /** Associates a fade proc with its per-bank state. */
    private class ProcState { lateinit var fade: State }
    private val states = Array(32) { State() }
    private val script = ProcScript(
        "ProcScr_PalFade",
        listOf(mark(PROC_MARK_EFX_BGCOL), repeat(ProcFunc { onLoop(it) }), end()),
    ) { ProcState() }

    /**
     * Starts a fade to [colors] for [bank], as a child of [parent].
     *
     * Transcribed from src/bmlib.c: StartPalFade. Portrait fades to and from black at duration 12
     * are exercised; arbitrary nonblack targets from nonblack sources, other durations, custom
     * early stops and generic proc behavior remain independently unverified.
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
     * Transcribed from src/bmlib.c: PalFade_OnLoop. Portrait fades to and from black at duration
     * 12 are exercised; arbitrary nonblack targets from nonblack sources, other durations, custom
     * early stops and generic proc behavior remain independently unverified.
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

    /** Interpolates one masked color component using the source routine. */
    private fun interpolate(from: Int, to: Int, mask: Int, state: State): Int =
        Interpolate.interpolate(
            Interpolate.LINEAR, from and mask, to and mask, state.elapsedFrames,
            state.durationFrames,
        )

    private companion object {
        const val PROC_MARK_EFX_BGCOL = 10
    }
}
