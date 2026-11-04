package fe8.testing

import gba.extract.IndexedPng
import gbafe.extract.fe8.portraits.PortraitsExtractor
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * M2-11: `portrait_data` (0x088ACBE0, 0x12D0 bytes = 172 entries, decomp/fireemblem8.nm.txt) against
 * the decomp's `src/portrait_data.c` (pinned 04d1edb). Dumps `testing/build/extracted/portraits/`.
 */
class PortraitsRomTest {
    private val romFile = File(System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets", "baserom.gba")

    private fun extract(): PortraitsExtractor.Output {
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        return PortraitsExtractor.extract(romFile.readBytes(), 0x8ACBE0, 0x12D0 / PortraitsExtractor.ENTRY_SIZE)
    }

    @Test
    fun `entries match portrait_data_c`() {
        val out = extract()
        val e = out.entries
        assertEquals(172, e.size)
        // 1: {portrait_Eirika_*, 0, 2, 6, 3, 4, FACE_BLINK_NORMAL}; // 2: same images, FACE_BLINK_CLOSED.
        assertEquals(listOf(2, 6), e[1]["mouthPos"]); assertEquals(listOf(3, 4), e[1]["eyesPos"])
        assertEquals(e[1]["sheet"], e[2]["sheet"])
        // FACE_BLINK_NORMAL = 1, FACE_BLINK_CLOSED = 6 (include/types.h).
        assertEquals(1, e[1]["blink"]); assertEquals(6, e[2]["blink"])
        assertEquals(null, e[1]["card"])
        // 115 entries with a face sheet, 57 generic cards (pointer census of the table).
        assertEquals(115, e.count { it["sheet"] != null }); assertEquals(57, e.count { it["card"] != null })
        val sheet = IndexedPng.read(out.files.getValue(e[1]["sheet"] as String)).image
        assertEquals(256 to 32, sheet.width to sheet.height)
        val card = IndexedPng.read(out.files.getValue(e.first { it["card"] != null }["card"] as String)).image
        assertEquals(80 to 72, card.width to card.height)
    }

    @Test
    fun `dump portraits for schema validation`() {
        val out = extract()
        val dir = File(System.getProperty("fe8.extractedOut") ?: "build/extracted", "portraits")
        for ((path, png) in out.files) File(dir, path).apply { parentFile.mkdirs() }.writeBytes(png)
        File(dir, "portraits.json").writeText(out.json)
    }
}
