package fe8.testing

import fe8.game.RomView
import fe8.game.bm.ActiveUnit
import fe8.game.bm.AiIntStep
import fe8.game.bm.AiProcStep
import fe8.game.bm.AiStep
import fe8.game.bm.AiTry
import fe8.game.bm.AiUnitStep
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
 * The AI decision loop (EP-3a) against the `aistate`, `aidecision`, `red`, `blue`, `gplayst` and
 * `actiondata` watches of `ram_trace_prologue_enemy_phase`: the enemy phase of f1611 runs through
 * `gProcScr_CpPhase` > `CpOrder` > `CpDecide`, and at each point where the decision loop calls a
 * stand-in the port's state is compared with the capture frame that shows the same moment (the
 * real CPU needs more than a frame per unit, so a frame snapshot lies inside `CpDecide_Main`):
 *
 * - Red 0's `AiUpdateNoMoveFlag` call = f1612 (decision zero, `unitIt` at the start, `decideState`
 *   0, nobody marked);
 * - Soldier 1's `AiUpdateNoMoveFlag` call = f1613 (Red 0 marked `US_HAS_MOVED_AI`, `unitIt` 5);
 * - Soldier 1's perform start = f1614 (decision `0x82` attack, `decideState` 2, `unitIt` 6);
 * - Soldier 2's `AiUpdateNoMoveFlag` call = f2037 (`decideState` 0, the old decision still set);
 * - Soldier 2's script A call = f2038 (decision cleared, `decideState` 2);
 * - Soldier 2's perform start = f2039 (decision `0x83` attack, `unitIt` 7);
 * - the end of the proc = f2742 (`decideState` 0).
 *
 * Stand-ins (the other tasks own them): `AiTryExecScriptA`/`B` (EP-4) call `AiSetDecision` with the
 * values read from the captured decision bytes of f1614 and f2039, so the script's choice is not
 * verified here, only that the decision loop clears, sets, iterates, marks, suspends and starts
 * the perform proc around it, and that `AiSetDecision` packs the arguments as the dump bytes
 * (the unit id comes from the active unit, not from the dump; `xMove`/`yMove` are both 5 and
 * `xTarget`/`yTarget`/`itemSlot` are 0 in the capture, so only a swap of the target and slot, or
 * a wrong action, is seen). `AiUpdateNoMoveFlag`, `AiInitDangerMap`, `DecideSpecialItems` (EP-3b)
 * only record the call; the perform proc (EP-8) lives three frames; `WriteSuspendSave` (EP-w)
 * records the call. The call order is the EP-0 inventory (script A 3x, script B and special
 * items once, for Red 0). Only the `US_HAS_MOVED_AI` bit of the unit state is compared: the
 * hide and wait bits (0x1, 0x2) are set by the perform proc (EP-8, EP-9).
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
            val map = BmMap(rom, ChapterData(rom), BmSt())
            map.initChapterMap(playSt.chapterIndex)
            val entityMaps = EntityBmMaps(map, units, playSt)
            val loader = UnitLoader(units, rom, { 0 }, RngService(), { false })
            cp = CpPhase(engine, rom, playSt, units, loader, map, entityMaps, active)
            hook()
        }

        /** Installs the stand-ins: each records its call; script A sets the captured decision. */
        private fun hook() {
            val h = cp.decide.hooks
            h.updateNoMoveFlag = AiUnitStep { id ->
                log += "update ${hex(id)}"
                when (id) {
                    0x81 -> compare(1612, "Red 0 before its decision")
                    0x82 -> compare(1613, "Soldier 1 before its decision")
                    0x83 -> compare(2037, "Soldier 2 before its decision")
                }
            }
            h.initDangerMap = AiStep { cp.aiState.dangerMapFilled = true }
            h.tryExecScriptA = AiTry {
                val id = active.unitId
                log += "scriptA ${hex(id)}"
                if (id == 0x83) compare(2038, "Soldier 2 in script A")
                val frame = if (id == 0x82) 1614 else if (id == 0x83) 2039 else 0
                if (frame != 0) {
                    val d = { off: Int -> decision.u8(frame, off) }
                    cp.decide.aiSetDecision(d(2), d(3), d(0), d(6), d(7), d(8), d(9))
                }
                true
            }
            h.tryExecScriptB = AiTry {
                log += "scriptB ${hex(active.unitId)}"
                true
            }
            h.decideSpecialItems = AiStep { log += "special ${hex(active.unitId)}" }
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
                if (id == 0x82) compare(1614, "Soldier 1 decided")
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
                "update 81", "scriptA 81", "scriptB 81", "special 81",
                "update 82", "scriptA 82", "perform 82", "suspend 3",
                "update 83", "scriptA 83", "perform 83", "suspend 3",
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
