package fe8.testing

import fe8.extract.characters.CharactersExtractor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Extracts `gCharacterData` from the ROM and spot-checks Eirika against the decomp's
 * `src/data_characters.c` and `src/data_supports.c` (M2-06). Also dumps
 * `testing/build/extracted/data/characters.json` for `tools/tests/test_schemas.py`.
 */
class CharactersRomTest {
    private val rom = File(System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets", "baserom.gba")

    /** `gCharacterData` in decomp/fireemblem8.nm.txt: 0x08803D64, size 0x3400 = 256 rows. */
    private val address = 0x08803D64
    private val count = 0x3400 / CharactersExtractor.ENTRY_SIZE

    @Suppress("UNCHECKED_CAST")
    private fun entries(): List<Map<String, Any?>> {
        assumeTrue(rom.isFile, "ROM not present (run tools/setup.sh)")
        val names = mapOf(1 to "CHARACTER_EIRIKA", 2 to "CHARACTER_SETH", 0xF to "CHARACTER_EPHRAIM")
        return CharactersExtractor.extract(rom.readBytes(), address, count, names, mapOf(2 to "CLASS_EIRIKA_LORD"))["entries"] as List<Map<String, Any?>>
    }

    @Test
    fun `256 rows, Eirika matches data_characters_c and data_supports_c`() {
        val all = entries()
        assertEquals(256, all.size)
        val e = all[0]
        assertEquals("EIRIKA", e["id"]); assertEquals(0x212, e["nameText"]); assertEquals(0x26e, e["descText"])
        assertEquals("EIRIKA_LORD", e["defaultClass"]); assertEquals(2, e["portrait"]); assertEquals("LIGHT", e["affinity"])
        assertEquals(1, e["level"]); assertEquals(5, (e["bases"] as Map<*, *>)["lck"])
        assertEquals(1, (e["baseRanks"] as Map<*, *>)["SWORD"])
        assertEquals(mapOf("hp" to 70, "pow" to 40, "skl" to 60, "spd" to 60, "def" to 30, "res" to 30, "lck" to 60), e["growths"])
        assertEquals(listOf("female"), e["attributes"]); assertEquals(7, e["visitGroup"])
        val sup = e["supports"] as List<Map<*, *>>
        assertEquals(7, sup.size)
        assertEquals(mapOf("partner" to "EPHRAIM", "baseExp" to 30, "growth" to 4), sup[0])
        assertEquals("SETH", sup[1]["partner"]); assertEquals(25, sup[1]["baseExp"])
    }

    @Test
    fun `dump characters json for schema validation`() {
        assumeTrue(rom.isFile, "ROM not present (run tools/setup.sh)")
        val out = File(System.getProperty("fe8.extractedOut") ?: "build/extracted", "data/characters.json")
        out.parentFile.mkdirs()
        out.writeText(CharactersExtractor.extractJson(rom.readBytes(), address, count, emptyMap()))
    }
}
