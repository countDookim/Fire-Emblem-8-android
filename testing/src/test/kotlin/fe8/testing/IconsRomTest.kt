package fe8.testing

import gba.extract.IndexedPng
import gbafe.extract.fe8.ui.IconsExtractor
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Icons: `item_icon_tiles` 0x085926F4 up to `item_icon_palette` 0x085996F4 (0x7000 bytes = 224 icons)
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

    @Test
    fun `cursors and misc OBJ UI extract`() {
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        // decomp/fireemblem8.nm.txt: gGfx_MiscUiGraphics 0x0859E8E0, gPal_MiscUiGraphics 0x0859ED70,
        // gImg_UiCursorHandTop 0x0859EDB0, gImg_UiCursorHandBottom 0x0859EDEC, sMapCursorSpriteLut 0x0859A4C0,
        // sSprite_MapCursorStretched 0x0859A4A6, gSprite_UiCursorHand_08A20B3C / _08A20B44. Checked by eye.
        val o = gbafe.extract.fe8.ui.CursorsExtractor.extract(
            romFile.readBytes(), 0x59E8E0, 0x59ED70, 0x59A4C0, 0x59A4A6, 0x59EDB0, 0x59EDEC, listOf(0xA20B3C, 0xA20B44),
        )
        val sheet = IndexedPng.read(o.files.getValue("cursors.png")).image
        assertEquals(144 to 32, sheet.width to sheet.height)
        val hand = IndexedPng.read(o.files.getValue("cursor_hand.png")).image
        assertTrue((0 until hand.height).any { y -> (0 until hand.width).any { x -> hand[x, y] != 0 } })
        // sMapCursorSpriteLut: 10 x A, B, 4 x C, B (src/bm.c) -> 4 distinct sprites with the stretched one.
        assertTrue(o.json.contains("\"frames\": [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 2, 2, 2, 2, 1]") ||
            o.json.contains("\"frames\":[0,0,0,0,0,0,0,0,0,0,1,2,2,2,2,1]"), o.json)
        val dir = File(System.getProperty("fe8.extractedOut") ?: "build/extracted", "ui")
        dir.mkdirs()
        for ((n, png) in o.files) File(dir, n).writeBytes(png)
        File(dir, "cursors.json").writeText(o.json)
    }
}
