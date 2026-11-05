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
 * `BmMapInit` is checked by the pool bases and row-pointer tables of five pools (movement and
 * range live in IWRAM, which these dumps do not cover). `BmMapFill` is checked with the fog map
 * (`BmMapFill(gBmMapFog, 1)`, every dump) and the chapter 0 unit map (`BmMapFill(gBmMapUnit, 0)`)
 * on a sentinel-filled pool. No dump holds a map uniformly filled with another value.
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

            checkPoolLayouts(ewram, map, sx, sy, at)

            // BmMapFill: the port's pool is first filled with a sentinel (so a no-op fill leaves
            // it), then filled with the value the game used; the game's fill region must match.
            // The fog map holds 1 in every dump (RefreshEntityBmMaps: BmMapFill(gBmMapFog, 1)).
            assertFill(ewram, map, map.fog, GBM_MAP_FOG, 1, sx, sy, "fog, $at")
            if (chapter == 0) {
                // Nothing has been placed on the map yet: the whole unit pool is BmMapFill(0).
                assertFill(ewram, map, map.unit, GBM_MAP_UNIT, 0, sx, sy, "unit, $at")
            }
        }
    }

    /** Pool bases and row-pointer tables the game's BmMapInit laid out, against [BmMap.Pool]. */
    private fun checkPoolLayouts(ewram: ByteArray, map: BmMap, sx: Int, sy: Int, at: String) {
        val pools = listOf(
            Triple(GBM_MAP_UNIT, SYM_UNIT_POOL, map.unit),
            Triple(GBM_MAP_TERRAIN, SYM_TERRAIN_POOL, map.terrain),
            Triple(GBM_MAP_FOG, SYM_FOG_POOL, map.fog),
            Triple(GBM_MAP_HIDDEN, SYM_HIDDEN_POOL, map.hidden),
            Triple(GBM_MAP_OTHER, SYM_OTHER_POOL, map.other),
        )
        for ((handle, base, pool) in pools) {
            val rows = ewram.u32(handle)
            assertEquals(base + 8, rows, "pool handle $handle at $at")
            assertEquals(4 * (sy + 4), pool.rowTableBytes, "row table bytes of $handle at $at")
            assertEquals(sx + 2, pool.stride, "stride of $handle at $at")
            for (i in -2 until sy + 2) {
                val expected = base + pool.rowTableBytes + (i + 2) * pool.stride
                assertEquals(expected, ewram.u32(rows + 4 * i), "row $i of $handle at $at")
            }
        }
    }

    /**
     * Sentinel-fills [pool], runs `BmMapFill([pool], [value])` and compares the whole fill region
     * with the game's pool behind [handle]; bytes outside it must keep the sentinel.
     */
    private fun assertFill(
        ewram: ByteArray,
        map: BmMap,
        pool: BmMap.Pool,
        handle: Int,
        value: Int,
        sx: Int,
        sy: Int,
        at: String,
    ) {
        val rows = ewram.u32(handle)
        val first = ewram.u32(rows - 8)
        val size = (sy + 4) * (sx + 2)
        for (i in 0 until size) assertEquals(value, ewram.u8(first + i), "game pool byte $i, $at")
        pool.bytes.fill(SENTINEL)
        map.bmMapFill(pool, value)
        for (y in -2 until sy + 2) {
            for (x in 0 until sx + 2) {
                assertEquals(ewram.u8(first + (y + 2) * (sx + 2) + x), pool[y, x], "($x,$y), $at")
            }
        }
        assertEquals(map.workingMap, pool, "working map, $at")
        for (i in 0 until pool.rowTableBytes) {
            assertEquals(SENTINEL, pool.bytes[i], "row table byte $i untouched, $at")
        }
        for (i in pool.rowTableBytes + size until pool.bytes.size) {
            assertEquals(SENTINEL, pool.bytes[i], "byte $i past the region untouched, $at")
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
        const val GBM_MAP_FOG = 0x0202E4E8
        const val GBM_MAP_HIDDEN = 0x0202E4EC
        const val GBM_MAP_OTHER = 0x0202E4F0
        const val SYM_UNIT_POOL = 0x0202E4F4
        const val SYM_TERRAIN_POOL = 0x0202ECAC
        const val SYM_FOG_POOL = 0x0202F464
        const val SYM_HIDDEN_POOL = 0x0202FC1C
        const val SYM_OTHER_POOL = 0x020303D4
        const val SENTINEL: Byte = 0xAA.toByte()
        const val SYM_TILESET_CONFIG = 0x02030B8C
        const val SYM_BASE_TILES_POOL = 0x02032F8C
        const val BM_MAP_BASE_TILES_PTR = 0x0859A9D4
    }
}
