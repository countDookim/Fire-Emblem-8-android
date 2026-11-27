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
import fe8.game.event.EventEngineProc
import fe8.game.event.EventScriptBattle
import fe8.game.event.EventScriptBattleHost
import fe8.game.event.EventState
import fe8.game.event.ScriptMemory
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.core.mechanics.Mechanics
import gbafe.core.rng.RngService
import gbafe.proc.ProcEngine
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * PC-10l: the scripted player attack of the Easy tutorial in `ram_trace_newgame_easy`.
 *
 * Step 1, the `EVSUBCMD_FIGHT_SCRIPT` command of `TutEirikaAttack` (the `Event3F` command that
 * follows the pc the event engine proc is parked on, its low nibble 2): from the event slots and
 * slot queue of
 * the `evglobals` watch after the step, `Event3F_ScriptBattle` continues (`EVC_ADVANCE_CONTINUE`)
 * and `SetScriptedBattle` stores the hit list: `gActionData.scriptedBattleHits` of the
 * `actiondata` watch after the command is the address of `script_hits`, whose words equal the
 * hits built from the queue.
 *
 * Step 2, `BattleGenerateReal` of the player's attack at `f27634` (dump `r27632` before, `r27633`
 * after): the same port state seeds the unit table, `gPlaySt`, `gBmSt`, the terrain, both battle
 * units, `gBattleStats`, the hit array and the RNG. `BattleGenerate` takes the scripted branch,
 * `BattleUnwindScripted` replays the hits NormalDamage, NormalDamage, CriticalHit (the last
 * FINISHES and KILLS_TARGET, then END) with `BattleGenerateHitScriptedDamage`. Compared with the
 * `r27634` dump: both battle units whole (HP, level 1 -> 2, exp, stat ups), `gBattleStats`, the
 * hit array, the iterator, the RNG state and the cleared `scriptedBattleHits`; and the battle
 * units' HP, level, exp and max HP with the `blue` and `red` watches at `f28790`, where the game
 * writes them back to the units (boss 23 -> 0, Eirika 16 -> 6, level 2, exp 22, max HP 17).
 *
 * Not exercised: the write-back itself (`BattleApplyGameStateUpdates`), the animation.
 */
@Timeout(300)
class EasyTutorialScriptedAttackDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    @VerifiesPort(
        "Event3F_ScriptBattle", "SetScriptedBattle", "GenerateScriptBattleHitFormEventQueue",
        "BattleGenerate", "BattleUnwindScripted", "BattleGenerateHitScriptedDamage",
    )
    fun `scripted player attack matches the capture f26308 and f27634`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        assumeTrue(File(ref.root, "$RUN/r$BEFORE.ewram.bin").isFile, "run $RUN not present")
        val r = ref.run(RUN)
        val romBytes = romFile.readBytes()
        val rom = RomView.ofRom(romBytes)
        val mem = ScriptMemory { a ->
            val o = a - 0x08000000
            (romBytes[o].toInt() and 0xFF) or ((romBytes[o + 1].toInt() and 0xFF) shl 8)
        }
        val before = r.region("r$BEFORE", "ewram")
        val beforeIw = r.region("r$BEFORE", "iwram")
        val after = r.region("r$AFTER", "ewram")
        val afterIw = r.region("r$AFTER", "iwram")
        val actionData = r.watch("actiondata")
        val globals = r.watch("evglobals")

        val table = UnitTable(rom).also { it.loadFrom(before) }
        val playSt = PlaySt().also { it.decode(before, GPLAYST - EWRAM) }
        val bmSt = BmSt().also { it.decode(before, GBMST - EWRAM) }
        val rows = u32(before, GBMMAPTERRAIN - EWRAM) - EWRAM
        val terrainAt = { y: Int, x: Int ->
            before[u32(before, rows + 4 * y) - EWRAM + x].toInt() and 0xFF
        }
        val rng = RngService()
        rng.primary.load(u16(beforeIw, 0), u16(beforeIw, 2), u16(beforeIw, 4))
        val loader = UnitLoader(table, rom, { 0 }, rng, { false })
        val battle = BmBattle(rom, table, loader, playSt, bmSt, terrainAt)
        val gen = BattleGenerate(battle, rom, loader, playSt, rng, Mechanics())

        // Step 1: the FIGHT_SCRIPT command. The command is the one the event engine proc is
        // parked on before the step that sets gActionData.scriptedBattleHits.
        var setStep = -1
        for (s in LOOK_FIRST..LOOK_LAST) {
            if (actionData.u32(s, POINTER_AT) != 0) {
                setStep = s
                break
            }
        }
        assertEquals(SET_STEP, setStep, "step of SetScriptedBattle")
        val procs = r.watch("procs")
        val cmdProc = ProcArrayDump.ofWatch(procs, setStep - 1, ProcArrayDump.romNames())
            .find(EventTrace.STD_EVENT_ENGINE)
        assertNotNull(cmdProc, "event engine proc")
        // The engine runs the whole run of commands (slot set-up, the queued hits, the command)
        // in the one step: walk the commands from where it was parked until the Event3F.
        var cur = cmdProc!!.u32(0x38)
        while (mem.u16(cur) ushr 8 != 0x3F) {
            check(cur - cmdProc.u32(0x38) < 0x200) { "no Event3F command after the parked pc" }
            cur += 2 * ((mem.u16(cur) ushr 4) and 0xF)
        }
        assertEquals(0x3F, mem.u16(cur) ushr 8, "Event3F command")
        assertEquals(2, mem.u16(cur) and 0xF, "EVSUBCMD_FIGHT_SCRIPT")
        assertNull(gen.scriptedBattleHits)
        val g = globals.step(setStep)
        val state = EventState()
        for (i in state.slots.indices) state.slots[i] = u32(g, 0x80 + 4 * i)
        for (i in state.slotQueue.indices) state.slotQueue[i] = u32(g, 0xB8 + 4 * i)
        assertTrue(state.slots[0xD] != 0, "the script queued hits")
        val host = object : EventScriptBattleHost {
            override fun getUnitStructFromEventParameter(pid: Int): Int = error("not a fight")

            override fun gameLock(): Int = 0
        }
        val handler = EventScriptBattle(ProcEngine(), state, mem, table, gen, host)
        val proc = EventEngineProc().also {
            it.pEventCurrent = cur
            it.evStateBits = cmdProc.u16(0x3C)
        }
        assertEquals(0, handler.event3FScriptBattle.run(proc), "EVC_ADVANCE_CONTINUE")
        val hits = checkNotNull(gen.scriptedBattleHits) { "scriptedBattleHits set" }
        assertSame(gen.scriptHits, hits, "the pointer is script_hits")
        assertEquals(SCRIPT_HITS, actionData.u32(setStep, POINTER_AT), "pointer value")
        var n = 0
        while (true) {
            assertEquals(
                "%08X".format(actionData.u32(setStep, SCRIPT_HITS_AT + 4 * n)),
                "%08X".format(hits[n]), "script_hits[$n]",
            )
            if ((hits[n] ushr 19) and 0x1F and INFO_END != 0) break
            n++
        }
        assertEquals(3, n, "two hits then END")
        // the pointer stays until the battle: nothing between sets or clears it
        assertEquals(SCRIPT_HITS, actionData.u32(BEFORE, POINTER_AT), "pointer before the fight")

        // Step 2: BattleGenerateReal.
        before.copyInto(battle.actor.bytes, 0, GACTOR - EWRAM, GACTOR - EWRAM + BattleUnit.SIZE)
        before.copyInto(battle.target.bytes, 0, GTARGET - EWRAM, GTARGET - EWRAM + BattleUnit.SIZE)
        battle.stats.decode(before, GBATTLESTATS - EWRAM)
        battle.actor.bytes[0x7E] = 0 // ActionCombat clears the item-effect flag
        for (i in 0 until BattleGenerate.BATTLE_HIT_MAX) {
            gen.hitArray[i] = u32(before, GHITARRAY - EWRAM + 4 * i)
        }
        val actorId = before[GACTIONDATA - EWRAM + 0x0C].toInt() and 0xFF
        val targetId = before[GACTIONDATA - EWRAM + 0x0D].toInt() and 0xFF
        gen.battleGenerateReal(table.bytes, table.unitOffset(actorId), table.unitOffset(targetId))

        for ((bu, at) in listOf(battle.actor to GACTOR, battle.target to GTARGET)) {
            val diffs = ArrayList<String>()
            for (i in 0 until BattleUnit.SIZE) {
                val want = after[at - EWRAM + i]
                if (bu.bytes[i] != want) {
                    diffs += "+0x%02X: port %02X dump %02X".format(
                        i, bu.bytes[i].toInt() and 0xFF, want.toInt() and 0xFF,
                    )
                }
            }
            assertTrue(diffs.isEmpty(), "unit at ${at.toString(16)}:\n" + diffs.joinToString("\n"))
        }
        val stats = ByteArray(BattleStats.SIZE)
        battle.stats.encode(stats, 0)
        val wantStats = after.copyOfRange(GBATTLESTATS - EWRAM, GBATTLESTATS - EWRAM + stats.size)
        assertEquals(hex(wantStats, stats.size), hex(stats, stats.size), "gBattleStats")
        for (i in 0 until BattleGenerate.BATTLE_HIT_MAX) {
            assertEquals(
                "%08X".format(u32(after, GHITARRAY - EWRAM + 4 * i)),
                "%08X".format(gen.hitArray[i]), "gBattleHitArray[$i]",
            )
        }
        assertEquals(
            u32(after, GHITARRAY - EWRAM + 4 * BattleGenerate.BATTLE_HIT_MAX),
            GHITARRAY + 4 * gen.hitIterator, "gBattleHitIterator",
        )
        assertEquals(2, gen.hitIterator, "iterator on the finishing hit")
        for (w in 0 until 3) assertEquals(u16(afterIw, 2 * w), rng.primary.word(w), "rng $w")
        assertNull(gen.scriptedBattleHits, "scriptedBattleHits cleared")
        assertEquals(0, u32(after, GACTIONDATA - EWRAM + POINTER_AT), "pointer cleared")

        // HP, level, exp and max HP as the game writes them back at f28790.
        val blue = r.watch("blue")
        val red = r.watch("red")
        val eirika = battle.actor.bytes
        val boss = battle.target.bytes
        assertEquals(6, eirika[UNIT_CUR_HP].toInt() and 0xFF, "Eirika HP after")
        assertEquals(0, boss[UNIT_CUR_HP].toInt() and 0xFF, "boss HP after")
        assertEquals(16, blue.u8(WRITE_BACK - 1, UNIT_SIZE + UNIT_CUR_HP), "Eirika HP before")
        assertEquals(23, red.u8(WRITE_BACK - 1, UNIT_CUR_HP), "boss HP before f28790")
        for ((off, name) in listOf(UNIT_LEVEL to "level", UNIT_EXP to "exp", UNIT_CUR_HP to "HP")) {
            assertEquals(
                blue.u8(WRITE_BACK, UNIT_SIZE + off), eirika[off].toInt() and 0xFF,
                "Eirika $name at f$WRITE_BACK",
            )
        }
        // the level-up's stat gains are added to the unit when the game writes it back
        assertEquals(
            blue.u8(WRITE_BACK, UNIT_SIZE + UNIT_MAX_HP),
            (eirika[UNIT_MAX_HP].toInt() and 0xFF) + eirika[BU_CHANGE_HP], "Eirika max HP",
        )
        assertEquals(red.u8(WRITE_BACK, UNIT_CUR_HP), boss[UNIT_CUR_HP].toInt() and 0xFF, "boss HP")
        assertEquals(2, eirika[UNIT_LEVEL].toInt() and 0xFF, "level-up")
    }

    private fun u16(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, o: Int) = u16(b, o) or (u16(b, o + 2) shl 16)

    private fun hex(b: ByteArray, n: Int) = (0 until n).joinToString("") { "%02X".format(b[it]) }

    private companion object {
        const val RUN = "ram_trace_newgame_easy"
        const val BEFORE = 27633
        const val AFTER = 27634
        const val SET_STEP = 26308
        const val LOOK_FIRST = 26200
        const val LOOK_LAST = 26400
        const val WRITE_BACK = 28790
        const val POINTER_AT = 0x18
        const val SCRIPT_HITS_AT = 0x1C
        const val SCRIPT_HITS = 0x0203A974
        const val EWRAM = 0x02000000
        const val GPLAYST = 0x0202BCF0
        const val GBMST = 0x0202BCB0
        const val GBMMAPTERRAIN = 0x0202E4DC
        const val GBATTLESTATS = 0x0203A4D4
        const val GACTOR = 0x0203A4EC
        const val GTARGET = 0x0203A56C
        const val GHITARRAY = 0x0203A5EC
        const val GACTIONDATA = 0x0203A958
        const val UNIT_SIZE = 0x48
        const val UNIT_LEVEL = 0x08
        const val UNIT_EXP = 0x09
        const val UNIT_MAX_HP = 0x12
        const val UNIT_CUR_HP = 0x13
        const val BU_CHANGE_HP = 0x73
        const val INFO_END = 1 shl 4
    }
}
