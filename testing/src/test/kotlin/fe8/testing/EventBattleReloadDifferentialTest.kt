package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BattleGenerate
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
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * `EventBattleReloadBmStatus` against `ram_trace_prologue_battle_end`: the unit table changes
 * between frames 15074 and 15075 (unit 0x01: state 1 to 0, curHP 30 to 13). The unit table,
 * `gBattleActor`, `gBattleTarget` and `gBattleStats` of frame 15074 are the inputs; the whole
 * unit table must equal frame 15075.
 *
 * Masks: bytes 0x3C..0x3F of every unit (sprite handle pointer, written by other code), and bit 0
 * (`US_HIDDEN`) of the state word of unit 0x01 only (it goes 1 to 0 at f15075 but is cleared by
 * the battle-animation end, not by this function).
 *
 * Not exercised: the item-backup branch (`ItemBackupEvtBattle` is 0xFFFF), the `MAPANIMS` death
 * branch (nobody dies) and the three refresh hooks (no-ops here).
 */
class EventBattleReloadDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val run = "ram_trace_prologue_battle_end"
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    @VerifiesPort("EventBattleReloadBmStatus")
    fun `event battle end reloads the unit table`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val r = runCatching { ref.run(run) }.getOrNull()
        assumeTrue(r != null, "run $run not present")
        val rom = RomView.ofRom(romFile.readBytes())
        val d = r!!.region("r15074", "ewram")
        val after = r.region("r15075", "ewram")
        val table = UnitTable(rom).also { it.loadFrom(d) }
        val playSt = PlaySt()
        val bmSt = BmSt()
        val rng = RngService()
        val loader = UnitLoader(table, rom, { 0 }, rng, { false })
        val battle = BmBattle(rom, table, loader, playSt, bmSt) { _, _ -> 0 }
        d.copyInto(battle.actor.bytes, 0, GACTOR - EWRAM, GACTOR - EWRAM + 0x80)
        d.copyInto(battle.target.bytes, 0, GTARGET - EWRAM, GTARGET - EWRAM + 0x80)
        battle.stats.decode(d, GBATTLESTATS - EWRAM)
        val gen = BattleGenerate(battle, rom, loader, playSt, rng, Mechanics())
        gen.itemBackupEvtBattle = 0xFFFF

        gen.eventBattleReloadBmStatus(table, {}, {}, {})

        val want = UnitTable(rom).also { it.loadFrom(after) }.bytes
        val got = table.bytes
        val unit01 = table.unitOffset(0x01)
        for (i in got.indices) {
            if (i % UnitTable.UNIT_SIZE in 0x3C..0x3F) {
                got[i] = want[i]
            } else if (i == unit01 + 0x0C) {
                got[i] = ((got[i].toInt() and 1.inv()) or (want[i].toInt() and 1)).toByte()
            }
        }
        assertEquals(want.toHex(), got.toHex(), "unit table")
    }

    private fun ByteArray.toHex() = joinToString("") { "%02X".format(it) }

    private companion object {
        const val EWRAM = 0x02000000
        const val GBATTLESTATS = 0x0203A4D4
        const val GACTOR = 0x0203A4EC
        const val GTARGET = 0x0203A56C
    }
}
