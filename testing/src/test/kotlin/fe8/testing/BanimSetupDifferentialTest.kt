package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BanimSetup
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
 * The battle animation set-up queries (EP-14) of `PrepareBattleGraphicsMaybe` against the RAM it
 * wrote at the three attacks of the Prologue captures: Seth on the boss (`ram_trace_prologue_clear`,
 * f4274, dump f4280) and the two Soldiers on Eirika (`ram_trace_prologue_enemy_phase`, f1696 and
 * f2129, dumps f1700 and f2130). The units, `gPlaySt` and `gBmSt` of the dump are fed in; the
 * left-hand unit is the one `gpEkrBattleUnitLeft` points to. Compared: `gBanimIdx`,
 * `gBanimFactionPal`, `gBanimTriAtkPalettes`, `gBanimLinkArenaFlag`,
 * `gBanimUniquePaletteDisabled`, `gBattleScripted` and the setting `GetBattleAnimPreconfType` gave.
 */
class BanimSetupDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    private class Case(val run: String, val dump: Int)

    @Test
    @VerifiesPort(
        "GetBattleAnimationId", "GetBanimFactionPalette", "SetBattleUnscripted",
        "SetBanimLinkArenaFlag", "GetBanimLinkArenaFlag",
    )
    fun `the set-up queries reproduce the battle animation RAM`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val rom = RomView.ofRom(romFile.readBytes())
        for (case in CASES) {
            val run = ref.run(case.run)
            val dumpNames = run.dumps.entries.associate { it.value to it.key }
            val ewram = run.region(dumpNames.getValue(case.dump), "ewram")
            val table = UnitTable(rom).also { it.loadFrom(ewram) }
            val playSt = PlaySt().also { it.decode(ewram, GPLAYST - EWRAM) }
            val bm = BmSt().also { it.decode(ewram, GBMST - EWRAM) }
            val rng = RngService()
            val loader = UnitLoader(table, rom, { 0 }, rng, { false })
            val battle = BmBattle(rom, table, loader, playSt, bm) { _, _ -> 0 }
            ewram.copyInto(battle.actor.bytes, 0, GBATTLE_ACTOR - EWRAM, GBATTLE_ACTOR - EWRAM + BattleUnit.SIZE)
            ewram.copyInto(battle.target.bytes, 0, GBATTLE_TARGET - EWRAM, GBATTLE_TARGET - EWRAM + BattleUnit.SIZE)
            val setup = BanimSetup(rom, playSt, battle)
            val tag = "${case.run} f${case.dump}"

            val left = if (u32(ewram, GEKR_UNIT_LEFT - EWRAM) == GBATTLE_ACTOR) battle.actor else battle.target
            val right = if (left === battle.actor) battle.target else battle.actor
            val sides = listOf(left, right)

            for (pos in 0..1) {
                val unit = sides[pos]
                val valid = u16(ewram, GBANIM_VALID - EWRAM + 2 * pos)
                if (valid == 0) continue
                val classPtr = u32(unit.bytes, 0x04)
                val weapon = u16(unit.bytes, 0x4A)
                val idx = setup.getBattleAnimationId(rom.u32(classPtr + 0x34), weapon)
                assertEquals(u16(ewram, GBANIM_IDX - EWRAM + 2 * pos), idx, "$tag gBanimIdx[$pos]")
                assertEquals(
                    u32(ewram, GBANIM_TRIATK - EWRAM + 4 * pos),
                    setup.filterBattleAnimCharacterPalette(idx.toShort().toInt(), weapon),
                    "$tag gBanimTriAtkPalettes[$pos]",
                )
                val faction = unit.bytes[0x0B].toInt() and 0xC0
                assertEquals(
                    u16(ewram, GBANIM_FACTION_PAL - EWRAM + 2 * pos),
                    setup.getBanimFactionPalette(faction),
                    "$tag gBanimFactionPal[$pos]",
                )
            }

            // PrepareBattleGraphicsMaybe: SetBanimLinkArenaFlag(gBmSt link arena bit).
            setup.setBanimLinkArenaFlag(if (bm.gameStateBits and BM_FLAG_LINKARENA != 0) 1 else 0)
            assertEquals(
                u32(ewram, GBANIM_LINK_ARENA - EWRAM), setup.getBanimLinkArenaFlag(),
                "$tag gBanimLinkArenaFlag",
            )
            val disabled = if (setup.getBanimLinkArenaFlag() == 1 || playSt.unitColor != 0) 1 else 0
            assertEquals(
                u16(ewram, GBANIM_UNIQUE_PAL_DISABLED - EWRAM), disabled,
                "$tag gBanimUniquePaletteDisabled",
            )

            // The capture ran the map animation, so the battle was not scripted.
            assertEquals(false, setup.checkBattleScripted(), "$tag CheckBattleScripted")
            setup.battleScripted = true
            setup.setBattleUnscripted()
            assertEquals(
                u32(ewram, GBATTLE_SCRIPTED - EWRAM), if (setup.battleScripted) 1 else 0,
                "$tag gBattleScripted",
            )
            assertEquals(playSt.animationType, setup.getBattleAnimPreconfType(), "$tag preconf type")
        }
    }

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, o: Int) = u16(b, o) or (u16(b, o + 2) shl 16)

    private companion object {
        const val EWRAM = 0x02000000
        const val GBMST = 0x0202BCB0
        const val GPLAYST = 0x0202BCF0
        const val GBATTLE_ACTOR = 0x0203A4EC
        const val GBATTLE_TARGET = 0x0203A56C
        const val GBANIM_LINK_ARENA = 0x0203E0F0
        const val GBANIM_VALID = 0x0203E104
        const val GBANIM_FACTION_PAL = 0x0203E114
        const val GBANIM_IDX = 0x0203E182
        const val GEKR_UNIT_LEFT = 0x0203E188
        const val GBANIM_TRIATK = 0x0203E19C
        const val GBANIM_UNIQUE_PAL_DISABLED = 0x0203E1DC
        const val GBATTLE_SCRIPTED = 0x0203E1E0
        const val BM_FLAG_LINKARENA = 0x40
        val CASES = listOf(
            Case("ram_trace_prologue_clear", 4280),
            Case("ram_trace_prologue_enemy_phase", 1700),
            Case("ram_trace_prologue_enemy_phase", 2130),
        )
    }
}
