package fe8.testing

import fe8.game.RomView
import fe8.game.worldmap.GmData
import fe8.game.worldmap.GmNodeLinks
import fe8.game.worldmap.WmPathData
import fe8.game.worldmap.WorldMapPath
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import java.io.File
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** `src/worldmap_path.c` node helpers against the prologue `ram_trace_prologue` dumps. */
class WorldMapPathTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    private fun available() =
        assumeTrue(ref.available && romFile.isFile, "reference data / ROM not present")

    private fun path(iwram: ByteArray) = WorldMapPath(
        GmData(iwram, RngService()),
        WmPathData(RomView.ofRom(romFile.readBytes())),
    )

    @Test
    @VerifiesPort("RefreshGmNodeLinksExt", "RefreshGmNodeLinks")
    fun `node links rebuilt from open paths match the traced table`() {
        available()
        for ((runName, dump) in DUMPS) {
            val run = ref.run(runName)
            val iwram = run.region(dump, "iwram").copyOf(0x8000)
            val ewram = run.region(dump, "ewram")
            val expected = ewram.copyOfRange(TABLE, TABLE + GmNodeLinks.SIZE)
            assertTrue(expected.any { it.toInt() != 0 }, "traced table empty in $runName/$dump")
            val links = GmNodeLinks(ByteArray(GmNodeLinks.SIZE) { 0x55 })
            path(iwram).refreshGmNodeLinks(links)
            assertArrayEquals(expected, links.bytes, "gUnknown_0201AFF0 in $runName/$dump")
            val ext = GmNodeLinks()
            path(iwram).refreshGmNodeLinksExt(ext)
            assertArrayEquals(expected, ext.bytes, "Ext in $runName/$dump")
        }
    }

    @Test
    fun `reset clears only the cleared bit and next node scans valid cleared nodes`() {
        available()
        val run = ref.run("ram_trace_prologue")
        val iwram = run.region("r05250", "iwram").copyOf(0x8000)
        val gm = GmData(iwram, RngService())
        val worldMap = path(iwram)
        val before = IntArray(GmNodeLinks.NODE_COUNT) { gm.nodeState(it) }
        val first = before.indexOfFirst { it and 3 == 3 }
        assertEquals(first, worldMap.getNextUnclearedNode())
        worldMap.resetGmStoryNode()
        for (i in before.indices) assertEquals(before[i] and 2.inv(), gm.nodeState(i))
        assertEquals(-1, worldMap.getNextUnclearedNode())
    }

    private companion object {
        /** Offset of `gUnknown_0201AFF0` (0x0201AFF0) in the EWRAM dump. */
        const val TABLE = 0x1AFF0
        val DUMPS = listOf(
            "ram_trace_ch6_start" to "f01140",
            "ram_trace_ch6_phase_start" to "f01620",
            "ram_trace_ch6_cursor_edges" to "f01990",
            "ram_trace_ch2_scroll" to "f01200",
        )
    }
}
