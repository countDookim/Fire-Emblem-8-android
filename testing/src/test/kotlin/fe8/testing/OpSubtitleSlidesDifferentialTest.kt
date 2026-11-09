package fe8.testing

import fe8.game.RomView
import fe8.game.opanim.OpSubtitleSlides
import gba.core.VerifiesPort
import gba.data.Decompress
import gba.testkit.ReferenceData
import gbafe.render.BgHardware
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/** Original New Game snapshots compared with the individual subtitle slide loaders. */
class OpSubtitleSlidesDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    private fun checkSnapshot(frame: Int, expectedProcIndex: Int, slideIndex: Int, loader: Loader) {
        assumeTrue(ref.available && romFile.isFile, "reference capture or ROM unavailable")
        val run = ref.run("ram_trace_prologue")
        val name = "f" + frame.toString().padStart(5, '0')
        val ewram = run.region(name, "ewram")
        val vram = run.region(name, "vram")
        val proc = checkNotNull(ProcArrayDump.ofEwram(ewram).find(0x08AA21BC))
        assertEquals(expectedProcIndex, proc.u8(0x29), "original slide index at $frame")

        val rom = RomView.ofRom(romFile.readBytes())
        val hardware = BgHardware()
        hardware.vram.fill(0x5A)
        hardware.lcd.dispcnt = 0x1284
        hardware.modifiedBgs = 4
        hardware.tilemaps.forEach { it.fill(0x5A5A.toShort()) }
        val scratch = ByteArray(0x10000)
        val slides = OpSubtitleSlides(rom, hardware, scratch)
        val state = OpSubtitleSlides.State(slideIndex, 99)
        when (loader) {
            Loader.SINGLE -> slides.loadSingle(state)
            Loader.DOUBLE -> slides.loadDouble(state)
            Loader.ALTERNATE -> slides.loadAtAlternateOffset(slideIndex)
        }
        val gfxAddress = rom.u32(0x08206FE4 + slideIndex * 12)
        val length = rom.u8(gfxAddress + 1) or (rom.u8(gfxAddress + 2) shl 8) or
            (rom.u8(gfxAddress + 3) shl 16)
        val offset = if (loader == Loader.ALTERNATE) 0x5000 else 0x1000
        assertArrayEquals(vram.copyOfRange(offset, offset + length),
            hardware.vram.copyOfRange(offset, offset + length), "frame $frame tile graphics")
        assertArrayEquals(ByteArray(offset) { 0x5A }, hardware.vram.copyOfRange(0, offset))
        assertArrayEquals(
            ByteArray(hardware.vram.size - offset - length) { 0x5A },
            hardware.vram.copyOfRange(offset + length, hardware.vram.size),
        )

        // Determine the expected rectangle independently of the loader's scratch buffer.
        val tsa = rom.blob(rom.u32(0x08206FE4 + slideIndex * 12 + 4))
        val decodedTsa = Decompress.decode(tsa.data, tsa.offset)
        val width = (decodedTsa[0].toInt() and 0xFF) + 1
        val height = (decodedTsa[1].toInt() and 0xFF) + 1
        for (bg in 0..1) {
            val isWritten = bg == 0 || loader == Loader.DOUBLE
            val base = if (bg == 0) 0x22CA8 else 0x234A8
            for (i in 0 until 0x400) {
                val inRect = i / 32 < height && i % 32 < width
                val expected = if (isWritten && inRect) {
                    (ewram[base + i * 2].toInt() and 0xFF) or
                        ((ewram[base + i * 2 + 1].toInt() and 0xFF) shl 8)
                } else {
                    0x5A5A
                }
                assertEquals(expected, hardware.tilemaps[bg][i].toInt() and 0xFFFF,
                    "frame $frame BG$bg entry $i")
            }
        }
        assertEquals(if (loader == Loader.DOUBLE) 7 else 5, hardware.modifiedBgs)
        assertEquals(if (loader == Loader.ALTERNATE) 0x1284 else 0x1384, hardware.lcd.dispcnt)
        assertEquals(if (loader == Loader.ALTERNATE) 99 else 0, state.transitionTimer)
        assertEquals(slideIndex, state.index)
    }

    @Test
    @VerifiesPort("sub_80C4C60")
    fun `single layer slides match original captures`() {
        checkSnapshot(1500, 0, 0, Loader.SINGLE)
        checkSnapshot(2100, 1, 1, Loader.SINGLE)
        checkSnapshot(4500, 6, 6, Loader.SINGLE)
    }

    @Test
    @VerifiesPort("sub_80C4CD0")
    fun `paired layer slides match original captures`() {
        checkSnapshot(3000, 3, 3, Loader.DOUBLE)
        checkSnapshot(3600, 4, 4, Loader.DOUBLE)
        // Slide index five remains loaded after the proc advances its index to six.
        checkSnapshot(3900, 6, 5, Loader.DOUBLE)
    }

    @Test
    @VerifiesPort("sub_80C4D54")
    fun `alternate offset slide matches original capture`() {
        checkSnapshot(2400, 2, 2, Loader.ALTERNATE)
    }
    private enum class Loader { SINGLE, DOUBLE, ALTERNATE }

}
