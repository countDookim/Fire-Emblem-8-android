package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BattleUnit
import fe8.game.bm.BmBattle
import fe8.game.bm.BmSt
import fe8.game.bm.PlaySt
import fe8.game.bm.UnitLoader
import fe8.game.bm.UnitTable
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * `BattleApplyGameStateUpdates` (src/bmbattle.c) against the Prologue captures: in the dump
 * before the write-back the unit table and both battle units hold the battle's result; the dump
 * after it must show the unit table entries of the actor and the target with level, exp, HP,
 * state, stats, weapon exp and items written back. The frame is found as the first dump in a
 * window whose unit entries differ from the previous dump.
 *
 * Cases: noanim f2074, kill run f1999 and f2549, cap3 f2028. The dead unit of the kill run is
 * not compared: `UnitKill` (MC-16) clears its struct in the same frame, so only the survivor
 * of each kill is checked.
 */
class BattleWriteBackDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    @VerifiesPort(
        "BattleApplyGameStateUpdates", "BattleApplyUnitUpdates", "UpdateUnitFromBattle",
        "GetBattleUnitUpdatedWeaponExp",
    )
    fun `the write-back reproduces the dumped unit table entries`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val rom = RomView.ofRom(romFile.readBytes())
        var changed = 0
        var cleared = 0
        for (case in CASES) {
            val run = ref.run(case.run)
            val names = run.dumps.entries.associate { it.value to it.key }
            fun ewram(frame: Int) = run.region(names.getValue(frame), "ewram")
            val actorId = ewram(case.from)[GACTIONDATA - EWRAM + 0x0C].toInt() and 0xFF
            val targetId = ewram(case.from)[GACTIONDATA - EWRAM + 0x0D].toInt() and 0xFF
            var d = case.from + 1
            var found = -1
            while (d <= case.to) {
                val x = UnitTable(rom).also { it.loadFrom(ewram(d - 1)) }
                val y = UnitTable(rom).also { it.loadFrom(ewram(d)) }
                if (unitBytes(x, actorId) != unitBytes(y, actorId)) {
                    found = d
                    break
                }
                d++
            }
            assertTrue(found > 0, "${case.run}: no write-back in ${case.from}..${case.to}")
            val before = ewram(found - 1)
            val after = ewram(found)
            val tag = "${case.run} f${found - 1}"

            val table = UnitTable(rom).also { it.loadFrom(before) }
            val playSt = PlaySt().also { it.decode(before, GPLAYST - EWRAM) }
            val bmSt = BmSt().also { it.decode(before, GBMST - EWRAM) }
            val rng = RngService()
            val loader = UnitLoader(table, rom, { 0 }, rng, { false })
            val battle = BmBattle(rom, table, loader, playSt, bmSt) { _, _ -> 0 }
            val actorAt = GBATTLE_ACTOR - EWRAM
            val targetAt = GBATTLE_TARGET - EWRAM
            before.copyInto(battle.actor.bytes, 0, actorAt, actorAt + BattleUnit.SIZE)
            before.copyInto(battle.target.bytes, 0, targetAt, targetAt + BattleUnit.SIZE)
            battle.stats.decode(before, GBATTLESTATS - EWRAM)

            battle.battleApplyGameStateUpdates()

            val want = UnitTable(rom).also { it.loadFrom(after) }
            for (id in intArrayOf(actorId, targetId)) {
                val o = table.unitOffset(id)
                // `UnitKill` (MC-16) clears the struct of a unit that died, in the same frame.
                if (want.bytes.copyOfRange(o, o + 4).all { it.toInt() == 0 }) {
                    cleared++
                    continue
                }
                for (i in 0 until UnitTable.UNIT_SIZE) {
                    assertEquals(
                        want.bytes[o + i], table.bytes[o + i],
                        "$tag unit $id byte 0x${i.toString(16)}",
                    )
                }
                if (unitBytes(UnitTable(rom).also { it.loadFrom(before) }, id) !=
                    unitBytes(want, id)
                ) {
                    changed++
                }
            }
        }
        assertTrue(changed >= 4, "the write-back must change units in the cases, got $changed")
        assertTrue(cleared <= 2, "only the dead units of the kill run are cleared, got $cleared")
    }

    private fun unitBytes(t: UnitTable, id: Int): List<Byte> {
        val o = t.unitOffset(id)
        return t.bytes.copyOfRange(o, o + UnitTable.UNIT_SIZE).toList()
    }

    private class Case(val run: String, val from: Int, val to: Int)

    private companion object {
        const val EWRAM = 0x02000000
        const val GBMST = 0x0202BCB0
        const val GPLAYST = 0x0202BCF0
        const val GBATTLESTATS = 0x0203A4D4
        const val GBATTLE_ACTOR = 0x0203A4EC
        const val GBATTLE_TARGET = 0x0203A56C
        const val GACTIONDATA = 0x0203A958
        val CASES = listOf(
            Case("ram_trace_prologue_attack_noanim", 2070, 2078),
            Case("ram_trace_prologue_kill", 1990, 2004),
            Case("ram_trace_prologue_kill", 2540, 2554),
            Case("ram_trace_prologue_attack", 2020, 2034),
        )
    }
}
