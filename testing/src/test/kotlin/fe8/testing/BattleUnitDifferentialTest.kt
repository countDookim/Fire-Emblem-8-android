package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BattleStats
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
 * The battle unit setup of `src/bmbattle.c` against `ram_trace_prologue_battle`: the Prologue
 * event battle (Eirika, `gUnitArrayBlue[0]`, against the first red unit) fills `gBattleActor` and
 * `gBattleTarget` between frames 14429 (all zero) and 14430. The unit arrays, `gPlaySt`, `gBmSt`
 * and the terrain map of frame 14429 are the inputs; the part of `EvtBattleGenerateRealInternal`
 * and `BattleGenerate` before the unwind is replayed on sentinel-filled battle units and the
 * result is compared with the frame 14430 structs, minus the fields written by code outside this
 * port (see [ACTOR_MASK] and [TARGET_MASK]).
 */
class BattleUnitDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val run = "ram_trace_prologue_battle"
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    // Not @VerifiesPort: InitBattleUnitWithoutBonuses, SetBattleUnitWeaponBallista,
    // ComputeBattleUnitBaseDefense, BattleApplyReaverEffect (not called here);
    // ComputeBattleUnitWeaponRankBonuses (no S rank), ComputeBattleUnitStatusBonuses (no status),
    // ComputeBattleUnitSpecialWeaponStats and BattleInitTargetCanCounter (no special case: a
    // broken condition survives); GetUnitSupportBonuses and the GetUnit* stat getters and item
    // bonus getters (the empty sum / zero bonuses hide their terms); GetItemWeight,
    // GetItemMaxRange, GetItemWeaponEffect, GetItemEffectiveness (a wrong field survives).
    @Test
    @VerifiesPort(
        "InitBattleUnit", "SetBattleUnitTerrainBonusesAuto", "SetBattleUnitTerrainBonuses",
        "SetBattleUnitWeapon", "ComputeBattleUnitStats", "ComputeBattleUnitEffectiveStats",
        "ComputeBattleUnitSupportBonuses", "ComputeBattleUnitDefense",
        "ComputeBattleUnitAttack", "ComputeBattleUnitSpeed", "ComputeBattleUnitHitRate",
        "ComputeBattleUnitAvoidRate", "ComputeBattleUnitCritRate", "ComputeBattleUnitDodgeRate",
        "ComputeBattleUnitEffectiveHitRate", "ComputeBattleUnitEffectiveCritRate",
        "ComputeBattleUnitSilencerRate", "BattleApplyWeaponTriangleEffect",
        "BattleUnitTargetCheckCanCounter", "GetItemMight", "GetItemHit", "GetItemCrit",
        "GetItemMinRange",
    )
    fun `battle units after init, terrain and stat computes match the dumped structs`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val rom = RomView.ofRom(romFile.readBytes())
        val r = ref.run(run)
        val before = r.region("r14429", "ewram")
        val after = r.region("r14430", "ewram")

        val table = UnitTable(rom).also { it.loadFrom(before) }
        val playSt = PlaySt().also { it.decode(before, GPLAYST - EWRAM) }
        val bmSt = BmSt().also { it.decode(before, GBMST - EWRAM) }
        val rows = before.u32(GBM_MAP_TERRAIN)
        val terrainAt = { y: Int, x: Int -> before.u8(before.u32(rows + 4 * y) + x) }
        val loader = UnitLoader(table, rom, { 0 }, RngService(), { false })
        val battle = BmBattle(rom, table, loader, playSt, bmSt, terrainAt)

        val actorId = 0x01
        val targetId = 0x81
        // Sentinels: every byte the port does not write stays visibly wrong.
        battle.actor.bytes.fill(0xA5.toByte())
        battle.target.bytes.fill(0x5A.toByte())
        // pad 0x59, hasItemEffectTarget (0x7E) and pad 0x7F are never written by the code in
        // this port: they keep the zero of the EWRAM_DATA initialiser.
        for (bu in listOf(battle.actor, battle.target)) {
            for (i in intArrayOf(0x59, 0x7E, 0x7F)) bu.bytes[i] = 0
        }
        battle.stats.config = BattleStats.CONFIG_REAL

        // EvtBattleGenerateRealInternal (src/eventscr3.c), up to BattleGenerate.
        battle.initBattleUnit(battle.actor, table.bytes, table.unitOffset(actorId))
        battle.initBattleUnit(battle.target, table.bytes, table.unitOffset(targetId))
        battle.stats.range =
            Math.abs(battle.actor.unitX - battle.target.unitX) +
            Math.abs(battle.actor.unitY - battle.target.unitY)
        battle.setBattleUnitWeapon(battle.actor, BmBattle.BU_ISLOT_AUTO)
        battle.setBattleUnitWeapon(battle.target, BmBattle.BU_ISLOT_AUTO)
        battle.battleInitTargetCanCounter()
        battle.battleApplyWeaponTriangleEffect(battle.actor, battle.target)
        battle.setBattleUnitTerrainBonusesAuto(battle.actor)
        battle.setBattleUnitTerrainBonusesAuto(battle.target)
        // BattleGenerate, before BattleUnwindScripted.
        battle.computeBattleUnitStats(battle.actor, battle.target)
        battle.computeBattleUnitStats(battle.target, battle.actor)
        battle.computeBattleUnitEffectiveStats(battle.actor, battle.target)
        battle.computeBattleUnitEffectiveStats(battle.target, battle.actor)
        battle.battleUnitTargetCheckCanCounter(battle.target)

        val diffs = ArrayList<String>()
        compare(battle.actor, after, GBATTLE_ACTOR - EWRAM, ACTOR_MASK, diffs)
        compare(battle.target, after, GBATTLE_TARGET - EWRAM, TARGET_MASK, diffs)
        assertTrue(diffs.isEmpty(), diffs.joinToString("\n"))
        assertEquals(1, battle.stats.range)
    }

    private fun compare(
        bu: BattleUnit,
        dump: ByteArray,
        at: Int,
        mask: Set<Int>,
        diffs: MutableList<String>,
    ) {
        for (i in 0 until BattleUnit.SIZE) {
            if (i in mask) continue
            if (bu.bytes[i] != dump[at + i]) {
                diffs += "+0x%02X: port %02X dump %02X".format(
                    i, bu.bytes[i].toInt() and 0xFF, dump[at + i].toInt() and 0xFF,
                )
            }
        }
    }

    private fun ByteArray.u8(addr: Int) = this[addr - EWRAM].toInt() and 0xFF
    private fun ByteArray.u32(addr: Int) =
        u8(addr) or (u8(addr + 1) shl 8) or (u8(addr + 2) shl 16) or (u8(addr + 3) shl 24)

    private companion object {
        const val EWRAM = 0x02000000
        const val GBMST = 0x0202BCB0
        const val GPLAYST = 0x0202BCF0
        const val GBATTLE_ACTOR = 0x0203A4EC
        const val GBATTLE_TARGET = 0x0203A56C
        const val GBM_MAP_TERRAIN = 0x0202E4DC

        /**
         * Actor bytes written by code outside this port: `unit.curHP` (0x13, the damage taken,
         * `BattleUnwindScripted`) and `wexpMultiplier` (0x7B, the unwind's weapon exp handling).
         */
        val ACTOR_MASK: Set<Int> = setOf(0x13, 0x7B)

        /**
         * Target bytes written by code outside this port: `wexpMultiplier` (0x7B) and
         * `nonZeroDamage` (0x7C), both set while the scripted hits are unwound
         * (`BattleUnwindScripted`, `BattleGenerateHitScriptedDamage`).
         */
        val TARGET_MASK: Set<Int> = setOf(0x7B, 0x7C)
    }
}
