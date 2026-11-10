package fe8.testing

import fe8.game.bm.PlaySt
import fe8.game.worldmap.GmData
import fe8.game.worldmap.WorldMapMain
import fe8.game.worldmap.WorldMapMainProc
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import gbafe.proc.ProcEngine
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.block
import gbafe.proc.ProcScripts.end
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** `NewWorldMap` and the wrapper script against the prologue `procs` capture. */
class WorldMapWrapperTest {
    private val ref = ReferenceData.fromEnvironment()

    @Test
    @VerifiesPort("NewWorldMap")
    fun `wrapper starts the world map main proc with the original timer`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val names = ProcArrayDump.romNames()
        assumeTrue(names.isNotEmpty(), "decomp nm file not present")
        val trace = ref.run("ram_trace_prologue").watch("procs")
        val expected = ProcArrayDump.ofWatch(trace, FIRST_STEP_AFTER_INIT, names)
        val main = checkNotNull(expected.find("ProcScr_WorldMapMain"))

        val engine = ProcEngine()
        val mainScript = ProcScript("ProcScr_WorldMapMain", listOf(block(), end())) {
            WorldMapMainProc()
        }
        val fadeIn = ProcScript("ProcScr_BmFadeIN", listOf(end()))
        val wm = WorldMapMain(engine, PlaySt(), GmData(ByteArray(0x8000), RngService()), mainScript, fadeIn)
        engine.start(wm.wrapperScript, 3)
        repeat(3) { engine.run(engine.root(3)) }
        val ours = checkNotNull(engine.find(mainScript))
        val state = ours.state as WorldMapMainProc
        assertEquals(main.u32(0x2C), state.timer, "timer")
        assertEquals((main.u8(0x29) shr 1) and 1, state.flags1, "flags_1")
    }

    private companion object {
        /** First step at which the original's `NewWorldMap` has set the timer (128 before). */
        const val FIRST_STEP_AFTER_INIT = 4902
    }
}
