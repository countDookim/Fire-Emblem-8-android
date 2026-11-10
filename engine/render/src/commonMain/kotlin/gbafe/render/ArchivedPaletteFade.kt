package gbafe.render

import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.yield

/** Generic fade-in proc (`ProcScr_BmFadeIN`) driven by the archived palette. */
public class ArchivedPaletteFade(
    private val fade: PaletteFade,
    private val hardware: BgHardware,
    private val engine: ProcEngine,
) {
    /** The `ProcFadeInOut` fields of a running fade (`white_out`, `timer`, `speed`, `mask`). */
    public class State {
        /** `white_out` (+0x29): fade from white instead of black. */
        public var whiteOut: Boolean = false

        /** `timer` (+0x2C): accumulated speed. */
        public var timer: Int = 0

        /** `speed` (+0x30): timer increment per frame. */
        public var speed: Int = 0

        /** `mask` (+0x34): bank mask passed to the palette write. */
        public var mask: Int = 0
    }

    private val script = ProcScript(
        "ProcScr_BmFadeIN",
        listOf(
            call(ProcFunc { disableGfx(it) }),
            yield(),
            call(ProcFunc { init(it) }),
            repeat(ProcFunc { loop(it) }),
            end(),
        ),
    ) { State() }

    /**
     * Starts the fade-in on proc tree 4; [parent] is unused, as in the original.
     *
     * Ported from src/sysutil.c: NewFadeIn
     */
    public fun newFadeIn(speed: Int, @Suppress("UNUSED_PARAMETER") parent: Proc?): Proc {
        val proc = engine.start(script, 4)
        val state = proc.state as State
        state.whiteOut = false
        state.speed = speed
        state.mask = -1
        return proc
    }

    /**
     * Returns whether a fade-in proc is running.
     *
     * Ported from src/sysutil.c: FadeInExists
     */
    public fun exists(): Boolean = engine.find(script) != null

    /** Returns the fade state of [proc], a proc started by [newFadeIn]. */
    public fun stateOf(proc: Proc): State = proc.state as State

    /**
     * Archives the current palettes and zeroes the timer.
     *
     * Ported from src/sysutil.c: FadeInOut_Init
     */
    private fun init(proc: Proc) {
        fade.archiveCurrentPalettes()
        (proc.state as State).timer = 0
    }

    /**
     * Advances the timer, writes the faded palette and breaks at timer 0x80.
     *
     * Ported from src/sysutil.c: FadeIn_Loop
     */
    private fun loop(proc: Proc) {
        val state = proc.state as State
        state.timer += state.speed
        val color = if (state.whiteOut) 0x200 - state.timer * 2 else state.timer * 2
        fade.writeFadedPaletteFromArchive(color, color, color, state.mask)
        hardware.lcd.setDispEnable(1, 1, 1, 1, 1)
        if (state.timer == 0x80) engine.breakProc(proc)
    }

    /**
     * Disables the display layers; a mask of exactly 0xFFFF keeps objects enabled.
     *
     * Ported from src/sysutil.c: FadeInOut_DisableGfx. The prologue capture covers the branch
     * where the reused proc mask is not 0xFFFF and only BG0 and OBJ are initially enabled. The
     * 0xFFFF branch and disabling initially enabled BG1..BG3 remain unverified.
     */
    private fun disableGfx(proc: Proc) {
        val obj = if ((proc.state as State).mask == 0xFFFF) 1 else 0
        hardware.lcd.setDispEnable(0, 0, 0, 0, obj)
    }
}
