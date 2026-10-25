package fe8.testing

import gbafe.extract.IndexedPng
import gbafe.extract.fe8.mapsprites.MapSpritesExtractor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * M2-17 (standing half): `unit_icon_wait_table` 0x088AF880 (0x358 bytes = 107 entries) and
 * `gPal_MapSprite` 0x0859EE20 (decomp/fireemblem8.nm.txt) against `src/unit_icon_wait_data.c` (pinned
 * 04d1edb). Eirika/Ephraim frames checked by eye. Dumps `testing/build/extracted/mapsprites/`.
 */
class MapSpritesRomTest {
    private val romFile = File(System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets", "baserom.gba")

    private fun extract(): MapSpritesExtractor.Output {
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        return MapSpritesExtractor.extract(romFile.readBytes(), 0x8AF880, 0x358 / MapSpritesExtractor.ENTRY_SIZE, 0x59EE20)
    }

    @Test
    fun `standing sprites match unit_icon_wait_data_c`() {
        val o = extract()
        assertEquals(107, o.entries.size)
        // 0: {2, UNIT_ICON_SIZE_16x32, Ephraim_Lord}, 1: {0, UNIT_ICON_SIZE_16x16, Eirika_Lord}, 6: Armor_Knight 16x16.
        assertEquals(listOf(16, 32, 2), listOf("width", "height", "pattern").map { o.entries[0][it] })
        assertEquals(listOf(16, 16, 0), listOf("width", "height", "pattern").map { o.entries[1][it] })
        assertEquals(16, o.entries[6]["height"])
        val img = IndexedPng.read(o.files.getValue("standing/000.png")).image
        assertEquals(16 to 96, img.width to img.height)
        assertEquals(mapOf(16 to 50, 32 to 57).toSortedMap(), o.entries.groupingBy { it["height"] as Int }.eachCount().toSortedMap())
    }

    @Test
    fun `dump map sprites`() {
        val o = extract()
        val dir = File(System.getProperty("fe8.extractedOut") ?: "build/extracted", "mapsprites")
        for ((p, png) in o.files) File(dir, p).apply { parentFile.mkdirs() }.writeBytes(png)
        File(dir, "mapsprites.json").writeText(o.json)
    }
}
