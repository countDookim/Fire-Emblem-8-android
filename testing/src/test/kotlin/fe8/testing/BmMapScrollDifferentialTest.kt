package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BmMap
import fe8.game.bm.BmMapRender
import fe8.game.bm.BmSt
import fe8.game.bm.ChapterData
import fe8.game.bm.PlaySt
import gba.core.VerifiesPort
import gba.testkit.AllocationMeter
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
 * [BmMapRender.updateBmMapDisplay] (with `RenderBmMapColumn` / `RenderBmMapLine`) against
 * the per-frame dumps of the cursor-hold windows of `ram_trace_first_phase` (no scrolling),
 * `ram_trace_ch2_scroll` (y scrolls) and `ram_trace_ch6_fog` (x and y scroll, fog). For each frame
 * k -> k+1 the port is seeded from dump k (`gBmSt` with `cameraPrevious` and `mapRenderOrigin`,
 * `gBG3TilemapBuffer`, BG3 offsets), the camera is set to dump k+1's and the whole BG3 buffer,
 * `cameraPrevious`, `mapRenderOrigin` and the BG3 offsets are compared with dump k+1. Transitions
 * where either dump was taken before the frame's UpdateBmMapDisplay (`cameraPrevious != camera`)
 * are skipped.
 */
class BmMapScrollDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val sym = RamSymbols.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    private fun u8(b: ByteArray, addr: Int) = b[addr - GbaMem.EWRAM].toInt() and 0xFF
    private fun u32(b: ByteArray, addr: Int) =
        u8(b, addr) or (u8(b, addr + 1) shl 8) or (u8(b, addr + 2) shl 16) or
            (u8(b, addr + 3) shl 24)

    private fun bmStOf(ew: ByteArray) =
        BmSt().also { it.decode(ew, sym["gBmSt"].address - GbaMem.EWRAM) }

    private fun readBg3(ew: ByteArray, out: ShortArray) {
        val off = sym["gBG3TilemapBuffer"].address - GbaMem.EWRAM
        for (i in out.indices) {
            val lo = ew[off + 2 * i].toInt() and 0xFF
            out[i] = (lo or (ew[off + 2 * i + 1].toInt() shl 8)).toShort()
        }
    }

    private fun lcdOf(iw: ByteArray) =
        LcdControl().apply { readFrom(iw, sym["gLCDControlBuffer"].address - GbaMem.IWRAM) }

    private fun crossed(prev: Int, cur: Int): Boolean =
        if (cur > prev) ((cur - 1) xor (prev - 1)) and 0x10 != 0 else (cur xor prev) and 0x10 != 0

    private fun window(runName: String, first: Int, last: Int, min: Int): Counts {
        assumeTrue(
            ref.available && romFile.isFile,
            "ROM / mGBA reference data not present (run tools/setup.sh)",
        )
        val run = ref.run(runName)
        val rom = RomView.ofRom(romFile.readBytes())
        val fogHandle = sym["gBmMapFog"].address
        val bmAddr = sym["gBmSt"].address - GbaMem.EWRAM
        val lcdAddr = sym["gLCDControlBuffer"].address - GbaMem.IWRAM
        // Per-frame dumps are `r<frame>`, or `f<frame>` where a full dump was taken.
        fun name(f: Int): String? = listOf("r", "f").map { "$it%05d".format(f) }
            .firstOrNull { File(ref.root, "$runName/$it.ewram.bin").isFile }
        val ew0 = run.region(name(first)!!, "ewram")
        val playSt = PlaySt().also { it.decode(ew0, sym["gPlaySt"].address - GbaMem.EWRAM) }
        val bmSt = BmSt()
        val map = BmMap(rom, ChapterData(rom), bmSt)
        map.initChapterMap(playSt.chapterIndex)
        val hw = BgHardware()
        val render = BmMapRender(bmSt, map, hw)
        val expected = ShortArray(BgHardware.TILEMAP_ENTRIES)
        val counts = Counts()
        var prevEw = ew0
        var prevIw = run.region(name(first)!!, "iwram")
        var pair: IntArray? = null
        var gap = false
        for (f in first + 1..last) {
            val nm = name(f)
            if (nm == null) {
                gap = true
                continue
            }
            val ew = run.region(nm, "ewram")
            val iw = run.region(nm, "iwram")
            val k = bmStOf(prevEw)
            val n = bmStOf(ew)
            val consistent = k.cameraPreviousX == k.cameraX && k.cameraPreviousY == k.cameraY &&
                n.cameraPreviousX == n.cameraX && n.cameraPreviousY == n.cameraY
            if (consistent && !gap) {
                val fog = u32(ew, fogHandle) - 8
                for (y in -2 until map.sizeY + 2) {
                    for (x in 0 until map.sizeX + 2) {
                        val row = (y + 2) * (map.sizeX + 2)
                        map.fog[y, x] = u8(ew, fog + map.fog.rowTableBytes + row + x)
                    }
                }
                map.loadBaseTiles(prevEw, BASE_TILES_POOL - GbaMem.EWRAM)
                bmSt.decode(prevEw, bmAddr)
                bmSt.cameraX = n.cameraX
                bmSt.cameraY = n.cameraY
                readBg3(prevEw, hw.tilemaps[3])
                hw.lcd.readFrom(prevIw, lcdAddr)
                hw.modifiedBgs = 0
                render.updateBmMapDisplay()

                val at = "$runName $nm"
                readBg3(ew, expected)
                for (i in expected.indices) {
                    assertEquals(expected[i], hw.tilemaps[3][i], "BG3 entry $i at $at")
                }
                val dumped = lcdOf(iw)
                assertEquals(n.cameraPreviousX, bmSt.cameraPreviousX, "cameraPrevious.x at $at")
                assertEquals(n.cameraPreviousY, bmSt.cameraPreviousY, "cameraPrevious.y at $at")
                assertEquals(n.mapRenderOriginX, bmSt.mapRenderOriginX, "origin.x at $at")
                assertEquals(n.mapRenderOriginY, bmSt.mapRenderOriginY, "origin.y at $at")
                assertEquals(dumped.bgHofs[3], hw.lcd.bgHofs[3], "BG3 hofs at $at")
                assertEquals(dumped.bgVofs[3], hw.lcd.bgVofs[3], "BG3 vofs at $at")
                val moved = k.cameraX != n.cameraX || k.cameraY != n.cameraY
                if (moved) counts.scrolled++ else counts.still++
                val colCross = k.cameraX != n.cameraX && crossed(k.cameraX, n.cameraX)
                val rowCross = k.cameraY != n.cameraY && crossed(k.cameraY, n.cameraY)
                if (colCross) counts.cols++
                if (rowCross) counts.rows++
                if ((colCross || rowCross) && pair == null) {
                    pair = intArrayOf(k.cameraX, k.cameraY, n.cameraX, n.cameraY)
                }
                assertEquals(
                    if (colCross || rowCross) BmMapRender.BG3_SYNC_BIT else 0,
                    hw.modifiedBgs,
                    "sync mask at $at",
                )
            }
            prevEw = ew
            prevIw = iw
            gap = false
        }
        assertTrue(
            counts.scrolled + counts.still >= min,
            "compared $runName: scrolled=${counts.scrolled} still=${counts.still}",
        )
        pair?.let { p ->
            var flip = false
            counts.bytes = AllocationMeter.measureMin(warmup = 20_000, iterations = 20_000) {
                flip = !flip
                bmSt.cameraX = if (flip) p[2] else p[0]
                bmSt.cameraY = if (flip) p[3] else p[1]
                render.updateBmMapDisplay()
            }
        }
        println(
            "$runName: scrolled=${counts.scrolled} still=${counts.still} " +
                "columns=${counts.cols} lines=${counts.rows} alloc=${counts.bytes}",
        )
        return counts
    }

    @Test
    @VerifiesPort("UpdateBmMapDisplay")
    fun `map that fits the screen never scrolls and leaves BG3 alone`() {
        val c = window("ram_trace_first_phase", 19421, 19520, 90)
        assertTrue(c.scrolled == 0 && c.still >= 90, "still=${c.still}")
    }

    @Test
    @VerifiesPort("UpdateBmMapDisplay", "RenderBmMapLine")
    fun `ch2 vertical scroll redraws lines into the wrapped BG3 buffer`() {
        val c = window("ram_trace_ch2_scroll", 1301, 1570, 200)
        assertTrue(c.scrolled >= 30 && c.rows >= 3, "scrolled=${c.scrolled} lines=${c.rows}")
        assertEquals(0L, c.bytes, "bytes allocated per call")
    }

    @Test
    @VerifiesPort("UpdateBmMapDisplay", "RenderBmMapColumn", "RenderBmMapLine")
    fun `ch6 fog scrolls in x and y`() {
        val c = window("ram_trace_ch6_fog", 2004, 2243, 200)
        assertTrue(
            c.scrolled >= 30 && c.cols >= 3 && c.rows >= 3,
            "scrolled=${c.scrolled} cols=${c.cols} lines=${c.rows}",
        )
        assertEquals(0L, c.bytes, "bytes allocated per call")
    }

    /** Transition counts of one run (declared below the tests: tools/progress.py). */
    private class Counts {
        var scrolled = 0
        var still = 0
        var cols = 0
        var rows = 0
        var bytes = -1L
    }

    private companion object {
        /** `sBmBaseTilesPool`, the target of ROM `gBmMapBaseTiles` (0x0859A9D4). */
        const val BASE_TILES_POOL = 0x02032F8C
    }
}
