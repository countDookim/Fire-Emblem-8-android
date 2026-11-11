package fe8.testing

import gbafe.extract.fe8.chapters.ChaptersExtractor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * `gChapterDataTable` (0x088B0890, 0x2DAC bytes = 79 chapters, decomp/fireemblem8.nm.txt) against
 * the decomp's `src/data/chapter_settings.json` (pinned 04d1edb). Dumps
 * `testing/build/extracted/data/chapters.json` for the schema test.
 */
class ChaptersRomTest {
    private val romFile = File(System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets", "baserom.gba")

    private fun chapters(): List<Map<String, Any?>> {
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        return ChaptersExtractor.extract(romFile.readBytes(), 0x8B0890, 0x2DAC / ChaptersExtractor.ENTRY_SIZE)
    }

    @Test
    fun `prologue and chapter 5 match chapter_settings_json`() {
        val c = chapters()
        assertEquals(79, c.size)
        val p = c[0]
        assertEquals("L00", p["internalName"])
        assertEquals(
            mapOf("obj1Id" to 1, "obj2Id" to 0, "paletteId" to 2, "tileConfigId" to 3, "mainLayerId" to 4, "objAnimId" to 5, "paletteAnimId" to 0, "changeLayerId" to 6),
            p["map"],
        )
        assertEquals(listOf(9, 19, 19, 9, 19, 19, 9, 65535, 11, 65535, 65535), p["mapBgmIds"])
        assertEquals(1, p["difficultModeLevelBonus"])
        assertEquals(50, p["mapCrackedWallHeath"])
        assertEquals(listOf(5, 5), p["turnsForTacticsRankAInEliwoodStory"])
        assertEquals(listOf(100, 100), p["gainedExpForExpRankAInEliwoodStory"])
        assertEquals(352, p["chapTitleTextId"])
        assertEquals(418, p["statusObjectiveTextId"]); assertEquals(413, p["goalWindowTextId"])
        assertEquals(255, p["destPosX"]); assertEquals(255, p["merchantPosX"])
        val c5 = c[5]
        assertEquals("I05", c5["internalName"])
        assertEquals(24, (c5["map"] as Map<*, *>)["obj1Id"]); assertEquals(29, (c5["map"] as Map<*, *>)["changeLayerId"])
        assertEquals(listOf(11, 19, 15, 11, 19, 15, 65535, 65535, 11, 65535, 65535), c5["mapBgmIds"])
        assertEquals(357, c5["chapTitleTextId"]); assertEquals(424, c5["statusObjectiveTextId"]); assertEquals(417, c5["goalWindowTextId"])
    }

    @Test
    fun `dump chapters json for schema validation`() {
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        val out = File(System.getProperty("fe8.extractedOut") ?: "build/extracted", "data/chapters.json")
        out.parentFile.mkdirs()
        out.writeText(ChaptersExtractor.extractJson(romFile.readBytes(), 0x8B0890, 0x2DAC / ChaptersExtractor.ENTRY_SIZE))
    }
}
