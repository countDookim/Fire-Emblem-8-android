package fe8.testing

import fe8.game.bm.Mu
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Differential test of `LockMus` and `ReleaseMus` against the per-frame `procs` watch of
 * `ram_trace_prologue_end` (PC-cap1): `Event23_DisaleMapDisp` locks the `ProcScr_Mu` procs at
 * `f4911 -> f4912`, `Event24_EnableMapDisp` releases them at `f9423 -> f9424`.
 */
class MuLockDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val helper = ProcMarkedDifferentialTest()

    private fun check(before: Int, after: Int, delta: Int, call: (gbafe.proc.ProcEngine) -> Unit) {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val trace = ref.run("ram_trace_prologue_end").watch("procs")
        val names = ProcArrayDump.romNames()
        assumeTrue(names.isNotEmpty(), "decomp nm file not present")
        val dumpBefore = helper.at(trace, before)
        val dumpAfter = helper.at(trace, after)
        val engine = helper.seed(dumpBefore)
        val mus = dumpBefore.live.filter { it.name == "ProcScr_Mu" }
        assertTrue(mus.isNotEmpty(), "a MU proc exists at f$before")
        assertTrue(mus.all { it.u8(MARK) == Mu.PROC_MARK_MU }, "MU procs carry PROC_MARK_MU")
        call(engine)
        for (mu in mus) {
            val expected = dumpAfter.live.first { it.slot == mu.slot }.u8(LOCK)
            assertEquals(mu.u8(LOCK) + delta, expected, "game lock count of slot ${mu.slot}")
            assertEquals(expected, engine.procs[mu.slot].lockCnt, "lockCnt of slot ${mu.slot}")
        }
        helper.assertMatches(engine, dumpBefore, dumpAfter, "f$after") { slot ->
            mus.any { it.slot == slot }
        }
    }

    @Test
    @VerifiesPort("LockMus")
    fun `LockMus raises the ProcScr_Mu lock count at f4912`() {
        check(4911, 4912, 1) { Mu.lockMus(it) }
    }

    @Test
    @VerifiesPort("ReleaseMus")
    fun `ReleaseMus lowers the ProcScr_Mu lock count at f9424`() {
        check(9423, 9424, -1) { Mu.releaseMus(it) }
    }

    private companion object {
        const val MARK = 0x26
        const val LOCK = 0x28
    }
}
