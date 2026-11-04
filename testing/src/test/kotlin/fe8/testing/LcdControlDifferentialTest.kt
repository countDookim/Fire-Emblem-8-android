package fe8.testing

import gba.core.VerifiesPort
import gba.testkit.GbaMem
import gba.testkit.RamSymbols
import gba.testkit.ReferenceData
import gbafe.render.LcdControl
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Differential test of [LcdControl.flushTo] (`FlushLCDControl`): for every full mGBA dump (RAM + I/O), the
 * registers our flush produces from the dumped `gLCDControlBuffer` must equal the dumped I/O registers.
 *
 * The dump is taken after the frame's VBlank flush, so where game code edits the buffer again after the
 * flush in the same frame the two can legitimately differ. The two such cases in the data are recognised
 * explicitly (power-on frames before the first flush; the health & safety BLDY pulse, one step ahead);
 * any other difference fails.
 * Coverage note: in these boot/title frames DISPCNT, DISPSTAT, BGxCNT, BLDCNT, BLDALPHA and BLDY carry
 * non-zero values; scroll, window and affine registers are all zero (a gameplay reference run would
 * exercise them).
 */
class LcdControlDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val sym = RamSymbols.fromEnvironment()

    @Test
    @VerifiesPort("FlushLCDControl")
    fun `flushed registers match mGBA I-O for every full dump`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val addr = sym["gLCDControlBuffer"].address - GbaMem.IWRAM
        val lcd = LcdControl()
        val mismatches = mutableListOf<String>()
        var checked = 0
        var exact = 0
        var preFlush = 0
        var pulse = 0
        var nonZero = 0
        for (runName in listOf("boot_idle", "boot_to_title", "title_state_replay")) {
            val run = ref.run(runName)
            for (dump in run.dumps.keys) {
                if (!File(ref.root, "$runName/$dump.io.bin").isFile) continue
                val io = run.region(dump, "io")
                lcd.readFrom(run.region(dump, "iwram"), addr)
                val ours = io.copyOf()
                lcd.flushTo(ours)
                // DISPSTAT bits 0..2 are live status flags owned by the hardware.
                ours[4] = ((ours[4].toInt() and 0xF8) or (io[4].toInt() and 7)).toByte()
                val diff = (0 until io.size).filter { ours[it] != io[it] }
                when {
                    diff.isEmpty() -> exact++
                    // Power-on: AgbMain has not flushed yet; DISPCNT still holds the reset value (forced blank).
                    run.dumps[dump]!! <= 10 && (io[0].toInt() and 0xFF) == 0x80 && io[1].toInt() == 0 -> preFlush++
                    // Health & safety screen: BLDY pulses by 1 per frame and the dump is taken after the logic
                    // already stepped the buffer for the next frame.
                    diff == listOf(0x54) && kotlin.math.abs(ours[0x54] - io[0x54]) == 1 -> pulse++
                    else -> mismatches += "$runName/$dump@" + diff.joinToString(",") { "0x%02x".format(it) }
                }
                if (lcd.bldcnt != 0 || lcd.blendY != 0 || lcd.blendCoeffA != 0) nonZero++
                checked++
            }
        }
        assertEquals(emptyList<String>(), mismatches)
        // Pinned for the current reference data: 14 exact, 6 power-on, 4 BLDY pulse frames.
        assertEquals(listOf(14, 6, 4), listOf(exact, preFlush, pulse), "exact / pre-flush / pulse of $checked dumps")
        assertTrue(nonZero >= 3, "only $nonZero dumps exercise the blend registers")
    }
}
