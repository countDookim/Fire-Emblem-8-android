package fe8.testing

import fe8.game.RomView
import fe8.game.bm.ActiveUnit
import fe8.game.bm.AiIntStep
import fe8.game.bm.AiIntPairStep
import fe8.game.bm.AiProcStep
import fe8.game.bm.AiReachTest
import fe8.game.bm.AiStep
import fe8.game.bm.AiTestStep
import fe8.game.bm.AiTry
import fe8.game.bm.AiUnitItemStep
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
 * of `AiAttemptOffensiveAction` (one `NextRN_N(100)` draw of command 05 after the captured state of f1611 / f2037; the
 * battle between them is EP-9's, so Soldier 2 starts from the f2037 state).
 *
 * Stand-ins (the other tasks own them): `AiAttemptOffensiveAction` (EP-5) is fed from the
 * capture: it calls `AiSetDecision` with the values read from the captured decision bytes of
 * f1614 and f2039, so the decision content (attack Eirika from (5, 5)) is NOT verified here; it
 * also checks that the captured target passes the `AiIsUnitEnemy` predicate it is handed.
 * `AiTryDoStaff` (EP-5) finds no staff (null hook). Any other script command (EP-12) fails the
 * test. What is verified of the decision loop: it clears, sets, iterates, marks, suspends and
 * starts the perform proc around it, and `AiSetDecision` packs the arguments as the dump bytes (the unit id comes from the active unit, not from the dump; `xMove`/`yMove` are both 5
 * and `xTarget`/`yTarget`/`itemSlot` are 0 in the capture, so only a swap of the target and
 * slot, or a wrong action, is seen). `AiCouldReachByBirdsEyeDistance` and
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
            map = BmMap(rom, ChapterData(rom), BmSt())
            map.initChapterMap(playSt.chapterIndex)
            val entityMaps = EntityBmMaps(map, units, playSt)
            val loader = UnitLoader(units, rom, { 0 }, rng, { false })
            cp = CpPhase(engine, rom, playSt, units, loader, map, entityMaps, active, rng)
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
            // The only stand-in left inside the script: AiAttemptOffensiveAction (EP-5) sets the
            // decision from the captured bytes. The RNG state at its call is the capture after
            // the one draw of AiScriptCmd_05 (f1614 for Soldier 1; f2038 for Soldier 2, whose
            // search draws once more at f2039).
            cp.script.hooks.attemptOffensiveAction = AiTestStep { test ->
                val id = active.unitId
                log += "offensive ${hex(id)}"
                val frame = if (id == 0x82) 1614 else 2039
                val rngFrame = if (id == 0x82) 1614 else 2038
                for (w in 0 until 3) {
                    assertEquals(
                        rngWatch.u16(rngFrame, 2 * w), rng.primary.word(w),
                        "gRNState word $w at the offensive action of ${hex(id)} (f$rngFrame)",
                    )
                }
                val d = { off: Int -> decision.u8(frame, off) }
                assertTrue(
                    test.run(d(6)),
                    "captured target ${d(6)} of ${hex(id)} is an enemy (f$frame)",
                )
                cp.decide.aiSetDecision(d(2), d(3), d(0), d(6), d(7), d(8), d(9))
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
                "update 82", "scriptA 82", "offensive 82", "perform 82", "suspend 3",
                "update 83", "scriptA 83", "offensive 83", "perform 83", "suspend 3",
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
