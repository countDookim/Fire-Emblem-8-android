package fe8.testing

import gbafe.extract.fe8.terrains.TerrainsExtractor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Extracts terrain tables from the ROM and spot-checks them against the decomp's `src/data_terrains.c`.
 * Addresses from decomp/fireemblem8.nm.txt (each table is 0x41 bytes). Also dumps
 * `testing/build/extracted/data/terrains.json` for `tools/tests/test_schemas.py`.
 */
class TerrainsRomTest {
    private val rom = File(System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets", "baserom.gba")

    private val tables = mapOf(
        TerrainsExtractor.HEAL_AMOUNT to 0x0880C744,
        TerrainsExtractor.HEALS_STATUS to 0x0880C785,
        "TerrainTable_Avo_Common" to 0x0880C479,
    )

    @Suppress("UNCHECKED_CAST")
    private fun doc(): Map<String, Any?> {
        assumeTrue(rom.isFile, "ROM not present (run tools/setup.sh)")
        return TerrainsExtractor.extract(rom.readBytes(), tables, mapOf(0x0A to "TERRAIN_FORT", 0x1F to "TERRAIN_THRONE"))
    }

    @Test
    fun `fort and throne heal values match data_terrains_c`() {
        @Suppress("UNCHECKED_CAST")
        val e = doc()["entries"] as List<Map<String, Any?>>
        assertEquals(65, e.size)
        assertEquals(mapOf("id" to "FORT", "healAmount" to 20, "healsStatus" to false), e[0x0A])
        assertEquals(mapOf("id" to "THRONE", "healAmount" to 10, "healsStatus" to true), e[0x1F])
        assertEquals(mapOf("id" to "TERRAIN_01", "healAmount" to 0, "healsStatus" to false), e[1])
    }

    @Test
    fun `other tables are exported by symbol`() {
        val t = doc()["tables"] as Map<*, *>
        assertEquals(setOf("TerrainTable_Avo_Common"), t.keys)
        // [TERRAIN_VILLAGE_REGULAR = 3] = 10, [TERRAIN_FORT] = 20 in TerrainTable_Avo_Common.
        assertEquals(10, (t["TerrainTable_Avo_Common"] as List<*>)[3])
        assertEquals(20, (t["TerrainTable_Avo_Common"] as List<*>)[0x0A])
    }

    @Test
    fun `dump terrains json for schema validation`() {
        assumeTrue(rom.isFile, "ROM not present (run tools/setup.sh)")
        val out = File(System.getProperty("fe8.extractedOut") ?: "build/extracted", "data/terrains.json")
        out.parentFile.mkdirs()
        out.writeText(TerrainsExtractor.extractJson(rom.readBytes(), tables, emptyMap()))
    }
}
