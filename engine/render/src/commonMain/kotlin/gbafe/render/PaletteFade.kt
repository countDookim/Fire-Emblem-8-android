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
