package fe8.game.bm

import fe8.game.RomView
import gbafe.core.rng.RngService
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

// Not @VerifiesPort: the cases are hand-derived from the C source (src/cp_utility.c), not from a
// reference RAM trace, so AiGetUnitClosestValidPosition stays "transcribed".

/**
 * Synthetic-map test of [UnitLoader.closestValidPosition]: a 15x10 map (the Prologue's size, read
 * from the ROM) with a fake class cost table, the occupancy pools set by hand.
 *
 * The C scans rows `h - 1` down to 0 and columns `w - 1` down to 0 and skips only tiles whose
 * range is `> best`, so among tiles at the same range the LAST scanned wins: the lowest y, then
 * the lowest x.
 */
class ClosestValidPositionTest {
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    private class Setup(val loader: UnitLoader, val map: BmMap)

    /** A loader whose unit 1 stands at ([ux], [uy]); terrain id 1 costs 1, id 2 costs 200. */
    private fun setup(ux: Int, uy: Int, wired: Boolean = true): Setup {
        val real = RomView.ofRom(romFile.readBytes())
        val fake = ByteArray(0x400)
        fun put32(at: Int, v: Int) = repeat(4) { fake[at + it] = (v shr (8 * it)).toByte() }
        put32(0x100 + 0x38, 0x08000200) // class cost table (clear weather)
        for (t in 0 until 0x100) fake[0x200 + t] = if (t == 2) 200.toByte() else 1
        fake[0x200] = 0
        val table = UnitTable(RomView.ofRom(fake))
        repeat(4) { table.bytes[0x04 + it] = (0x08000100 shr (8 * it)).toByte() } // class
        table.bytes[0x10] = ux.toByte()
        table.bytes[0x11] = uy.toByte()
        val loader = UnitLoader(table, RomView.ofRom(fake), { 0 }, RngService(), { false })
        val map = BmMap(real, ChapterData(real), BmSt())
        map.unpackChapterMap(0)
        for (p in listOf(map.unit, map.other, map.hidden, map.movement, map.range)) {
            map.bmMapInit(p, map.sizeX, map.sizeY)
            map.bmMapFill(p, 0)
        }
        if (wired) loader.bmMap = map
        return Setup(loader, map)
    }

    private fun search(s: Setup, x: Int, y: Int, terrain: Int = 1): Int =
        s.loader.closestValidPosition(0, x, y) { _, _ -> terrain }

    @Test
    fun `an occupied target picks the first free tile at range 1 in the C scan order`() {
        assumeTrue(romFile.isFile, "ROM not present")
        val s = setup(1, 1)
        assertEquals(15, s.map.sizeX)
        assertEquals(10, s.map.sizeY)
        s.map.unit[5, 5] = 1
        assertEquals(5 or (4 shl 8), search(s, 5, 5)) // (5,4): lowest y among the 4 neighbours
    }

    @Test
    fun `occupied neighbours are skipped and ties go to the last scanned tile`() {
        assumeTrue(romFile.isFile, "ROM not present")
        val s = setup(1, 1)
        s.map.unit[5, 5] = 1
        s.map.other[4, 5] = 1 // (5,4) held by an "other" marker
        s.map.hidden[6, 5] = 1 // (5,6) held by a hidden marker
        // Left at range 1: (4,5) and (6,5), scanned x downward, so (4,5) is scanned last.
        assertEquals(4 or (5 shl 8), search(s, 5, 5))
    }

    @Test
    fun `a target the unit cannot approach finds nothing`() {
        assumeTrue(romFile.isFile, "ROM not present")
        val s = setup(1, 1)
        s.map.unit[5, 5] = 1
        assertEquals(-1, search(s, 5, 5, terrain = 2)) // cost 200 per tile: nothing within 124
    }

    @Test
    fun `an unwired bmMap is a wiring error`() {
        assumeTrue(romFile.isFile, "ROM not present")
        val s = setup(1, 1, wired = false)
        assertThrows(IllegalStateException::class.java) { search(s, 5, 5) }
    }
}
