package fe8.testing

import fe8.game.bm.BattleStats
import fe8.game.bm.BattleUnit
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The action half of the AI perform proc (EP-9, src/cp_perform.c) on the same set-up as
 * [CpPerformDifferentialTest] (captured `gAiDecision` as input, the rest real): `CpPerform_PerformAction`,
 * `AiStartCombatAction` (through the player phase's `ApplyUnitAction` and `ActionCombat`, MC-13),
 * `CpPerform_WaitAction`, `AiDummyAction`, `CpPerform_Cleanup` and `AiRefreshMap`.
 *
 * Soldier 1 (f1696..2013) and Soldier 2 (f2130..2402) of `ram_trace_prologue_enemy_phase` attack
 * Eirika and die to her counter; the boss of `ram_trace_prologue_clear` (f3831..3832) only moves
 * (action 0). Compared per step: the `CpPerform` and `sProcScr_CombatAction` procs (existence,
 * script position), `gActionData` (subject, target, move tile, move count, type, item slot), both
 * battle units, `gBattleStats`, the RNG, and the unit table (the three Soldiers and both blue
 * units, every byte but the map sprite handle). The post-action event of the original (EP-11,
 * f2014..2017 and f2403..2723) is not run, so the end of the proc is compared by state, not by
 * frame: after the proc has ended the unit table and `gActionData` equal the capture at f2018,
 * f2723 and f3832 (the boss without its inventory: `CpPerform_EquipBest`, EP-9b). The map
 * animation is a placeholder that ends with the captured `ProcScr_MapAnimBattle` (as in
 * `CombatActionDifferentialTest`).
 */
class CpPerformActionDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    private fun compare(s: CpPerformSim, f: Int, names: Map<Int, String>) {
        val at = "f$f"
        val dump = ProcArrayDump.ofWatch(s.data.watch("procs"), f, names)
        val perform = dump.find(PROC_SCR_CP_PERFORM)
        val ours = s.engine.find(s.perform.script)
        assertEquals(perform != null, ours != null, "gProcScr_CpPerform exists $at")
        if (perform != null) {
            assertEquals(perform.scrCur, ours!!.scrCur, "gProcScr_CpPerform script position $at")
        }
        val combat = dump.find(PROC_SCR_COMBAT)
        val combatOurs = s.engine.find(s.combat.script)
        assertEquals(combat != null, combatOurs != null, "sProcScr_CombatAction exists $at")
        if (combat != null) {
            assertEquals(combat.scrCur, combatOurs!!.scrCur, "sProcScr_CombatAction position $at")
        }
        actionData(s, f, at)
        val bactor = s.data.watch("bactor")
        val btarget = s.data.watch("btarget")
        for (i in 0 until BattleUnit.SIZE) {
            if (i in AI_PC) continue
            val a = s.battle.actor.bytes[i].toInt() and 0xFF
            assertEquals(bactor.u8(f, i), a, "gBattleActor+$i $at")
            val t = s.battle.target.bytes[i].toInt() and 0xFF
            assertEquals(btarget.u8(f, i), t, "gBattleTarget+$i $at")
        }
        val stats = ByteArray(BattleStats.SIZE)
        s.battle.stats.encode(stats, 0)
        val bstats = s.data.watch("bstats")
        for (i in stats.indices) {
            assertEquals(bstats.u8(f, i), stats[i].toInt() and 0xFF, "gBattleStats+$i $at")
        }
        val rn = s.data.watch("rng")
        for (w in 0 until 3) assertEquals(rn.u16(f, 2 * w), s.rng.primary.word(w), "rng $w $at")
        units(s, f, at, withItems = true)
    }

    /** The map cursor `AiRefreshMap` puts on the unit's tile (`SetCursorMapPosition`). */
    private fun cursor(s: CpPerformSim, f: Int, at: String) {
        val want = fe8.game.bm.BmSt().also { it.decode(s.data.watch("gbmst").step(f), 0) }
        assertEquals(want.playerCursorX, s.bm.playerCursorX, "gBmSt.playerCursor.x $at")
        assertEquals(want.playerCursorY, s.bm.playerCursorY, "gBmSt.playerCursor.y $at")
        assertEquals(want.cursorTargetX, s.bm.cursorTargetX, "gBmSt.cursorTarget.x $at")
        assertEquals(want.cursorTargetY, s.bm.cursorTargetY, "gBmSt.cursorTarget.y $at")
        assertEquals(
            want.playerCursorDisplayX, s.bm.playerCursorDisplayX, "gBmSt.cursorDisplay.x $at",
        )
        assertEquals(
            want.playerCursorDisplayY, s.bm.playerCursorDisplayY, "gBmSt.cursorDisplay.y $at",
        )
    }

    private fun actionData(s: CpPerformSim, f: Int, at: String) {
        val action = s.data.watch("actiondata")
        val a = s.active.action
        assertEquals(action.u8(f, 0x0C), a.subjectIndex, "gActionData.subjectIndex $at")
        assertEquals(action.u8(f, 0x0D), a.targetIndex, "gActionData.targetIndex $at")
        assertEquals(action.u8(f, 0x0E), a.xMove, "gActionData.xMove $at")
        assertEquals(action.u8(f, 0x0F), a.yMove, "gActionData.yMove $at")
        assertEquals(action.u8(f, 0x10), a.moveCount, "gActionData.moveCount $at")
        assertEquals(action.u8(f, 0x11), a.unitActionType, "gActionData.unitActionType $at")
        assertEquals(action.u8(f, 0x12), a.itemSlotIndex, "gActionData.itemSlotIndex $at")
    }

    /** The unit table: the two blue units and the Soldiers, but the sprite handle (0x3C..0x3F). */
    private fun units(s: CpPerformSim, f: Int, at: String, withItems: Boolean, only: Int = -1) {
        val blue = s.data.watch("blue")
        val red = s.data.watch("red")
        for (u in 0 until 2) {
            if (only != -1) continue
            for (i in 0 until UNIT_SIZE) {
                if (i in 0x3C..0x3F || i in AI_PC) continue
                assertEquals(
                    blue.u8(f, u * UNIT_SIZE + i), s.units.bytes[u * UNIT_SIZE + i].toInt() and 0xFF,
                    "blue unit $u byte 0x${i.toString(16)} $at",
                )
            }
        }
        for (u in 0 until 3) {
            if (only != -1 && only != u) continue
            for (i in 0 until UNIT_SIZE) {
                if (i in 0x3C..0x3F || i in AI_PC) continue
                if (!withItems && i in 0x1E..0x27) continue
                assertEquals(
                    red.u8(f, u * UNIT_SIZE + i),
                    s.units.bytes[RED_OFFSET + u * UNIT_SIZE + i].toInt() and 0xFF,
                    "red unit $u byte 0x${i.toString(16)} $at",
                )
            }
        }
    }

    private fun attack(seed: Int, rngStep: Int, unit: Int, start: Int, last: Int, endStep: Int) {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val s = CpPerformSim.create(ref, romFile, "ram_trace_prologue_enemy_phase", seed, unit, rngStep)
        val names = ProcArrayDump.romNames()
        // Engine frame f shows capture step f up to the frame that starts the attack ([start]); the
        // battle generation of that frame runs over the vertical blank, so the capture's step
        // [start] is a snapshot inside it and its end is step start + 1: from there on engine frame
        // f is compared with step f + 1.
        for (f in seed + 2..last - 1) {
            s.frame()
            if (f >= start) compare(s, f + 1, names)
        }
        // The post-action event of the original is not run: finish the proc and compare the state
        // the capture shows after its end.
        var extra = 0
        while (s.engine.find(s.perform.script) != null && extra++ < 20) s.frame()
        assertNull(s.engine.find(s.perform.script), "the perform proc ends")
        actionData(s, endStep, "(end) f$endStep")
        units(s, endStep, "(end) f$endStep", withItems = true)
        cursor(s, endStep, "(end) f$endStep")
    }

    @Test
    @VerifiesPort(
        "CpPerform_PerformAction", "AiStartCombatAction", "CpPerform_WaitAction", "AiDummyAction",
        "CpPerform_Cleanup", "AiRefreshMap",
    )
    fun `Soldier 1 attacks and dies as the capture f1696 to f2018`() {
        attack(1613, 1614, 0x82, 1696, 2013, 2018)
    }

    @Test
    @VerifiesPort(
        "CpPerform_PerformAction", "AiStartCombatAction", "CpPerform_WaitAction", "AiDummyAction",
        "CpPerform_Cleanup", "AiRefreshMap",
    )
    fun `Soldier 2 attacks and dies as the capture f2129 to f2723`() {
        attack(2038, 2038, 0x83, 2129, 2402, 2723)
    }

    @Test
    @VerifiesPort(
        "CpPerform_PerformAction", "CpPerform_WaitAction", "AiDummyAction", "CpPerform_Cleanup",
        "AiRefreshMap",
        "AiCanEquip", "AiEquipGetFlags", "AiEquipGetDanger", "AiIsWithinFlyingDistance",
    )
    fun `the boss moves without an action as the capture f3831 to f3832`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val s = CpPerformSim.create(ref, romFile, "ram_trace_prologue_clear", 3803, 0x81)
        val names = ProcArrayDump.romNames()
        for (f in 3805..3831) {
            s.frame()
            if (f == 3831) {
                val dump = ProcArrayDump.ofWatch(s.data.watch("procs"), f, names)
                assertEquals(dump.find(PROC_SCR_CP_PERFORM)!!.scrCur,
                    s.engine.find(s.perform.script)!!.scrCur, "gProcScr_CpPerform position f3831")
                actionData(s, f, "f$f")
                units(s, f, "f$f", withItems = true, only = 0)
                comparePool(s, "r03831", GBM_MAP_MOVEMENT, s.map.movement, "gBmMapMovement f3831")
                // The decision's danger map (EP-3b, not built here) is the capture's; the
                // equip's danger query clears and rebuilds it.
                copyPool(s, "r03831", GBM_MAP_OTHER, s.map.other)
            }
        }
        s.frame()
        assertNull(s.engine.find(s.perform.script), "the perform proc ends at f3832")
        actionData(s, 3832, "f3832")
        cursor(s, 3832, "f3832")
        // `CpPerform_EquipBest` (EP-9b) runs at f3832: the inventory and the equipped weapon (slot
        // 0) are the capture's, the danger query clears `gBmMapOther` (no player unit reaches the
        // boss's tile) and leaves the flood of the last player unit in range in `gBmMapMovement`.
        units(s, 3832, "f3832", withItems = true, only = 0)
        comparePool(s, "r03832", GBM_MAP_MOVEMENT, s.map.movement, "gBmMapMovement f3832")
        comparePool(s, "r03832", GBM_MAP_OTHER, s.map.other, "gBmMapOther f3832")
        s.frame()
        units(s, 3833, "f3833", withItems = true, only = 0)
    }

    /** One `gBmMap*` pool of the EWRAM or IWRAM dump [name] (15 x 10) as rows. */
    private fun dumpPool(s: CpPerformSim, name: String, pointer: Int): Array<IntArray> {
        val ewram = s.data.region(name, "ewram")
        val iwram = s.data.region(name, "iwram")
        fun byte(a: Int) = (if (a >= 0x03000000) iwram[a - 0x03000000] else ewram[a - 0x02000000])
            .toInt() and 0xFF
        fun word(a: Int) = (0..3).sumOf { byte(a + it) shl (8 * it) }
        val rows = word(pointer)
        return Array(s.map.sizeY) { y ->
            val row = word(rows + 4 * y)
            IntArray(s.map.sizeX) { x -> byte(row + x) }
        }
    }

    private fun copyPool(s: CpPerformSim, name: String, pointer: Int, pool: fe8.game.bm.BmMap.Pool) {
        val rows = dumpPool(s, name, pointer)
        for (y in rows.indices) for (x in rows[y].indices) pool[y, x] = rows[y][x]
    }

    private fun comparePool(
        s: CpPerformSim,
        name: String,
        pointer: Int,
        pool: fe8.game.bm.BmMap.Pool,
        what: String,
    ) {
        val rows = dumpPool(s, name, pointer)
        for (y in rows.indices) {
            for (x in rows[y].indices) {
                assertEquals(rows[y][x], pool[y, x] and 0xFF, "$what ($x, $y)")
            }
        }
    }

    private companion object {
        const val PROC_SCR_CP_PERFORM = 0x085A8024
        const val PROC_SCR_COMBAT = 0x0859DABC
        val AI_PC = 0x42..0x45
        const val UNIT_SIZE = 0x48
        const val RED_OFFSET = 62 * 0x48
        const val GBM_MAP_MOVEMENT = 0x0202E4E0
        const val GBM_MAP_OTHER = 0x0202E4F0
    }
}
