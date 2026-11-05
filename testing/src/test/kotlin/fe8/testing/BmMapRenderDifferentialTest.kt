package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BmMap
import fe8.game.bm.BmMapRender
import fe8.game.bm.BmSt
import fe8.game.bm.ChapterData
import fe8.game.bm.PlaySt
import gba.core.VerifiesPort
import gba.testkit.GbaMem
import gba.testkit.RamSymbols
import gba.testkit.ReferenceData
import gbafe.render.BgHardware
import gbafe.render.LcdControl
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * M5-40: [BmMapRender.renderBmMap] against `gBG3TilemapBuffer` of per-frame dumps of
 * `ram_trace_first_phase` (r19280..r19420 and some f-dumps after, camera at (0, 0) on the 15x10
 * chapter 0 map) and `ram_trace_newgame_bm` (f09529..f09769, chapter 16; the chapter 0 dumps
 * f09319.. have no BG3 map yet). The port's map pools are built by [BmMap.initChapterMap]; the fog
 * map, the camera and `gLCDControlBuffer` come from the dump. The BG3 buffer is seeded with the
 * dump's content outside the 30x20 entries the function draws and a sentinel inside, then the
 * whole 32x32 buffer is compared, along with the BG3 offsets, the BG3 sync bit and the display
 * enable bits.
 *
 * Not tagged: `DisplayBmTile` (every fog map in the dumps is all 1s, so its fogged branch never
 * runs) and `RenderBmMapOnBg2` (no trace calls it).
 */
class BmMapRenderDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val sym = RamSymbols.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    private fun frames(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (n in 19280..19420) out += "ram_trace_first_phase" to "r$n"
        for (d in listOf("f19430", "f19440", "f19450", "f19500", "f19530")) {
            out += "ram_trace_first_phase" to d
        }
        for (n in 9529..9769 step 30) out += "ram_trace_newgame_bm" to "f%05d".format(n)
        return out
    }

    private fun u8(b: ByteArray, addr: Int) = b[addr - GbaMem.EWRAM].toInt() and 0xFF
    private fun u32(b: ByteArray, addr: Int) =
        u8(b, addr) or (u8(b, addr + 1) shl 8) or (u8(b, addr + 2) shl 16) or
            (u8(b, addr + 3) shl 24)

    @Test
    @VerifiesPort("RenderBmMap")
    fun `BG3 tilemap matches the dump`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        val rom = RomView.ofRom(romFile.readBytes())
        val bg3Addr = sym["gBG3TilemapBuffer"].address
        val fogHandle = sym["gBmMapFog"].address
        var compared = 0
        val chapters = HashSet<Int>()
        for ((runName, dump) in frames()) {
            if (!File(ref.root, "$runName/$dump.ewram.bin").isFile) continue
            val run = ref.run(runName)
            val ew = run.region(dump, "ewram")
            val iw = run.region(dump, "iwram")
            val at = "$runName/$dump"
            val gameBm = BmSt().also { it.decode(ew, sym["gBmSt"].address - GbaMem.EWRAM) }
            val playSt = PlaySt().also { it.decode(ew, sym["gPlaySt"].address - GbaMem.EWRAM) }
            val bmSt = BmSt()
            bmSt.cameraX = gameBm.cameraX
            bmSt.cameraY = gameBm.cameraY
            val map = BmMap(rom, ChapterData(rom), bmSt)
            map.initChapterMap(playSt.chapterIndex)
            val first = u32(ew, fogHandle) - 8
            for (y in -2 until map.sizeY + 2) {
                for (x in 0 until map.sizeX + 2) {
                    map.fog[y, x] = u8(ew, first + (y + 2) * (map.sizeX + 2) + x)
                }
            }
            val hw = BgHardware()
            val dumped = LcdControl().apply {
                readFrom(iw, sym["gLCDControlBuffer"].address - GbaMem.IWRAM)
            }
            val bgOff = bg3Addr - GbaMem.EWRAM
            val expected = ShortArray(BgHardware.TILEMAP_ENTRIES) {
                ((ew[bgOff + 2 * it].toInt() and 0xFF) or (ew[bgOff + 2 * it + 1].toInt() shl 8))
                    .toShort()
            }
            for (i in expected.indices) {
                val inside = i % 0x20 < 2 * BmMapRender.VISIBLE_COLUMNS &&
                    i / 0x20 < 2 * BmMapRender.VISIBLE_ROWS
                hw.tilemaps[3][i] = if (inside) SENTINEL else expected[i]
            }
            hw.lcd.setBgPosition(3, 0x1234, 0x2345)
            hw.lcd.setDispEnable(0, 0, 0, 0, 0)

            BmMapRender(bmSt, map, hw).renderBmMap()

            for (i in expected.indices) {
                assertEquals(expected[i], hw.tilemaps[3][i], "BG3 entry $i at $at")
            }
            assertEquals(dumped.bgHofs[3], hw.lcd.bgHofs[3], "BG3 hofs at $at")
            assertEquals(dumped.bgVofs[3], hw.lcd.bgVofs[3], "BG3 vofs at $at")
            assertEquals(dumped.dispcnt and 0x1F00, hw.lcd.dispcnt and 0x1F00, "dispcnt at $at")
            assertEquals(BmMapRender.BG3_SYNC_BIT, hw.modifiedBgs, "sync mask at $at")
            assertEquals(gameBm.cameraX shr 4, bmSt.mapRenderOriginX, "origin x at $at")
            assertEquals(gameBm.cameraY shr 4, bmSt.mapRenderOriginY, "origin y at $at")
            chapters += playSt.chapterIndex
            compared++
        }
        assertTrue(compared >= 100, "frames compared: $compared")
        assertTrue(0 in chapters && 16 in chapters, "chapters seen: $chapters")
    }

    private companion object {
        const val SENTINEL: Short = 0x5A5A
    }

    // Helper classes go last: tools/progress.py credits a tag to the last class above it.
}
