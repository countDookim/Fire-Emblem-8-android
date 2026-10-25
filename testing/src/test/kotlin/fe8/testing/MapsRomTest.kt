package fe8.testing

import gbafe.extract.fe8.maps.MapsExtractor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * M2-13: the prologue map (chapter 0, `map` ids obj1 1, obj2 0, palette 2, tileConfig 3, mainLayer 4 in
 * `src/data/chapter_settings.json`) from `gChapterDataAssetTable` (0x088B363C, decomp/fireemblem8.nm.txt).
 * Rendered output was checked by eye against the game's prologue map. Dumps
 * `testing/build/extracted/maps/` for the Tiled-structure test in `tools/tests/test_schemas.py`.
 */
class MapsRomTest {
    private val romFile = File(System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets", "baserom.gba")

    private companion object {
        /** Chapter 2's `map` ids (obj1, obj2, palette, tileConfig, mainLayer, changeLayer) in chapter_settings.json. */
        val CH2 = intArrayOf(1, 0, 2, 3, 11, 12)
    }

    @Test
    fun `every chapter map converts`() {
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        val rom = romFile.readBytes()
        var maps = 0
        for (ch in gbafe.extract.fe8.chapters.ChaptersExtractor.extract(rom, 0x8B0890, 79)) {
            val m = (ch["map"] as Map<*, *>).mapValues { it.value as Int }
            if (m.getValue("mainLayerId") == 0) continue
            val o = MapsExtractor.extract(
                rom, 0x8B363C, m.getValue("obj1Id"), m.getValue("obj2Id"), m.getValue("paletteId"),
                m.getValue("tileConfigId"), m.getValue("mainLayerId"), m.getValue("changeLayerId"),
            )
            assertTrue(o.width in 1..127 && o.height in 1..127, "chapter ${ch["index"]}: ${o.width}x${o.height}")
            o.tilesetPng()
            for (c in o.changes) assertTrue(c.x + c.w <= o.width && c.y + c.h <= o.height, "chapter ${ch["index"]} change ${c.id}")
            maps++
        }
        assertEquals(79, maps, "every chapter in the table has a main layer")
    }

    private fun prologue(): MapsExtractor.Output {
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        return MapsExtractor.extract(romFile.readBytes(), 0x8B363C, 1, 0, 2, 3, 4)
    }

    @Test
    fun `prologue map size and cells`() {
        val m = prologue()
        // The prologue map is 15x10 metatiles (header bytes of asset 4); every cell is a valid metatile.
        assertEquals(15 to 10, m.width to m.height)
        assertEquals(150, m.cells.size)
        assertTrue(m.cells.all { it in 0 until MapsExtractor.METATILES })
        assertTrue(m.terrain.all { it in 0 until 65 }, "terrain ids < TERRAIN_COUNT")
    }

    @Test
    fun `dump prologue tmj`() {
        val m = prologue()
        val dir = File(System.getProperty("fe8.extractedOut") ?: "build/extracted", "maps")
        dir.mkdirs()
        File(dir, "tileset_03.png").writeBytes(m.tilesetPng())
        File(dir, "chapter_00.tmj").writeText(m.tmj("tileset_03", "tileset_03.png"))
        // Chapter 2 (map ids from chapter_settings.json) has map changes (doors/chests).
        val c2 = MapsExtractor.extract(romFile.readBytes(), 0x8B363C, CH2[0], CH2[1], CH2[2], CH2[3], CH2[4], CH2[5])
        assertTrue(c2.changes.isNotEmpty())
        File(dir, "tileset_c2.png").writeBytes(c2.tilesetPng())
        File(dir, "chapter_02.tmj").writeText(c2.tmj("tileset_c2", "tileset_c2.png"))
    }
}
