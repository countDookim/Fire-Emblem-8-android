package fe8.testing

import fe8.game.worldmap.GmapRmBaPalAnimProc
import fe8.game.worldmap.WorldMapRmBaPalAnim
import gba.testkit.ReferenceData
import gbafe.proc.ProcEngine
import gbafe.render.LcdControl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * `ProcScr_GmapRmBaPalAnim1` (`src/worldmap_rm.c`) against the `procs` watch of
 * `ram_trace_prologue`: script position, `flag` and `timer` at every step of both runs (5554-5648
 * and 6965-7059). The blend registers are only dumped outside the runs, so the functions stay
 * "transcribed" (no `@VerifiesPort`).
 */
class WorldMapRmBaPalAnimTest {
    private val ref = ReferenceData.fromEnvironment()

    @Test
    fun `both pulses follow the original script position, flag and timer every step`() {
        assumeTrue(ref.available && ProcArrayDump.romNames().isNotEmpty(), "no reference data")
        val run = ref.run("ram_trace_prologue")
        val names = ProcArrayDump.romNames()
        val trace = run.watch("procs")
        for ((first, last) in listOf(5554 to 5648, 6965 to 7059)) {
            val engine = ProcEngine()
            val anim = WorldMapRmBaPalAnim(engine, LcdControl()) { 1 }
            // `Proc_Start` runs the init; the original's first sampled step is that start.
            val proc = anim.startGmapRmBaPalAnim1(null)
            for (step in first..last) {
                if (step > first) engine.run(engine.root(3))
                val expected = checkNotNull(
                    ProcArrayDump.ofWatch(trace, step, names).find("ProcScr_GmapRmBaPalAnim1"),
                    { "step $step" },
                )
                val s = proc.state as GmapRmBaPalAnimProc
                val ours = Triple(proc.scrCur, s.flag, s.timer)
                val theirs = Triple(
                    expected.scrCur, expected.u8(0x29), expected.u16(0x2A).toShort().toInt(),
                )
                assertEquals(theirs, ours, "script position, flag, timer at step $step")
            }
        }
    }
}
