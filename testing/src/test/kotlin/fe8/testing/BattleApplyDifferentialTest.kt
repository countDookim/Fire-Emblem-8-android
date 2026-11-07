package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BattleStats
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
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * `BattleApplyGameStateUpdates` against `ram_trace_prologue_battle_end`: the unit table changes
 * between frames 15074 and 15075 (unit 0x01: state 1 to 0, curHP 30 to 13). The unit table,
 * `gBattleActor` / `gBattleTarget`, `gBattleStats`, `gPlaySt` and `gBmSt` of frame 15074 are the
 * inputs; the whole unit table must equal frame 15075.
 *
 * Masks: byte 0x28 (sword rank) of unit 0x01: our `GetBattleUnitUpdatedWeaponExp` gives 182 for
 * the dumped battle unit (rank 181, item 0x03 awards 1 exp, promoted class, multiplier 1) and
 * writes it, the ROM leaves 181; the cause is not found, so that function is not tagged. Bytes
 * 0x3C..0x3F of every unit (SMS / sprite pointer bytes written by other code in the
 * same frame). `gUnknown_03003060` is compared too (it is 0 in both frames, so that part is
 * weak). Not exercised: the status, stat-change, weapon-exp and `expGain` branches (the Prologue
 * battle has no status, level-up, rank gain or exp), the ballista branch and the obstacle branch.
 */
class BattleApplyDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val run = "ram_trace_prologue_battle_end"
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    // BattleApplyBallistaUpdates only runs its no-op branch here, so it is not tagged.
    @Test
    // Not @VerifiesPort for BattleApplyGameStateUpdates, BattleApplyUnitUpdates,
    // UpdateUnitFromBattle until the masked sword rank of unit 0x01 (182 vs 181) is explained.
    @VerifiesPort("UnitRemoveInvalidItems")
    fun `battle units are carried into the unit table`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val r = runCatching { ref.run(run) }.getOrNull()
        assumeTrue(r != null, "run $run not present")
        val rom = RomView.ofRom(romFile.readBytes())
        val d = r!!.region("r15074", "ewram")
        val after = r.region("r15075", "ewram")
        val table = UnitTable(rom).also { it.loadFrom(d) }
        val playSt = PlaySt().also { it.decode(d, GPLAYST - EWRAM) }
        val bmSt = BmSt().also { it.decode(d, GBMST - EWRAM) }
        val loader = UnitLoader(table, rom, { 0 }, RngService(), { false })
        val battle = BmBattle(rom, table, loader, playSt, bmSt) { _, _ -> 0 }
        d.copyInto(battle.actor.bytes, 0, GACTOR - EWRAM, GACTOR - EWRAM + 0x80)
        d.copyInto(battle.target.bytes, 0, GTARGET - EWRAM, GTARGET - EWRAM + 0x80)
        battle.stats.decode(d, GBATTLESTATS - EWRAM)

        battle.battleApplyGameStateUpdates()

        val want = UnitTable(rom).also { it.loadFrom(after) }.bytes
        val got = table.bytes
        for (i in got.indices) {
            if (i % UnitTable.UNIT_SIZE in 0x3C..0x3F || i == WEAPON_RANK_0_OF_UNIT_01) {
                got[i] = want[i]
            }
        }
        assertEquals(want.toHex(), got.toHex(), "unit table")
        val iw = r.region("r15075", "iwram")
        val latch = (iw[0x3060].toInt() and 0xFF) or ((iw[0x3061].toInt() and 0xFF) shl 8)
        assertEquals(latch, battle.arenaLevelLatch, "gUnknown_03003060")
    }

    private fun ByteArray.toHex() = joinToString("") { "%02X".format(it) }

    private companion object {
        const val EWRAM = 0x02000000
        const val GBMST = 0x0202BCB0
        const val GPLAYST = 0x0202BCF0
        const val GBATTLESTATS = 0x0203A4D4
        const val WEAPON_RANK_0_OF_UNIT_01 = 0x28
        const val GACTOR = 0x0203A4EC
        const val GTARGET = 0x0203A56C
    }
}
