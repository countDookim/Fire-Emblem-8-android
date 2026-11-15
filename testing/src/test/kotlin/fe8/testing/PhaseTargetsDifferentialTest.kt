package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BmMap
import fe8.game.bm.BmSt
import fe8.game.bm.ChapterData
import fe8.game.bm.MapTraps
import fe8.game.bm.PhaseStartEnd
import fe8.game.bm.PhaseTargets
import fe8.game.bm.PlaySt
import fe8.game.bm.SelectTargets
import fe8.game.bm.UnitTable
import fe8.game.flags.Fe8EventFlags
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import gbafe.proc.ProcEngine
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.block
import gbafe.proc.ProcScripts.end
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The start-of-phase target lists (EP-1b) over the units, map, trap pool and RNG of the RAM
 * dumps of the start-of-phase windows: f1607..1609 (red phase) and f2754..2757 (green phase) of
 * `ram_trace_prologue_enemy_phase`, f3798..3801 (red phase of turn 2) of
 * `ram_trace_prologue_clear`.
 *
 * What these captures can and cannot show: the lists are empty in every window (no unit on a
 * terrain that heals, none with a status, no Gorgon egg; the Prologue map has no terrain the
 * `TerrainTable_Heal*` tables mark), and a stand-in that leaves the list empty passes too. So
 * the `Make*TargetList` functions and the two `bmmap.c` lookups stay Transcribed; this test
 * only checks that the real scans, run over the real units, do not invent a target or draw a
 * random number where the capture shows none, and the display procs of the clear capture.
 */
class PhaseTargetsDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    /** (run, dump) of every start-of-phase window checked, with the faction the phase is of. */
    private val windows = listOf(
        Triple("ram_trace_prologue_enemy_phase", "r01607", 0x80),
        Triple("ram_trace_prologue_enemy_phase", "r01609", 0x80),
        Triple("ram_trace_prologue_enemy_phase", "r02754", 0x40),
        Triple("ram_trace_prologue_enemy_phase", "r02757", 0x40),
        Triple("ram_trace_prologue_clear", "r03798", 0x80),
        Triple("ram_trace_prologue_clear", "r03799", 0x80),
        Triple("ram_trace_prologue_clear", "f03800", 0x80),
        Triple("ram_trace_prologue_clear", "r03801", 0x80),
    )

    private inner class Sim(runName: String, dump: String) {
        val rom: RomView
        val engine = ProcEngine()
        val playSt = PlaySt()
        val units: UnitTable
        val targets = SelectTargets()
        val rng = RngService()
        val map: BmMap
        val traps: MapTraps
        val lists: PhaseTargets
        val ewram: ByteArray
        val iwram: ByteArray
        val rngWords: List<Int>

        init {
            assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
            assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
            val run = ref.run(runName)
            ewram = run.region(dump, "ewram")
            iwram = run.region(dump, "iwram")
            rom = RomView.ofRom(romFile.readBytes())
            playSt.decode(ewram, PLAY_ST - EWRAM)
            units = UnitTable(rom).also { it.loadFrom(ewram) }
            map = BmMap(rom, ChapterData(rom), BmSt())
            map.initChapterMap(playSt.chapterIndex)
            readPool(GBM_MAP_TERRAIN, map.terrain)
            traps = MapTraps(
                ewram.copyOfRange(TRAP_POOL - EWRAM, TRAP_POOL - EWRAM + MapTraps.POOL_SIZE),
                map, units, playSt, Fe8EventFlags.create(), targets,
            )
            // gRNSeeds: three halfwords
            rngWords = (0 until 3).map { u16(iwram, 2 * it) }
            rng.primary.load(rngWords[0], rngWords[1], rngWords[2])
            // `GetUnitMaxHp` adds the equipped weapon's HP bonus, which no Prologue weapon has
            lists = PhaseTargets(
                rom, units, targets, map, traps, rng,
                maxHp = { id -> units.bytes[units.unitOffset(id) + OFF_MAX_HP].toInt() },
            )
        }

        fun rngUnchanged() = (0 until 3).map { rng.primary.word(it) } == rngWords

        private fun readPool(handle: Int, pool: BmMap.Pool) {
            val rows = u32(ewram, handle - EWRAM)
            val first = u32(ewram, rows - EWRAM - 8) - EWRAM
            for (y in -2 until map.sizeY + 2) {
                for (x in 0 until map.sizeX + 2) {
                    pool[y, x] = ewram[first + (y + 2) * (map.sizeX + 2) + x].toInt() and 0xFF
                }
            }
        }
    }

    @Test
    fun `the four lists scan the captured units and stay empty without drawing a number`() {
        for ((runName, dump, faction) in windows) {
            val sim = Sim(runName, dump)
            assertEquals(faction, sim.playSt.faction, "faction $dump")
            // the scans really have units to look at: valid, available, not rescued, of the faction
            val scanned = (faction + 1 until faction + 0x40).count {
                sim.units.isValid(it) &&
                    (sim.units.state(it) and (UnitTable.US_UNAVAILABLE or US_RESCUED)) == 0
            }
            // (the green faction has no units in the Prologue: its phase scans nothing)
            assertEquals(faction != FACTION_GREEN, scanned > 0, "units scanned $dump")
            // and the terrain under them is a real map tile
            for (id in faction + 1 until faction + 0x40) {
                if (!sim.units.isValid(id)) continue
                assertTrue(sim.units.xPos(id) in 0 until sim.map.sizeX, "x $dump $id")
                assertTrue(sim.units.yPos(id) in 0 until sim.map.sizeY, "y $dump $id")
            }
            sim.targets.addTarget(1, 1, 1, 1)
            sim.lists.makeTerrainHealTargetList(faction)
            assertEquals(0, sim.targets.getSelectTargetCount(), "terrain heal $dump")
            sim.targets.addTarget(1, 1, 1, 1)
            sim.lists.makePoisonDamageTargetList(faction)
            assertEquals(0, sim.targets.getSelectTargetCount(), "poison $dump")
            sim.targets.addTarget(1, 1, 1, 1)
            sim.lists.makeGorgonEggHatchTargetList(faction)
            assertEquals(0, sim.targets.getSelectTargetCount(), "gorgon egg $dump")
            sim.lists.sub8026414(4)
            assertTrue(sim.rngUnchanged(), "no random number drawn $dump")
        }
    }

    @Test
    @VerifiesPort(
        "StatusDecayDisplay_Init", "TerrainHealDisplay_Init", "PoisonDamageDisplay_Init",
        "GorgonEggHatchDisplay_Init",
    )
    fun `the four display inits end their proc at once in the clear capture windows`() {
        val names = ProcArrayDump.romNames()
        assumeTrue(names.isNotEmpty(), "decomp nm file not present")
        val displays = setOf(
            "gProcScr_StatusDecayDisplay", "gProcScr_TerrainHealDisplay",
            "gProcScr_PoisonDamageDisplay", "gProcScr_GorgonEggHatchDisplay",
        )
        val clear = windows.filter { it.first == "ram_trace_prologue_clear" }
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val trace = ref.run("ram_trace_prologue_clear").watch("procs")
        for (step in 3798..3801) {
            val alive = ProcArrayDump.ofWatch(trace, step, names).live.mapNotNull { it.name }
            assertTrue(alive.none { it in displays }, "display alive f$step: $alive")
        }
        val idle = ProcScript("idle", listOf(block(), end()))
        for ((runName, dump, faction) in clear) {
            val sim = Sim(runName, dump)
            val phase = PhaseStartEnd(sim.engine, sim.units, sim.playSt, sim.targets, sim.lists)
            val inits = listOf<(gbafe.proc.Proc) -> Unit>(
                phase::statusDecayDisplayInit, phase::terrainHealDisplayInit,
                phase::poisonDamageDisplayInit, phase::gorgonEggHatchDisplayInit,
            )
            assertEquals(faction, sim.playSt.faction)
            for ((i, init) in inits.withIndex()) {
                val proc = sim.engine.start(idle, 5)
                init(proc)
                assertNull(sim.engine.find(idle), "init $i ended its proc $dump")
            }
            assertTrue(sim.rngUnchanged(), "no random number drawn $dump")
        }
    }

    private companion object {
        const val EWRAM = 0x02000000
        const val PLAY_ST = 0x0202BCF0
        const val GBM_MAP_TERRAIN = 0x0202E4DC
        const val TRAP_POOL = 0x0203A614
        const val OFF_MAX_HP = 0x12
        const val FACTION_GREEN = 0x40
        const val US_RESCUED = 1 shl 5

        fun u16(b: ByteArray, at: Int): Int =
            (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

        fun u32(b: ByteArray, at: Int): Int =
            (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
                ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)
    }
}
