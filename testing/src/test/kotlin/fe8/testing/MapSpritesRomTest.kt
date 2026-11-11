package fe8.testing

import gba.extract.IndexedPng
import gbafe.extract.fe8.mapsprites.MapSpritesExtractor
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * `unit_icon_wait_table` 0x088AF880 (0x358 bytes = 107 entries), `unit_icon_move_table` 0x089A2E00
 * (0x3F8 bytes = 127 = class count) and
 * `gPal_MapSprite` 0x0859EE20 (decomp/fireemblem8.nm.txt) against `src/unit_icon_wait_data.c` (pinned
 * 04d1edb) and `data/const_data_unit_icon_move.s`. Eirika/Ephraim frames checked by eye. Dumps `testing/build/extracted/mapsprites/`.
 */
class MapSpritesRomTest {
    private val romFile = File(System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets", "baserom.gba")

    private fun extract(): MapSpritesExtractor.Output {
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        return MapSpritesExtractor.extract(
            romFile.readBytes(), 0x8AF880, 0x358 / MapSpritesExtractor.ENTRY_SIZE, 0x59EE20,
            0x9A2E00, 0x3F8 / MapSpritesExtractor.MOVE_ENTRY_SIZE,
        )
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
    fun `moving sprites match const_data_unit_icon_move_s`() {
        val o = extract()
        assertEquals(127, o.moving.size)
        // Eirika_Lord (entry 1): 19 frames, 5 anims; frame 0 = one 32x32 object {0xE0, 0x81F0, 0} sheet tile 0,
        // frame 4 the h-flipped copy (0x91F0); anim_0 = 13/0, 6/1, 13/2, 6/3 loop; anim_4 = 20/16, 4/17, 20/18, 4/17.
        val eirika = o.moving[1]
        assertEquals(2, eirika["classId"])
        @Suppress("UNCHECKED_CAST") val frames = eirika["frames"] as List<List<Map<String, Int>>>
        @Suppress("UNCHECKED_CAST") val anims = eirika["anims"] as List<Map<String, Any>>
        assertEquals(19, frames.size)
        assertEquals(5, anims.size)
        assertEquals(mapOf("attr0" to 0xE0, "attr1" to 0x81F0, "attr2" to 0, "tile" to 0), frames[0].single())
        assertEquals(mapOf("attr0" to 0xE0, "attr1" to 0x91F0, "attr2" to 0, "tile" to 0x10), frames[5].single())
        assertEquals(listOf(13 to 0, 6 to 1, 13 to 2, 6 to 3), steps(anims[0]))
        assertEquals(listOf(20 to 16, 4 to 17, 20 to 18, 4 to 17), steps(anims[4]))
        assertEquals("loop", anims[0]["end"])
        // Shared sheets ({Cavalier, Cavalier} at 4 and 5) are written once.
        assertEquals(o.moving[4]["image"], o.moving[5]["image"])
        val sheet = IndexedPng.read(o.files.getValue(eirika["image"] as String)).image
        assertEquals(32, sheet.width)
        // Every object tile range lies inside its sheet.
        for (m in o.moving) {
            val h = IndexedPng.read(o.files.getValue(m["image"] as String)).image.height
            @Suppress("UNCHECKED_CAST")
            for (obj in (m["frames"] as List<List<Map<String, Int>>>).flatten()) assert(obj.getValue("tile") * 64 < 32 * h) { "${m["id"]}: $obj" }
        }
    }

    @Test
    fun `class map sprite ids are valid standing ids`() {
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        val rom = romFile.readBytes()
        // gClassData 0x08807164, 127 rows of 0x54 bytes; byte 6 = SMSId (ClassesExtractor "mapSprite").
        val ids = List(127) { rom[0x807164 + it * 0x54 + 6].toInt() and 0xFF }
        assert(ids.all { it < 107 }) { ids }
    }

    private fun steps(anim: Map<String, Any>): List<Pair<Int, Int>> {
        @Suppress("UNCHECKED_CAST")
        return (anim["steps"] as List<Map<String, Int>>).map { it.getValue("duration") to it.getValue("frame") }
    }

    @Test
    fun `dump map sprites`() {
        val o = extract()
        val dir = File(System.getProperty("fe8.extractedOut") ?: "build/extracted", "mapsprites")
        for ((p, png) in o.files) File(dir, p).apply { parentFile.mkdirs() }.writeBytes(png)
        File(dir, "mapsprites.json").writeText(o.json)
    }
}
