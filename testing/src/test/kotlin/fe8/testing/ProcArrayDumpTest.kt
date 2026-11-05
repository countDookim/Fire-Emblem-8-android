package fe8.testing

import gba.testkit.ReferenceData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/** Checks [ProcArrayDump] against the `procs` watch and full dumps of `ram_trace_prologue`. */
class ProcArrayDumpTest {
    private val ref = ReferenceData.fromEnvironment()
    private val names = ProcArrayDump.romNames()

    @Test
    fun `per-step watch holds the map main proc and the game control proc`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val run = ref.run("ram_trace_prologue")
        assumeTrue(run.hasWatch("procs"), "ram_trace_prologue has no procs watch")
        val at = ProcArrayDump.ofWatch(run.watch("procs"), FRAME, names)
        checkNotNull(at.find(GAME_CONTROL)) { "gProcScr_GameControl alive at step $FRAME" }
        val bmapMain = checkNotNull(at.find(BMAP_MAIN)) { "gProc_BMapMain live at step $FRAME" }
        assertTrue(at.live.all { it.script ushr 24 == 8 }, "script pointers are ROM addresses")
        if (names.isNotEmpty()) {
            assertEquals("gProc_BMapMain", bmapMain.name)
            assertEquals(bmapMain.slot, at.find("gProc_BMapMain")?.slot)
        }
    }

    @Test
    fun `ewram dump and watch record decode to the same procs`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val run = ref.run("ram_trace_prologue")
        assumeTrue(run.hasWatch("procs"), "ram_trace_prologue has no procs watch")
        val trace = run.watch("procs")
        var checked = 0
        for ((dump, frame) in run.dumps) {
            if (frame !in trace.firstStep..trace.lastStep) continue
            if (!File(ref.root, "ram_trace_prologue/$dump.ewram.bin").isFile) continue
            val full = ProcArrayDump.ofEwram(run.region(dump, "ewram"))
            val watched = ProcArrayDump.ofWatch(trace, frame)
            assertEquals(full.live.map { it.slot to it.script }, watched.live.map { it.slot to it.script }, dump)
            assertEquals(full.live.map { it.u32(4) }, watched.live.map { it.u32(4) }, "$dump scrCur")
            checked++
        }
        assertTrue(checked > 0)
    }

    private companion object {
        /** First step at which the map main proc exists. */
        const val FRAME = 9319
        const val GAME_CONTROL = 0x085916D4
        const val BMAP_MAIN = 0x0859A1F0
    }
}
