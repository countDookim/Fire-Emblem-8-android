package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BmMap
import fe8.game.bm.BmSt
import fe8.game.bm.ChapterData
import gba.core.VerifiesPort
import gba.testkit.AllocationMeter
import gba.testkit.ReferenceData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * M5-36: [BmMap] against the EWRAM dumps of `ram_trace_newgame_bm`, taken right after the game's
 * `InitChapterMap` for chapter 0 (and for the next chapter the opening event loads). The game's
 * pools are decoded by following the row-pointer tables in EWRAM, independently of [BmMap].
 */
class BmMapDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    private fun ByteArray.u8(addr: Int) = this[addr - EWRAM].toInt() and 0xFF
    private fun ByteArray.u16(addr: Int) = u8(addr) or (u8(addr + 1) shl 8)
    private fun ByteArray.u32(addr: Int) = u16(addr) or (u16(addr + 2) shl 16)

    /** The first dump per chapter index after the game's `InitChapterMap` laid its pools out. */
    private fun settledDumps(rom: ByteArray, run: ReferenceData.Run): Map<Int, String> {
        val chapters = ChapterData(RomView.ofRom(rom))
        val probe = BmMap(RomView.ofRom(rom), chapters, BmSt())
        val out = LinkedHashMap<Int, String>()
        for (dump in run.dumps.keys) {
            if (!File(ref.root, "ram_trace_newgame_bm/$dump.ewram.bin").isFile) continue
            val ewram = run.region(dump, "ewram")
            val chapter = ewram.u8(PLAY_ST + 0x0E)
            if (chapter in out || chapter >= ChapterData.CHAPTER_COUNT) continue
            probe.unpackChapterMap(chapter)
            val sx = ewram.u8(BM_MAP_SIZE)
            val sy = ewram.u8(BM_MAP_SIZE + 2)
            // gBmMapSize is set first; InitBaseTilesBmMap (laying the row table out for this size)
            // and the terrain pool come later in the same call, so wait for both.
            val baseDone = ewram.u32(SYM_BASE_TILES_POOL) == SYM_BASE_TILES_POOL + 4 * (sy + 1)
            val terrainRows = ewram.u32(GBM_MAP_TERRAIN)
            val terrainDone = terrainRows != 0 &&
                ewram.u32(terrainRows + 4) - ewram.u32(terrainRows) == sx + 2
            if (sx == probe.sizeX && sy == probe.sizeY && baseDone && terrainDone) {
                out[chapter] = dump
            }
        }
        return out
    }

    @Test
    @VerifiesPort(
        "InitChapterMap",
        "UnpackChapterMap",
        "BmMapInit",
        "BmMapFill",
        "InitBaseTilesBmMap",
        "RefreshTerrainBmMap",
        "GetTrueTerrainAt",
    )
    fun `pools match the game after InitChapterMap`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        val rom = romFile.readBytes()
        val run = ref.run("ram_trace_newgame_bm")
        val dumps = settledDumps(rom, run)
        assertTrue(0 in dumps, "no settled chapter 0 dump: $dumps")
        for ((chapter, dump) in dumps) {
            val ewram = run.region(dump, "ewram")
            val bmSt = BmSt()
            val map = BmMap(RomView.ofRom(rom), ChapterData(RomView.ofRom(rom)), bmSt)
            map.initChapterMap(chapter)
            val at = "chapter $chapter, $dump"

            val sx = ewram.u8(BM_MAP_SIZE)
            val sy = ewram.u8(BM_MAP_SIZE + 2)
            assertEquals(sx, map.sizeX, "gBmMapSize.x at $at")
            assertEquals(sy, map.sizeY, "gBmMapSize.y at $at")

            val gameBmSt = BmSt()
            gameBmSt.decode(ewram, BM_ST - EWRAM)
            assertEquals(gameBmSt.cameraMaxX, bmSt.cameraMaxX, "cameraMax.x at $at")
            assertEquals(gameBmSt.cameraMaxY, bmSt.cameraMaxY, "cameraMax.y at $at")

            val configAt = SYM_TILESET_CONFIG - EWRAM
            for (i in 0 until BmMap.TILESET_CONFIG_SIZE) {
                assertEquals(ewram[configAt + i], map.tilesetConfig[i], "sTilesetConfig[$i], $at")
            }

            // gBmMapBaseTiles: a ROM-resident pointer to the row-pointer table in EWRAM.
            val baseRows = readU32(rom, BM_MAP_BASE_TILES_PTR)
            assertEquals(SYM_BASE_TILES_POOL, baseRows)
            for (y in 0 until sy) {
                val row = ewram.u32(baseRows + 4 * y)
                for (x in 0 until sx) {
                    assertEquals(ewram.u16(row + 2 * x), map.baseTile(x, y), "tile ($x,$y) at $at")
                }
            }

            val terrainRows = ewram.u32(GBM_MAP_TERRAIN)
            for (y in 0 until sy) {
                val row = ewram.u32(terrainRows + 4 * y)
                for (x in 0 until sx) {
                    assertEquals(ewram.u8(row + x), map.terrain[y, x], "terrain ($x,$y) at $at")
                    val lookup = SYM_TILESET_CONFIG + BmMap.TERRAIN_LOOKUP
                    val tile = ewram.u16(ewram.u32(baseRows + 4 * y) + 2 * x)
                    val expected = ewram.u8(lookup + (tile shr 2))
                    assertEquals(expected, map.getTrueTerrainAt(x, y), "true terrain $x,$y, $at")
                }
            }

            if (chapter == 0) {
                // Nothing has been placed on the map yet: the whole unit pool is BmMapFill(0), the
                // two safety rows above and below included.
                val unitRows = ewram.u32(GBM_MAP_UNIT)
                val first = ewram.u32(unitRows - 8)
                for (i in 0 until (sy + 4) * (sx + 2)) {
                    assertEquals(0, ewram.u8(first + i), "game unit pool byte $i")
                }
                for (y in -2 until sy + 2) {
                    for (x in 0 until sx + 2) assertEquals(0, map.unit[y, x], "unit ($x,$y)")
                }
            }
        }
    }

    @Test
    fun `second InitChapterMap does not allocate`() {
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        assumeTrue(AllocationMeter.supported, "allocation accounting unsupported")
        val rom = romFile.readBytes()
        val view = RomView.ofRom(rom)
        val map = BmMap(view, ChapterData(view), BmSt())
        map.initChapterMap(0)
        val bytes = AllocationMeter.measureMin(warmup = 20, iterations = 20) {
            map.initChapterMap(0)
        }
        assertEquals(0L, bytes)
    }

    private fun readU32(rom: ByteArray, addr: Int): Int {
        val o = addr - 0x08000000
        return (rom[o].toInt() and 0xFF) or ((rom[o + 1].toInt() and 0xFF) shl 8) or
            ((rom[o + 2].toInt() and 0xFF) shl 16) or ((rom[o + 3].toInt() and 0xFF) shl 24)
    }

    private companion object {
        const val EWRAM = 0x02000000
        const val PLAY_ST = 0x0202BCF0
        const val BM_ST = 0x0202BCB0
        const val BM_MAP_SIZE = 0x0202E4D4
        const val GBM_MAP_UNIT = 0x0202E4D8
        const val GBM_MAP_TERRAIN = 0x0202E4DC
        const val SYM_TILESET_CONFIG = 0x02030B8C
        const val SYM_BASE_TILES_POOL = 0x02032F8C
        const val BM_MAP_BASE_TILES_PTR = 0x0859A9D4
    }
}
