package fe8.testing

import gba.testkit.ReferenceData
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** Provenance of the original New Game Start-skip captures. */
class OpSubtitleSkipDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()

    @Test
    fun `skip captures share original input and RAM through the divergence boundary`() {
        assumeTrue(ref.available, "reference captures unavailable")
        val original = ref.run("ram_trace_prologue")
        val control = ref.run("ram_trace_subtitle_skip_control")
        assertArrayEquals(original.inputs.copyOf(control.inputs.size), control.inputs)
        for (step in listOf(960, 990)) {
            for (region in listOf("ewram", "iwram")) {
                val dump = "r%05d".format(step)
                assertArrayEquals(original.region(dump, region), control.region(dump, region))
            }
        }
        for (start in listOf(1505, 2375, 4405)) {
            val run = ref.run("ram_trace_subtitle_skip_$start")
            val expected = original.inputs.copyOf(run.inputs.size)
            expected[start - 1] = 8 // One START input, with the rest of NEWGAME unchanged.
            assertArrayEquals(expected, run.inputs, "input timeline $start")
            for (step in listOf(974, 975, 976, start - 1)) {
                for (region in listOf("ewram", "iwram")) {
                    val dump = "r%05d".format(step)
                    assertArrayEquals(
                        control.region(dump, region), run.region(dump, region),
                        "$start $dump $region",
                    )
                }
            }
            val keys = run.watch("keys")
            assertEquals(8, keys.u16(start, 8), "original newKeys at $start")
        }
    }
}
