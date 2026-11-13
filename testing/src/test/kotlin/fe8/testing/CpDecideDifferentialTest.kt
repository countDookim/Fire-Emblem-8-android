package fe8.testing

import fe8.game.RomView
import fe8.game.bm.ActiveUnit
import fe8.game.bm.AiIntStep
import fe8.game.bm.AiIntPairStep
import fe8.game.bm.AiProcStep
import fe8.game.bm.AiReachTest
import fe8.game.bm.AiBattleSimulation
import fe8.game.bm.AiScoreStep
import fe8.game.bm.AiStep
import fe8.game.bm.AiTry
import fe8.game.bm.AiUnitItemStep
import fe8.game.bm.AiUnitStep
import fe8.game.bm.BattleGenerate
import fe8.game.bm.BattleStats
import fe8.game.bm.BattleUnit
import fe8.game.bm.BmBattle
import fe8.game.bm.BmMap
import fe8.game.bm.BmSt
import fe8.game.bm.ChapterData
import fe8.game.bm.CpPhase
import fe8.game.bm.EntityBmMaps
import fe8.game.bm.PlaySt
import fe8.game.bm.UnitLoader
import fe8.game.bm.UnitTable
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gba.testkit.WatchTrace
import gbafe.core.mechanics.Mechanics
import gbafe.core.rng.RngService
import gbafe.proc.ProcEngine
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.repeat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The AI decision loop and its helpers (EP-3a, EP-3b) against the `aistate`, `aidecision`, `red`,
 * `blue`, `gplayst` and `actiondata` watches and the `gBmMapOther` of the full dumps f1612, f1613,
 * f1614 and f2038 of `ram_trace_prologue_enemy_phase`: the enemy phase of f1611 runs through
 * `gProcScr_CpPhase` > `CpOrder` > `CpDecide`, and at each point where the decision loop calls a
 * hook the port's state is compared with the capture frame that shows the same moment (the real
 * CPU needs more than a frame per unit, so a frame snapshot lies inside `CpDecide_Main`).
 * `RefreshUnitSprites` is the call right before `AiUpdateNoMoveFlag`:
 *
 * - Red 0's start = f1612 (decision zero, `unitIt` at the start, `decideState` 0, nobody marked);
 *   its script A call = the f1612 danger map (the dump lies after `AiInitDangerMap`);
 * - Soldier 1's start = f1613 (Red 0 marked `US_HAS_MOVED_AI`, `unitIt` 5; the danger map is
 *   still Red 0's);
 * - Soldier 1's perform start = f1614 (decision `0x82` attack, `decideState` 2, `unitIt` 6, the
 *   danger map of Soldier 1);
 * - Soldier 2's start = f2037 (`decideState` 0, the old decision still set);
 * - Soldier 2's script A call = f2038 (decision cleared, `decideState` 2, its danger map);
 * - Soldier 2's perform start = f2039 (decision `0x83` attack, `unitIt` 7);
 * - the end of the proc = f2742 (`decideState` 0).
 *
 * The AI script interpreter (EP-4, `CpScript`) runs for real on the unit bytes of f1611 and the
 * ROM script tables; compared at each snapshot: `ai1`, `ai_a_pc`, `ai2`, `ai_b_pc` of the three
 * units (Red 0 0 -> 1 / 0 -> 1 at f1613, Soldier 1 a-pc 0 -> 1 at f1614, Soldier 2 at f2039),
 * `unk7E` and `cmd_result` (all zero: no written command sets it), and the RNG state at the call
 * of the combat score (one `NextRN_N(100)` draw of command 05 after the captured state of f1611 /
 * f2037; the battle between them is EP-9's, so Soldier 2 starts from the f2037 state).
 *
 * The AI attack search (EP-5, `CpBattle`) runs for real: `AiAttemptOffensiveAction` decides the
 * attack on Eirika, so the target, move tile and item slot of `gAiDecision` at f1614 and f2039
 * come from the port's code. Its inputs at the `tracecall aicombatscore` entry dumps
 * (`call_aicombatscore_{1,2}_entry`) are compared: the `AiCombatSimulationSt` (xMove, yMove,
 * targetId, itemSlot; the padding and the stale score are not), `gBmMapRange` (the reversed
 * attack range of the target) and the simulated battle (`gBattleActor`, `gBattleTarget`,
 * `gBattleStats`, `gBattleHitArray`) from the real `BattleGenerateSimulation` (MC-9).
 * Stand-ins fed from the capture: `AiComputeCombatScore` (EP-6a/6b) returns the `score` of the exit
 * dump (6 for both calls); the three position score components (EP-6a/6b) are 0 (there is one
 * candidate tile per target, so no captured value shows the position score); the movement map
 * (`GenerateUnitMovementMap`, EP-7) is the entry dump's `gBmMapMovement`; every unit is in
 * reach (`AiReachesByBirdsEyeDistance`, EP-7: Seth has no tile to attack from either way), so the
 * reach test, the movement costs and the range are NOT verified. The simulation starts from the
 * `gBattle*` structs of the dump before it (unused bytes), the blue units and the actor's sprite
 * handle of the capture at that moment. `AiTryDoStaff` runs for real on Soldiers with a lance (no
 * staff, so the staff functions are an EP-12 stand-in). Any other script command (EP-12) fails
 * the test. What is verified of the decision loop: it clears, sets, iterates, marks, suspends and
 * starts the perform proc around it. `AiCouldReachByBirdsEyeDistance` and
 * `FillMovementAndRangeMapForItem` (EP-7) are fed from the dump: the reach test says which
 * player unit is in reach, and the range map of the first player unit is the tiles where the
 * captured danger map is not 0, of the second the tiles where it is 23 (14 + 9). So what the
 * danger map verifies is the unit and weapon filters, the `(power + might) / 2` damage (14 and
 * 9: Seth's and Eirika's `pow` and best weapon), the sum over tiles, the clear per unit and the
 * flags; not the reach and the range themselves. The perform proc (EP-8) lives three frames;
 * `WriteSuspendSave` (EP-w) records the call. The call order is the EP-0 inventory (script A 3x,
 * script B once, for Red 0). The special item functions and the rogue part of `sub_8040C5C`
 * (EP-12) fail the test if entered. Only the `US_HAS_MOVED_AI` bit of the unit state is
 * compared: the hide and wait bits (0x1, 0x2) are set by the perform proc (EP-8, EP-9).
 */
class CpDecideDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    private inner class Sim {
        val run: ReferenceData.Run
        val rom: RomView
        val engine = ProcEngine()
        val playSt = PlaySt()
        val units: UnitTable
        val cp: CpPhase
        val active = ActiveUnit()
        val aiState: WatchTrace
        val decision: WatchTrace
        val red: WatchTrace
        val actionData: WatchTrace
        val rngWatch: WatchTrace
        val rng = RngService()
        val map: BmMap
        val gen: BattleGenerate
        val battle: BmBattle
        val log = ArrayList<String>()
        val errors = ArrayList<String>()
        var hold = 0

        init {
            assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
            assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
            run = ref.run(RUN)
            assumeTrue(run.hasWatch("aidecision"), "run has no aidecision watch")
            rom = RomView.ofRom(romFile.readBytes())
            units = UnitTable(rom)
            run.watch("blue").step(1611).copyInto(units.bytes, 0)
            run.watch("red").step(1611).copyInto(units.bytes, 62 * 0x48)
            playSt.decode(run.watch("gplayst").step(1611), 0)
            aiState = run.watch("aistate")
            decision = run.watch("aidecision")
            red = run.watch("red")
            actionData = run.watch("actiondata")
            rngWatch = run.watch("rng")
            rng.primary.load(rngWatch.u16(1611, 0), rngWatch.u16(1611, 2), rngWatch.u16(1611, 4))
            val bmSt = BmSt()
            map = BmMap(rom, ChapterData(rom), bmSt)
            map.initChapterMap(playSt.chapterIndex)
            val entityMaps = EntityBmMaps(map, units, playSt)
            val loader = UnitLoader(units, rom, { 0 }, rng, { false })
            cp = CpPhase(engine, rom, playSt, units, loader, map, entityMaps, active, rng)
            battle = BmBattle(rom, units, loader, playSt, bmSt) { y, x -> map.terrain[y, x] }
            gen = BattleGenerate(battle, rom, loader, playSt, rng, Mechanics()).also {
                it.bmMap = map
                it.unitTable = units
            }
            hook()
        }

        /** Installs the stand-ins: each records its call; script A sets the captured decision. */
        private fun hook() {
            val h = cp.decide.hooks
            // RefreshUnitSprites is the call right before AiUpdateNoMoveFlag: the loop state of
            // a unit that begins to decide
            h.refreshUnitSprites = AiStep {
                val id = active.unitId
                log += "update ${hex(id)}"
                when (id) {
                    0x81 -> compare(1612, "Red 0 before its decision")
                    0x82 -> {
                        compare(1613, "Soldier 1 before its decision")
                        compareDanger(1613, "r01613", "Soldier 1 before its decision")
                    }
                    0x83 -> {
                        compare(2037, "Soldier 2 before its decision")
                        // Soldier 1's battle (EP-9) drew from the RNG: take the captured state
                        rng.primary.load(
                            rngWatch.u16(2037, 0), rngWatch.u16(2037, 2), rngWatch.u16(2037, 4),
                        )
                        compareDanger(2037, null, "Soldier 2 before its decision")
                    }
                }
            }
            h.couldReachByBirdsEyeDistance = AiReachTest { a, other, _ ->
                // Red 0 is only in reach of the first player unit (EP-7 owns the real test)
                !(a == 0x81 && other == 2)
            }
            h.fillMovementAndRangeMapForItem = AiUnitItemStep { unit, _ ->
                // EP-7 owns the range map: here the tiles the captured danger map shows
                val shown = dangerDump(dumpOf(active.unitId))
                for (y in 0 until map.sizeY) {
                    for (x in 0 until map.sizeX) {
                        val v = shown[y * map.sizeX + x]
                        val reached = when (unit) {
                            1 -> v != 0
                            2 -> v == 23
                            else -> true
                        }
                        map.range[y, x] = if (reached) 1 else 0
                    }
                }
            }
            h.specialItemFunc = AiIntPairStep { _, _ ->
                errors += "special item function entered: not in the capture"
            }
            h.rogueSpecialItems = AiTry {
                errors += "rogue special items entered: not in the capture"
                false
            }
            val realA = h.tryExecScriptA!!
            h.tryExecScriptA = AiTry {
                val id = active.unitId
                log += "scriptA ${hex(id)}"
                if (id == 0x81) compareDanger(1612, "r01612", "Red 0 in script A")
                if (id == 0x83) {
                    compare(2038, "Soldier 2 in script A")
                    compareDanger(2038, "r02038", "Soldier 2 in script A")
                }
                realA.run()
            }
            // AiAttemptOffensiveAction (EP-5) runs for real. Its stand-ins: the movement map is
            // the dump's (EP-7), every unit is in reach (EP-7: Seth has no tile to attack from
            // either way), the battle is the real BattleGenerateSimulation (MC-9), and the score
            // of the battle (EP-6a/6b) is read from the exit dump of the same call.
            val b = cp.battle!!
            b.hooks.generateUnitMovementMap = AiUnitStep {
                val shown = poolDump(entryOf(active.unitId), GBM_MAP_MOVEMENT)
                for (y in 0 until map.sizeY) {
                    for (x in 0 until map.sizeX) map.movement[y, x] = shown[y * map.sizeX + x]
                }
            }
            b.hooks.reachesByBirdsEyeDistance = AiReachTest { _, _, _ -> true }
            b.hooks.battleGenerateSimulation = AiBattleSimulation { actor, target, x, y, slot ->
                // The unused bytes of the battle structs hold the previous battle's: start from
                // the dump of the frame before the decision
                val before = run.region(if (actor == 0x82) "r01612" else "r02038", "ewram")
                // and the blue units are the capture's of that moment (the phase start wrote
                // their ai_counter, which the port's table, loaded at f1611, does not have)
                val frame = if (actor == 0x82) 1613 else 2037
                run.watch("blue").step(frame).copyInto(units.bytes, 0)
                // as is the actor's pMapSpriteHandle (0x3C..0x3F), which the sprite code
                // reallocates after Soldier 1's death
                val own = (actor - 0x81) * RED_SIZE + OFF_SPRITE_HANDLE
                run.watch("red").step(frame).copyInto(
                    units.bytes, units.unitOffset(actor) + OFF_SPRITE_HANDLE, own, own + 4,
                )
                val actorAt = BATTLE_ACTOR - EWRAM
                val targetAt = BATTLE_TARGET - EWRAM
                before.copyInto(battle.actor.bytes, 0, actorAt, actorAt + BattleUnit.SIZE)
                before.copyInto(battle.target.bytes, 0, targetAt, targetAt + BattleUnit.SIZE)
                battle.stats.decode(before, BATTLE_STATS - EWRAM)
                gen.battleGenerateSimulation(
                    units.bytes, units.unitOffset(actor), units.unitOffset(target), x, y, slot,
                )
            }
            b.hooks.computeCombatScore = AiScoreStep { st ->
                val id = active.unitId
                log += "score ${hex(id)}"
                val entry = entryOf(id)
                // The inputs the port built: gAiState's RNG draw, the candidate, the range map
                // of the target, the simulated battle
                for (w in 0 until 3) {
                    assertEquals(
                        rngWatch.u16(if (id == 0x82) 1614 else 2038, 2 * w), rng.primary.word(w),
                        "gRNState word $w at the combat score of ${hex(id)}",
                    )
                }
                val iwram = run.region(entry, "iwram")
                val at = (if (id == 0x82) ST_1 else ST_2) - IWRAM
                assertEquals(iwram[at].toInt() and 0xFF, st.xMove, "st.xMove at $entry")
                assertEquals(iwram[at + 1].toInt() and 0xFF, st.yMove, "st.yMove at $entry")
                assertEquals(iwram[at + 2].toInt() and 0xFF, st.targetId, "st.targetId at $entry")
                assertEquals(
                    (iwram[at + 4].toInt() and 0xFF) or ((iwram[at + 5].toInt() and 0xFF) shl 8),
                    st.itemSlot, "st.itemSlot at $entry",
                )
                val range = poolDump(entry, GBM_MAP_RANGE)
                for (y in 0 until map.sizeY) {
                    for (x in 0 until map.sizeX) {
                        assertEquals(
                            range[y * map.sizeX + x], map.range[y, x],
                            "gBmMapRange ($x, $y) $entry",
                        )
                    }
                }
                compareBattle(entry)
                // EP-6a/6b stand-in: the score of the exit dump (6 for both calls)
                val exit = run.region(entry.replace("entry", "exit"), "iwram")
                st.score = (0 until 4).sumOf { (exit[at + 8 + it].toInt() and 0xFF) shl (8 * it) }
            }
            cp.script.hooks.unportedCommand = AiIntStep {
                errors += "unwritten script command ${hex(it)} entered: not in the capture"
            }
            val realB = h.tryExecScriptB!!
            h.tryExecScriptB = AiTry {
                log += "scriptB ${hex(active.unitId)}"
                realB.run()
            }
            h.healBranch = AiStep { errors += "healing branch entered: not in the capture" }
            h.escapeBranch = AiStep { errors += "escape branch entered: not in the capture" }
            val performScript = ProcScript(
                "gProcScr_CpPerform",
                listOf(
                    repeat {
                        hold++
                        if (hold == 2 && active.unitId == 0x83) compare(2741, "Soldier 2 acting")
                        if (hold >= 3) engine.breakProc(it)
                    },
                    end(),
                ),
            )
            h.startCpPerform = AiProcStep {
                val id = active.unitId
                log += "perform ${hex(id)}"
                if (id == 0x82) {
                    compare(1614, "Soldier 1 decided")
                    compareDanger(1614, "r01614", "Soldier 1 decided")
                }
                if (id == 0x83) compare(2039, "Soldier 2 decided")
                hold = 0
                engine.startBlocking(performScript, it)
            }
            h.writeSuspendSave = AiIntStep { slot ->
                log += "suspend ${hex(slot)}"
                val frame = if (log.count { it.startsWith("suspend") } == 1) 2035 else 2740
                assertEquals(
                    actionData.u8(frame, 0x16), active.action.suspendPointType,
                    "suspendPointType at f$frame",
                )
            }
        }

        fun hex(v: Int) = Integer.toHexString(v)

        fun dumpOf(unitId: Int) = when (unitId) {
            0x81 -> "r01612"
            0x82 -> "r01614"
            else -> "r02038"
        }

        /** `gBmMapOther` of the EWRAM dump [name], row by row (15 x 10). */
        fun dangerDump(name: String): IntArray {
            val ewram = run.region(name, "ewram")
            fun word(a: Int) =
                (0..3).sumOf { (ewram[a - EWRAM + it].toInt() and 0xFF) shl (8 * it) }
            val rows = word(GBM_MAP_OTHER)
            val out = IntArray(map.sizeX * map.sizeY)
            for (y in 0 until map.sizeY) {
                val row = word(rows + 4 * y)
                for (x in 0 until map.sizeX) {
                    out[y * map.sizeX + x] = ewram[row - EWRAM + x].toInt() and 0xFF
                }
            }
            return out
        }

        /** The `aicombatscore` entry dump of the call of unit [unitId]. */
        fun entryOf(unitId: Int) =
            if (unitId == 0x82) "call_aicombatscore_1_entry" else "call_aicombatscore_2_entry"

        /** A `gBmMap*` pool of the dump [name] (EWRAM or IWRAM by the address), 15 x 10. */
        fun poolDump(name: String, addr: Int): IntArray {
            val ewram = run.region(name, "ewram")
            val iwram = run.region(name, "iwram")
            fun byte(a: Int) =
                (if (a >= IWRAM) iwram[a - IWRAM] else ewram[a - EWRAM]).toInt() and 0xFF
            fun word(a: Int) = (0..3).sumOf { byte(a + it) shl (8 * it) }
            val rows = word(addr)
            val out = IntArray(map.sizeX * map.sizeY)
            for (y in 0 until map.sizeY) {
                val row = word(rows + 4 * y)
                for (x in 0 until map.sizeX) out[y * map.sizeX + x] = byte(row + x)
            }
            return out
        }

        /** Compares the simulated battle with `gBattleActor`, `gBattleTarget`, `gBattleStats`. */
        fun compareBattle(name: String) {
            val ewram = run.region(name, "ewram")
            for ((bu, at) in listOf(battle.actor to BATTLE_ACTOR, battle.target to BATTLE_TARGET)) {
                for (i in 0 until BattleUnit.SIZE) {
                    assertEquals(
                        ewram[at - EWRAM + i].toInt() and 0xFF, bu.bytes[i].toInt() and 0xFF,
                        "battle unit ${at.toString(16)} +0x${i.toString(16)} at $name",
                    )
                }
            }
            val stats = ByteArray(BattleStats.SIZE)
            battle.stats.encode(stats, 0)
            for (i in stats.indices) {
                assertEquals(
                    ewram[BATTLE_STATS - EWRAM + i].toInt() and 0xFF, stats[i].toInt() and 0xFF,
                    "gBattleStats +0x${i.toString(16)} at $name",
                )
            }
            for (i in 0 until BattleGenerate.BATTLE_HIT_MAX) {
                val at = HIT_ARRAY - EWRAM + 4 * i
                val want = (0..3).sumOf { (ewram[at + it].toInt() and 0xFF) shl (8 * it) }
                assertEquals(want, gen.hitArray[i], "gBattleHitArray[$i] at $name")
            }
        }

        /** Compares `dangerMapFilled` and, with a [dump], `gBmMapOther` with the capture. */
        fun compareDanger(frame: Int, dump: String?, what: String) {
            val at = "$what (f$frame)"
            assertEquals(
                aiState.u8(frame, 0x7A) != 0, cp.aiState.dangerMapFilled, "dangerMapFilled $at",
            )
            if (dump == null) return
            val shown = dangerDump(dump)
            for (y in 0 until map.sizeY) {
                for (x in 0 until map.sizeX) {
                    assertEquals(
                        shown[y * map.sizeX + x], map.other[y, x], "gBmMapOther ($x, $y) $at",
                    )
                }
            }
        }

        /** Compares the loop state of the port with the capture at step [frame]. */
        fun compare(frame: Int, what: String, orderAndFlags: Boolean = true) {
            val s = cp.aiState
            val at = "$what (f$frame)"
            assertEquals(aiState.u32(frame, 0x74) - AI_STATE_ADDRESS, s.unitIt, "unitIt $at")
            if (orderAndFlags) {
                assertEquals(aiState.u8(frame, 0x78), s.orderState, "orderState $at")
                assertEquals(aiState.u8(frame, 0x7B), s.flags, "flags $at")
            }
            assertEquals(aiState.u8(frame, 0x79), s.decideState, "decideState $at")
            assertEquals(aiState.u8(frame, 0x7C), s.unk7C, "unk7C $at")
            assertEquals(aiState.u8(frame, 0x7D), s.combatWeightTableId, "combatWeight $at")
            val d = cp.decide.decision
            val ours = intArrayOf(
                d.actionId, d.unitId, d.xMove, d.yMove, d.unk04, d.unk05, d.targetId, d.itemSlot,
                d.xTarget, d.yTarget, if (d.actionPerformed) 1 else 0,
            )
            for (i in ours.indices) {
                assertEquals(decision.u8(frame, i), ours[i], "gAiDecision byte $i $at")
            }
            assertEquals(aiState.u8(frame, 0x7E), s.unk7E, "unk7E $at")
            for (i in 0 until 8) {
                assertEquals(
                    aiState.u8(frame, 0x86 + i), s.cmdResult[i].toInt() and 0xFF,
                    "cmd_result[$i] $at",
                )
            }
            for (u in 0 until 3) {
                val id = 0x81 + u
                assertEquals(red.u8(frame, u * 0x48 + 0x42), units.ai1(id), "ai1 of ${hex(id)} $at")
                assertEquals(
                    red.u8(frame, u * 0x48 + 0x43), units.aiAPc(id), "ai_a_pc of ${hex(id)} $at",
                )
                assertEquals(red.u8(frame, u * 0x48 + 0x44), units.ai2(id), "ai2 of ${hex(id)} $at")
                assertEquals(
                    red.u8(frame, u * 0x48 + 0x45), units.aiBPc(id), "ai_b_pc of ${hex(id)} $at",
                )
            }
            for (u in 0 until 3) {
                val captured = red.u32(frame, u * 0x48 + 0x0C) and HAS_MOVED_AI
                val id = 0x81 + u
                assertEquals(
                    captured, units.state(id) and HAS_MOVED_AI, "US_HAS_MOVED_AI of ${hex(id)} $at",
                )
            }
        }

        fun frame() {
            for (t in 0 until 8) engine.run(engine.root(t))
        }
    }

    @Test
    @VerifiesPort(
        "CpDecide_Main", "CpDecide_Suspend", "AiClearDecision", "AiSetDecision", "AiDecideMain",
        "AiUpdateNoMoveFlag", "AiInitDangerMap", "AiFillDangerMap",
        "AiTryExecScriptA", "AiTryExecScriptB", "AiScript_Exec",
        "AiScriptCmd_05_DoStandardAction", "AiScriptCmd_06_DoNothing", "AiScriptCmd_0E_DoNothing",
        "AiAttemptOffensiveAction", "AiFillReversedAttackRangeMap",
        "AiSimulateBestBattleAgainstTarget", "AiSimulateBattleAgainstTargetAtPosition",
        "AiIsBadFight", "AiTryDoStaff",
    )
    fun `the decision loop decides for the three enemies as the capture`() {
        val sim = Sim()
        assertEquals(0x80, sim.playSt.faction)
        val parent = sim.engine.start(ProcScript("parent", listOf(repeat { }, end())), 4)
        sim.engine.startBlocking(sim.cp.phaseScript, parent)
        var frames = 0
        while (sim.engine.find(sim.cp.decide.script) == null && frames < 5) {
            sim.frame()
            frames++
        }
        while (sim.engine.find(sim.cp.decide.script) != null && frames < 60) {
            sim.frame()
            frames++
        }
        // CpDecide has just ended (f2742: decideState 0, the last decision stays)
        sim.compare(2742, "after the last unit", orderAndFlags = false)
        while (sim.engine.find(sim.cp.phaseScript) != null && frames < 60) {
            sim.frame()
            frames++
        }
        assertTrue(sim.errors.isEmpty(), sim.errors.toString())
        assertNull(sim.engine.find(sim.cp.decide.script), "CpDecide ended")
        assertEquals(
            listOf(
                "update 81", "scriptA 81", "scriptB 81",
                "update 82", "scriptA 82", "score 82", "perform 82", "suspend 3",
                "update 83", "scriptA 83", "score 83", "perform 83", "suspend 3",
            ),
            sim.log,
            "the decision calls in order (EP-0 inventory)",
        )
        assertEquals(sim.aiState.u8(2744, 0x78), sim.cp.aiState.orderState, "orderState f2744")
        assertEquals(sim.aiState.u8(2746, 0x7B), sim.cp.aiState.flags, "flags f2746")
    }
}

private const val RUN = "ram_trace_prologue_enemy_phase"
private const val AI_STATE_ADDRESS = 0x0203AA04
private const val HAS_MOVED_AI = 1 shl 10
private const val EWRAM = 0x02000000
private const val GBM_MAP_OTHER = 0x0202E4F0
private const val IWRAM = 0x03000000
private const val GBM_MAP_MOVEMENT = 0x0202E4E0
private const val GBM_MAP_RANGE = 0x0202E4E4
private const val BATTLE_STATS = 0x0203A4D4
private const val BATTLE_ACTOR = 0x0203A4EC
private const val BATTLE_TARGET = 0x0203A56C
private const val HIT_ARRAY = 0x0203A5EC
private const val ST_1 = 0x03007C90
private const val ST_2 = 0x03007CAC
private const val RED_SIZE = 0x48
private const val OFF_SPRITE_HANDLE = 0x3C
