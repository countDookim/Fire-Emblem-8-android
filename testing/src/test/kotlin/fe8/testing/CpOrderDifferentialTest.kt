package fe8.testing

import fe8.game.RomView
import fe8.game.bm.AiStep
import fe8.game.bm.CpPhase
import fe8.game.bm.PlaySt
import fe8.game.bm.UnitLoader
import fe8.game.bm.UnitTable
import gba.core.VerifiesPort
import gbafe.core.rng.RngService
import gba.testkit.ReferenceData
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.repeat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The AI order of the Prologue enemy phase (EP-2a) against the `aistate`, `blue`, `red`,
 * `gplayst` and `procs` watches of `ram_trace_prologue_enemy_phase`: the units, `gPlaySt` and
 * `gAiState` of f1611 are fed to `gProcScr_CpPhase`, and the first frame must give the state of
 * f1612 (unit list 0x81, 0x82, 0x83, `unitIt` at the start, `orderState` 1, flags, `unk7E`, the
 * special item flags) and the proc tree CpPhase > CpOrder > CpDecide. The state is read at the
 * first `RefreshUnitSprites` call of the real `CpDecide` (its default stand-ins decide nothing);
 * then the order ends (`orderState` 2) and the cleanup clears the flags, as at f2744..f2746.
 */
class CpOrderDifferentialTest {
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
        val ai = ref.run(RUN).watch("aistate")

        init {
            assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
            assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
            run = ref.run(RUN)
            assumeTrue(run.hasWatch("aistate"), "run has no aistate watch")
            rom = RomView.ofRom(romFile.readBytes())
            units = UnitTable(rom)
            val loader = UnitLoader(units, rom, { 0 }, RngService(), { false })
            cp = CpPhase(engine, rom, playSt, units, loader)
        }

        fun seed(step: Int) {
            run.watch("blue").step(step).copyInto(units.bytes, 0)
            run.watch("red").step(step).copyInto(units.bytes, 62 * 0x48)
            playSt.decode(run.watch("gplayst").step(step), 0)
        }

        fun frame() {
            for (t in 0 until 8) engine.run(engine.root(t))
        }
    }

    @Test
    @VerifiesPort("AiPhaseInit", "CpOrderMain", "CpOrderFunc_BeginDecide", "BuildAiUnitList")
    fun `the first frame of the enemy phase builds the unit list and starts CpDecide`() {
        val sim = Sim()
        sim.seed(1611)
        assertEquals(0x80, sim.playSt.faction)
        // f1611: nothing is built yet; f1612: the list, unitIt, orderState, flags
        assertEquals(listOf(0, 0, 0), (0..2).map { sim.ai.u8(1611, it) })
        val parent = sim.engine.start(ProcScript("parent", listOf(repeat { }, end())), 4)
        val phase = sim.engine.startBlocking(sim.cp.phaseScript, parent)
        assertNotNull(phase)
        val state = sim.cp.aiState
        var checked = false
        // The first `RefreshUnitSprites` call is the first moment CpDecide decides: the state
        // there is the state of f1612 (the real CPU needs more than a frame for the loop).
        sim.cp.decide.hooks.refreshUnitSprites = AiStep {
            if (!checked) {
                checked = true
                assertEquals(
                    (0..3).map { sim.ai.u8(1612, it) },
                    (0..3).map { state.units[it].toInt() and 0xFF },
                )
                assertEquals(0, state.unitIt)
                assertEquals(0x0203AA04, sim.ai.u32(1612, 0x74), "unitIt points at the list")
                assertEquals(sim.ai.u8(1612, 0x78), state.orderState)
                assertEquals(sim.ai.u8(1612, 0x7B), state.flags)
                assertEquals(sim.ai.u8(1612, 0x7E), state.unk7E)
                assertEquals(sim.ai.u32(1612, 0x80), state.specialItemFlags)
                assertEquals(sim.ai.u8(1612, 0x84), state.unk84)
                assertEquals(
                    (0 until 8).map { sim.ai.u8(1612, 0x86 + it) },
                    state.cmdResult.map { it.toInt() },
                )
                // the proc tree: CpPhase > CpOrder > CpDecide
                val order = sim.engine.find(sim.cp.orderScript)
                assertNotNull(order)
                assertNotNull(sim.engine.find(sim.cp.decide.script))
                assertEquals(phase, order!!.parent)
                assertEquals(order, sim.engine.find(sim.cp.decide.script)!!.parent)
            }
        }
        sim.frame()
        assertTrue(checked, "CpDecide started in the first frame")

        // the decision finishes: the order ends (orderState 2) and the phase clears the flags
        for (i in 0 until 6) sim.frame()
        assertNull(sim.engine.find(sim.cp.orderScript))
        assertNull(sim.engine.find(sim.cp.phaseScript))
        assertEquals(sim.ai.u8(2744, 0x78), state.orderState)
        assertEquals(sim.ai.u8(2746, 0x7B), state.flags)
    }

    @Test
    fun `the Prologue enemies have equal priorities, so the sort keeps the list order`() {
        val sim = Sim()
        sim.seed(1611)
        // mov 5 + 20 (a melee weapon) for all three: only the stability of the sort shows
        val priorities = (0x81..0x83).map { sim.cp.getUnitAiPriority(it) }
        assertEquals(listOf(25, 25, 25), priorities)
        assertEquals(listOf(20, 20, 20), (0x81..0x83).map { sim.cp.getUnitBattleAiPriority(it) })
    }
}

private const val RUN = "ram_trace_prologue_enemy_phase"
