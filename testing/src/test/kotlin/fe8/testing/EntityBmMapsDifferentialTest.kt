package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BmMap
import fe8.game.bm.BmSt
import fe8.game.bm.ChapterData
import fe8.game.bm.EntityBmMaps
import fe8.game.bm.PlaySt
import fe8.game.bm.UnitTable
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * [EntityBmMaps] against EWRAM dumps. The units and `gPlaySt` come from a dump taken after
 * the game ran `RefreshEntityBmMaps`; the port's unit, fog and hidden pools are sentinel-filled,
 * the refresh is run, and the whole pools (rows -2 to height + 1) and the unit structs are
 * compared with the dump. Dumps: `ram_trace_first_phase` (chapter 0, the player phase; the maps
 * do not change across it) and `ram_trace_newgame_bm` (chapter 0 after StartBattleMap, and the
 * chapter 16 map the opening event loads). Those have `chapterVisionRange` 0.
 *
 * Also `ram_trace_ch6_fog` (chapter 6, vision 3) f1990..f2130, the idle player phase (the maps
 * only change with the units, not the cursor or camera), exercises the fog lighting and
 * hidden-by-fog branches; there the unit structs (state bits 8/9) are compared whole as well.
 * The torchlight and mine passes are hooks and chapter 6 has no traps they would act on.
 */
class EntityBmMapsDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    private fun ByteArray.u8(addr: Int) = this[addr - EWRAM].toInt() and 0xFF
    private fun ByteArray.u32(addr: Int) =
        u8(addr) or (u8(addr + 1) shl 8) or (u8(addr + 2) shl 16) or (u8(addr + 3) shl 24)

    @Test
    // Not @VerifiesPort (CLAUDE.md rule 4): RefreshEntityBmMaps is partial (trap passes are
    // hooks). These dumps have chapterVisionRange 0; the fog branches run in the ch6 test below.
    fun `unit, fog and hidden maps match the game`() {
        val total = compareDumps(DUMPS)
        assertTrue(total >= 10, "units placed across the dumps: $total")
    }

    @Test
    @VerifiesPort("RefreshUnitsOnBmMap", "MapAddInRange")
    fun `fog maps match the game on chapter 6`() {
        val total = compareDumps(FOG_DUMPS, requireFog = true)
        assertTrue(total >= 10, "units placed across the dumps: $total")
    }

    /** Runs the refresh on every dump in [dumps], compares the pools, returns units placed. */
    private fun compareDumps(dumps: List<Pair<String, String>>, requireFog: Boolean = false): Int {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        val rom = RomView.ofRom(romFile.readBytes())
        var checked = 0
        var total = 0
        var fogged = 0
        var lit = 0
        var hiddenCells = 0
        for ((runName, dump) in dumps) {
            val ewram = ref.run(runName).region(dump, "ewram")
            val at = "$runName/$dump"
            val playSt = PlaySt()
            playSt.decode(ewram, PLAY_ST - EWRAM)
            if (requireFog) assertTrue(playSt.chapterVisionRange != 0, "fog off at $at")
            val map = BmMap(rom, ChapterData(rom), BmSt())
            map.initChapterMap(playSt.chapterIndex)
            val units = UnitTable(rom)
            units.loadFrom(ewram)
            val before = units.bytes.copyOf()
            for (pool in listOf(map.unit, map.fog, map.hidden)) pool.bytes.fill(SENTINEL)

            EntityBmMaps(map, units, playSt).refreshEntityBmMaps()

            val pools = listOf(
                Triple("unit", GBM_MAP_UNIT, map.unit),
                Triple("fog", GBM_MAP_FOG, map.fog),
                Triple("hidden", GBM_MAP_HIDDEN, map.hidden),
            )
            for ((name, handle, pool) in pools) {
                val first = ewram.u32(ewram.u32(handle) - 8)
                for (y in -2 until map.sizeY + 2) {
                    for (x in 0 until map.sizeX + 2) {
                        val expected = ewram.u8(first + (y + 2) * (map.sizeX + 2) + x)
                        assertEquals(expected, pool[y, x], "$name ($x,$y) at $at")
                    }
                }
            }
            for (y in 0 until map.sizeY) {
                for (x in 0 until map.sizeX) {
                    if (map.unit[y, x] != 0) total++
                    if (map.fog[y, x] == 0) fogged++ else if (map.fog[y, x] > 1) lit++
                    if (map.hidden[y, x] != 0) hiddenCells++
                }
            }
            // The state bits 8/9 are the only unit fields the refresh writes: compare them too.
            val after = units.bytes
            val expectedUnits = ewram.copyOfRange(UnitTable.BLUE_ADDRESS - EWRAM, UnitTable.BLUE_ADDRESS - EWRAM + after.size)
            if (requireFog) {
                assertArrayEquals(expectedUnits, after, "unit structs at $at")
            } else {
                // Units are only touched through the fog state bits, which no dump changes.
                assertArrayEquals(before, after, "unit structs at $at")
            }
            checked++
        }
        assertEquals(dumps.size, checked)
        if (requireFog) {
            assertTrue(fogged > 0 && lit > 0, "fog cells: dark $fogged, overlapped $lit")
            assertTrue(hiddenCells > 0, "hidden cells: $hiddenCells")
        }
        return total
    }

    @Test
    fun `MapAddInRange adds in a diamond clipped to the map`() {
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        val rom = RomView.ofRom(romFile.readBytes())
        val map = BmMap(rom, ChapterData(rom), BmSt())
        map.initChapterMap(0)
        map.bmMapFill(map.fog, 0)
        EntityBmMaps(map, UnitTable(rom), PlaySt()).mapAddInRange(0, 0, 2, 1)
        var sum = 0
        for (y in 0 until map.sizeY) for (x in 0 until map.sizeX) sum += map.fog[y, x]
        assertEquals(6, sum)
        assertEquals(1, map.fog[2, 0])
        assertEquals(0, map.fog[2, 1])
    }

    private companion object {
        const val EWRAM = 0x02000000
        const val PLAY_ST = 0x0202BCF0
        const val GBM_MAP_UNIT = 0x0202E4D8
        const val GBM_MAP_FOG = 0x0202E4E8
        const val GBM_MAP_HIDDEN = 0x0202E4EC
        const val SENTINEL: Byte = 0xAA.toByte()
        val FOG_DUMPS = listOf("f01990", "f02000", "f02010", "f02070", "f02130").map {
            "ram_trace_ch6_fog" to it
        }
        val DUMPS = listOf(
            "ram_trace_first_phase" to "f19430",
            "ram_trace_newgame_bm" to "f09319",
            "ram_trace_newgame_bm" to "f09529",
            "ram_trace_newgame_bm" to "f09799",
        )
    }
}
