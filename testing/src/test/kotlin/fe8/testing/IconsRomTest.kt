package fe8.testing

import gbafe.extract.IndexedPng
import gbafe.extract.fe8.ui.IconsExtractor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * M2-20 (icons): `item_icon_tiles` 0x085926F4 up to `item_icon_palette` 0x085996F4 (0x7000 bytes = 224 icons)
 * in decomp/fireemblem8.nm.txt (`data/data_item_icon.s`, pinned 04d1edb). Icon 1 = `item_icon_sword_iron`
 * (0x08592774 = tiles + 0x80). Sheet checked by eye. Dumps `testing/build/extracted/ui/`.
 */
class IconsRomTest {
    private val romFile = File(System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets", "baserom.gba")

    @Test
    fun `icon sheet has 224 icons`() {
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        val o = IconsExtractor.extract(romFile.readBytes(), 0x5926F4, 0x7000 / IconsExtractor.ICON_BYTES, 0x5996F4)
        assertEquals(224, o.count)
        val img = IndexedPng.read(o.png).image
        assertEquals(256 to 224, img.width to img.height)
        // The iron sword icon (index 1) is not blank.
        assertTrue((0 until 16).any { y -> (16 until 32).any { x -> img[x, y] != 0 } })
        val dir = File(System.getProperty("fe8.extractedOut") ?: "build/extracted", "ui")
        dir.mkdirs()
        File(dir, "icons.png").writeBytes(o.png)
        File(dir, "icons.json").writeText(o.json)
    }

    @Test
    fun `window frames extract for all 4 colors`() {
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        // sUiFrameModelTilemapLookup 0x085B6430, sUiFramePaletteLookup 0x085B6450, sUiFrameImageLookup 0x085B6470
        // (static, decomp/fireemblem8.nm.txt); style 0 model = gUnknown_080DA2F4. Frames checked by eye.
        val o = gbafe.extract.fe8.ui.WindowFramesExtractor.extract(romFile.readBytes(), 0x5B6470, 0x5B6450, 0x5B6430)
        assertEquals(4, o.files.size)
        val dir = File(System.getProperty("fe8.extractedOut") ?: "build/extracted", "ui")
        dir.mkdirs()
        for ((n, png) in o.files) File(dir, n).writeBytes(png)
        File(dir, "windowframes.json").writeText(o.json)
    }
}
