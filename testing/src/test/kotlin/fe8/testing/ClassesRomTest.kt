package fe8.testing

import gbafe.extract.fe8.classes.ClassesExtractor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Extracts `gClassData` from the ROM and spot-checks rows against the decomp's `src/data_classes.c`.
 * Also dumps `testing/build/extracted/data/classes.json` for `tools/tests/test_schemas.py`.
 */
class ClassesRomTest {
    private val rom = File(System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets", "baserom.gba")

    /** `gClassData` in decomp/fireemblem8.nm.txt: 0x08807164, size 0x29AC = 127 rows (classes 1..127). */
    private val address = 0x08807164
    private val count = 0x29AC / ClassesExtractor.ENTRY_SIZE
    private val names = mapOf(1 to "CLASS_EPHRAIM_LORD", 3 to "CLASS_EPHRAIM_MASTER_LORD", 0x39 to "CLASS_PUPIL_T2", 0x7F to "CLASS_PUPIL_T1")

    @Suppress("UNCHECKED_CAST")
    private fun entries(): List<Map<String, Any?>> {
        assumeTrue(rom.isFile, "ROM not present (run tools/setup.sh)")
        return ClassesExtractor.extract(rom.readBytes(), address, count, names)["entries"] as List<Map<String, Any?>>
    }

    @Test
    fun `127 classes, first is Ephraim Lord and matches data_classes_c`() {
        val all = entries()
        assertEquals(127, all.size)
        val e = all[0]
        assertEquals("EPHRAIM_LORD", e["id"]); assertEquals(0x2bf, e["nameText"]); assertEquals(0x309, e["descText"])
        assertEquals("EPHRAIM_MASTER_LORD", e["promotion"]); assertEquals(0x8b, e["defaultPortrait"])
        assertEquals(mapOf("hp" to 18, "pow" to 6, "skl" to 6, "spd" to 7, "def" to 6, "res" to 0, "con" to 8, "mov" to 5), e["bases"])
        assertEquals(60, (e["caps"] as Map<*, *>)["hp"]); assertEquals(3, e["relativePower"])
        assertEquals(mapOf("hp" to 90, "pow" to 45, "skl" to 40, "spd" to 45, "def" to 15, "res" to 15, "lck" to 40), e["growths"])
        assertEquals(listOf("lord", "lock_5"), e["attributes"])
        assertEquals(31, (e["baseRanks"] as Map<*, *>)["LANCE"]); assertEquals(0, (e["baseRanks"] as Map<*, *>)["SWORD"])
        assertEquals(mapOf("weaponType" to "LANCE", "anim" to 1), (e["battleAnims"] as List<*>)[0])
        // TerrainTable_Avo_Common[TERRAIN_VILLAGE_REGULAR = 3] = 10 (src/data_terrains.c).
        assertEquals(10, (e["terrainAvoid"] as List<*>)[3])
        assertEquals(null, e["terrainUnknown50"])
    }

    @Test
    fun `last class Pupil T1 and Brigand's extra terrain table`() {
        val all = entries()
        val last = all[126]
        assertEquals("PUPIL_T1", last["id"]); assertEquals(0x2d9, last["nameText"]); assertEquals("PUPIL_T2", last["promotion"])
        assertEquals(17, (last["bases"] as Map<*, *>)["hp"]); assertEquals(55, (last["growths"] as Map<*, *>)["hp"])
        assertEquals(31, (last["baseRanks"] as Map<*, *>)["ANIMA"])
        val brigand = all[0x41 - 1] // CLASS_BRIGAND = 0x41
        assertNotNull(brigand["terrainUnknown50"])
    }

    @Test
    fun `dump classes json for schema validation`() {
        assumeTrue(rom.isFile, "ROM not present (run tools/setup.sh)")
        val out = File(System.getProperty("fe8.extractedOut") ?: "build/extracted", "data/classes.json")
        out.parentFile.mkdirs()
        out.writeText(ClassesExtractor.extractJson(rom.readBytes(), address, count, emptyMap()))
    }
}
