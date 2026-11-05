package fe8.testing

import fe8.game.event.EventConst.EVC_ADVANCE_CONTINUE
import fe8.game.event.EventConst.EVC_ADVANCE_YIELD
import fe8.game.event.EventConst.EVC_END
import fe8.game.event.EventConst.EVC_STOP_YIELD
import fe8.game.event.EventConst.EV_STATE_SKIPPING
import fe8.game.event.EventEngineProc
import fe8.game.event.EventHandler
import fe8.game.event.EventScrCore
import fe8.game.event.EventState
import fe8.game.event.ScriptMemory
import gba.testkit.ReferenceData
import java.io.File
import java.util.TreeMap
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Differential test of the ported core event commands against the mGBA `ram_trace_prologue`
 * trace. A trace step is one emulated frame, and `EventEngine_OnUpdate` runs commands until one
 * yields, so one step pair (A, B) is a run of commands, not a single one. For each pair the
 * engine state of A is loaded, commands are executed with the ported handlers following
 * `EventEngine_OnUpdate` (slot 0 cleared before each command, advance by the length nibble,
 * stop on a yield or end) and the result is compared with B. A pair is skipped as soon as the
 * run reaches a command without a ported handler, or when the engine is absent in A. Slot 0 is
 * not compared (`OnUpdate` zeroes it and other code may write it).
 */
class EventVmDifferentialTest {
    private val assets =
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets"

    private class Stats {
        var executed = 0
        var mismatches = 0
        val details = ArrayList<String>()
    }

    @Test
    fun `ported core commands match the prologue trace`() {
        val romFile = File(assets, "baserom.gba")
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        val ref = ReferenceData.fromEnvironment()
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val trace = EventTrace.prologue(ref)
        assumeTrue(trace != null, "ram_trace_prologue watches not present")
        val rom = romFile.readBytes()
        val mem = ScriptMemory { a ->
            val o = a - 0x08000000
            (rom[o].toInt() and 0xFF) or ((rom[o + 1].toInt() and 0xFF) shl 8)
        }
        val state = EventState()
        val core = EventScrCore(state, mem, object : fe8.game.event.EventInput {})
        val table = HashMap<Int, EventHandler>()
        table[0x00] = core.event00Null
        table[0x01] = core.event01End
        table[0x05] = core.event05SetSlot
        table[0x06] = core.event06SlotOperation
        table[0x07] = core.event07SlotQueueOperations
        table[0x08] = core.event08Label
        table[0x09] = core.event09Goto
        table[0x0A] = core.event0aCall
        table[0x0C] = core.event0cBranch
        table[0x0E] = core.event0eStal
        table[0x0F] = core.event0fCounterOps

        val stats = TreeMap<Int, Stats>()
        val seen = HashSet<Int>()
        var pairs = 0
        var skippedNoEngine = 0
        var skippedUnported = 0
        var skippedStuck = 0
        var compared = 0
        var stubbed = 0
        val unportedHist = TreeMap<Int, Int>()
        var partial = 0
        val steps = trace!!.steps
        var prev: EventSnapshot? = null
        for (step in steps) {
            val b = trace.at(step)
            val a = prev
            prev = b
            if (a == null) continue
            pairs++
            val ea = a.engine
            if (ea == null || ea.pEventCurrent == 0) {
                skippedNoEngine++
                continue
            }
            loadState(state, a)
            val proc = EventEngineProc()
            proc.pEventStart = ea.pEventStart
            proc.pEventIdk = ea.pEventIdk
            proc.pEventCurrent = ea.pEventCurrent
            proc.evStateBits = ea.evStateBits
            proc.evStallTimer = ea.evStallTimer
            proc.execType = ea.execType
            val codes = ArrayList<Int>()
            var ended = false
            var unported = false
            var stuck = false
            while (true) {
                if (codes.size > MAX_COMMANDS) {
                    stuck = true
                    break
                }
                state.slots[0] = 0
                val code = mem.u16(proc.pEventCurrent) ushr 8
                val handler = table[code]
                if (handler == null) {
                    val eb = b.engine
                    val skipping = proc.evStateBits and EV_STATE_SKIPPING != 0
                    val stubbable = code in STUB_YIELD && eb != null &&
                        eb.pEventCurrent == proc.pEventCurrent +
                        ((mem.u16(proc.pEventCurrent) ushr 4) and 0xF) * 2 &&
                        !(skipping && code != 0x30)
                    if (stubbable) {
                        proc.pEventCurrent += ((mem.u16(proc.pEventCurrent) ushr 4) and 0xF) * 2
                        stubbed++
                        break
                    }
                    unportedHist.merge(code, 1, Int::plus)
                    unported = true
                    break
                }
                codes += code
                seen += code
                val r = handler.run(proc)
                if (r == EVC_ADVANCE_CONTINUE || r == EVC_ADVANCE_YIELD) {
                    proc.pEventCurrent += ((mem.u16(proc.pEventCurrent) ushr 4) and 0xF) * 2
                }
                if (r == EVC_ADVANCE_YIELD || r == EVC_STOP_YIELD) break
                if (r == EVC_END) {
                    ended = true
                    break
                }
            }
            if (unported) {
                // Partial check: if B is still parked on the unported command it stalled in
                // place, so the effects of the ported prefix must be visible in B unchanged.
                val eb = b.engine
                if (codes.isNotEmpty() && eb != null && eb.pEventCurrent == proc.pEventCurrent) {
                    val diffs = compare(proc, state, false, b)
                    partial++
                    for (code in codes.toSet()) {
                        val s = stats.getOrPut(code) { Stats() }
                        s.executed += codes.count { it == code }
                        if (diffs.isNotEmpty()) {
                            s.mismatches++
                            s.details += "PARTIAL step ${b.step} codes " +
                                "${codes.map { "%02X".format(it) }} " +
                                "at ${"%08X".format(ea.pEventCurrent)}: $diffs"
                        }
                    }
                    continue
                }
                skippedUnported++
                continue
            }
            if (stuck) {
                skippedStuck++
                continue
            }
            compared++
            val diffs = compare(proc, state, ended, b)
            for (code in codes.toSet()) {
                val s = stats.getOrPut(code) { Stats() }
                s.executed += codes.count { it == code }
                if (diffs.isNotEmpty()) {
                    s.mismatches++
                    s.details += "step ${b.step} codes ${codes.map { "%02X".format(it) }} " +
                        "at ${"%08X".format(ea.pEventCurrent)}: $diffs"
                }
            }
        }
        println(
            "EventVm pairs=$pairs compared=$compared stubbed=$stubbed partial=$partial skippedNoEngine=$skippedNoEngine " +
                "skippedUnported=$skippedUnported skippedStuck=$skippedStuck",
        )
        for ((code, s) in stats) {
            println("EventVm code %02X executed=%d mismatches=%d".format(code, s.executed, s.mismatches))
            s.details.take(5).forEach { println("EventVm   mismatch $it") }
        }
        println("EventVm unported stop codes: ${unportedHist.map { "%02X=%d".format(it.key, it.value) }}")
        val never = table.keys.filter { (stats[it]?.executed ?: 0) == 0 }.sorted()
        println("EventVm ported codes never executed: ${never.map { "%02X".format(it) }}")
        val bad = stats.filter { it.value.mismatches > 0 }.keys
        assertTrue(bad.isEmpty(), "mismatching codes: ${bad.map { "%02X".format(it) }}")
        for (code in intArrayOf(0x01, 0x0E)) {
            assertTrue((stats[code]?.executed ?: 0) > 0, "code %02X never executed".format(code))
        }
        assertTrue(compared > 1000, "only $compared fully compared pairs")
    }

    private fun loadState(state: EventState, a: EventSnapshot) {
        a.slots.copyInto(state.slots)
        a.slotQueue.copyInto(state.slotQueue)
        state.slotCounter = a.slotCounter
        a.callQueue.forEachIndexed { i, e ->
            state.callQueue[2 * i] = e.events
            state.callQueue[2 * i + 1] = (e.execType and 0xFF) or ((e.isUsed and 0xFF) shl 8)
        }
        a.activeQueue.forEachIndexed { i, e ->
            state.activeQueue[2 * i] = e.evt1
            state.activeQueue[2 * i + 1] = e.evt2
        }
    }

    private fun compare(
        proc: EventEngineProc,
        state: EventState,
        ended: Boolean,
        b: EventSnapshot,
    ): List<String> {
        val d = ArrayList<String>()
        val eb = b.engine
        if (eb == null) {
            // After EVC_END the proc is broken out of its repeat loop; it is removed a frame or
            // two later, so an absent engine is only an error when the run did not end.
            if (!ended) d += "engine gone in B, not ended"
        } else {
            fun chk(name: String, got: Int, want: Int) {
                if (got != want) d += "$name got %X want %X".format(got, want)
            }
            chk("pEventCurrent", proc.pEventCurrent, eb.pEventCurrent)
            chk("pEventIdk", proc.pEventIdk, eb.pEventIdk)
            chk("evStateBits", proc.evStateBits, eb.evStateBits)
            chk("evStallTimer", proc.evStallTimer, eb.evStallTimer)
            chk("execType", proc.execType, eb.execType)
        }
        for (i in 1 until EventState.SLOT_COUNT) {
            if (state.slots[i] != b.slots[i]) {
                d += "slot$i got %X want %X".format(state.slots[i], b.slots[i])
            }
        }
        if (!state.slotQueue.contentEquals(b.slotQueue)) d += "slotQueue differs"
        if (state.slotCounter != b.slotCounter) {
            d += "counter got %X want %X".format(state.slotCounter, b.slotCounter)
        }
        b.activeQueue.forEachIndexed { i, e ->
            if (state.activeQueue[2 * i] != e.evt1 || state.activeQueue[2 * i + 1] != e.evt2) {
                d += "activeQueue[$i] got %X,%X want %X,%X".format(
                    state.activeQueue[2 * i], state.activeQueue[2 * i + 1], e.evt1, e.evt2,
                )
            }
        }
        return d
    }

    private companion object {
        /**
         * Unported commands stubbed as `EVC_ADVANCE_YIELD` (advance by the length nibble, end the
         * frame), only when B's pEventCurrent is exactly the next command. Allowlist, none of them writes
         * slots, slot queue, counter, active queue, evStallTimer, execType or pEventIdk, and the
         * evStateBits are only read:
         * - 0x12 Event12_StartBGM: starts a song, returns ADVANCE_YIELD (CONTINUE when skipping,
         *   so not stubbed while the skipping bit is set).
         * - 0x15 Event15_BgmVolume: starts a volume proc, ADVANCE_YIELD (CONTINUE when skipping,
         *   likewise not stubbed while skipping).
         * (0x1A Event1A_TEXTSTART is NOT allowed: sub_800E640 sets EV_STATE_GFXLOCKED, seen as
         * evStateBits 0x1102 vs 0x102 in the trace.)
         * - 0x30 Event30_ENUN: refreshes maps and sprites, ADVANCE_YIELD (STOP_YIELD while a MU
         *   moves, which leaves pEventCurrent on the command and is excluded by the B check).
         */
        val STUB_YIELD = setOf(0x12, 0x15, 0x30)
        const val MAX_COMMANDS = 5000
    }
}
