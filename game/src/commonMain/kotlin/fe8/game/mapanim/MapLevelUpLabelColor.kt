package fe8.game.mapanim

import fe8.game.RomView
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.repeat
import gbafe.render.BgHardware

/** Palette cycle for the map level-up gain labels, independent of their parent lifecycle. */
public class MapLevelUpLabelColor(private val hardware: BgHardware, rom: RomView) {
    private val colors = rom.pal(PALETTE, 0x36)

    /** Fields of `ManimLevelUpLabelColorProc`. */
    public class State {
        /** Signed 32-bit cycle clock. */
        public var clock: Int = 0

        /** First OBJ palette bank relative to OBJ bank zero. */
        public var pal: Int = 0
    }

    /**
     * Resets the cycle clock. The parent assigns [State.pal] after starting the proc.
     *
     * Ported from src/mapanim_lvup.c: ManimLevelUpLabelColor_Init
     */
    public fun init(state: State) {
        state.clock = 0
    }

    /**
     * Advances the clock and copies seven colors into each of two OBJ banks every fourth step.
     *
     * Not covered by the capture: signed clock overflow and OBJ banks other than the one used in
     * the Prologue level-up.
     *
     * Ported from src/mapanim_lvup.c: ManimLevelUpLabelColor_Loop
     */
    public fun loop(state: State) {
        state.clock++
        if (state.clock % 4 != 0) return
        val phase = (state.clock shr 2) and 15
        val dst = (16 + state.pal) * 16 + 9
        for (i in 0 until 7) {
            hardware.palette[dst + i] = colors[phase + i]
            hardware.palette[dst + 16 + i] = colors[phase + 0x20 + i]
        }
        hardware.enablePaletteSync()
    }

    /** Color child script; its opcodes and lifecycle are not covered by the capture. */
    public val script: ProcScript = ProcScript(
        "ProcScr_ManimLevelUpLabelColor",
        listOf(
            call(ProcFunc { init(it.state as State) }),
            repeat(ProcFunc { loop(it.state as State) }),
            end(),
        ),
        { State() },
    )

    private companion object {
        // Pal_ManimLevelUpStatGainCycling in FE8U.
        const val PALETTE = 0x089AC5CC
    }
}
