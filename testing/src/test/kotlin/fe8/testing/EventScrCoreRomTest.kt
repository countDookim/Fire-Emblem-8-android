package fe8.testing

import fe8.game.event.EVENT_SCR_08592114
import fe8.game.event.EventConst.EVC_ADVANCE_CONTINUE
import fe8.game.event.EventConst.EVC_END
import fe8.game.event.EventConst.EV_EXEC_GAMEPLAY
import fe8.game.event.EventEngineProc
import fe8.game.event.EventHandler
import fe8.game.event.EventScrCore
import fe8.game.event.EventState
import fe8.game.event.ScriptMemory
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Runs the real core event handlers (not a port check) over the ROM scripts `EventScr_08592114`,
 * `EventScr_08592140` and `EventScr_08592170` and compares call/goto/return targets with
 * addresses decoded independently from the ROM words.
 */
class EventScrCoreRomTest {
    private val assets =
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets"
    private val romFile = File(assets, "baserom.gba")

    private fun rom(): ByteArray {
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        return romFile.readBytes()
    }

    private fun word(rom: ByteArray, addr: Int): Int {
        val o = addr - 0x08000000
        return (rom[o].toInt() and 0xFF) or ((rom[o + 1].toInt() and 0xFF) shl 8)
    }

    private fun words(rom: ByteArray, addr: Int, size: Int): List<Int> =
        List(size / 2) { word(rom, addr + it * 2) }

    /** Address of the first LABEL command with [id] in the script, by scanning the raw words. */
    private fun labelAddr(rom: ByteArray, addr: Int, size: Int, id: Int): Int {
        for (i in 0 until size / 2 - 1) {
            if (word(rom, addr + i * 2) == 0x0820 && word(rom, addr + i * 2 + 2) == id) {
                return addr + i * 2
            }
        }
        error("label $id not found")
    }

    private class Run(val trace: List<Int>, val state: EventState, val proc: EventEngineProc)

    private fun run(rom: ByteArray, start: Int, slotC: Int): Run {
        val state = EventState()
        state.slots[0xC] = slotC
        val mem = ScriptMemory { a -> word(rom, a) }
        val core = EventScrCore(
            state, mem, object : fe8.game.event.EventInput {},
            rng = gbafe.core.rng.RngService(), playSt = fe8.game.bm.PlaySt(),
            keys = gbafe.input.KeyStatus(),
        )
        val table = HashMap<Int, EventHandler>()
        table[0x01] = core.event01End
        table[0x05] = core.event05SetSlot
        table[0x06] = core.event06SlotOperation
        table[0x07] = core.event07SlotQueueOperations
        table[0x08] = core.event08Label
        table[0x09] = core.event09Goto
        table[0x0A] = core.event0aCall
        table[0x0C] = core.event0cBranch
        val proc = EventEngineProc()
        proc.pEventIdk = start
        proc.pEventCurrent = start
        proc.execType = EV_EXEC_GAMEPLAY
        val trace = ArrayList<Int>()
        var steps = 0
        while (steps++ < 1000) {
            val w = mem.u16(proc.pEventCurrent)
            trace += proc.pEventCurrent
            val r = table[w ushr 8]?.run(proc) ?: EVC_ADVANCE_CONTINUE
            when (r) {
                EVC_ADVANCE_CONTINUE ->
                    proc.pEventCurrent += ((mem.u16(proc.pEventCurrent) ushr 4) and 0xF) * 2
                EVC_END -> return Run(trace, state, proc)
                else -> Unit // UNK4 re-runs at the new position
            }
        }
        error("script did not end")
    }

    /** A LABEL command is 2 words; the engine advances past it after GOTO. */
    private val LABEL_LEN = 4

    private val a114 = EVENT_SCR_08592114
    private val a140 = 0x08592140
    private val a170 = 0x08592170

    @Test
    fun `08592114 calls 08592140 and returns past the call`() {
        val rom = rom()
        assertEquals(0x08592114, a114)
        val w = words(rom, a114, 0x2c)
        // independent decode: the CALL (0x0A40) at word index 6 carries its target in words 8-9
        val callIdx = w.indexOf(0x0A40)
        val callAddr = a114 + callIdx * 2
        val target = w[callIdx + 2] or (w[callIdx + 3] shl 16)
        assertEquals(a140, target)
        val label1 = labelAddr(rom, a114, 0x2c, 1)

        val r = run(rom, a114, slotC = 0) // BNE slotC != slot0 not taken
        val i = r.trace.indexOf(callAddr)
        assertTrue(i >= 0)
        assertEquals(target, r.trace[i + 1], "call lands on the ROM target")
        val back = r.trace.subList(i, r.trace.size).indexOf(callAddr + 8)
        assertTrue(back > 0, "END returned past the CALL")
        // the goto after the call jumps over the second call to label 1
        assertTrue(r.trace.contains(label1 + LABEL_LEN), "goto lands past label 1")
        assertTrue(!r.trace.contains(a114 + w.lastIndexOf(0x0A40) * 2), "second call skipped")
        assertEquals(0, r.state.activeQueue[0])
    }

    @Test
    fun `08592114 taken branch lands on label 0 and calls 08592170`() {
        val rom = rom()
        val w = words(rom, a114, 0x2c)
        val label0 = labelAddr(rom, a114, 0x2c, 0)
        val secondCall = a114 + w.lastIndexOf(0x0A40) * 2
        val idx = w.lastIndexOf(0x0A40)
        val target = w[idx + 2] or (w[idx + 3] shl 16)
        assertEquals(a170, target)

        val r = run(rom, a114, slotC = 1) // BNE taken
        val branchAddr = a114 + w.indexOf(0x0C41) * 2
        val b = r.trace.indexOf(branchAddr)
        assertEquals(label0 + LABEL_LEN, r.trace[b + 1], "branch goes to label 0")
        assertEquals(secondCall, label0 + LABEL_LEN)
        assertEquals(target, r.trace[b + 2])
    }

    @Test
    fun `08592140 and 08592170 branch targets match the ROM labels`() {
        val rom = rom()
        // 08592140: slotC != 0 at the first BNE goes to label 0 (word 0x0820, 0)
        var r = run(rom, a140, slotC = 1)
        val l0 = labelAddr(rom, a140, 0x30, 0)
        val br = a140 + words(rom, a140, 0x30).indexOf(0x0C41) * 2
        assertEquals(l0 + LABEL_LEN, r.trace[r.trace.indexOf(br) + 1])
        // slotC == 0 does not branch: the next command is the one after the branch
        r = run(rom, a140, slotC = 0)
        assertEquals(br + 8, r.trace[r.trace.indexOf(br) + 1])

        // 08592170: BEQ slotC == 0 goes to label 1 (u16 pair 0x0820, 1)
        val w = words(rom, a170, 0x3c)
        val beq = a170 + w.indexOf(0x0C40) * 2
        val l1 = labelAddr(rom, a170, 0x3c, 1)
        r = run(rom, a170, slotC = 0)
        assertEquals(l1 + LABEL_LEN, r.trace[r.trace.indexOf(beq) + 1])
        r = run(rom, a170, slotC = 5)
        assertEquals(beq + 8, r.trace[r.trace.indexOf(beq) + 1])
        // SADD slot2 = slotC + slot0 and SETVAL slot 0xB = 0 ran in that script
        assertEquals(5, r.state.slots[2])
        assertEquals(0, r.state.slots[0xB])
    }
}
