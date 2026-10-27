package fe8.testing

import fe8.game.opanim.OpAnimHs
import gbafe.core.VerifiesPort
import gbafe.data.Lz77
import gbafe.input.KeyStatus
import gbafe.render.BgHardware
import gbafe.render.LcdControl
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * `GameIntroPrepareNintendofx` and `PrepareHealthAndSafetyScreen` (with `SetupBackgrounds` and `Decompress`) run
 * on a fresh [BgHardware] against mGBA: BG control, scroll, DISPCNT, the four tilemap buffers, VRAM tilemap
 * destinations and OAM split from the dumped RAM, the decompressed logo tiles (and the cleared first 64 bytes of
 * BG3's tile data) from the dumped VRAM. Logos: `boot_logos` f300 (Nintendo fade-in); health & safety:
 * `ram_trace_boot` f100 (no VRAM in that run). Symbol addresses from decomp/fireemblem8.nm.txt (pinned 04d1edb).
 */
class ScreenPrepareDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val sym = RamSymbols.fromEnvironment()
    private val romFile = File(System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets", "baserom.gba")

    private fun blob(rom: ByteArray, address: Int) = OpAnimHs.Blob(rom, address - ROM)

    private fun checkRam(hw: BgHardware, ew: ByteArray, iw: ByteArray, what: String) {
        val lcd = LcdControl().apply { readFrom(iw, sym["gLCDControlBuffer"].address - GbaMem.IWRAM) }
        assertArrayEquals(lcd.bgCnt, hw.lcd.bgCnt, "$what bgcnt")
        assertArrayEquals(lcd.bgHofs, hw.lcd.bgHofs, "$what hofs")
        assertArrayEquals(lcd.bgVofs, hw.lcd.bgVofs, "$what vofs")
        assertEquals(lcd.dispcnt.toString(16), hw.lcd.dispcnt.toString(16), "$what dispcnt")
        for (bg in 0..3) {
            val at = sym["gBG${bg}TilemapBuffer"].address
            val dumped = ShortArray(1024) { GbaMem.u16(ew, GbaMem.EWRAM, at + 2 * it).toShort() }
            assertArrayEquals(dumped, hw.mapBuffer(bg), "$what BG$bg tilemap buffer")
            val ptr = GbaMem.u32(ew, GbaMem.EWRAM, sym["gBGVramTilemapPointers"].address + 4 * bg)
            assertEquals(ptr - VRAM, hw.vramTilemapOffsets[bg], "$what gBGVramTilemapPointers[$bg]")
        }
        assertEquals(GbaMem.u16(iw, GbaMem.IWRAM, sym["sOamLo"].address + 10), hw.oamLoCount, "$what sOamLo.count")
        assertEquals(GbaMem.u16(iw, GbaMem.IWRAM, sym["sOamHi"].address + 10), 128 - hw.oamLoCount, "$what sOamHi.count")
    }

    @Test
    @VerifiesPort(
        "SetupBackgrounds", "SetBackgroundTileDataOffset", "SetBackgroundMapDataOffset", "SetBackgroundScreenSize",
        "GetBackgroundTileDataOffset", "BG_Fill", "BG_GetMapBuffer", "InitBmBgLayers", "InitOam",
        "GameIntroPrepareNintendofx", "Decompress", "FlushBackgrounds",
    )
    fun `intro logo screen setup matches mGBA`() {
        assumeTrue(ref.available && romFile.isFile, "ROM / mGBA reference data not present (run tools/setup.sh)")
        val rom = romFile.readBytes()
        val run = ref.run("boot_logos")
        val hw = BgHardware()
        OpAnimHs.prepareNintendofx(
            OpAnimHs.Proc(), hw,
            blob(rom, 0x08B10630), blob(rom, 0x08B104D8), blob(rom, 0x08B10CA4), blob(rom, 0x08B10ADC),
        )
        checkRam(hw, run.region("f00300", "ewram"), run.region("f00300", "iwram"), "logos")
        val vram = run.region("f00300", "vram")
        for ((img, at) in listOf(0x08B10630 to 0, 0x08B10CA4 to 0x2000)) {
            val n = Lz77.headerSize(rom, img - ROM)
            assertArrayEquals(vram.copyOfRange(at, at + n), hw.vram.copyOfRange(at, at + n), "VRAM tiles at 0x${at.toString(16)}")
        }
        assertArrayEquals(vram.copyOfRange(0x8000, 0x8040), hw.vram.copyOfRange(0x8000, 0x8040), "BG3 first tiles")
        // VBlank sync: f300 is in the Nintendo logo's hold (palette constant), so PLTT = the dumped palette buffer.
        val ew = run.region("f00300", "ewram")
        val palAt = sym["gPaletteBuffer"].address
        ShortArray(512) { GbaMem.u16(ew, GbaMem.EWRAM, palAt + 2 * it).toShort() }.copyInto(hw.palette)
        hw.enablePaletteSync()
        hw.flushBackgrounds()
        assertEquals(0, hw.modifiedBgs)
        assertArrayEquals(vram.copyOfRange(0x6000, 0x8000), hw.vram.copyOfRange(0x6000, 0x8000), "VRAM tilemaps BG0..BG3")
        val pal = run.region("f00300", "pal")
        assertArrayEquals(ShortArray(512) { GbaMem.u16(pal, 0, 2 * it).toShort() }, hw.paletteRam, "palette RAM")
    }

    @Test
    @VerifiesPort("PrepareHealthAndSafetyScreen", "SetKeyStatus_IgnoreMask")
    fun `health and safety screen setup matches mGBA`() {
        assumeTrue(ref.available && romFile.isFile, "ROM / mGBA reference data not present (run tools/setup.sh)")
        val rom = romFile.readBytes()
        val run = ref.run("ram_trace_boot")
        val hw = BgHardware()
        val keys = KeyStatus()
        val proc = OpAnimHs.Proc().apply { paletteTimer = 7 }
        val loaded = OpAnimHs.prepareHealthSafety(
            proc, hw, keys, false, blob(rom, 0x08B11D0C), blob(rom, 0x08B118C4), blob(rom, 0x08B11C0C),
        )
        assertEquals(true, loaded)
        assertEquals(0, proc.paletteTimer)
        val iw = run.region("r00100", "iwram")
        checkRam(hw, run.region("r00100", "ewram"), iw, "health & safety")
        assertEquals(GbaMem.u16(iw, GbaMem.IWRAM, sym["gKeyStatusIgnoredSt"].address), keys.ignoreMask, "gKeyStatusIgnoredSt")
        val untouched = BgHardware()
        assertEquals(false, OpAnimHs.prepareHealthSafety(proc, untouched, keys, true, blob(rom, 0), blob(rom, 0), blob(rom, 0)))
        assertEquals(0, untouched.lcd.dispcnt)
    }

    private companion object {
        const val ROM = 0x08000000
        const val VRAM = 0x06000000
    }
}
