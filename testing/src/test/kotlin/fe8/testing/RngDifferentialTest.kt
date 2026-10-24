package fe8.testing

import fe8.core.VerifiesPort
import fe8.core.rng.RngService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Differential test: the RNG state our port produces at boot must equal the state mGBA shows in IWRAM.
 *
 * Ground truth: `ram_trace_boot` dump at frame 10 (state is fixed from boot until the first gameplay
 * draw). `gRNSeeds` (u16[3]) and `gLCGRNValue` (int) are located via [RamSymbols] (decomp ELF symbols).
 */
class RngDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val sym = RamSymbols.fromEnvironment()
    private val rnSeeds get() = sym["gRNSeeds"].also { check(it.size == 6) }.address
    private val lcgValue get() = sym["gLCGRNValue"].also { check(it.size == 4) }.address

    @Test
    @VerifiesPort("NextRN", "InitRN", "SetLCGRNValue", "AdvanceGetLCGRNValue")
    fun `boot RNG state matches mGBA`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val iwram = ref.run("ram_trace_boot").region("r00010", "iwram")

        val rng = RngService()
        rng.seedLikeBoot()

        assertEquals(GbaMem.u32(iwram, GbaMem.IWRAM, lcgValue), rng.lcg.value, "gLCGRNValue")
        for (i in 0..2) {
            assertEquals(GbaMem.u16(iwram, GbaMem.IWRAM, rnSeeds + 2 * i), rng.primary.word(i), "gRNSeeds[$i]")
        }
    }

    @Test
    fun `RNG state is stable through the boot trace`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val run = ref.run("ram_trace_boot")
        val first = run.region(run.dumps.keys.first(), "iwram").copyOf(12)
        for (d in run.dumps.keys) {
            // Nothing draws from the RNG before the title screen; if this changes, the test above must
            // be re-anchored to the first frame after InitRN.
            assertEquals(first.toList(), run.region(d, "iwram").copyOf(12).toList(), "RNG words changed at $d")
        }
    }
}
