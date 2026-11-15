package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BanimPrepare
import fe8.game.bm.BanimSetup
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
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * `PrepareBattleGraphicsMaybe` (EP-15) and the queries it runs, against the EWRAM variables it
 * wrote at the three attacks of the Prologue captures: Seth on the boss
 * (`ram_trace_prologue_clear`, call at f4274, dump f4280) and the two Soldiers on Eirika
 * (`ram_trace_prologue_enemy_phase`, calls at f1696 and f2129, dumps f1700 and f2130).
 *
 * The battle units, `gBattleStats`, the hits and `gPlaySt` come from the dump after the call
 * (none of the fields read changes before the write-back); `gBmSt` (the camera) from the dump
 * before it (f4270, f1690, f2120). The real chain then runs and every `gBanim*` and `gEkr*`
 * variable of the 0x0203E0F0 block it writes is compared with the dump, as is its return value
 * (the capture ran the map animation, so false).
 */
class BanimPrepareDifferentialTest {
    @Test
    @VerifiesPort(
        "PrepareBattleGraphicsMaybe", "sub_8055BC4", "ParseBattleHitToBanimCmd",
        "ResetEkrDragonStatus", "SetBanimArenaFlag", "GetBattleAnimArenaFlag",
        "CheckBanimHensei", "GetEfxHp",
    )
    fun `the real chain writes the battle animation variables of the capture`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val rom = RomView.ofRom(romFile.readBytes())
        for (case in CASES) {
            val run = ref.run(case.run)
            val names = run.dumps.entries.associate { it.value to it.key }
            val after = run.region(names.getValue(case.after), "ewram")
            val before = run.region(names.getValue(case.before), "ewram")
            val table = UnitTable(rom).also { it.loadFrom(after) }
            val playSt = PlaySt().also { it.decode(after, GPLAYST - EWRAM) }
            val bm = BmSt().also { it.decode(before, GBMST - EWRAM) }
            val loader = UnitLoader(table, rom, { 0 }, RngService(), { false })
            val battle = BmBattle(rom, table, loader, playSt, bm) { _, _ -> 0 }
            after.copyInto(
                battle.actor.bytes, 0, GBATTLE_ACTOR - EWRAM,
                GBATTLE_ACTOR - EWRAM + BattleUnit.SIZE,
            )
            after.copyInto(
                battle.target.bytes, 0, GBATTLE_TARGET - EWRAM,
                GBATTLE_TARGET - EWRAM + BattleUnit.SIZE,
            )
            battle.stats.decode(after, GBATTLESTATS - EWRAM)
            battle.stats.config = battle.stats.config and BattleStats.CONFIG_MAPANIMS.inv()
            val hits = IntArray(HIT_COUNT) { u32(after, GHITARRAY - EWRAM + 4 * it) }
            val setup = BanimSetup(rom, playSt, battle)
            val prep = BanimPrepare(rom, playSt, bm, battle, hits, setup)
            val tag = "${case.run} f${case.after}"

            // Stale state the call must overwrite.
            prep.dragonStatus.fill(1)
            prep.setBanimArenaFlag(1)

            assertEquals(false, prep.sub8055BC4(), "$tag sub_8055BC4")

            assertEquals(
                u32(after, GBANIM_LINK_ARENA - EWRAM), setup.getBanimLinkArenaFlag(),
                "$tag link arena",
            )
            assertEquals(
                u32(after, GBA_ARENA_FLAG - EWRAM), prep.getBattleAnimArenaFlag(),
                "$tag arena flag",
            )
            assertEquals(0, prep.dragonStatus.sum(), "$tag dragon status")
            assertEquals(s16(after, GBANIM_BG), prep.banimBg, "$tag gBanimBG")
            assertEquals(
                s16(after, GEKR_INITIAL_HIT_SIDE), prep.initialHitSide,
                "$tag gEkrInitialHitSide",
            )
            assertEquals(s16(after, GEKR_SNOW_WEATHER), prep.snowWeather, "$tag gEkrSnowWeather")
            assertEquals(
                s16(after, GEKR_DISTANCE_TYPE), prep.distanceType,
                "$tag gEkrDistanceType",
            )
            assertEquals(
                u32(after, GEKR_UNIT_LEFT - EWRAM),
                if (prep.leftIsActor) GBATTLE_ACTOR else GBATTLE_TARGET,
                "$tag gpEkrBattleUnitLeft",
            )
            for (side in 0..1) {
                fun at(base: Int) = s16(after, base + 2 * side)
                assertEquals(at(GBANIM_VALID), prep.valid[side], "$tag gBanimValid[$side]")
                assertEquals(
                    at(GBANIM_POSITION_IS_ENEMY), prep.positionIsEnemy[side],
                    "$tag gBanimPositionIsEnemy[$side]",
                )
                assertEquals(at(GBANIM_IDX_BAK), prep.idxBak[side], "$tag gBanimIdx_bak[$side]")
                assertEquals(
                    at(GBANIM_UNIQUE_PAL), prep.uniquePal[side],
                    "$tag gBanimUniquePal[$side]",
                )
                assertEquals(
                    at(GBANIM_FACTION_PAL), prep.factionPal[side],
                    "$tag gBanimFactionPal[$side]",
                )
                assertEquals(
                    at(GEKR_SPELL_ANIM_INDEX), prep.spellAnimIndex[side],
                    "$tag gEkrSpellAnimIndex[$side]",
                )
                assertEquals(at(GBANIM_FLOORFX), prep.floorfx[side], "$tag gBanimFloorfx[$side]")
                assertEquals(at(GBANIM_IDX), prep.idx[side], "$tag gBanimIdx[$side]")
                assertEquals(
                    after[GEKR_PIDS_PAIR - EWRAM + side].toInt() and 0xFF, prep.pids[side],
                    "$tag gEkrPids[$side]",
                )
                assertEquals(
                    u32(after, GBANIM_TRIATK - EWRAM + 4 * side), prep.triAtkPalettes[side],
                    "$tag gBanimTriAtkPalettes[$side]",
                )
                assertEquals(
                    u32(after, GEKR_TRIANGLE_UNITS - EWRAM + 4 * side), prep.triangleUnits[side],
                    "$tag gpEkrTriangleUnits[$side]",
                )
                assertEquals(at(GEKR_GAUGE_HP), prep.gaugeHp[side], "$tag gEkrGaugeHp[$side]")
                assertEquals(at(GBANIM_MAX_HP), prep.maxHp[side], "$tag gBanimMaxHP[$side]")
                assertEquals(at(GEKR_GAUGE_HIT), prep.gaugeHit[side], "$tag gEkrGaugeHit[$side]")
                assertEquals(at(GEKR_GAUGE_DMG), prep.gaugeDmg[side], "$tag gEkrGaugeDmg[$side]")
                assertEquals(at(GEKR_GAUGE_CRT), prep.gaugeCrt[side], "$tag gEkrGaugeCrt[$side]")
                assertEquals(
                    at(GBANIM_EXP_PREVIOUS), prep.expPrevious[side],
                    "$tag gBanimExpPrevious[$side]",
                )
                assertEquals(at(GBANIM_EXP_GAIN), prep.expGain[side], "$tag gBanimExpGain[$side]")
                assertEquals(at(GBANIM_TERRAIN), prep.terrain[side], "$tag gBanimTerrain[$side]")
                assertEquals(at(GBANIM_CON), prep.con[side], "$tag gBanimCon[$side]")
                assertEquals(at(GBANIM_WTA_BONUS), prep.wtaBonus[side], "$tag gBanimWtaBonus[$side]")
                assertEquals(
                    at(GBANIM_EFFECTIVENESS), prep.effectiveness[side],
                    "$tag gBanimEffectiveness[$side]",
                )
                assertEquals(
                    at(GBANIM_UNIQUE_PAL_DISABLED), prep.uniquePaletteDisabled[side],
                    "$tag gBanimUniquePaletteDisabled[$side]",
                )
            }
            for (i in 0 until 4) {
                assertEquals(
                    s16(after, GEKR_BM_LOCATION + 2 * i), prep.bmLocation[i],
                    "$tag gEkrBmLocation[$i]",
                )
            }
            for (i in 0 until 20) {
                assertEquals(
                    u16(after, GANIM_ROUND_DATA - EWRAM + 2 * i), prep.animRoundData[i],
                    "$tag gAnimRoundData[$i]",
                )
            }
            for (i in 0 until 22) {
                assertEquals(
                    u16(after, GEFX_HP_LUT - EWRAM + 2 * i), setup.efxHpLut[i],
                    "$tag gEfxHpLut[$i]",
                )
            }
            assertEquals(
                u32(after, GBATTLE_SCRIPTED - EWRAM), if (setup.battleScripted) 1 else 0,
                "$tag gBattleScripted",
            )
        }
    }

    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    private class Case(val run: String, val before: Int, val after: Int)

    private fun u16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun s16(b: ByteArray, address: Int): Int = u16(b, address - EWRAM).toShort().toInt()

    private fun u32(b: ByteArray, o: Int): Int = u16(b, o) or (u16(b, o + 2) shl 16)

    private companion object {
        const val EWRAM = 0x02000000
        const val GBMST = 0x0202BCB0
        const val GPLAYST = 0x0202BCF0
        const val GBATTLESTATS = 0x0203A4D4
        const val GBATTLE_ACTOR = 0x0203A4EC
        const val GBATTLE_TARGET = 0x0203A56C
        const val GHITARRAY = 0x0203A5EC
        const val HIT_COUNT = 8
        const val GBANIM_LINK_ARENA = 0x0203E0F0
        const val GBANIM_BG = 0x0203E0FE
        const val GEKR_INITIAL_HIT_SIDE = 0x0203E100
        const val GEKR_SNOW_WEATHER = 0x0203E102
        const val GBANIM_VALID = 0x0203E104
        const val GBANIM_POSITION_IS_ENEMY = 0x0203E108
        const val GBANIM_IDX_BAK = 0x0203E10C
        const val GBANIM_UNIQUE_PAL = 0x0203E110
        const val GBANIM_FACTION_PAL = 0x0203E114
        const val GEKR_SPELL_ANIM_INDEX = 0x0203E118
        const val GBANIM_FLOORFX = 0x0203E11C
        const val GEKR_DISTANCE_TYPE = 0x0203E120
        const val GEKR_BM_LOCATION = 0x0203E122
        const val GANIM_ROUND_DATA = 0x0203E12A
        const val GEFX_HP_LUT = 0x0203E156
        const val GBANIM_IDX = 0x0203E182
        const val GEKR_UNIT_LEFT = 0x0203E188
        const val GEKR_PIDS_PAIR = 0x0203E190
        const val GEKR_TRIANGLE_UNITS = 0x0203E194
        const val GBANIM_TRIATK = 0x0203E19C
        const val GEKR_GAUGE_HP = 0x0203E1AC
        const val GBANIM_MAX_HP = 0x0203E1B0
        const val GEKR_GAUGE_HIT = 0x0203E1B8
        const val GEKR_GAUGE_DMG = 0x0203E1BC
        const val GEKR_GAUGE_CRT = 0x0203E1C0
        const val GBANIM_EXP_PREVIOUS = 0x0203E1C4
        const val GBANIM_EXP_GAIN = 0x0203E1C8
        const val GBANIM_TERRAIN = 0x0203E1CC
        const val GBANIM_CON = 0x0203E1D0
        const val GBANIM_WTA_BONUS = 0x0203E1D4
        const val GBANIM_EFFECTIVENESS = 0x0203E1D8
        const val GBANIM_UNIQUE_PAL_DISABLED = 0x0203E1DC
        const val GBATTLE_SCRIPTED = 0x0203E1E0
        const val GBA_ARENA_FLAG = 0x0203E1E4
        val CASES = listOf(
            Case("ram_trace_prologue_clear", 4270, 4280),
            Case("ram_trace_prologue_enemy_phase", 1690, 1700),
            Case("ram_trace_prologue_enemy_phase", 2120, 2130),
        )
    }
}
