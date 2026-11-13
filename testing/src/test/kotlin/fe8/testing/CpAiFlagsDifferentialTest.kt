package fe8.testing

import fe8.game.RomView
import fe8.game.bm.AiState
import fe8.game.bm.BmMap
import fe8.game.bm.BmSt
import fe8.game.bm.ChapterData
import fe8.game.bm.CpPhase
import fe8.game.bm.CpUtility
import fe8.game.bm.EntityBmMaps
import fe8.game.bm.PlaySt
import fe8.game.bm.UnitLoader
import fe8.game.bm.UnitTable
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import gbafe.proc.ProcEngine
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.repeat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The AI unit flag setup (EP-2b) against the `aistate`, `blue`, `red` and `gplayst` watches and the
 * `gBmMapUnit` pool of the full dumps of `ram_trace_prologue_enemy_phase`. Each case feeds the
 * units and `gPlaySt` of the step before the call and compares `aiFlags` and `ai_counter` of every
 * watched unit and `bestBlueMov` of `gAiState` with the step after: f1526 (berserk init, faction
 * 0, the first run: Seth's HP 13 of 30 enters healing, the ally counts and the best Mov are
 * set), f1612 (`AiPhaseInit`, faction 0x80, nothing changes), f2018 and f2723 (the healing
 * refresh after an action: dead red units keep their flags).
 */
class CpAiFlagsDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    private inner class Sim(before: Int, dump: String?) {
        val run: ReferenceData.Run
        val rom: RomView
        val playSt = PlaySt()
        val units: UnitTable
        val map: BmMap
        val entityMaps: EntityBmMaps
        val loader: UnitLoader

        init {
            assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
            assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
            run = ref.run(RUN)
            assumeTrue(run.hasWatch("aistate"), "run has no aistate watch")
            rom = RomView.ofRom(romFile.readBytes())
            units = UnitTable(rom)
            run.watch("blue").step(before).copyInto(units.bytes, 0)
            run.watch("red").step(before).copyInto(units.bytes, 62 * 0x48)
            playSt.decode(run.watch("gplayst").step(before), 0)
            map = BmMap(rom, ChapterData(rom), BmSt())
            map.initChapterMap(playSt.chapterIndex)
            if (dump != null) readUnitPool(run.region(dump, "ewram"))
            entityMaps = EntityBmMaps(map, units, playSt)
            loader = UnitLoader(units, rom, { 0 }, RngService(), { false })
        }

        /** Copies `gBmMapUnit` out of the EWRAM image [ewram]. */
        private fun readUnitPool(ewram: ByteArray) {
            fun word(a: Int) = (0..3).sumOf { (ewram[a - EWRAM + it].toInt() and 0xFF) shl (8 * it) }
            val first = word(word(GBM_MAP_UNIT) - 8)
            for (y in -2 until map.sizeY + 2) {
                for (x in 0 until map.sizeX + 2) {
                    map.unit[y, x] = ewram[first - EWRAM + (y + 2) * (map.sizeX + 2) + x].toInt() and 0xFF
                }
            }
        }

        /** Asserts the `aiFlags` and `ai_counter` of every watched unit equal step [after]. */
        fun assertUnits(after: Int, what: String) {
            val ids = (1..2) + (0x81..0xB2)
            for (id in ids) {
                val src = if (id < 0x80) run.watch("blue") else run.watch("red")
                val base = (if (id < 0x80) id - 1 else id - 0x81) * 0x48
                assertEquals(src.u8(after, base + 0x0A), units.aiFlags(id), "aiFlags of $id $what")
                assertEquals(src.u8(after, base + 0x46), units.aiCounter(id), "ai_counter of $id $what")
            }
        }
    }

    @Test
    @VerifiesPort(
        "SetupUnitInventoryAIFlags", "SaveNumberOfAlliedUnitsIn0To8Range",
        "UpdateAllPhaseHealingAIStatus", "AiUpdateGetUnitIsHealing",
    )
    fun `the berserk phase start sets the healing flag, the best Mov and the ally counts`() {
        val sim = Sim(1525, "f01520")
        val ai = sim.run.watch("aistate")
        assertEquals(0, ai.u8(1525, 0x85))
        assertEquals(0, sim.units.aiCounter(1))
        val engine = ProcEngine()
        val cp = CpPhase(engine, sim.rom, sim.playSt, sim.units, sim.loader, sim.map, sim.entityMaps)
        val parent = engine.start(ProcScript("parent", listOf(repeat { }, end())), 4)
        engine.startBlocking(cp.berserkPhaseScript, parent)
        for (t in 0 until 8) engine.run(engine.root(t))
        sim.assertUnits(1526, "f1526")
        assertEquals(ai.u8(1526, 0x85), cp.aiState.bestBlueMov, "bestBlueMov f1526")
        assertEquals(8, cp.aiState.bestBlueMov)
        assertEquals(1, sim.units.aiFlags(1) and 1, "Seth is healing")
        assertEquals(1, sim.units.aiCounter(1))
        assertEquals(1, sim.units.aiCounter(2))
    }

    @Test
    @VerifiesPort(
        "SetupUnitInventoryAIFlags", "SaveNumberOfAlliedUnitsIn0To8Range",
        "UpdateAllPhaseHealingAIStatus", "AiUpdateGetUnitIsHealing",
    )
    fun `the enemy phase start keeps the flags, the best Mov and the counts`() {
        val sim = Sim(1611, "f01610")
        val ai = sim.run.watch("aistate")
        val engine = ProcEngine()
        val cp = CpPhase(engine, sim.rom, sim.playSt, sim.units, sim.loader, sim.map, sim.entityMaps)
        cp.aiState.bestBlueMov = 0
        val parent = engine.start(ProcScript("parent", listOf(repeat { }, end())), 4)
        engine.startBlocking(cp.phaseScript, parent)
        for (t in 0 until 8) engine.run(engine.root(t))
        sim.assertUnits(1612, "f1612")
        assertEquals(ai.u8(1612, 0x85), cp.aiState.bestBlueMov, "bestBlueMov f1612")
        assertEquals(ai.u8(1612, 0x7B), cp.aiState.flags, "flags f1612")
    }

    @Test
    @VerifiesPort("UpdateAllPhaseHealingAIStatus", "AiUpdateGetUnitIsHealing")
    fun `the healing refresh after an action leaves the dead red units alone`() {
        for ((before, after) in listOf(2017 to 2018, 2722 to 2723)) {
            val sim = Sim(before, null)
            assertTrue(!sim.units.isValid(0x82), "Soldier 1 is cleared at f$before")
            val util = CpUtility(
                sim.rom, sim.units, sim.playSt, AiState(), sim.loader, sim.map, sim.entityMaps,
            )
            util.updateAllPhaseHealingAiStatus()
            sim.assertUnits(after, "f$after")
        }
    }

    private companion object {
        const val RUN = "ram_trace_prologue_enemy_phase"
        const val EWRAM = 0x02000000
        const val GBM_MAP_UNIT = 0x0202E4D8
    }
}
