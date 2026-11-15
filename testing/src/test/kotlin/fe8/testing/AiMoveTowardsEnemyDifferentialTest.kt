package fe8.testing

import fe8.game.RomView
import fe8.game.bm.ActiveUnit
import fe8.game.bm.AiState
import fe8.game.bm.BmBattle
import fe8.game.bm.BmMap
import fe8.game.bm.BmSt
import fe8.game.bm.ChapterData
import fe8.game.bm.CpDecide
import fe8.game.bm.CpPhase
import fe8.game.bm.CpScript
import fe8.game.bm.CpUtility
import fe8.game.bm.EntityBmMaps
import fe8.game.bm.MovementMap
import fe8.game.bm.PlaySt
import fe8.game.bm.UnitLoader
import fe8.game.bm.UnitTable
import fe8.game.bm.Vec2
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import gbafe.proc.ProcEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Differential tests for task EP-7d:
 * - `AiFindTargetInReachByFunc` ([CpUtility.aiFindTargetInReachByFunc])
 * - `AiTryMoveTowards` ([CpUtility.aiTryMoveTowards])
 * - `AiScriptCmd_12_MoveTowardsEnemy` ([CpScript.cmdMoveTowardsEnemy])
 *
 * Verified against `ram_trace_prologue_clear` (f3803..f3832, especially r03803 and r03804):
 * boss (0x81) at (10, 8) targets Eirika (char 1) at (4, 5) and decides a move to (9, 6).
 */
class AiMoveTowardsEnemyDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    @VerifiesPort("AiFindTargetInReachByFunc")
    fun `aiFindTargetInReachByFunc finds target Eirika for boss at r03803`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        val rom = RomView.ofRom(romFile.readBytes())
        val run = ref.run(PROLOGUE_CLEAR)
        val dump3803 = Dump(run.region("r03803", "ewram"), run.region("r03803", "iwram"))

        val playSt = PlaySt().also { it.decode(dump3803.ewram, PLAY_ST - EWRAM) }
        val map = BmMap(rom, ChapterData(rom), BmSt()).also {
            it.initChapterMap(playSt.chapterIndex)
        }
        readPool(dump3803, GBM_MAP_TERRAIN, map, map.terrain)
        readPool(dump3803, GBM_MAP_UNIT, map, map.unit)
        val units = UnitTable(rom).also { it.loadFrom(dump3803.ewram) }
        val aiState = AiState()
        val rng = RngService()
        val loader = UnitLoader(units, rom, { 0 }, rng, { false })
        val entityMaps = EntityBmMaps(map, units, playSt)
        val active = ActiveUnit().also {
            it.unit = BOSS_UNIT_ID
            it.unitId = BOSS_UNIT_ID
        }
        val utility = CpUtility(rom, units, playSt, aiState, loader, map, entityMaps, active)
        val script = CpScript(rom, units, aiState, active, rng)

        val targetPos = Vec2()
        val found = utility.aiFindTargetInReachByFunc(script.isUnitEnemy, targetPos)

        assertTrue(found, "target enemy in reach found")
        // Target is Eirika at (4, 5)
        assertEquals(4, targetPos.x, "target x")
        assertEquals(5, targetPos.y, "target y")
        assertEquals(CHARACTER_EIRIKA, units.charId(map.unit[targetPos.y, targetPos.x]))

        // Non-enemy filter (allied filter) finds no allied target in range
        val alliedFound = utility.aiFindTargetInReachByFunc({ !script.isUnitEnemy.run(it) }, Vec2())
        assertFalse(alliedFound, "no non-enemy target in range")
    }

    @Test
    @VerifiesPort("AiTryMoveTowards")
    fun `aiTryMoveTowards decides boss move and matches r03804 movement and range maps`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        val rom = RomView.ofRom(romFile.readBytes())
        val run = ref.run(PROLOGUE_CLEAR)
        val dump3803 = Dump(run.region("r03803", "ewram"), run.region("r03803", "iwram"))
        val dump3804 = Dump(run.region("r03804", "ewram"), run.region("r03804", "iwram"))

        val playSt = PlaySt().also { it.decode(dump3803.ewram, PLAY_ST - EWRAM) }
        val map = BmMap(rom, ChapterData(rom), BmSt()).also {
            it.initChapterMap(playSt.chapterIndex)
        }
        readPool(dump3803, GBM_MAP_TERRAIN, map, map.terrain)
        readPool(dump3803, GBM_MAP_UNIT, map, map.unit)
        readPool(dump3803, GBM_MAP_OTHER, map, map.other)
        readPool(dump3803, GBM_MAP_MOVEMENT, map, map.movement)
        readPool(dump3803, GBM_MAP_RANGE, map, map.range)

        // Verify r03803 movement and range maps match dump before decision
        assertPoolMatches(dump3803, GBM_MAP_MOVEMENT, map, map.movement, "r03803 movement")
        assertPoolMatches(dump3803, GBM_MAP_RANGE, map, map.range, "r03803 range")

        val units = UnitTable(rom).also { it.loadFrom(dump3803.ewram) }
        val aiState = AiState()
        val rng = RngService()
        val loader = UnitLoader(units, rom, { 0 }, rng, { false })
        val entityMaps = EntityBmMaps(map, units, playSt)
        val active = ActiveUnit().also {
            it.unit = BOSS_UNIT_ID
            it.unitId = BOSS_UNIT_ID
        }
        val utility = CpUtility(rom, units, playSt, aiState, loader, map, entityMaps, active)
        val engine = ProcEngine()
        val decide = CpDecide(engine, units, aiState, active, utility, entityMaps, rom, loader, map)
        utility.decide = decide

        // Move towards target Eirika at (4, 5) with maxDanger 0xFF and unk 1
        val moved = utility.aiTryMoveTowards(4, 5, 0, 0xFF, 1)

        assertTrue(moved, "aiTryMoveTowards succeeded")
        val d = decide.decision
        assertEquals(0, d.actionId, "actionId is 0 (move only)")
        assertEquals(BOSS_UNIT_ID, d.unitId, "unitId is boss")
        assertEquals(9, d.xMove, "xMove is 9")
        assertEquals(6, d.yMove, "yMove is 6")
        assertEquals(0, d.targetId, "targetId is 0")
        assertEquals(0, d.itemSlot, "itemSlot is 0")
        assertTrue(d.actionPerformed, "actionPerformed is true")

        // Map comparison against r03804
        assertPoolMatches(dump3804, GBM_MAP_MOVEMENT, map, map.movement, "r03804 movement")
        assertPoolMatches(dump3804, GBM_MAP_RANGE, map, map.range, "r03804 range")
        assertEquals(5, map.movement[6, 9], "movement cost at (9, 6)")
        assertEquals(7, map.range[6, 9], "range cost at (9, 6)")
    }

    @Test
    @VerifiesPort("AiScriptCmd_12_MoveTowardsEnemy")
    fun `script cmd 12 executes move towards enemy and advances boss script pc`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        val rom = RomView.ofRom(romFile.readBytes())
        val run = ref.run(PROLOGUE_CLEAR)
        val dump3803 = Dump(run.region("r03803", "ewram"), run.region("r03803", "iwram"))
        val dump3804 = Dump(run.region("r03804", "ewram"), run.region("r03804", "iwram"))

        val playSt = PlaySt().also { it.decode(dump3803.ewram, PLAY_ST - EWRAM) }
        val map = BmMap(rom, ChapterData(rom), BmSt()).also {
            it.initChapterMap(playSt.chapterIndex)
        }
        readPool(dump3803, GBM_MAP_TERRAIN, map, map.terrain)
        readPool(dump3803, GBM_MAP_UNIT, map, map.unit)
        readPool(dump3803, GBM_MAP_OTHER, map, map.other)

        val units = UnitTable(rom).also { it.loadFrom(dump3803.ewram) }
        val active = ActiveUnit().also {
            it.unit = BOSS_UNIT_ID
            it.unitId = BOSS_UNIT_ID
        }
        val engine = ProcEngine()
        val rng = RngService()
        val loader = UnitLoader(units, rom, { 0 }, rng, { false })
        val entityMaps = EntityBmMaps(map, units, playSt)

        val cp = CpPhase(engine, rom, playSt, units, loader, map, entityMaps, active, rng)

        assertEquals(0, units.aiBPc(BOSS_UNIT_ID), "ai_b_pc starts at 0")
        assertFalse(cp.decide.decision.actionPerformed, "decision not yet performed")

        // Execute script B (starts at entry 0: command 12 AI_CMD_MOVE_TO_ENEMY)
        val ended = cp.script.tryExecScriptB()

        assertTrue(ended, "script B ended")
        assertEquals(1, units.aiBPc(BOSS_UNIT_ID), "ai_b_pc advanced to 1")

        val d = cp.decide.decision
        assertEquals(0, d.actionId, "actionId is 0")
        assertEquals(BOSS_UNIT_ID, d.unitId, "unitId is boss")
        assertEquals(9, d.xMove, "xMove is 9")
        assertEquals(6, d.yMove, "yMove is 6")
        assertTrue(d.actionPerformed, "actionPerformed is true")

        assertPoolMatches(dump3804, GBM_MAP_MOVEMENT, map, map.movement, "r03804 movement")
        assertPoolMatches(dump3804, GBM_MAP_RANGE, map, map.range, "r03804 range")
    }

    @Test
    fun `aiDecideMain runs scripts A and B matching capture f3803 to f3804 and timeline`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        val rom = RomView.ofRom(romFile.readBytes())
        val run = ref.run(PROLOGUE_CLEAR)
        val dump3803 = Dump(run.region("r03803", "ewram"), run.region("r03803", "iwram"))
        val dump3804 = Dump(run.region("r03804", "ewram"), run.region("r03804", "iwram"))

        val playSt = PlaySt().also { it.decode(dump3803.ewram, PLAY_ST - EWRAM) }
        val map = BmMap(rom, ChapterData(rom), BmSt()).also {
            it.initChapterMap(playSt.chapterIndex)
        }
        readPool(dump3803, GBM_MAP_TERRAIN, map, map.terrain)
        readPool(dump3803, GBM_MAP_UNIT, map, map.unit)
        readPool(dump3803, GBM_MAP_OTHER, map, map.other)

        val units = UnitTable(rom).also { it.loadFrom(dump3803.ewram) }
        val active = ActiveUnit().also {
            it.unit = BOSS_UNIT_ID
            it.unitId = BOSS_UNIT_ID
        }
        val engine = ProcEngine()
        val rng = RngService()
        val loader = UnitLoader(units, rom, { 0 }, rng, { false })
        val entityMaps = EntityBmMaps(map, units, playSt)

        val cp = CpPhase(engine, rom, playSt, units, loader, map, entityMaps, active, rng)
        val bmSt = BmSt()
        val battle = BmBattle(rom, units, loader, playSt, bmSt) { y, x -> map.terrain[y, x] }
        val b = cp.battle!!
        b.battleUnits = battle

        // Verify initial state at step 3803 matches watch
        val red = run.watch("red")
        val decWatch = run.watch("aidecision")
        fun bossAi(step: Int) = (0x42..0x45).map { red.u8(step, it) }

        assertEquals(listOf(0, 0, 0, 0), bossAi(3803), "boss ai at 3803")
        assertEquals(10, red.u8(3803, 0x10), "boss x at 3803")
        assertEquals(8, red.u8(3803, 0x11), "boss y at 3803")

        // Run full decision loop: script A attempts combat, misses; script B moves towards enemy
        cp.decide.aiDecideMain()

        // Verify state at step 3804
        assertEquals(listOf(0, 1, 0, 1), bossAi(3804), "boss ai at 3804 (pc advanced)")
        assertEquals(3, cp.aiState.decideState, "decideState is 3 after script B sets decision")
        assertEquals(1, units.aiAPc(BOSS_UNIT_ID), "ai_a_pc is 1")
        assertEquals(1, units.aiBPc(BOSS_UNIT_ID), "ai_b_pc is 1")

        val expectedDec = decWatch.step(3804).toList()
        val actualDec = listOf(
            cp.decide.decision.actionId.toByte(),
            cp.decide.decision.unitId.toByte(),
            cp.decide.decision.xMove.toByte(),
            cp.decide.decision.yMove.toByte(),
            cp.decide.decision.unk04.toByte(),
            cp.decide.decision.unk05.toByte(),
            cp.decide.decision.targetId.toByte(),
            cp.decide.decision.itemSlot.toByte(),
            cp.decide.decision.xTarget.toByte(),
            cp.decide.decision.yTarget.toByte(),
            (if (cp.decide.decision.actionPerformed) 1 else 0).toByte(),
            0.toByte(),
        )
        assertEquals(expectedDec, actualDec, "aidecision matches watch at 3804")

        assertPoolMatches(dump3804, GBM_MAP_MOVEMENT, map, map.movement, "r03804 movement")
        assertPoolMatches(dump3804, GBM_MAP_RANGE, map, map.range, "r03804 range")

        // Verify watches through step 3832
        for (step in 3804..3832) {
            assertEquals(expectedDec, decWatch.step(step).toList(), "aidecision at step $step")
            assertEquals(listOf(0, 1, 0, 1), bossAi(step), "boss ai at step $step")
        }
        // Boss walks from (10, 8) to (9, 6) in steps 3805..3831
        assertEquals(10, red.u8(3830, 0x10), "boss x at 3830")
        assertEquals(8, red.u8(3830, 0x11), "boss y at 3830")
        assertEquals(9, red.u8(3831, 0x10), "boss x at 3831")
        assertEquals(6, red.u8(3831, 0x11), "boss y at 3831")
        assertEquals(9, red.u8(3832, 0x10), "boss x at 3832")
        assertEquals(6, red.u8(3832, 0x11), "boss y at 3832")
    }

    private fun readPool(dump: Dump, handle: Int, map: BmMap, pool: BmMap.Pool) {
        val first = dump.rowPointer(handle, -2)
        for (y in -2 until map.sizeY + 2) {
            for (x in 0 until map.sizeX + 2) {
                pool[y, x] = dump.byte(first + (y + 2) * (map.sizeX + 2) + x)
            }
        }
    }

    private fun assertPoolMatches(
        dump: Dump,
        handle: Int,
        map: BmMap,
        pool: BmMap.Pool,
        name: String,
    ) {
        val first = dump.rowPointer(handle, -2)
        for (y in -2 until map.sizeY + 2) {
            for (x in 0 until map.sizeX + 2) {
                val expected = dump.byte(first + (y + 2) * (map.sizeX + 2) + x)
                assertEquals(expected, pool[y, x], "$name ($x,$y)")
            }
        }
    }

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
        const val GBM_MAP_MOVEMENT = 0x0202E4E0
        const val GBM_MAP_OTHER = 0x0202E4F0
        const val CHARACTER_EIRIKA = 0x01
        const val BOSS_UNIT_ID = 0x81
        const val PROLOGUE_CLEAR = "ram_trace_prologue_clear"
    }
}
