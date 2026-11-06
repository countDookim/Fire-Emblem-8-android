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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The battle unit setup of `src/bmbattle.c` against `ram_trace_prologue_battle`: the Prologue
 * event battle (Eirika, `gUnitArrayBlue[0]`, against the first red unit) fills `gBattleActor` and
 * `gBattleTarget` between frames 14429 (all zero) and 14430. The unit arrays, `gPlaySt`, `gBmSt`
 * and the terrain map of frame 14429 are the inputs; `StartEventBattle`
 * is replayed through `BattleGenerate.startEventBattle` on sentinel-filled battle units and the
 * result is compared with the frame 14430 structs (the hits and stats are compared by
 * [BattleScriptedDifferentialTest]).
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
        "GetItemMinRange", "BattleGenerateHitEffects",
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
        val rng = RngService()
        val loader = UnitLoader(table, rom, { 0 }, rng, { false })
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
        // StartEventBattle (src/eventscr3.c) for the scripted Prologue battle; the hits are the
        // event queue of frame 14429, written to gActionData.script_hits.
        val gen = BattleGenerate(battle, rom, loader, playSt, rng, Mechanics())
        // Event3F_ScriptBattle: the hits come from the event queue and slot 0xD. The event code
        // fills them in the same frame as the battle, so they are read from r14430 (the battle
        // code does not touch them).
        val iw = ref.run(run).region("r14430", "iwram")
        val slotD = iw.u32i(GEVENTSLOTS + 4 * 0xD)
        gen.generateScriptBattleHitFormEventQueue(slotD, iw, GEVENTSLOTQUEUE - IWRAM)
        gen.startEventBattle(
            table.bytes, table.unitOffset(actorId), table.unitOffset(targetId), false,
            gen.scriptHits,
        )

        val diffs = ArrayList<String>()
        compare(battle.actor, after, GBATTLE_ACTOR - EWRAM, diffs)
        compare(battle.target, after, GBATTLE_TARGET - EWRAM, diffs)
        assertTrue(diffs.isEmpty(), diffs.joinToString("\n"))
        assertEquals(1, battle.stats.range)
    }

    private fun compare(
        bu: BattleUnit,
        dump: ByteArray,
        at: Int,
        diffs: MutableList<String>,
    ) {
        for (i in 0 until BattleUnit.SIZE) {
            if (bu.bytes[i] != dump[at + i]) {
                diffs += "+0x%02X: port %02X dump %02X".format(
                    i, bu.bytes[i].toInt() and 0xFF, dump[at + i].toInt() and 0xFF,
                )
            }
        }
    }

    private fun ByteArray.u32i(o: Int) =
        (this[o].toInt() and 0xFF) or ((this[o + 1].toInt() and 0xFF) shl 8) or
            ((this[o + 2].toInt() and 0xFF) shl 16) or ((this[o + 3].toInt() and 0xFF) shl 24)

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
        const val IWRAM = 0x03000000
        const val GEVENTSLOTS = 0x030004B8 - 0x03000000
        const val GEVENTSLOTQUEUE = 0x030004F0
        const val GACTIONDATA = 0x0203A958
    }
}
