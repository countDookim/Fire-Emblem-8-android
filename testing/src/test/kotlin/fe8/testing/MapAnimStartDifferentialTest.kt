package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BattleGenerate
import fe8.game.bm.BattleStats
import fe8.game.bm.BattleUnit
import fe8.game.bm.BmBattle
import fe8.game.bm.BmSt
import fe8.game.bm.CamMove
import fe8.game.bm.MapAnimApi
import fe8.game.bm.Mu
import fe8.game.bm.MuHooks
import fe8.game.bm.MuProc
import fe8.game.bm.PlaySt
import fe8.game.bm.UnitLoader
import fe8.game.bm.UnitTable
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.core.mechanics.Mechanics
import gbafe.core.rng.RngService
import gbafe.input.KeyStatus
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The start of the map battle animation (EP-13, `BeginBattleAnimations` of src/bmbattle.c and
 * the functions of src/mapanim_api.c it reaches) against the three attacks of the Prologue
 * captures: Seth on the boss (`ram_trace_prologue_clear`, step 4275) and the two Soldiers on
 * Eirika (`ram_trace_prologue_enemy_phase`, steps 1697 and 2130).
 *
 * The step before the one in question holds `sProcScr_CombatAction` just started, with the battle
 * generated. The dump a few steps later (f4280, f1700, f2130) seeds the unit table, both battle
 * units, `gBattleStats` and the first hit; `BeginBattleAnimations` runs in a tree 2 proc, then
 * all trees run per step as `OnMain` does. Compared with the `procs` watch at the step of the
 * start and the two after it: the `ProcScr_MapAnimBattle` proc (script position, sleep timer, game
 * lock of `gBmSt`) and both `ProcScr_Mu` procs (unit, slot, state, class, facing, step sound
 * clock, position), and with the dump `gManimSt` (without the Mu pointers: actor count, round
 * pointer, special script, units, battle units, HP) and `sMuConfig`. The command after the last
 * compared one (the battle quote, EP-28) is replaced by an empty stand-in.
 */
class MapAnimStartDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    private class Case(val run: String, val dump: Int, val start: Int)

    @Test
    @VerifiesPort(
        "BeginBattleMapAnims", "SetupMapAnimSpellData", "SetupMapBattleAnim",
        "MakeBattleMOVEUNIT", "SetBattleAnimFacing", "SetupBattleMOVEUNITs", "sub_807B4D0",
        "SetMuFacing", "StartMu",
    )
    fun `the map animation start reproduces gManimSt, the MOVEUNIT procs and the animation proc`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val rom = RomView.ofRom(romFile.readBytes())
        val names = ProcArrayDump.romNames()
        for (case in CASES) {
            val run = ref.run(case.run)
            val dumpNames = run.dumps.entries.associate { it.value to it.key }
            val ewram = run.region(dumpNames.getValue(case.dump), "ewram")
            val iwram = run.region(dumpNames.getValue(case.dump), "iwram")
            val procs = run.watch("procs")
            val gbm = run.watch("gbmst")

            val table = UnitTable(rom).also { it.loadFrom(ewram) }
            val playSt = PlaySt().also { it.decode(ewram, GPLAYST - EWRAM) }
            val bm = BmSt().also { it.decode(ewram, GBMST - EWRAM) }
            val rng = RngService()
            val loader = UnitLoader(table, rom, { 0 }, rng, { false })
            val battle = BmBattle(rom, table, loader, playSt, bm) { _, _ -> 0 }
            val gen = BattleGenerate(battle, rom, loader, playSt, rng, Mechanics())
            ewram.copyInto(battle.actor.bytes, 0, GBATTLE_ACTOR - EWRAM, GBATTLE_ACTOR - EWRAM + BattleUnit.SIZE)
            ewram.copyInto(battle.target.bytes, 0, GBATTLE_TARGET - EWRAM, GBATTLE_TARGET - EWRAM + BattleUnit.SIZE)
            battle.stats.decode(ewram, GBATTLESTATS - EWRAM)
            battle.stats.config = battle.stats.config and BattleStats.CONFIG_MAPANIMS.inv()
            gen.hitArray[0] = u32(ewram, GHITARRAY - EWRAM)
            bm.lock = 0

            val engine = ProcEngine()
            val mu = Mu(engine, bm, playSt, KeyStatus(), CamMove(engine, bm).script, ClassHooks(rom))
            val api = MapAnimApi(engine, bm, battle, gen, table, loader, mu, rom)
            api.hooks.spritePalette = { id ->
                when (id and 0xC0) {
                    0x00 -> 0xC
                    0x80 -> 0xD
                    0x40 -> 0xE
                    else -> 0xB
                }
            }
            val hidden = ArrayList<Int>()
            api.hooks.hideUnitSprite = { id -> hidden += id }
            // The battle quote (EP-28) follows the last compared command.
            api.hooks.restOfAnimation = ProcFunc {}
            engine.start(
                ProcScript(
                    "CombatAction (test)",
                    listOf(
                        ProcScripts.call(ProcFunc { api.beginBattleAnimations() }),
                        ProcScripts.end(),
                    ),
                ),
                2,
            )

            for (step in case.start..case.start + 2) {
                for (tree in 0 until 8) engine.run(engine.root(tree))
                val at = "${case.run} step $step"
                val dump = ProcArrayDump.ofWatch(procs, step, names)
                val original = checkNotNull(dump.find("ProcScr_MapAnimBattle")) { "$at animation proc" }
                val mine = checkNotNull(engine.find(api.script)) { "$at my animation proc" }
                assertEquals(original.scrCur, mine.scrCur, "$at script position")
                assertEquals(original.u8(0x24), mine.sleepTime, "$at sleep timer")
                assertEquals(gbm.u8(step, 1), bm.lock, "$at gBmSt.lock")
                assertEquals(
                    (original.u8(0x26)), mine.mark, "$at mark",
                )

                val mus = dump.findAll("ProcScr_Mu".let { nameAddress(names, it) })
                    .sortedBy { it.slot }
                assertEquals(2, mus.size, "$at MOVEUNIT procs in the capture")
                for ((i, theirs) in mus.withIndex()) {
                    val m = api.state.mu[i]!!.state as MuProc
                    val mt = "$at MOVEUNIT $i"
                    assertEquals(theirs.u32(0x2C), m.unitAddress, "$mt unit")
                    assertEquals(theirs.u8(0x3C), m.slot, "$mt slot")
                    assertEquals(theirs.u8(0x3E), if (m.camB) 1 else 0, "$mt cam_b")
                    assertEquals(theirs.u8(0x3F), m.state, "$mt state")
                    assertEquals(theirs.u8(0x40), m.hiddenB, "$mt hidden_b")
                    assertEquals(theirs.u8(0x41), m.jid, "$mt jid")
                    assertEquals(theirs.u8(0x42), m.facing and 0xFF, "$mt facing")
                    assertEquals(theirs.u8(0x43), m.stepSoundClock, "$mt step_sound_clock")
                    assertEquals(theirs.u16(0x48), m.moveClockQ4, "$mt move_clock_q4")
                    assertEquals(theirs.u16(0x4C), m.xQ4 and 0xFFFF, "$mt x_q4")
                    assertEquals(theirs.u16(0x4E), m.yQ4 and 0xFFFF, "$mt y_q4")
                    assertEquals(theirs.u16(0x50), m.xOffsetQ4 and 0xFFFF, "$mt x_offset")
                    assertEquals(theirs.u16(0x52), m.yOffsetQ4 and 0xFFFF, "$mt y_offset")
                    val config = mu.configs[m.slot]
                    val c = CFG - IW + 0x4C * m.slot
                    assertEquals(iwram[c].toInt() and 0xFF, config.slot, "$mt config.slot")
                    assertEquals(iwram[c + 1].toInt() and 0xFF, config.pal, "$mt config.pal")
                    assertEquals(
                        (iwram[c + 2].toInt() and 0xFF) or ((iwram[c + 3].toInt() and 0xFF) shl 8),
                        config.chr, "$mt config.chr",
                    )
                }
            }

            // gManimSt of the dump (MOVEUNIT pointers and the fields later steps set excepted).
            val s = api.state
            val g = GMANIM - EWRAM
            val tag = "${case.run} f${case.dump} gManimSt"
            assertEquals(ewram[g + 0x5E].toInt(), s.actorCount, "$tag actorCount")
            assertEquals(u32(ewram, g + 0x50), GHITARRAY + 4 * s.currentRound, "$tag pCurrentRound")
            assertEquals(u32(ewram, g + 0x54), s.specialProcScr, "$tag specialProcScr")
            assertEquals(ewram[g + 0x5F].toInt(), s.hpChanging, "$tag hp_changing")
            assertEquals(ewram[g + 0x62].toInt(), s.mapAnimKind, "$tag mapAnimKind")
            for (i in 0 until s.actorCount) {
                val a = g + 0x14 * i
                assertEquals(u32(ewram, a), s.unitAddress[i], "$tag actor $i unit")
                assertEquals(u32(ewram, a + 4), s.buAddress[i], "$tag actor $i bu")
                assertEquals(ewram[a + 0x0C].toInt() and 0xFF, s.hpMax[i], "$tag actor $i hp_max")
                assertEquals(ewram[a + 0x0D].toInt() and 0xFF, s.hpCur[i], "$tag actor $i hp_cur")
            }
            // The target's map sprite is hidden: its handle in the dump has the hide bit.
            val targetId = battle.target.bytes[0x0B].toInt() and 0xFF
            val handle = u32(table.bytes, table.unitOffset(targetId) + 0x3C)
            assertEquals(
                0x80, ewram[handle - EWRAM + 0xB].toInt() and 0x80, "$tag target handle hidden",
            )
            assertEquals(listOf(targetId), hidden, "$tag HideUnitSprite calls")
            assertEquals(
                true, battle.stats.config and BattleStats.CONFIG_MAPANIMS != 0,
                "$tag BATTLE_CONFIG_MAPANIMS set",
            )
        }
    }

    private fun nameAddress(names: Map<Int, String>, name: String): Int =
        names.entries.first { it.value == name }.key

    private class ClassHooks(private val rom: RomView) : MuHooks {
        override fun classAttributes(jid: Int): Int =
            rom.u32(GCLASS_DATA + (jid - 1) * CLASS_SIZE + 0x28)

        override fun classSlowWalking(jid: Int): Int =
            rom.u8(GCLASS_DATA + (jid - 1) * CLASS_SIZE + 0x07)
    }

    private fun u32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private companion object {
        const val EWRAM = 0x02000000
        const val GCLASS_DATA = 0x08807164
        const val CLASS_SIZE = 0x54
        const val IW = 0x03000000
        const val CFG = 0x03001900
        const val GBMST = 0x0202BCB0
        const val GPLAYST = 0x0202BCF0
        const val GBATTLESTATS = 0x0203A4D4
        const val GBATTLE_ACTOR = 0x0203A4EC
        const val GBATTLE_TARGET = 0x0203A56C
        const val GHITARRAY = 0x0203A5EC
        const val GMANIM = 0x0203E1F0
        val CASES = listOf(
            Case("ram_trace_prologue_clear", 4280, 4275),
            Case("ram_trace_prologue_enemy_phase", 1700, 1697),
            Case("ram_trace_prologue_enemy_phase", 2130, 2130),
        )
    }
}
