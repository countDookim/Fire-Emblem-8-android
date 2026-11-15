package fe8.testing

import fe8.game.RomView
import gbafe.core.rng.RngService
import fe8.game.bm.AiState
import fe8.game.bm.BmMap
import fe8.game.bm.BmSt
import fe8.game.bm.ChapterData
import fe8.game.bm.CpUtility
import fe8.game.bm.EntityBmMaps
import fe8.game.bm.MovementMap
import fe8.game.bm.Mu
import fe8.game.bm.PlaySt
import fe8.game.bm.UnitLoader
import fe8.game.bm.UnitTable
import fe8.game.bm.revertMovementScript
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Tests for EP-7c movement helpers of the boss move in the Prologue:
 * - `GenerateExtendedMovementMapOnRange` ([MovementMap.generateExtendedMovementMapOnRange])
 * - `RevertMovementScript` ([fe8.game.bm.revertMovementScript])
 * - `AiCheckDangerAt` ([CpUtility.aiCheckDangerAt])
 * - `UnitHasMagicRank` ([UnitTable.unitHasMagicRank])
 *
 * Verified against `ram_trace_prologue_clear` (f3803..f3805) and `ram_trace_prologue_enemy_phase`
 * (f1614) dumps.
 */
class AiBossMoveHelpersDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    @VerifiesPort("GenerateExtendedMovementMapOnRange")
    fun `extended movement map on range matches boss move r03804`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        val rom = RomView.ofRom(romFile.readBytes())
        val run = ref.run(PROLOGUE_CLEAR)
        val before = Dump(run.region("r03803", "ewram"), run.region("r03803", "iwram"))
        val after = Dump(run.region("r03804", "ewram"), run.region("r03804", "iwram"))

        val playSt = PlaySt().also { it.decode(before.ewram, PLAY_ST - EWRAM) }
        val map = BmMap(rom, ChapterData(rom), BmSt()).also {
            it.initChapterMap(playSt.chapterIndex)
        }
        readPool(before, GBM_MAP_TERRAIN, map, map.terrain)
        readPool(before, GBM_MAP_UNIT, map, map.unit)
        val units = UnitTable(rom).also { it.loadFrom(before.ewram) }

        // Boss (unit 0x81) at (10, 8) moves towards target Eirika (char 1) at (4, 5).
        val bossUnitId = 0x81
        val targetChar = units.getUnitFromCharId(CHARACTER_EIRIKA)
        assertTrue(targetChar > 0, "target unit exists")
        val targetX = units.xPos(targetChar)
        val targetY = units.yPos(targetChar)
        assertEquals(4, targetX)
        assertEquals(5, targetY)

        val moves = MovementMap(rom, map, units, playSt)
        val costTable = moves.unitMovementCostTable(bossUnitId)

        map.bmMapFill(map.range, SENTINEL)
        moves.generateExtendedMovementMapOnRange(targetX, targetY, costTable)

        assertSame(map.range, map.workingMap, "working map is range map")

        val first = after.rowPointer(GBM_MAP_RANGE, -2)
        var reached = 0
        for (y in -2 until map.sizeY + 2) {
            for (x in 0 until map.sizeX + 2) {
                val expected = after.byte(first + (y + 2) * (map.sizeX + 2) + x)
                assertEquals(expected, map.range[y, x], "range ($x,$y) at r03804")
                if (y in 0 until map.sizeY && x in 0 until map.sizeX && expected < 0xFF) {
                    reached++
                }
            }
        }
        assertTrue(reached >= 30, "tiles reached: $reached")
        assertEquals(0, map.range[5, 4], "target tile cost is 0")
        assertEquals(1, map.range[5, 5], "adjacent tile cost is 1")
        assertEquals(7, map.range[6, 9], "boss move tile (9, 6) cost is 7")
        assertEquals(11, map.range[8, 10], "boss origin tile (10, 8) cost is 11")
    }

    @Test
    @VerifiesPort("RevertMovementScript")
    fun `revert movement script matches captured movement scripts`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val runClear = ref.run(PROLOGUE_CLEAR)
        val dClear = Dump(runClear.region("r03804", "ewram"), runClear.region("r03804", "iwram"))

        // Case 1: Boss move at r03804 (UP, LEFT, UP -> [3, 0, 3, HALT]).
        // The path search walks backward from (9, 6) to (10, 8), yielding steps: UP, LEFT, UP.
        val bossDirs = intArrayOf(
            Mu.MOVE_CMD_MOVE_UP,
            Mu.MOVE_CMD_MOVE_LEFT,
            Mu.MOVE_CMD_MOVE_UP,
        )
        val bossOut = IntArray(0x40)
        revertMovementScript(bossDirs, 3, bossOut)
        for (i in 0..3) {
            assertEquals(
                dClear.byte(WORKING_SCRIPT + i),
                bossOut[i],
                "r03804 gWorkingMovementScript+$i",
            )
        }
        assertEquals(Mu.MOVE_CMD_HALT, bossOut[3], "boss script terminated with HALT")

        // Case 2: Asymmetric movement script from r01614 (Soldier 1: [3, 0, 0, 0, 4]).
        // Backward path search yields [0, 0, 0, 3]; reversed walk must be [3, 0, 0, 0, 4].
        val runEnemy = ref.run(PROLOGUE_ENEMY)
        val dEnemy = Dump(runEnemy.region("r01614", "ewram"), runEnemy.region("r01614", "iwram"))
        val soldierDirs = intArrayOf(
            Mu.MOVE_CMD_MOVE_LEFT,
            Mu.MOVE_CMD_MOVE_LEFT,
            Mu.MOVE_CMD_MOVE_LEFT,
            Mu.MOVE_CMD_MOVE_UP,
        )
        val soldierOut = IntArray(0x40)
        revertMovementScript(soldierDirs, 4, soldierOut)
        for (i in 0..4) {
            assertEquals(
                dEnemy.byte(WORKING_SCRIPT + i),
                soldierOut[i],
                "r01614 gWorkingMovementScript+$i",
            )
        }

        // Case 3: In-place reversal on the same array.
        val inPlace = intArrayOf(
            Mu.MOVE_CMD_MOVE_LEFT,
            Mu.MOVE_CMD_MOVE_LEFT,
            Mu.MOVE_CMD_MOVE_LEFT,
            Mu.MOVE_CMD_MOVE_UP,
            0,
        )
        revertMovementScript(inPlace, 4)
        for (i in 0..4) {
            assertEquals(
                dEnemy.byte(WORKING_SCRIPT + i),
                inPlace[i],
                "in-place gWorkingMovementScript+$i",
            )
        }
    }

    @Test
    fun `aiCheckDangerAt evaluates danger map at r03804`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        val rom = RomView.ofRom(romFile.readBytes())
        val run = ref.run(PROLOGUE_CLEAR)
        val dump = Dump(run.region("r03804", "ewram"), run.region("r03804", "iwram"))

        val playSt = PlaySt().also { it.decode(dump.ewram, PLAY_ST - EWRAM) }
        val map = BmMap(rom, ChapterData(rom), BmSt()).also {
            it.initChapterMap(playSt.chapterIndex)
        }
        readPool(dump, GBM_MAP_OTHER, map, map.other)
        val units = UnitTable(rom).also { it.loadFrom(dump.ewram) }
        val aiState = AiState()
        val loader = UnitLoader(units, rom, { 0 }, RngService(), { false })
        val entityMaps = EntityBmMaps(map, units, playSt)
        val utility = CpUtility(rom, units, playSt, aiState, loader, map, entityMaps)

        // Boss decided tile (9, 6) has danger 23 (0x17) on gBmMapOther.
        assertEquals(23, map.other[6, 9], "danger at (9, 6)")
        // AI script passes threshold 0xFF: danger 23 <= 255.
        assertTrue(utility.aiCheckDangerAt(9, 6, 255))
        assertTrue(utility.aiCheckDangerAt(9, 6, 23))
        assertFalse(utility.aiCheckDangerAt(9, 6, 22))

        // Boss origin tile (10, 8) has 0 danger.
        assertEquals(0, map.other[8, 10], "danger at (10, 8)")
        assertTrue(utility.aiCheckDangerAt(10, 8, 0))
    }

    @Test
    fun `unitHasMagicRank checks magic weapon ranks on captured units`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        val rom = RomView.ofRom(romFile.readBytes())
        val run = ref.run(PROLOGUE_CLEAR)
        val dump = Dump(run.region("r03804", "ewram"), run.region("r03804", "iwram"))
        val units = UnitTable(rom).also { it.loadFrom(dump.ewram) }

        // In Prologue, no deployed unit has magic ranks.
        val eirika = units.getUnitFromCharId(CHARACTER_EIRIKA)
        val seth = units.getUnitFromCharId(CHARACTER_SETH)
        val boss = 0x81
        assertFalse(units.unitHasMagicRank(eirika), "Eirika has no magic rank")
        assertFalse(units.unitHasMagicRank(seth), "Seth has no magic rank")
        assertFalse(units.unitHasMagicRank(boss), "Boss has no magic rank")

        // Weapon ranks (sword, lance, axe, bow) do not count as magic.
        val eirikaOffset = units.unitOffset(eirika)
        assertTrue(units.bytes[eirikaOffset + 0x28].toInt() > 0, "Eirika has sword rank")
        assertFalse(units.unitHasMagicRank(eirika))

        // Setting each magic rank individually causes unitHasMagicRank to return true.
        val offRanks = units.unitOffset(boss) + 0x28
        // ITYPE_STAFF = 4, ANIMA = 5, LIGHT = 6, DARK = 7
        for (itype in 4..7) {
            units.bytes[offRanks + itype] = 1
            assertTrue(units.unitHasMagicRank(boss), "magic rank at itype $itype")
            units.bytes[offRanks + itype] = 0
        }
        assertFalse(units.unitHasMagicRank(boss), "ranks restored to 0")
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

        fun pointer(handle: Int): Int = word(handle)

        fun rowPointer(handle: Int, row: Int): Int = word(pointer(handle) + 4 * row)
    }

    private companion object {
        const val EWRAM = 0x02000000
        const val IWRAM = 0x03000000
        const val PLAY_ST = 0x0202BCF0
        const val GBM_MAP_TERRAIN = 0x0202E4DC
        const val GBM_MAP_UNIT = 0x0202E4D8
        const val GBM_MAP_RANGE = 0x0202E4E4
        const val GBM_MAP_OTHER = 0x0202E4F0
        const val WORKING_SCRIPT = 0x02033EFC
        const val CHARACTER_EIRIKA = 0x01
        const val CHARACTER_SETH = 0x02
        const val SENTINEL = 0xAA
        const val PROLOGUE_CLEAR = "ram_trace_prologue_clear"
        const val PROLOGUE_ENEMY = "ram_trace_prologue_enemy_phase"
    }
}
