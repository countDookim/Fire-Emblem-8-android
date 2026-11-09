package fe8.game.bm

import fe8.game.RomView
import gbafe.core.rng.RngService
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import kotlin.random.Random
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
        fake[0x203] = 255.toByte()
        fake[0x204] = 124
        fake[0x205] = 3
        fake[0x206] = 9
        fake[0x207] = 17
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
    fun `frontier matches full relaxation on weighted padded maps`() {
        assumeTrue(romFile.isFile, "ROM not present")
        val s = setup(1, 1)
        val random = Random(397)
        val width = s.map.sizeX
        val height = s.map.sizeY
        val costs = intArrayOf(0, 1, 200, 255, 124, 3, 9, 17)
        val terrainChoices = intArrayOf(0, 1, 1, 1, 1, 2, 3, 4, 5, 5, 6, 7)
        repeat(80) { case ->
            val terrain = IntArray(width * (height + 4)) {
                if (case % 5 == 0) 1 else terrainChoices[random.nextInt(terrainChoices.size)]
            }
            val x = if (case == 0) -1 else random.nextInt(width)
            val y = random.nextInt(-2, height + 2)
            val terrainAt: (Int, Int) -> Int = { tx, ty -> terrain[(ty + 2) * width + tx] }
            s.loader.floodMovement(s.map, s.map.movement, x, y, 0x08000200, terrainAt)
            val expected = s.map.range
            s.map.bmMapFill(expected, 255)
            if (x in 0 until width) {
                expected[y, x] = 0
                var changed: Boolean
                do {
                    changed = false
                    for (cy in -2 until height + 2) for (cx in 0 until width) {
                        val base = expected[cy, cx]
                        if (base > 124) continue
                        for ((dx, dy) in listOf(0 to -1, 1 to 0, 0 to 1, -1 to 0)) {
                            val nx = cx + dx
                            val ny = cy + dy
                            if (nx !in 0 until width || ny !in -2 until height + 2) continue
                            val cost = base + costs[terrainAt(nx, ny)]
                            if (cost < expected[ny, nx] && cost <= 124) {
                                expected[ny, nx] = cost
                                changed = true
                            }
                        }
                    }
                } while (changed)
            }
            assertTrue(expected.bytes.contentEquals(s.map.movement.bytes), "case $case")
        }
    }

    @Test
    fun `frontier visits only reachable neighbours on an open map`() {
        assumeTrue(romFile.isFile, "ROM not present")
        val s = setup(1, 1)
        var lookups = 0
        s.loader.floodMovement(
            s.map, s.map.movement, s.map.sizeX - 1, s.map.sizeY + 1, 0x08000200,
        ) { _, _ ->
            lookups++
            1
        }
        // On this 15 by 14 padded grid every directed neighbour edge is visited once.
        assertEquals(782, lookups)
        assertEquals(27, s.map.movement[-2, 0])
    }

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
