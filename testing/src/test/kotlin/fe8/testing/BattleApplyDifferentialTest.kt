package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BattleStats
import fe8.game.bm.BmBattle
import fe8.game.bm.BmSt
import fe8.game.bm.PlaySt
import fe8.game.bm.UnitLoader
import fe8.game.bm.UnitTable
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * `BattleApplyGameStateUpdates` on the battle units of `ram_trace_prologue_battle`.
 *
 * Not a `@VerifiesPort` test: the trace has no ground truth for the update. The unit arrays are
 * identical at every dumped frame f14431..f15059 (the update runs in `ProcScr_ekrBattleEnding`
 * after the last dump), so this only checks that the update carries the battle units' level, exp,
 * HP, state and items into the table and leaves every other byte alone. The expected values are
 * derived from the decomp source, not from a dump.
 */
class BattleApplyDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    fun `battle units are carried into the unit table`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val rom = RomView.ofRom(romFile.readBytes())
        val d = ref.run("ram_trace_prologue_battle").region("r15059", "ewram")
        val table = UnitTable(rom).also { it.loadFrom(d) }
        val playSt = PlaySt().also { it.decode(d, GPLAYST - EWRAM) }
        val bmSt = BmSt().also { it.decode(d, GBMST - EWRAM) }
        val loader = UnitLoader(table, rom, { 0 }, RngService(), { false })
        val battle = BmBattle(rom, table, loader, playSt, bmSt) { _, _ -> 0 }
        d.copyInto(battle.actor.bytes, 0, GACTOR - EWRAM, GACTOR - EWRAM + 0x80)
        d.copyInto(battle.target.bytes, 0, GTARGET - EWRAM, GTARGET - EWRAM + 0x80)
        battle.stats.decode(d, GBATTLESTATS - EWRAM)
        val before = table.bytes.copyOf()

        battle.battleApplyGameStateUpdates()

        for ((id, bu) in listOf(ACTOR_ID to battle.actor, TARGET_ID to battle.target)) {
            val o = table.unitOffset(id)
            val want = before.copyOfRange(o, o + 0x48)
            for (i in intArrayOf(0x08, 0x09, 0x0C, 0x0D, 0x0E, 0x0F, 0x13)) want[i] = bu.bytes[i]
            val items = (0 until 5).map { bu.bytes[0x1E + 2 * it] to bu.bytes[0x1F + 2 * it] }
                .filter { it.first.toInt() != 0 || it.second.toInt() != 0 }
            for (i in 0 until 5) {
                want[0x1E + 2 * i] = items.getOrNull(i)?.first ?: 0
                want[0x1F + 2 * i] = items.getOrNull(i)?.second ?: 0
            }
            val got = table.bytes.copyOfRange(o, o + 0x48)
            // Weapon ranks (0x28..0x2F) may only rise (GetBattleUnitUpdatedWeaponExp).
            for (i in 0x28..0x2F) {
                assertTrue(got[i].toInt() and 0xFF >= want[i].toInt() and 0xFF, "rank $i of $id")
                want[i] = got[i]
            }
            assertEquals(want.toHex(), got.toHex(), "unit $id")
        }
    }

    private fun ByteArray.toHex() = joinToString("") { "%02X".format(it) }

    private companion object {
        const val EWRAM = 0x02000000
        const val GBMST = 0x0202BCB0
        const val GPLAYST = 0x0202BCF0
        const val GBATTLESTATS = 0x0203A4D4
        const val GACTOR = 0x0203A4EC
        const val GTARGET = 0x0203A56C
        const val ACTOR_ID = 0x01
        const val TARGET_ID = 0x81
    }
}
