package fe8.game.worldmap

import gba.core.VerifiesPort
import gbafe.core.rng.RngService
import java.io.File
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * `GmDataInit` runs once from `AgbMain`; the prologue trace's first dump already holds its result.
 */
class GmDataTest {
    private val assets =
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets"

    @VerifiesPort("GmDataInit")
    @Test
    fun `gmDataInit from garbage matches gGMData in the first trace dump`() {
        val dump = File(assets, "reference/out/ram_trace_prologue/r00030.iwram.bin")
        assumeTrue(dump.isFile, "ram_trace_prologue missing")
        val expected = dump.readBytes()
        val iwram = ByteArray(expected.size) { 0x5A }
        val rng = RngService().apply { seedLikeBoot() }
        GmData(iwram, rng).init()
        val range = GmData.BASE until GmData.BASE + GmData.CLEAR_SIZE
        assertArrayEquals(
            expected.copyOfRange(range.first, range.last),
            iwram.copyOfRange(range.first, range.last),
            "gGMData bytes",
        )
    }
}
