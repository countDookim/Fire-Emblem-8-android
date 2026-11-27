package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BattleGenerate
import fe8.game.bm.BmBattle
import fe8.game.bm.BmSt
import fe8.game.bm.PlaySt
import fe8.game.bm.UnitLoader
import fe8.game.bm.UnitTable
import fe8.game.event.EventEngine
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
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.repeat
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * PC-10k: the scripted kill of the Easy tutorial in `ram_trace_newgame_easy` (`f24210..24700`):
 * `Event3F_ScriptBattle` (FIGHT, hits from the slot queue) at `f24262`, then the blocking
 * `ScriptBattleDeamon` until the game lock is back, whose `EventBattleReloadBmStatus` writes the
 * HP back (red slot 2: 20 -> 0 at `f24647`).
 *
 * Inputs: the unit table, `gPlaySt`, `gBmSt` and the terrain of the dump `r24261`, the event
 * slots and slot queue of the `evglobals` watch at `f24262`. Compared at `f24262` with the
 * `bactor`, `btarget`, `bstats` and `bhits` watches: the battle units, the stats and the hit
 * array up to the END hit. Then the proc engine runs the deamon frame by frame: the host's game
 * lock is the capture's `gBmSt` lock byte (the animation takes it one frame after the command;
 * the deamon sees the lock of the frame before, `f24647` being its first frame at the saved
 * value). Per frame the HP of Seth (blue slot 0) and of the Soldier (red slot 2) equal the
 * `blue` and `red` watches, the deamon exists as in the `procs` watch (which is one step
 * behind), and the event proc mark is `PROC_MARK_EVENT_ANIM` until the reload.
 *
 * Not exercised: the map-animation death branch (the fight is an animation fight), the item
 * backup, the refresh hooks (no-ops here), KILL and DISA_IF after the deamon (`f24666..24699`).
 */
@Timeout(300)
class EasyTutorialScriptedKillDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    @VerifiesPort(
        "Event3F_ScriptBattle", "GenerateScriptBattleHitFormEventQueue", "ScriptBattleDeamon",
        "EventBattleReloadBmStatus",
    )
    fun `scripted kill matches the capture f24262 to f24650`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        assumeTrue(File(ref.root, "$RUN/r$BEFORE.ewram.bin").isFile, "run $RUN not present")
        val r = ref.run(RUN)
        val romBytes = romFile.readBytes()
        val rom = RomView.ofRom(romBytes)
        val mem = ScriptMemory { a ->
            val o = a - 0x08000000
            (romBytes[o].toInt() and 0xFF) or ((romBytes[o + 1].toInt() and 0xFF) shl 8)
        }
        val d = r.region("r$BEFORE", "ewram")
        val g = r.watch("evglobals").step(STEP)
        val lockWatch = r.watch("gbmst")
        val blue = r.watch("blue")
        val red = r.watch("red")
        val procsWatch = r.watch("procs")
        val names = ProcArrayDump.romNames()

        val table = UnitTable(rom).also { it.loadFrom(d) }
        val playSt = PlaySt().also { it.decode(d, GPLAYST - EWRAM) }
        val bmSt = BmSt().also { it.decode(d, GBMST - EWRAM) }
        val rng = RngService()
        val loader = UnitLoader(table, rom, { 0 }, rng, { false })
        val battle = BmBattle(rom, table, loader, playSt, bmSt) { y, x ->
            d[u32(d, u32(d, GBMMAPTERRAIN - EWRAM) - EWRAM + 4 * y) - EWRAM + x].toInt() and 0xFF
        }
        val gen = BattleGenerate(battle, rom, loader, playSt, rng, Mechanics())
        // The arrays are not cleared by the fight: the END hit keeps the attributes of the earlier
        // battle's entry, so the arrays start with the content of the capture before the command.
        val before = r.watch("bhits").step(STEP - 1)
        val scripts = r.watch("actiondata").step(STEP - 1)
        for (i in 0 until HIT_WORDS) {
            gen.hitArray[i] = u32(before, 4 * i)
            gen.scriptHits[i] = u32(scripts, 0x1C + 4 * i)
        }
        val state = EventState()
        for (i in state.slots.indices) state.slots[i] = u32(g, 0x80 + 4 * i)
        for (i in state.slotQueue.indices) state.slotQueue[i] = u32(g, 0xB8 + 4 * i)

        var frame = STEP
        var animationLocked = false
        val startLock = lockWatch.u8(STEP - 1, 1)
        val host = object : EventScriptBattleHost {
            override fun getUnitStructFromEventParameter(pid: Int): Int {
                if (pid != -2) return table.getUnitFromCharId(pid)
                // CHAR_EVT_POSITION_AT_SLOTB: the unit at the tile of slot 0xB in gBmMapUnit
                val slot = state.slots[0xB]
                val x = slot and 0xFFFF
                val y = slot ushr 16
                val rows = u32(d, GBMMAPUNIT - EWRAM) - EWRAM
                return d[u32(d, rows + 4 * y) - EWRAM + x].toInt() and 0xFF
            }

            override fun gameLock(): Int =
                if (frame == STEP) startLock else lockSeen(lockWatch.u8(frame, 1), frame)

            override fun startScriptBattleAnim(scripted: Boolean, useMapAnims: Boolean) {
                animationLocked = true
            }

            private fun lockSeen(captured: Int, f: Int): Int =
                if (f == STEP + 1) captured else lockWatch.u8(f - 1, 1)
        }
        val engine = ProcEngine().also { it.init() }
        val script = EventScriptBattle(engine, state, mem, table, gen, host)
        val parentScript = ProcScript("test event proc", listOf(repeat(ProcFunc { }), end()))
        val parent = engine.start(parentScript, 0)
        val proc = EventEngineProc().also {
            it.proc = parent
            it.pEventCurrent = CMD_PC
            it.evStateBits = 0
        }

        assertEquals(2, script.event3FScriptBattle.run(proc), "EVC_ADVANCE_YIELD")
        assertTrue(animationLocked)
        assertEquals(PROC_MARK_EVENT_ANIM, parent.mark, "event proc mark while fighting")
        assertNotNull(engine.find(script.deamonScript), "deamon started")
        assertEquals(
            hex(r.watch("bactor").step(STEP), 0x80), hex(battle.actor.bytes, 0x80), "gBattleActor",
        )
        assertEquals(
            hex(r.watch("btarget").step(STEP), 0x80), hex(battle.target.bytes, 0x80),
            "gBattleTarget",
        )
        val stats = ByteArray(0x18).also { battle.stats.encode(it, 0) }
        assertEquals(hex(r.watch("bstats").step(STEP), 0x18), hex(stats, 0x18), "gBattleStats")
        val hitWatch = r.watch("bhits").step(STEP)
        val want = StringBuilder()
        val got = StringBuilder()
        var i = 0
        while (true) {
            val w = u32(hitWatch, 4 * i)
            want.append("%08X ".format(w))
            got.append("%08X ".format(gen.hitArray[i]))
            if ((w ushr 19) and 0x1F and INFO_END != 0 || ++i >= HIT_WORDS) break
        }
        assertEquals(want.toString(), got.toString(), "gBattleHitArray")

        val sethOffset = 0
        val soldierOffset = RED_BASE + 2 * UnitTable.UNIT_SIZE
        var reloadFrame = -1
        for (f in STEP + 1..LAST) {
            frame = f
            engine.run(engine.root(0))
            assertEquals(
                blue.u8(f, UNIT_CUR_HP), table.bytes[sethOffset + UNIT_CUR_HP].toInt() and 0xFF,
                "Seth HP f$f",
            )
            assertEquals(
                red.u8(f, 2 * UnitTable.UNIT_SIZE + UNIT_CUR_HP),
                table.bytes[soldierOffset + UNIT_CUR_HP].toInt() and 0xFF,
                "Soldier HP f$f",
            )
            if (reloadFrame < 0 && table.bytes[soldierOffset + UNIT_CUR_HP].toInt() == 0) {
                reloadFrame = f
            }
            assertEquals(
                procsWatch.let { ProcArrayDump.ofWatch(it, f + 1, names) }
                    .find("ProcScr_ScriptBattleDeamon") != null,
                engine.find(script.deamonScript) != null,
                "deamon exists after f$f",
            )
        }
        assertEquals(RELOAD_FRAME, reloadFrame, "frame of EventBattleReloadBmStatus")
        assertEquals(EventEngine.PROC_MARK_EVENT, parent.mark, "event proc mark after the fight")
        assertNull(engine.find(script.deamonScript))
        assertEquals(0, table.bytes[soldierOffset + UNIT_CUR_HP].toInt())
    }

    private fun assertTrue(b: Boolean) = org.junit.jupiter.api.Assertions.assertTrue(b)

    private fun u32(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or
        ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or
        ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun hex(b: ByteArray, n: Int) = (0 until n).joinToString("") { "%02X".format(b[it]) }

    private companion object {
        const val RUN = "ram_trace_newgame_easy"
        const val STEP = 24262
        const val BEFORE = 24261
        const val LAST = 24650
        const val RELOAD_FRAME = 24647
        const val CMD_PC = 0x089EF5DC
        const val PROC_MARK_EVENT_ANIM = 7
        const val EWRAM = 0x02000000
        const val GPLAYST = 0x0202BCF0
        const val GBMST = 0x0202BCB0
        const val GBMMAPUNIT = 0x0202E4D8
        const val GBMMAPTERRAIN = 0x0202E4DC
        const val RED_BASE = 62 * 0x48
        const val UNIT_CUR_HP = 0x13
        const val HIT_WORDS = 7
        const val INFO_END = 1 shl 4
    }
}
