package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BattleGenerate
import fe8.game.bm.BattleStats
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * `BattleGenerateSimulation` (src/bmbattle.c), the attack forecast, against the Prologue
 * captures: `AttackMapSelect_SwitchIn` calls it with the active unit, the highlighted target, no
 * position (-1, -1) and the item slot of `gActionData`. The dump just before seeds the unit
 * table, `gPlaySt`, the terrain, both battle units, `gBattleStats` and the RNG; the dump after
 * must be reproduced: `gBattleActor`, `gBattleTarget`, `gBattleStats`, `gBattleHitArray` with
 * `gBattleHitIterator`, and the RNG state, which a forecast does not advance. The active unit is
 * `gActiveUnit` of the before dump; the target is the unit that `gBattleTarget` holds after (the
 * highlighted target lives in the target selection proc, which is not ported yet).
 *
 * Cases: cap3 f1430 -> f1431, noanim f1660 -> f1661 and f1790 -> f1791, kill run Seth f1680 ->
 * f1681 and Eirika f2250 -> f2260 (10 frames apart: the weapon choice moves
 * the item to the front in between, so the unit table and item slot are read from the after dump,
 * which the forecast does not change).
 */
class BattleGenerateSimulationDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    @VerifiesPort(
        "BattleGenerateSimulation", "BattleGenerateSimulationInternal",
    )
    fun `forecast reproduces the dumped battle units, hits and RNG state`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val rom = RomView.ofRom(romFile.readBytes())
        var hitCount = 0
        for (case in CASES) {
            val run = ref.run(case.run)
            val names = run.dumps.entries.associate { it.value to it.key }
            val before = run.region(names.getValue(case.before), "ewram")
            val beforeIw = run.region(names.getValue(case.before), "iwram")
            val after = run.region(names.getValue(case.after), "ewram")
            val afterIw = run.region(names.getValue(case.after), "iwram")

            val inputs = if (case.inputsFromAfter) after else before
            val table = UnitTable(rom).also { it.loadFrom(inputs) }
            val playSt = PlaySt().also { it.decode(before, GPLAYST - EWRAM) }
            val bmSt = BmSt().also { it.decode(before, GBMST - EWRAM) }
            val rows = before.u32(GBM_MAP_TERRAIN)
            val terrainAt = { y: Int, x: Int -> before.u8(before.u32(rows + 4 * y) + x) }
            val rng = RngService()
            rng.primary.load(beforeIw.u16(0), beforeIw.u16(2), beforeIw.u16(4))
            val loader = UnitLoader(table, rom, { 0 }, rng, { false })
            val battle = BmBattle(rom, table, loader, playSt, bmSt, terrainAt)
            val gen = BattleGenerate(battle, rom, loader, playSt, rng, Mechanics())

            val actorAt = GBATTLE_ACTOR - EWRAM
            val targetAt = GBATTLE_TARGET - EWRAM
            before.copyInto(battle.actor.bytes, 0, actorAt, actorAt + BattleUnit.SIZE)
            before.copyInto(battle.target.bytes, 0, targetAt, targetAt + BattleUnit.SIZE)
            battle.stats.decode(before, GBATTLESTATS - EWRAM)
            // ActionCombat clears the item-effect flag before the call.
            battle.actor.bytes[0x7E] = 0
            gen.hitArray.fill(0x5A5A5A5A)
            val actionData = GACTIONDATA - EWRAM
            val activeUnit = beforeIw.raw32(GACTIVEUNIT - IWRAM)
            val actorId = table.bytes[activeUnit - UnitTable.BLUE_ADDRESS + 0x0B].toInt() and 0xFF
            val targetId = after[targetAt + 0x0B].toInt() and 0xFF
            val slot = inputs[actionData + 0x12].toInt()

            gen.battleGenerateSimulation(
                table.bytes, table.unitOffset(actorId), table.unitOffset(targetId), -1, -1, slot,
            )

            val tag = "${case.run} f${case.after}"
            val units = listOf(battle.actor to GBATTLE_ACTOR, battle.target to GBATTLE_TARGET)
            for ((bu, at) in units) {
                val diffs = ArrayList<String>()
                for (i in 0 until BattleUnit.SIZE) {
                    val want = after[at - EWRAM + i]
                    if (bu.bytes[i] != want) {
                        diffs += "+0x%02X: port %02X dump %02X".format(
                            i, bu.bytes[i].toInt() and 0xFF, want.toInt() and 0xFF,
                        )
                    }
                }
                assertTrue(
                    diffs.isEmpty(),
                    "$tag unit at ${at.toString(16)}:\n" + diffs.joinToString("\n"),
                )
            }
            val stats = ByteArray(BattleStats.SIZE)
            battle.stats.encode(stats, 0)
            assertEquals(
                after.copyOfRange(GBATTLESTATS - EWRAM, GBATTLESTATS - EWRAM + stats.size).toHex(),
                stats.toHex(), "$tag gBattleStats",
            )
            for (i in 0 until BattleGenerate.BATTLE_HIT_MAX) {
                assertEquals(
                    after.u32(GHITARRAY + 4 * i), gen.hitArray[i], "$tag gBattleHitArray[$i]",
                )
            }
            assertEquals(
                after.u32(GHITARRAY + 4 * BattleGenerate.BATTLE_HIT_MAX),
                GHITARRAY + 4 * gen.hitIterator, "$tag gBattleHitIterator",
            )
            for (w in 0 until 3) {
                assertEquals(afterIw.u16(2 * w), rng.primary.word(w), "$tag rng $w")
            }
            hitCount += gen.hitIterator
        }
        // cap3 and noanim: 3 hits each (actor, counter, follow-up); kill run: 3 and 2.
        assertTrue(hitCount >= 10, "expected the cases to generate hits, got $hitCount")
    }

    private class Case(
        val run: String,
        val before: Int,
        val after: Int,
        val inputsFromAfter: Boolean = false,
    )

    private fun ByteArray.toHex() = joinToString("") { "%02X".format(it) }
    private fun ByteArray.u8(addr: Int) = this[addr - EWRAM].toInt() and 0xFF
    private fun ByteArray.raw32(o: Int) = u16(o) or (u16(o + 2) shl 16)
    private fun ByteArray.u16(o: Int) =
        (this[o].toInt() and 0xFF) or ((this[o + 1].toInt() and 0xFF) shl 8)
    private fun ByteArray.u32(addr: Int) =
        u8(addr) or (u8(addr + 1) shl 8) or (u8(addr + 2) shl 16) or (u8(addr + 3) shl 24)

    private companion object {
        const val EWRAM = 0x02000000
        const val GBMST = 0x0202BCB0
        const val GPLAYST = 0x0202BCF0
        const val GBM_MAP_TERRAIN = 0x0202E4DC
        const val GBATTLESTATS = 0x0203A4D4
        const val GBATTLE_ACTOR = 0x0203A4EC
        const val GBATTLE_TARGET = 0x0203A56C
        const val GHITARRAY = 0x0203A5EC
        const val GACTIONDATA = 0x0203A958
        const val IWRAM = 0x03000000
        const val GACTIVEUNIT = 0x03004E50
        val CASES = listOf(
            Case("ram_trace_prologue_attack", 1430, 1431),
            Case("ram_trace_prologue_attack_noanim", 1660, 1661),
            Case("ram_trace_prologue_attack_noanim", 1790, 1791),
            Case("ram_trace_prologue_kill", 1680, 1681),
            Case("ram_trace_prologue_kill", 2250, 2260, inputsFromAfter = true),
        )
    }
}
