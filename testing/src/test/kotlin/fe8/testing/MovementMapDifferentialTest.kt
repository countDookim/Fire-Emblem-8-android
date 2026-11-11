package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BmMap
import fe8.game.bm.BmSt
import fe8.game.bm.ChapterData
import fe8.game.bm.MovementMap
import fe8.game.bm.PlaySt
import fe8.game.bm.UnitTable
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * [MovementMap] against the Prologue captures where the player selects Eirika (the
 * `DisplayUnitEffectRange` call of `PlayerPhase_InitUnitMovementSelect`, which floods
 * `gBmMapMovement` through `GenerateUnitMovementMapExt` with `UNIT_MOV - moveCount`). Units,
 * `gPlaySt`, the terrain map and the unit map come from the dump taken just before the flood; the
 * movement pool is filled with a sentinel, the port runs, and the whole pool (rows -2 to
 * height + 1, `width + 2` bytes each), `gWorkingTerrainMoveCosts` and the working map pointer are
 * compared with the dump right after the flood.
 *
 * Cases: `ram_trace_prologue_select_move` r01261 -> r01262 (A pressed at f1260) and
 * `ram_trace_prologue_cancel_end` r01261 -> r01262 and r01311 -> r01312 (second select, f1310).
 */
class MovementMapDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    @VerifiesPort(
        "GenerateUnitMovementMapExt",
        "GetUnitMovementCost",
        "SetWorkingMoveCosts",
        "SetWorkingBmMap",
        "GenerateMovementMap",
    )
    fun `select flood matches the game`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        val rom = RomView.ofRom(romFile.readBytes())
        var reached = 0
        for (case in CASES) reached += compare(rom, case)
        assertTrue(reached >= 30, "tiles reached across the cases: $reached")
    }

    /** Runs the flood for [case] and compares the pool; returns the tiles it reached. */
    private fun compare(rom: RomView, case: Case): Int {
        val run = ref.run(case.run)
        val at = "${case.run} ${case.before}->${case.after}"
        val before = Dump(run.region(case.before, "ewram"), run.region(case.before, "iwram"))
        val after = Dump(run.region(case.after, "ewram"), run.region(case.after, "iwram"))

        val playSt = PlaySt()
        playSt.decode(before.ewram, PLAY_ST - EWRAM)
        val map = BmMap(rom, ChapterData(rom), BmSt())
        map.initChapterMap(playSt.chapterIndex)
        readPool(before, GBM_MAP_TERRAIN, map, map.terrain)
        readPool(before, GBM_MAP_UNIT, map, map.unit)
        val units = UnitTable(rom)
        units.loadFrom(before.ewram)
        val eirika = units.getUnitFromCharId(CHARACTER_EIRIKA)
        assertTrue(eirika > 0, "Eirika not found at $at")
        val unitOffset = units.unitOffset(eirika)
        assertEquals(PLAYER_UNIT, UnitTable.BLUE_ADDRESS + unitOffset, "unit address at $at")

        // UNIT_MOV(unit) - gActionData.moveCount
        val classAddress = units.bytes.u32(unitOffset + 0x04)
        val movBonus = units.bytes[unitOffset + 0x1D].toInt()
        val moveCount = after.byte(ACTION_DATA + 0x10)
        val movement = rom.u8(classAddress + 0x12).toByte() + movBonus - moveCount

        map.bmMapFill(map.movement, SENTINEL)
        val moves = MovementMap(rom, map, units, playSt)
        moves.generateUnitMovementMapExt(eirika, movement)
        for (i in moves.workingMoveCosts.indices) {
            assertEquals(
                after.byte(WORKING_COSTS + i),
                moves.workingMoveCosts[i].toInt() and 0xFF,
                "gWorkingTerrainMoveCosts[$i] at $at",
            )
        }
        assertEquals(after.pointer(GBM_MAP_MOVEMENT), after.word(WORKING_BM_MAP))
        assertSame(map.movement, map.workingMap, "working map at $at")

        val first = after.rowPointer(GBM_MAP_MOVEMENT, -2)
        var reached = 0
        for (y in -2 until map.sizeY + 2) {
            for (x in 0 until map.sizeX + 2) {
                val expected = after.byte(first + (y + 2) * (map.sizeX + 2) + x)
                assertEquals(expected, map.movement[y, x], "movement ($x,$y) at $at")
                if (y in 0 until map.sizeY && x < map.sizeX && expected < 0xFF) reached++
            }
        }
        return reached
    }

    /** Copies the `gBmMap*` pool [handle] of [dump] into [pool]. */
    private fun readPool(dump: Dump, handle: Int, map: BmMap, pool: BmMap.Pool) {
        val first = dump.rowPointer(handle, -2)
        for (y in -2 until map.sizeY + 2) {
            for (x in 0 until map.sizeX + 2) {
                pool[y, x] = dump.byte(first + (y + 2) * (map.sizeX + 2) + x)
            }
        }
    }

    private fun ByteArray.u8(at: Int) = this[at].toInt() and 0xFF
    private fun ByteArray.u32(at: Int) =
        u8(at) or (u8(at + 1) shl 8) or (u8(at + 2) shl 16) or (u8(at + 3) shl 24)

    private companion object {
        const val EWRAM = 0x02000000
        const val IWRAM = 0x03000000
        const val PLAY_ST = 0x0202BCF0
        const val PLAYER_UNIT = 0x0202BE94
        const val ACTION_DATA = 0x0203A958
        const val GBM_MAP_UNIT = 0x0202E4D8
        const val GBM_MAP_TERRAIN = 0x0202E4DC
        const val GBM_MAP_MOVEMENT = 0x0202E4E0
        const val WORKING_BM_MAP = 0x030049A0
        const val WORKING_COSTS = 0x03004BB0
        const val CHARACTER_EIRIKA = 0x01
        const val SENTINEL = 0xAA
        val CASES = listOf(
            Case("ram_trace_prologue_select_move", "r01261", "r01262"),
            Case("ram_trace_prologue_cancel_end", "r01261", "r01262"),
            Case("ram_trace_prologue_cancel_end", "r01311", "r01312"),
        )
    }

    private class Case(val run: String, val before: String, val after: String)

    /** The EWRAM and IWRAM of one dump, addressed with GBA addresses. */
    private class Dump(val ewram: ByteArray, val iwram: ByteArray) {
        fun byte(address: Int): Int =
            if (address >= IWRAM) {
                iwram[address - IWRAM].toInt() and 0xFF
            } else {
                ewram[address - EWRAM].toInt() and 0xFF
            }

        fun word(address: Int): Int =
            byte(address) or (byte(address + 1) shl 8) or (byte(address + 2) shl 16) or
                (byte(address + 3) shl 24)

        /** The value of the `gBmMapX` handle at [handle]: the address of its row 0 pointer. */
        fun pointer(handle: Int): Int = word(handle)

        /** The address of the data of row [row] (the handle points at the row 0 pointer). */
        fun rowPointer(handle: Int, row: Int): Int = word(pointer(handle) + 4 * row)
    }
}
