package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BMapMain
import fe8.game.bm.BattleMapNodes
import fe8.game.bm.BmSt
import fe8.game.bm.CamMove
import fe8.game.bm.ChapterData
import fe8.game.bm.PhaseStartEnd
import fe8.game.bm.PlaySt
import fe8.game.bm.ResetCursorPosition
import fe8.game.bm.SelectTargets
import fe8.game.bm.UnitTable
import fe8.game.flags.Fe8EventFlags
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.block
import gbafe.proc.ProcScripts.end
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The start and end of a phase of the Prologue enemy phase (EP-1) against the per-frame
 * `blue`, `red`, `gplayst`, `gbmst` and `procs` watches of `ram_trace_prologue_enemy_phase`:
 * the unit state before and after `ClearActiveFactionGrayedStates` (f1529 blue to red, f2750 red
 * to green, f2768 green to blue) with the favour change in `gPidStatsData` (dumps f1520, f1530);
 * the four display `_Init` functions (no display proc is ever alive, the target lists are empty);
 * `TickActiveFactionTurn` (f1607, f2754, f2846: nothing changes); the phase switches; and the
 * frames from the tick to the phase proc for the red, green and blue phase start (the tick frame
 * is the one of the function-entry trace in docs/plans/M6C-J-breakdown.md "EP-0 inventory").
 */
class PhaseStartEndDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )
    private val runName = "ram_trace_prologue_enemy_phase"

    private val noNodes = object : BattleMapNodes {
        override fun chapterId(node: Int) = -1
        override fun isCleared(node: Int) = false
        override fun encounters(node: Int) = 0
        override fun placementFlag(node: Int) = 0
    }

    private inner class Sim {
        val run: ReferenceData.Run
        val rom: RomView
        val engine = ProcEngine()
        val playSt = PlaySt()
        val units: UnitTable
        val targets = SelectTargets()
        val phase: PhaseStartEnd

        init {
            assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
            assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
            run = ref.run(runName)
            assumeTrue(run.hasWatch("red"), "run has no red watch")
            rom = RomView.ofRom(romFile.readBytes())
            units = UnitTable(rom)
            phase = PhaseStartEnd(engine, units, playSt, targets)
        }

        val blue = run.watch("blue")
        val red = run.watch("red")
        val playStTrace = run.watch("gplayst")

        fun seedUnits(step: Int, into: UnitTable = units) {
            blue.step(step).copyInto(into.bytes, 0)
            red.step(step).copyInto(into.bytes, RED_OFFSET)
        }

        fun seedPlaySt(step: Int) = playSt.decode(playStTrace.step(step), 0)

        fun faction(step: Int) = playStTrace.u8(step, 0x0F)

        /** The whole arrays, or only the state words if [stateOnly] (support exp changes f2768). */
        fun assertUnits(step: Int, message: String, stateOnly: Boolean = false) {
            if (stateOnly) {
                for (i in 0 until 62 + 50) {
                    val at = i * 0x48 + 0x0C
                    val expected = if (i < 62) blue else red
                    val index = if (i < 62) i else i - 62
                    if (i < 62 && index >= 2) continue
                    for (b in 0 until 4) {
                        assertEquals(
                            expected.u8(step, index * 0x48 + 0x0C + b),
                            units.bytes[at + b].toInt() and 0xFF,
                            "state unit slot $i $message",
                        )
                    }
                }
                return
            }
            assertArrayEquals(
                blue.step(step), units.bytes.copyOfRange(0, BLUE_SIZE), "blue $message",
            )
            assertArrayEquals(
                red.step(step),
                units.bytes.copyOfRange(RED_OFFSET, RED_OFFSET + RED_SIZE),
                "red $message",
            )
        }
    }

    @Test
    @VerifiesPort("ClearActiveFactionGrayedStates")
    fun `ClearActiveFactionGrayedStates matches the units at the three phase ends`() {
        val sim = Sim()
        // (step before the call, step after): faction 0 (blue branch), 0x80, 0x40
        for ((before, factionBefore) in listOf(1528 to 0, 2749 to 0x80, 2767 to 0x40)) {
            sim.seedUnits(before)
            sim.seedPlaySt(before)
            assertEquals(factionBefore, sim.playSt.faction, "faction f$before")
            val pids = ArrayList<Int>()
            sim.phase.hooks.pidStatsSubFavval08 = PhaseStartEnd.PidAction { pids.add(it) }
            sim.phase.clearActiveFactionGrayedStates()
            sim.assertUnits(before + 1, "after f${before + 1}", stateOnly = before == 2767)
            if (factionBefore == 0) {
                // Seth (pid 2) and Eirika (pid 1) are the two units that lose favour
                assertEquals(listOf(2, 1), pids, "favour calls f$before")
            } else {
                assertTrue(pids.isEmpty(), "no favour call for faction $factionBefore")
            }
        }
        // red unit 0 (id 0x81) had US_HAS_MOVED_AI before f2750: the clearing is seen
        sim.seedUnits(2749)
        val redZero = sim.units.state(0x81)
        assertNotEquals(0, redZero, "red unit 0 state before f2750")
        sim.seedUnits(2750)
        assertEquals(0, sim.units.state(0x81), "red unit 0 state at f2750")
    }

    @Test
    @VerifiesPort("ClearActiveFactionGrayedStates")
    fun `the favour of Seth and Eirika drops by 8 across the blue phase end`() {
        val sim = Sim()
        val dump1520 = sim.run.dumps.entries.first { it.value == 1520 }.key
        val dump1530 = sim.run.dumps.entries.first { it.value == 1530 }.key
        val before = sim.run.region(dump1520, "ewram")
        val after = sim.run.region(dump1530, "ewram")
        val stats = before.copyOfRange(PID_STATS - EWRAM, PID_STATS - EWRAM + PID_STATS_SIZE)
        sim.seedUnits(1528)
        sim.seedPlaySt(1528)
        sim.phase.hooks.pidStatsSubFavval08 = PhaseStartEnd.PidAction { pid ->
            // favval: u16 at byte 1 of the 16 byte entry of pid - 1 (`PidStatsAddFavval(pid, -8)`)
            val at = (pid - 1) * 16 + 1
            val value = (stats[at].toInt() and 0xFF) or ((stats[at + 1].toInt() and 0xFF) shl 8)
            val next = value - 8
            stats[at] = next.toByte()
            stats[at + 1] = (next shr 8).toByte()
        }
        sim.phase.clearActiveFactionGrayedStates()
        assertArrayEquals(
            after.copyOfRange(PID_STATS - EWRAM, PID_STATS - EWRAM + PID_STATS_SIZE),
            stats,
            "gPidStatsData f1530",
        )
        assertNotEquals(
            before.copyOfRange(PID_STATS - EWRAM, PID_STATS - EWRAM + PID_STATS_SIZE).toList(),
            stats.toList(),
        )
    }

    @Test
    fun `TickActiveFactionTurn changes nothing at the three phase starts`() {
        val sim = Sim()
        for ((step, factionAt) in listOf(1606 to 0x80, 2753 to 0x40, 2845 to 0)) {
            sim.seedUnits(step)
            sim.seedPlaySt(step)
            assertEquals(factionAt, sim.playSt.faction, "faction f$step")
            sim.phase.tickActiveFactionTurn()
            sim.assertUnits(step + 1, "f${step + 1}")
            assertEquals(0, sim.targets.getSelectTargetCount(), "no status ran out f$step")
        }
    }

    @Test
    @VerifiesPort(
        "StatusDecayDisplay_Init", "TerrainHealDisplay_Init", "PoisonDamageDisplay_Init",
        "GorgonEggHatchDisplay_Init",
    )
    fun `the four display inits end their proc at once on an empty list as in the capture`() {
        val sim = Sim()
        val names = ProcArrayDump.romNames()
        assumeTrue(names.isNotEmpty(), "decomp nm file not present")
        val trace = sim.run.watch("procs")
        // no display proc is alive at the end of any frame of the three phase starts
        val displays = setOf(
            "gProcScr_StatusDecayDisplay", "gProcScr_TerrainHealDisplay",
            "gProcScr_PoisonDamageDisplay", "gProcScr_GorgonEggHatchDisplay",
        )
        for (step in listOf(1606..1612, 2753..2759, 2845..2851).flatMap { it.toList() }) {
            val alive = ProcArrayDump.ofWatch(trace, step, names).live.mapNotNull { it.name }
            assertTrue(alive.none { it in displays }, "display alive f$step: $alive")
        }
        val calls = ArrayList<String>()
        val h = sim.phase.hooks
        h.makeTerrainHealTargetList = PhaseStartEnd.FactionAction {
            calls += "terrain"
            sim.targets.initTargets(0, 0)
        }
        h.makePoisonDamageTargetList = PhaseStartEnd.FactionAction {
            calls += "poison"
            sim.targets.initTargets(0, 0)
        }
        h.preparePoisonDefeats = Runnable { calls += "poisonDefeats" }
        h.makeGorgonEggHatchTargetList = PhaseStartEnd.FactionAction {
            calls += "gorgon"
            sim.targets.initTargets(0, 0)
        }
        val idle = ProcScript("idle", listOf(block(), end()))
        val inits = listOf<(Proc) -> Unit>(
            sim.phase::statusDecayDisplayInit, sim.phase::terrainHealDisplayInit,
            sim.phase::poisonDamageDisplayInit, sim.phase::gorgonEggHatchDisplayInit,
        )
        for ((step, factionAt) in listOf(1606 to 0x80, 2753 to 0x40, 2845 to 0)) {
            sim.seedUnits(step)
            sim.seedPlaySt(step)
            assertEquals(factionAt, sim.playSt.faction)
            // the unit data of the window has no status at all: every list is empty
            for (id in listOf(1, 2, 0x81, 0x82, 0x83)) {
                if (sim.units.isValid(id)) assertEquals(0, sim.units.statusIndex(id), "id $id")
            }
            calls.clear()
            for ((i, init) in inits.withIndex()) {
                val proc = sim.engine.start(idle, 5)
                init(proc)
                assertNull(sim.engine.find(idle), "init $i ended its proc f$step")
            }
            assertEquals(listOf("terrain", "poison", "poisonDefeats", "gorgon"), calls)
        }
    }

    @Test
    @VerifiesPort("SwitchPhases", "BmMain_ChangePhase")
    fun `the three phase switches and the turn number match gPlaySt`() {
        val sim = Sim()
        val assets = ResetAssets(sim)
        val main = assets.main
        val calls = ArrayList<String>()
        main.hooks.clearActiveFactionGrayedStates = { calls += "clear" }
        main.hooks.refreshUnitSprites = { calls += "refresh" }
        main.hooks.processTurnSupportExp = { calls += "support" }
        // blue to red (f1529), red to green (f2750), green to blue with turn 2 (f2768)
        for (step in listOf(1529, 2750, 2768)) {
            sim.seedPlaySt(step - 1)
            main.changePhase()
            val expected = PlaySt().also { it.decode(sim.playStTrace.step(step), 0) }
            assertEquals(expected.faction, sim.playSt.faction, "faction f$step")
            assertEquals(expected.chapterTurnNumber, sim.playSt.chapterTurnNumber, "turn f$step")
        }
        assertEquals(2, sim.playSt.chapterTurnNumber)
        assertEquals(
            listOf("clear", "refresh", "clear", "refresh", "clear", "refresh", "support"), calls,
        )
    }

    @Test
    @VerifiesPort("BmMain_UpdateTraps")
    fun `UpdateTraps starts the trap proc only after the green phase`() {
        val sim = Sim()
        val names = ProcArrayDump.romNames()
        assumeTrue(names.isNotEmpty(), "decomp nm file not present")
        val trace = sim.run.watch("procs")
        val main = ResetAssets(sim).main
        val traps = ProcScript("traps", listOf(block(), end()))
        main.hooks.updateTraps = traps
        var decays = 0
        main.hooks.decayTraps = { decays++ }
        val parent = ProcScript("parent", listOf(block(), end()))
        // the capture: gProcScr_UpdateTraps is alive at f2766 only (after green), not at f2745
        fun trapsAlive(step: Int) =
            ProcArrayDump.ofWatch(trace, step, names).live.any { it.name == "gProcScr_UpdateTraps" }
        for (step in 2735..2770) {
            if (step in 2742..2765) continue
            assertEquals(step == 2766, trapsAlive(step), "UpdateTraps proc f$step")
        }
        for ((faction, proc) in listOf(0x80 to false, 0x40 to true)) {
            sim.playSt.faction = faction
            val p = sim.engine.start(parent, 6)
            assertEquals(!proc, main.updateTraps(p), "result faction $faction")
            assertEquals(proc, sim.engine.find(traps) != null, "trap proc faction $faction")
            assertEquals(if (proc) 1 else 0, decays)
            sim.engine.end(sim.engine.find(traps))
            sim.engine.end(p)
            decays = 0
        }
    }

    @Test
    @VerifiesPort("sub_8015434", "BmMain_StartPhase")
    fun `the phase proc starts five frames after the tick in the red, green and blue phase`() {
        val names = ProcArrayDump.romNames()
        assumeTrue(names.isNotEmpty(), "decomp nm file not present")
        // (tick step of the function-entry trace, faction at that step, phase proc script)
        val windows = listOf(
            Triple(1607, 0x80, "gProcScr_CpPhase"),
            Triple(2754, 0x40, "gProcScr_CpPhase"),
            Triple(2846, 0, "gProcScr_PlayerPhase"),
        )
        for ((tick, factionAt, phaseName) in windows) {
            val sim = Sim()
            val trace = sim.run.watch("procs")
            var first = tick
            while (ProcArrayDump.ofWatch(trace, first, names).live.none { it.name == phaseName }) {
                first++
            }
            val expected = first - tick
            assertEquals(5, expected, "capture: $phaseName first alive f$first")

            sim.seedUnits(tick - 1)
            sim.seedPlaySt(tick - 1)
            assertEquals(factionAt, sim.playSt.faction)
            val assets = ResetAssets(sim)
            val main = assets.main
            val startEnd = PhaseStartEnd(sim.engine, sim.units, sim.playSt, SelectTargets())
            var tickFrame = -1
            var frame = 0
            main.hooks.tickActiveFactionTurn = {
                if (tickFrame < 0) tickFrame = frame
                startEnd.tickActiveFactionTurn()
            }
            main.hooks.statusDecayDisplay = startEnd.statusDecayDisplay
            main.hooks.terrainHealDisplay = startEnd.terrainHealDisplay
            main.hooks.poisonDamageDisplay = startEnd.poisonDamageDisplay
            main.hooks.gorgonEggHatchDisplay = startEnd.gorgonEggHatchDisplay
            val phaseScript =
                if (phaseName == "gProcScr_CpPhase") main.hooks.cpPhase else main.hooks.playerPhase
            val mainProc = sim.engine.start(main.script, 2)
            sim.engine.goto(mainProc, 9)
            var started = -1
            while (frame < 40 && started < 0) {
                frame++
                for (t in 0 until 8) sim.engine.run(sim.engine.root(t))
                if (sim.engine.find(phaseScript) != null) started = frame
            }
            assertTrue(tickFrame > 0, "tick ran")
            assertEquals(expected, started - tickFrame, "$phaseName frames after the tick")
        }
    }

    @Test
    fun `GetPhaseAbleUnitCount is 0 exactly when the phase has no banner`() {
        val sim = Sim()
        val names = ProcArrayDump.romNames()
        assumeTrue(names.isNotEmpty(), "decomp nm file not present")
        val trace = sim.run.watch("procs")
        // (step, faction, expected banner): red f1590, green f2753 (no banner), blue turn 2 f2800
        for ((step, factionAt, banner) in listOf(
            Triple(1590, 0x80, true), Triple(2753, 0x40, false), Triple(2800, 0, true),
        )) {
            sim.seedUnits(step)
            val alive = ProcArrayDump.ofWatch(trace, step, names).live.any {
                it.name == "ProcScr_PhaseIntro"
            }
            assertEquals(banner, alive, "banner f$step")
            val count = sim.units.getPhaseAbleUnitCount(factionAt)
            assertEquals(banner, count > 0, "count $count f$step")
        }
        sim.seedUnits(2750)
        assertEquals(1, sim.units.getPhaseAbleUnitCount(0x80), "red unit 0 left at f2750")
        assertEquals(2, sim.units.getPhaseAbleUnitCount(0), "Seth and Eirika")
    }

    /** The BMapMain wiring of one [Sim] with the real [ResetCursorPosition] over its units. */
    private inner class ResetAssets(sim: Sim) {
        val bm = BmSt()
        val flags = Fe8EventFlags.create()
        val camMove = CamMove(sim.engine, bm)
        val reset = ResetCursorPosition(sim.engine, bm, sim.playSt, sim.units, camMove, noNodes)
        val main = BMapMain(
            sim.engine, sim.playSt, sim.units, flags, ChapterData(sim.rom), sim.rom, reset, camMove,
        )

        init {
            bm.decode(sim.run.watch("gbmst").step(1600), 0)
        }
    }

    private companion object {
        const val EWRAM = 0x02000000
        const val BLUE_SIZE = 2 * 0x48
        const val RED_OFFSET = 62 * 0x48
        const val RED_SIZE = 50 * 0x48
        const val PID_STATS = 0x0203E894
        const val PID_STATS_SIZE = 1120
    }
}
