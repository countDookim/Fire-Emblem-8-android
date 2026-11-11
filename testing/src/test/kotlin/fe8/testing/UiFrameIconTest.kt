package fe8.testing

import fe8.game.RomView
import fe8.game.ui.ItemIcon
import fe8.game.ui.UiFrame
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.render.BgHardware
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [UiFrame] and [ItemIcon] verifying against the popup capture in
 * `ram_trace_item_popup`.
 */
class UiFrameIconTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    private fun loadRomView(): RomView {
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        return RomView.ofRom(romFile.readBytes())
    }

    @Test
    fun `getIconGfxTileIndex returns base tile address for slot`() {
        assertEquals(0x300, ItemIcon.getIconGfxTileIndex(0))
        assertEquals(0x2FC, ItemIcon.getIconGfxTileIndex(1))
        assertEquals(0x280, ItemIcon.getIconGfxTileIndex(32))
    }

    @Test
    @VerifiesPort("LoadIconPalette")
    fun `loadIconPalette matches reference OBJ palette 4 in item popup`() {
        assumeTrue(ref.available, "mGBA reference data not present")
        val rom = loadRomView()
        val run = ref.run("ram_trace_item_popup")
        val palBytes = run.region("f18630", "pal")

        val hardware = BgHardware()
        // OBJ palette 4 corresponds to palette slot 16 + 4 = 20 (0x14).
        ItemIcon.loadIconPalette(hardware, rom, index = 0, paletteSlot = 0x14)

        val actualPal = hardware.palette.copyOfRange(20 * 16, 21 * 16)
        val expectedBuf = ByteBuffer.wrap(palBytes, 512 + 4 * 32, 32)
            .order(ByteOrder.LITTLE_ENDIAN)
        val expectedPal = ShortArray(16) { expectedBuf.short }

        assertArrayEquals(expectedPal, actualPal, "OBJ palette 4 mismatch vs reference f18630")
    }

    @Test
    @VerifiesPort("LoadIconObjectGraphics")
    fun `loadIconObjectGraphics matches reference Rapier icon tiles in OBJ VRAM`() {
        assumeTrue(ref.available, "mGBA reference data not present")
        val rom = loadRomView()
        val run = ref.run("ram_trace_item_popup")
        val vramBytes = run.region("f18630", "vram")

        val hardware = BgHardware()
        // Rapier icon index is 8; tile index is 0x240.
        ItemIcon.loadIconObjectGraphics(hardware, rom, index = 8, tileIndex = 0x240)

        val target = ItemIcon.OBJ_VRAM0 + (0x240 * 0x20)
        val actualTop = hardware.vram.copyOfRange(target, target + 0x40)
        val actualBot = hardware.vram.copyOfRange(target + 0x400, target + 0x400 + 0x40)

        val expectedTop = vramBytes.copyOfRange(target, target + 0x40)
        val expectedBot = vramBytes.copyOfRange(target + 0x400, target + 0x400 + 0x40)

        assertArrayEquals(expectedTop, actualTop, "Rapier icon top row mismatch vs f18630")
        assertArrayEquals(expectedBot, actualBot, "Rapier icon bottom row mismatch vs f18630")

        // Clearing with index < 0 zeroes both rows.
        ItemIcon.loadIconObjectGraphics(hardware, rom, index = -1, tileIndex = 0x240)
        val clearedTop = hardware.vram.copyOfRange(target, target + 0x40)
        val clearedBot = hardware.vram.copyOfRange(target + 0x400, target + 0x400 + 0x40)
        assertArrayEquals(ByteArray(0x40), clearedTop, "cleared top row not zero")
        assertArrayEquals(ByteArray(0x40), clearedBot, "cleared bottom row not zero")
    }

    @Test
    @VerifiesPort("LoadUiFrameGraphics")
    fun `loadUiFrameGraphics matches reference VRAM image and palette in item popup`() {
        assumeTrue(ref.available, "mGBA reference data not present")
        val rom = loadRomView()
        val run = ref.run("ram_trace_item_popup")
        val vramBytes = run.region("f18630", "vram")
        val palBytes = run.region("f18630", "pal")

        val hardware = BgHardware()
        UiFrame.loadUiFrameGraphics(hardware, rom, windowColor = 0)

        // Decompressed image is 4096 bytes (128 tiles) at VRAM offset 0.
        val actualImage = hardware.vram.copyOfRange(0, 4096)
        val expectedImage = vramBytes.copyOfRange(0, 4096)
        assertArrayEquals(expectedImage, actualImage, "Frame image mismatch vs f18630 VRAM")

        // BG Palette 1 is 32 bytes (16 shorts) at offset 32.
        val actualPal = hardware.palette.copyOfRange(16, 32)
        val expectedBuf = ByteBuffer.wrap(palBytes, 32, 32).order(ByteOrder.LITTLE_ENDIAN)
        val expectedPal = ShortArray(16) { expectedBuf.short }
        assertArrayEquals(expectedPal, actualPal, "Frame palette mismatch vs f18630 palette 1")
    }

    @Test
    @VerifiesPort("DrawUiFrame2")
    fun `drawUiFrame2 matches reference BG0 and BG1 tilemaps in item popup`() {
        assumeTrue(ref.available, "mGBA reference data not present")
        val rom = loadRomView()
        val run = ref.run("ram_trace_item_popup")
        val ewramBytes = run.region("f18630", "ewram")

        val hardware = BgHardware()
        // Rapier popup is at x=9, y=8, width=12, height=4, style=0.
        UiFrame.drawUiFrame2(hardware, rom, x = 9, y = 8, width = 12, height = 4, style = 0)

        val bg0 = hardware.mapBuffer(0)
        val bg1 = hardware.mapBuffer(1)

        val bg1Buf = ByteBuffer.wrap(ewramBytes, 0x234a8, 0x800).order(ByteOrder.LITTLE_ENDIAN)
        val expectedBg1 = ShortArray(1024) { bg1Buf.short }

        for (y in 8..11) {
            for (x in 9..20) {
                val idx = y * 32 + x
                assertEquals(0.toShort(), bg0[idx], "BG0 at ($x, $y) not zeroed")
                assertEquals(expectedBg1[idx], bg1[idx], "BG1 at ($x, $y) mismatch vs f18630")
            }
        }

        // Also test style 3 branch to verify alternate frame drawing without errors.
        val altBg0 = ShortArray(1024)
        val altBg1 = ShortArray(1024)
        UiFrame.drawUiFrame2(altBg0, altBg1, rom, x = 2, y = 2, width = 10, height = 6, style = 3)
        assertEquals(0.toShort(), altBg0[2 * 32 + 2])
    }
}
