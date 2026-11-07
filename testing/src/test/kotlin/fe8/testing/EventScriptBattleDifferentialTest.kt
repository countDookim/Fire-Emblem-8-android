package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BattleGenerate
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
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * `Event3F_ScriptBattle` (and the hit queue it builds, `GenerateScriptBattleHitFormEventQueue`)
 * against `ram_trace_prologue_battle`. Every step has an EWRAM dump (`r<step>`) and watches
 * equal the dump of the same step. The `procs` watch gives the first step S where the engine
 * proc's `pEventCurrent` is on a 0x3F command. The frame ends inside the handler: the dump of
 * step S already holds the generated battle, the ready slot queue and the unchanged pc.
 *
 * Inputs: unit table, `gPlaySt`, `gBmSt`, the map terrain and the battle structs of the dump of
 * step S-1 (before the battle; the unit state differs in `US_HIDDEN`, which the start of the
 * battle sets), the event slots and slot queue from the `evglobals` watch at step S (the handler
 * only reads them), `evStateBits` from the proc at step S. Nothing is seeded for the RNG: scripted
 * battles roll nothing.
 *
 * Compared with the dump of step S, with no masks: `gBattleActor` and `gBattleTarget` (0x80
 * bytes each), `gBattleStats` and `gBattleHitArray` up to and including the END hit. The hit
 * array is the slot queue turned into hits ([EventScriptBattle.generateScriptBattleHits]: begin
 * and END info, the retaliation side, attributes) and unwound by the real battle, so the queue
 * conversion is verified through it. Not exercised: the deamon (the harness stays at the
 * command), `weaponId != 0`, FIGHT_SCRIPT, ballista, the skipping path.
 */
class EventScriptBattleDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val run = "ram_trace_prologue_battle"
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    @VerifiesPort("Event3F_ScriptBattle", "GenerateScriptBattleHitFormEventQueue")
    fun `script battle command generates the traced battle`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        assumeTrue(File(ref.root, "$run/procs.watch.bin").isFile, "run $run not present")
        val r = ref.run(run)
        val romBytes = romFile.readBytes()
        val rom = RomView.ofRom(romBytes)
        val procsWatch = r.watch("procs")
        val globals = r.watch("evglobals")
        val names = ProcArrayDump.romNames()
        val mem = ScriptMemory { a ->
            val o = a - 0x08000000
            (romBytes[o].toInt() and 0xFF) or ((romBytes[o + 1].toInt() and 0xFF) shl 8)
        }
        var found = 0
        for (step in procsWatch.firstStep..procsWatch.lastStep) {
            val engine = ProcArrayDump.ofWatch(procsWatch, step, names)
                .find(EventTrace.STD_EVENT_ENGINE) ?: continue
            val cur = engine.u32(0x38)
            if (cur.toUInt() < 0x08000000u || cur.toUInt() >= 0x0A000000u) continue
            if (mem.u16(cur) ushr 8 != 0x3F) continue
            if (!File(ref.root, "$run/r${step - 1}.ewram.bin").isFile ||
                !File(ref.root, "$run/r$step.ewram.bin").isFile
            ) {
                continue
            }
            // The command is parked on the same pc for several steps; use the first one.
            val prev = ProcArrayDump.ofWatch(procsWatch, step - 1, names)
                .find(EventTrace.STD_EVENT_ENGINE)
            if (prev?.u32(0x38) == cur) continue
            println("EventScriptBattle: 0x3F at step $step pc=%08X".format(cur))
            found++
            check(r, rom, mem, step, cur, engine.u16(0x3C), globals.step(step))
        }
        assumeTrue(found > 0, "no 0x3F command in $run")
    }

    private fun check(
        r: ReferenceData.Run,
        rom: RomView,
        mem: ScriptMemory,
        step: Int,
        cur: Int,
        evStateBits: Int,
        g: ByteArray,
    ) {
        val d = r.region("r${step - 1}", "ewram")
        val after = r.region("r$step", "ewram")
        val table = UnitTable(rom).also { it.loadFrom(d) }
        val playSt = PlaySt().also { it.decode(d, GPLAYST - EWRAM) }
        val bmSt = BmSt().also { it.decode(d, GBMST - EWRAM) }
        val rng = RngService()
        val loader = UnitLoader(table, rom, { 0 }, rng, { false })
        val battle = BmBattle(rom, table, loader, playSt, bmSt) { y, x ->
            // gBmMapTerrain is a u8** (row pointers into the map pool).
            d[u32(d, u32(d, GBMMAPTERRAIN - EWRAM) - EWRAM + 4 * y) - EWRAM + x].toInt() and 0xFF
        }
        val gen = BattleGenerate(battle, rom, loader, playSt, rng, Mechanics())
        val state = EventState()
        for (i in state.slots.indices) state.slots[i] = u32(g, 0x80 + 4 * i)
        for (i in state.slotQueue.indices) state.slotQueue[i] = u32(g, 0xB8 + 4 * i)
        val host = object : EventScriptBattleHost {
            override fun getUnitStructFromEventParameter(pid: Int): Int =
                table.getUnitFromCharId(pid)

            override fun gameLock(): Int = 0
        }
        val handler = EventScriptBattle(ProcEngine(), state, mem, table, gen, host)
        val proc = EventEngineProc().also {
            it.pEventCurrent = cur
            it.evStateBits = evStateBits
        }

        val result = handler.event3FScriptBattle.run(proc)

        assertEquals(2, result, "EVC_ADVANCE_YIELD at step $step")
        assertEquals(
            hex(after, GACTOR - EWRAM, 0x80), hex(battle.actor.bytes, 0, 0x80),
            "gBattleActor at step $step",
        )
        assertEquals(
            hex(after, GTARGET - EWRAM, 0x80), hex(battle.target.bytes, 0, 0x80),
            "gBattleTarget at step $step",
        )
        val stats = ByteArray(0x18).also { battle.stats.encode(it, 0) }
        assertEquals(hex(after, GBATTLESTATS - EWRAM, 0x18), hex(stats, 0, 0x18), "gBattleStats")
        val want = StringBuilder()
        val got = StringBuilder()
        var i = 0
        while (true) {
            val w = u32(after, GHITARRAY - EWRAM + 4 * i)
            want.append("%08X ".format(w))
            got.append("%08X ".format(gen.hitArray[i]))
            if ((w ushr 19) and 0x1F and INFO_END != 0 || ++i >= HIT_WORDS) break
        }
        assertEquals(want.toString(), got.toString(), "gBattleHitArray at step $step")
    }

    private fun u32(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or
        ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or
        ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun hex(b: ByteArray, o: Int, n: Int) =
        (o until o + n).joinToString("") { "%02X".format(b[it]) }

    private companion object {
        const val EWRAM = 0x02000000
        const val GPLAYST = 0x0202BCF0
        const val GBMST = 0x0202BCB0
        const val GBMMAPTERRAIN = 0x0202E4DC
        const val GBATTLESTATS = 0x0203A4D4
        const val GACTOR = 0x0203A4EC
        const val GTARGET = 0x0203A56C
        const val GHITARRAY = 0x0203A5EC
        const val HIT_WORDS = 7
        const val INFO_END = 1 shl 4
    }
}
