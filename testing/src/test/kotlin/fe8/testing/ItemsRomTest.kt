package fe8.testing

import fe8.extract.items.ItemsExtractor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Extracts `gItemData` from the ROM and spot-checks rows against the decomp's `src/data_items.c`,
 * `src/data_itemuse.c` and `src/data_itembonus.c` (M2-04). Schema validity of the full output is checked
 * by `tools/tests/test_schemas.py` on the same JSON.
 */
class ItemsRomTest {
    private val rom = File(System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets", "baserom.gba")

    /** `gItemData` in decomp/fireemblem8.nm.txt: 0x08809B10, size 0x1CF8 = 206 rows. */
    private val address = 0x08809B10
    private val count = 0x1CF8 / ItemsExtractor.ENTRY_SIZE

    @Suppress("UNCHECKED_CAST")
    private fun entries(): List<Map<String, Any?>> {
        assumeTrue(rom.isFile, "ROM not present (run tools/setup.sh)")
        val doc = ItemsExtractor.extract(rom.readBytes(), address, count, mapOf(1 to "ITEM_SWORD_IRON"))
        return doc["entries"] as List<Map<String, Any?>>
    }

    @Test
    fun `table has 206 rows numbered in order`() {
        assertEquals(206, entries().size)
    }

    @Test
    fun `first item Iron Sword matches data_items_c`() {
        val e = entries()[1]
        assertEquals("SWORD_IRON", e["id"])
        assertEquals(0x354, e["nameText"]); assertEquals(0x404, e["descText"])
        assertEquals("SWORD", e["weaponType"]); assertEquals(listOf("weapon"), e["attributes"])
        assertEquals(46, e["uses"]); assertEquals(5, e["might"]); assertEquals(90, e["hit"])
        assertEquals(5, e["weight"]); assertEquals(0, e["crit"])
        assertEquals(1, e["minRange"]); assertEquals(1, e["maxRange"])
        assertEquals(10, e["costPerUse"]); assertEquals(1, e["rankExp"]); assertEquals(1, e["weaponExp"])
        assertEquals(null, e["statBonuses"]); assertEquals(null, e["effectiveAgainst"])
    }

    @Test
    fun `last item ITEM_UNK_CD matches data_items_c`() {
        val e = entries()[0xCD]
        assertEquals("ITEM_CD", e["id"])
        assertEquals(0x403, e["nameText"]); assertEquals(0x4ab, e["descText"])
        assertEquals("DANCE", e["weaponType"]); assertEquals(emptyList<String>(), e["attributes"])
        assertEquals(1, e["minRange"]); assertEquals(2, e["maxRange"]); assertEquals(1, e["weaponExp"])
    }

    @Test
    fun `pointers resolve - Rapier effectiveness and Excalibur bonus`() {
        val all = entries()
        val rapier = all[0x09]
        assertEquals(listOf("weapon", "unsellable", "lock_4"), rapier["attributes"])
        assertEquals(listOf(0x05, 0x06, 0x07), (rapier["effectiveAgainst"] as List<*>).take(3))
        assertEquals(20, (rapier["effectiveAgainst"] as List<*>).size) // 20 classes + CLASS_NONE terminator
        val bonus = all[0x3E]["statBonuses"] as Map<*, *>
        assertEquals(5, bonus["spd"]); assertEquals(0, bonus["skl"])
    }

    /**
     * Writes the extracted JSON to `testing/build/extracted/data/items.json` (build dir, never committed)
     * for `tools/tests/test_schemas.py`, which validates it against `schemas/items.schema.json`.
     */
    @Test
    fun `dump items json for schema validation`() {
        assumeTrue(rom.isFile, "ROM not present (run tools/setup.sh)")
        val out = File(System.getProperty("fe8.extractedOut") ?: "build/extracted", "data/items.json")
        out.parentFile.mkdirs()
        out.writeText(ItemsExtractor.extractJson(rom.readBytes(), address, count, emptyMap()))
    }
}
