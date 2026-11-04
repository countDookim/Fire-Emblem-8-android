package fe8.testing

import gba.testkit.ReferenceData
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** Checks the harness watch traces and SRAM dumps against the full RAM dumps of the same frames. */
class WatchTraceReferenceTest {
    private val ref = ReferenceData.fromEnvironment()

    @Test
    fun `watch records equal the full dumps at the same frame`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val run = ref.run("ram_trace_prologue")
        val watches = listOf(
            Triple("rng", 0x03000000, "iwram"),
            Triple("procs", 0x02024E68, "ewram"),
            Triple("gplayst", 0x0202BCF0, "ewram"),
        )
        var checks = 0
        for ((name, address, region) in watches) {
            val w = run.watch(name)
            assertEquals(address, w.address)
            val base = if (region == "iwram") 0x03000000 else 0x02000000
            for ((dump, frame) in run.dumps) {
                val file = java.io.File(
                    ref.root, "ram_trace_prologue/$dump.$region.bin",
                )
                if (!file.isFile) continue
                val mem = file.readBytes()
                val expected = mem.copyOfRange(address - base, address - base + w.length)
                assertArrayEquals(expected, w.step(frame), "$name at $dump (frame $frame)")
                assertEquals(
                    expected[0].toInt() and 0xFF, w.u8(frame, 0), "$name u8 at $dump",
                )
                checks++
            }
        }
        assertTrue(checks > 600, "only $checks checks")
    }

    @Test
    fun `newgame run holds a header-valid sram dump`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val run = ref.run("ram_trace_newgame")
        assertTrue(run.hasSram)
        val sram = run.sram("s01230")
        assertEquals(32768, sram.size)
        assertEquals("AGB-FE9\u0000", String(sram, 0, 8, Charsets.US_ASCII))
    }
}
