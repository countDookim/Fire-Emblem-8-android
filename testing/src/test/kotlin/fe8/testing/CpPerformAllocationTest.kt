package fe8.testing

import gba.testkit.AllocationMeter
import gba.testkit.ReferenceData
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Zero bytes per frame in the AI perform proc: the unit walk and the target cursor (EP-8), and
 * the map fade (EP-9c).
 */
class CpPerformAllocationTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    private fun bytesPerFrame(frames: Int): LongArray {
        val sim = CpPerformSim.create(ref, romFile, "ram_trace_prologue_enemy_phase", 1613, 0x82)
        val out = LongArray(frames)
        for (i in 0 until frames) {
            val before = AllocationMeter.current()
            sim.frame()
            out[i] = AllocationMeter.current() - before
        }
        return out
    }

    @Test
    fun `the perform walk and target cursor frames allocate nothing`() {
        assumeTrue(AllocationMeter.supported)
        // Two passes over the same deterministic walk; a frame counts only if it allocates in
        // both (as in PlayerPhaseAllocationTest). Frames 3..30 are the steady walk (the MU starts
        // and stops around them), frames 36..80 the target cursor (it starts at 34, ends at 81).
        val first = bytesPerFrame(90)
        val second = bytesPerFrame(90)
        val bytes = LongArray(90) { minOf(first[it], second[it]) }
        for ((from, to) in listOf(3 to 30, 36 to 80)) {
            val allocating = (from..to).filter { bytes[it] != 0L }
            assertTrue(
                allocating.isEmpty(),
                "frames $from..$to allocate in frames $allocating: ${allocating.map { bytes[it] }}",
            )
        }
    }

    private fun fadeBytes(): LongArray {
        val sim = CpPerformSim.create(ref, romFile, "ram_trace_prologue_clear", 3803, 0x81)
        var guard = 0
        while (!sim.fade.doesBmxFadeExist() && guard++ < 100) sim.frame()
        val out = LongArray(16)
        for (i in out.indices) {
            val before = AllocationMeter.current()
            sim.frame()
            out[i] = AllocationMeter.current() - before
        }
        return out
    }

    @Test
    fun `the map fade frames allocate nothing`() {
        assumeTrue(AllocationMeter.supported)
        // The fade (EP-9c) runs 16 frames after its start frame; a frame counts only if it
        // allocates in both passes.
        val first = fadeBytes()
        val second = fadeBytes()
        val allocating = first.indices.filter { minOf(first[it], second[it]) != 0L }
        assertTrue(allocating.isEmpty(), "fade frames allocate: $allocating")
    }
}
