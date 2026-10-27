package fe8.testing

import gbafe.core.VerifiesPort
import gbafe.data.Lz77
import gbafe.render.BgTilemap
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * [BgTilemap.applyTsa] against the BG tilemap buffers in mGBA RAM: the health & safety screen
 * (`PrepareHealthAndSafetyScreen`: BG1 then BG0 TSA, tile base 0; `ram_trace_boot` f100) and the intro logos
 * (`GameIntroPrepareNintendofx`: Nintendo on BG0 base 0, Intelligent Systems on BG1 base 0x1100; `boot_logos`
 * f300). `SetupBackgrounds` cleared the buffers first, so the whole 32×32 buffer must match. TSA offsets from
 * decomp/fireemblem8.nm.txt (pinned 04d1edb).
 */
class BgTilemapDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val sym = RamSymbols.fromEnvironment()
    private val romFile = File(System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets", "baserom.gba")

    private fun buffer(ew: ByteArray, bg: Int): ShortArray {
        val at = sym["gBG${bg}TilemapBuffer"].address - GbaMem.EWRAM
        return ShortArray(1024) { ((ew[at + 2 * it].toInt() and 0xFF) or ((ew[at + 2 * it + 1].toInt() and 0xFF) shl 8)).toShort() }
    }

    private fun ours(rom: ByteArray, tsaRom: Int, base: Int): ShortArray {
        val t = ShortArray(1024)
        BgTilemap.applyTsa(t, 0, Lz77.decode(rom, tsaRom - 0x08000000).data, 0, base)
        return t
    }

    @Test
    @VerifiesPort("TmApplyTsa", "CallARM_FillTileRect")
    fun `TSA tilemaps match the BG buffers in mGBA RAM`() {
        assumeTrue(ref.available && romFile.isFile, "ROM / mGBA reference data not present (run tools/setup.sh)")
        val rom = romFile.readBytes()
        val hs = ref.run("ram_trace_boot").region("r00100", "ewram")
        assertArrayEquals(buffer(hs, 0), ours(rom, 0x08B11C0C, 0), "health & safety BG0")
        assertArrayEquals(buffer(hs, 1), ours(rom, 0x08B118C4, 0), "health & safety BG1")
        val logos = ref.run("boot_logos").region("f00300", "ewram")
        assertArrayEquals(buffer(logos, 0), ours(rom, 0x08B104D8, 0), "Nintendo BG0")
        assertArrayEquals(buffer(logos, 1), ours(rom, 0x08B10ADC, 0x1100), "Intelligent Systems BG1")
        // Offset placement (TILEMAP_LOCATED(2, 3)): same rows shifted.
        val tsa = byteArrayOf(1, 0, 1, 0, 2, 0)
        val t = ShortArray(1024)
        BgTilemap.applyTsa(t, 3 * 32 + 2, tsa, 0, 0xFFFF)
        assertArrayEquals(shortArrayOf(0, 1), shortArrayOf(t[3 * 32 + 2], t[3 * 32 + 3]))
    }
}
