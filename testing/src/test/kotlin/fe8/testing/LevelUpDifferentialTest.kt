package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BattleGenerate
import fe8.game.bm.BattleUnit
import fe8.game.bm.BmBattle
import fe8.game.bm.BmSt
import fe8.game.bm.PlaySt
import fe8.game.bm.UnitLoader
import fe8.game.bm.UnitTable
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.core.mechanics.Mechanics
import gbafe.core.rng.RngService
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The level-up path of `src/bmbattle.c` against `ram_trace_prologue_levelup` turn 5: Eirika's
 * kill strike, in which `BattleApplyExpGains` gives her 80 exp (87 -> 167) and
 * `CheckBattleUnitLevelUp` takes her from level 1 to 2 (exp 67; max HP, skl, def and lck +1; pow,
 * spd and res unchanged). The battle is generated within one frame (f6054; the dump of f6050 is
 * the last full one before it and equals the state at f6053 in every watched struct); the
 * write-back is f6750 -> f6760.
 *
 * - The first test seeds the dump of f6050 (unit table, `gPlaySt`, battle units, `gBattleStats`,
 *   the RNG state) and runs `BattleGenerateReal`: both battle units (level, exp, `expGain`, the
 *   stat-change fields) and the RNG state must equal f6054.
 * - The second seeds the state right before the growth rolls (the same battle run with Eirika's
 *   exp 0, so that the 80 exp do not level her up: the RNG state after it is the state before the
 *   rolls), runs `CheckBattleUnitLevelUp` alone on her with 167 exp, and compares her battle unit
 *   and the RNG state with f6054.
 * - The third runs the write-back on the f6750 dump and compares Eirika's unit struct with f6760.
 */
class LevelUpDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    private class Sim(rom: RomView, run: ReferenceData.Run, frame: Int) {
        val ewram: ByteArray
        val iwram: ByteArray
        val table: UnitTable
        val rng = RngService()
        val battle: BattleGenerate
        val bm: BmBattle

        init {
            val names = run.dumps.entries.associate { it.value to it.key }
            ewram = run.region(names.getValue(frame), "ewram")
            iwram = run.region(names.getValue(frame), "iwram")
            table = UnitTable(rom).also { it.loadFrom(ewram) }
            val playSt = PlaySt().also { it.decode(ewram, GPLAYST - EWRAM) }
            val bmSt = BmSt().also { it.decode(ewram, GBMST - EWRAM) }
            val rows = u32(ewram, GBM_MAP_TERRAIN - EWRAM)
            val terrainAt = { y: Int, x: Int ->
                ewram[u32(ewram, rows - EWRAM + 4 * y) - EWRAM + x].toInt() and 0xFF
            }
            rng.primary.load(u16(iwram, 0), u16(iwram, 2), u16(iwram, 4))
            val loader = UnitLoader(table, rom, { 0 }, rng, { false })
            bm = BmBattle(rom, table, loader, playSt, bmSt, terrainAt)
            battle = BattleGenerate(bm, rom, loader, playSt, rng, Mechanics())
            battle.unitTable = table
            ewram.copyInto(bm.actor.bytes, 0, GBATTLE_ACTOR - EWRAM, GBATTLE_ACTOR - EWRAM + BattleUnit.SIZE)
            ewram.copyInto(bm.target.bytes, 0, GBATTLE_TARGET - EWRAM, GBATTLE_TARGET - EWRAM + BattleUnit.SIZE)
            bm.stats.decode(ewram, GBATTLESTATS - EWRAM)
            // ActionCombat clears the item-effect flag before the call.
            bm.actor.bytes[0x7E] = 0
            battle.hitArray.fill(0x5A5A5A5A)
        }

        fun runReal() {
            val actorId = ewram[GACTIONDATA - EWRAM + 0x0C].toInt() and 0xFF
            val targetId = ewram[GACTIONDATA - EWRAM + 0x0D].toInt() and 0xFF
            battle.battleGenerateReal(table.bytes, table.unitOffset(actorId), table.unitOffset(targetId))
        }
    }

    @Test
    @VerifiesPort(
        "CheckBattleUnitLevelUp", "GetStatIncrease", "CheckBattleUnitStatCaps",
        "BattleApplyExpGains", "BattleGenerateReal",
    )
    fun `the kill strike levels Eirika up as dumped`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val rom = RomView.ofRom(romFile.readBytes())
        val run = ref.run(RUN)
        val sim = Sim(rom, run, 6050)
        sim.runReal()
        assertBattleUnit("f6054 gBattleActor", run.watch("bactor").step(6054), sim.bm.actor)
        assertBattleUnit("f6054 gBattleTarget", run.watch("btarget").step(6054), sim.bm.target)
        val rngAfter = run.watch("rng").step(6054)
        for (w in 0 until 3) {
            assertEquals(u16(rngAfter, 2 * w), sim.rng.primary.word(w), "f6054 rng word $w")
        }
    }

    @Test
    @VerifiesPort("CheckBattleUnitLevelUp", "GetStatIncrease", "CheckBattleUnitStatCaps")
    fun `the growth rolls from the state before them give the dumped gains and RNG state`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val rom = RomView.ofRom(romFile.readBytes())
        val run = ref.run(RUN)
        // The battle without the level-up: exp 0 + 80 stays below 100.
        val pre = Sim(rom, run, 6050)
        val actorId = pre.ewram[GACTIONDATA - EWRAM + 0x0C].toInt() and 0xFF
        pre.table.bytes[pre.table.unitOffset(actorId) + OFF_EXP] = 0
        pre.runReal()
        val before = IntArray(3) { pre.rng.primary.word(it) }

        val sim = Sim(rom, run, 6050)
        sim.rng.primary.load(before[0], before[1], before[2])
        val after = run.watch("bactor").step(6054)
        val rngAfter = run.watch("rng").step(6054)
        // Eirika as BattleApplyExpGains leaves her: 87 + 80 exp, the kill's expGain 80.
        after.copyInto(sim.bm.actor.bytes, 0, 0, BattleUnit.SIZE)
        sim.bm.actor.bytes[OFF_LEVEL] = 1
        sim.bm.actor.bytes[OFF_EXP] = 167.toByte()
        for (i in 0x73..0x79) sim.bm.actor.bytes[i] = 0
        sim.battle.checkBattleUnitLevelUp(sim.bm.actor)
        assertBattleUnit("f6054 gBattleActor", after, sim.bm.actor)
        for (w in 0 until 3) {
            assertEquals(u16(rngAfter, 2 * w), sim.rng.primary.word(w), "f6054 rng word $w")
        }
        val beforeState = (0 until 3).map { before[it] }
        val afterState = (0 until 3).map { u16(rngAfter, 2 * it) }
        assertNotEquals(beforeState, afterState, "the growth rolls must advance the RNG")
    }

    @Test
    @VerifiesPort("UpdateUnitFromBattle", "BattleApplyUnitUpdates", "BattleApplyGameStateUpdates")
    fun `the write-back stores Eirika's new level and stats`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val rom = RomView.ofRom(romFile.readBytes())
        val run = ref.run(RUN)
        val sim = Sim(rom, run, 6750)
        sim.bm.battleApplyGameStateUpdates()
        val after = run.region(run.dumps.entries.first { it.value == 6760 }.key, "ewram")
        val want = UnitTable(rom).also { it.loadFrom(after) }
        val id = sim.ewram[GACTIONDATA - EWRAM + 0x0C].toInt() and 0xFF
        val o = sim.table.unitOffset(id)
        val watched = run.watch("blue").step(6760)
        for (i in 0 until UnitTable.UNIT_SIZE) {
            assertEquals(want.bytes[o + i], sim.table.bytes[o + i], "f6760 Eirika byte 0x${i.toString(16)}")
            assertEquals(watched[(id - 1) * UnitTable.UNIT_SIZE + i], sim.table.bytes[o + i], "f6760 Eirika (watch) byte 0x${i.toString(16)}")
        }
    }

    private fun assertBattleUnit(tag: String, dump: ByteArray, bu: BattleUnit) {
        val diffs = ArrayList<String>()
        for (i in 0 until BattleUnit.SIZE) {
            val want = dump[i]
            if (bu.bytes[i] != want) {
                diffs += "+0x%02X: port %02X dump %02X".format(
                    i, bu.bytes[i].toInt() and 0xFF, want.toInt() and 0xFF,
                )
            }
        }
        assertEquals(emptyList<String>(), diffs, "$tag differs from the dump")
    }

    private companion object {
        const val RUN = "ram_trace_prologue_levelup"
        const val EWRAM = 0x02000000
        const val OFF_LEVEL = 0x08
        const val OFF_EXP = 0x09
        const val GBMST = 0x0202BCB0
        const val GPLAYST = 0x0202BCF0
        const val GBM_MAP_TERRAIN = 0x0202E4DC
        const val GBATTLESTATS = 0x0203A4D4
        const val GBATTLE_ACTOR = 0x0203A4EC
        const val GBATTLE_TARGET = 0x0203A56C
        const val GACTIONDATA = 0x0203A958

        fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        fun u32(b: ByteArray, o: Int) = u16(b, o) or (u16(b, o + 2) shl 16)
    }
}
