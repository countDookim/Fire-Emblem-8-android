package fe8.testing

import gba.extract.IndexedPng
import gbafe.extract.font.FontExtractor
import java.io.File
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * M2-10: text fonts against the decomp's `data/fonts/glyphs_{1,2}.h` (pinned 04d1edb). Table addresses
 * from decomp/fireemblem8.nm.txt: `TextGlyphs_System` 0x0858C7EC, `TextGlyphs_Talk` 0x0858F6F4,
 * `TextGlyphs_Special` 0x08590B44. Also dumps `testing/build/extracted/fonts/` for the schema test.
 */
class FontsRomTest {
    private val romFile = File(System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets", "baserom.gba")

    private fun font(addr: Int): List<FontExtractor.Glyph> {
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        return FontExtractor.read(romFile.readBytes(), addr - 0x08000000)
    }

    @Test
    fun `glyph widths match glyphs_h`() {
        val system = font(0x0858C7EC).associateBy { it.code }
        val talk = font(0x0858F6F4).associateBy { it.code }
        // TextGlyphs_System['A'] = gUnknown_0858A62C (.width = 5), ['a'] = gUnknown_0858AF2C (.width = 6).
        assertEquals(5, system.getValue('A'.code).width)
        assertEquals(6, system.getValue('a'.code).width)
        // TextGlyphs_Talk['A'] = gUnknown_0858D57C (.width = 6), ['w'] = gUnknown_0858E4AC (.width = 6).
        assertEquals(6, talk.getValue('A'.code).width)
        assertEquals(6, talk.getValue('w'.code).width)
        // Control bytes have no glyph (NULL entries).
        assertEquals(null, system[1])
    }

    @Test
    fun `bitmap rows decode 2bpp LSB first`() {
        val a = font(0x0858C7EC).first { it.code == 'A'.code }
        // gUnknown_0858A62C.bitmap[3] = 0x000000F0, [4] = 0x000003AC.
        assertArrayEquals(byteArrayOf(0, 0, 3, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), a.pixels.copyOfRange(3 * 16, 4 * 16))
        assertArrayEquals(byteArrayOf(0, 3, 2, 2, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), a.pixels.copyOfRange(4 * 16, 5 * 16))
    }

    @Test
    fun `atlas png round trips and dump for schema validation`() {
        val out = File(System.getProperty("fe8.extractedOut") ?: "build/extracted", "fonts")
        out.mkdirs()
        for ((name, addr) in listOf("system" to 0x0858C7EC, "talk" to 0x0858F6F4, "special" to 0x08590B44)) {
            val glyphs = font(addr)
            val png = FontExtractor.png(glyphs)
            assertArrayEquals(FontExtractor.atlas(glyphs).pixels, IndexedPng.read(png).image.pixels, name)
            File(out, "$name.png").writeBytes(png)
            File(out, "$name.json").writeText(FontExtractor.toJson("fe8", name, "$name.png", glyphs))
        }
    }
}
