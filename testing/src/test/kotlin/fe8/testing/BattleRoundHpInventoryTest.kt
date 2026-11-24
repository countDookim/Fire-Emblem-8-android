package fe8.testing

import gba.testkit.ReferenceData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Inventory (not a port claim, BA-i4) of the round progression of `ekrBattle`
 * (banim-ekrbattle.c) and of the HP-bar update `efxHPBar` (banim-efxhpbar.c: `NewEfxHpBar`,
 * `EfxHpBar_DeclineToDeath`, `EfxHpBar_MoveCameraOnEnd`, `EfxHpBar_WaitCameraMove`) in
 * `ram_trace_prologue_attack` (3 hits: right hits left, left hits right, right hits left).
 * There is no function-entry trace, so everything is inferred from the per-step EWRAM dumps:
 * `gEkrGaugeHp`, `gEfxHpLutOff`, `gEkrHitNow`, `gEkrHpBarCount`, `gBanimDoneFlag`, the live
 * `ProcEkrBattle`/`ProcEfxHpBar` fields (struct layouts from `ekrbattle.h`/`efxbattle.h`) and
 * the `proc_idleCb` pointer of `ekrBattle`, which is mapped to a decomp function name through
 * the nm file at runtime (the ROM is not read and no bytes are embedded here). Step numbers
 * are 1-based dump steps. See docs/plans/M6C-J-breakdown.md ("BA-i4 result").
 */
class BattleRoundHpInventoryTest {
    private val ref = ReferenceData.fromEnvironment()

    private class Setup(
        val ram: Map<Int, ByteArray>,
        val names: Map<Int, String>,
        val funcs: Map<Int, String>,
    )

    private fun u8(e: ByteArray, a: Int) = e[a - EWRAM].toInt() and 0xFF
    private fun u16(e: ByteArray, a: Int) = u8(e, a) or (u8(e, a + 1) shl 8)
    private fun s16(e: ByteArray, a: Int) = u16(e, a).toShort().toInt()
    private fun u32(e: ByteArray, a: Int) = u16(e, a) or (u16(e, a + 2) shl 16)

    private fun load(): Setup {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val names = ProcArrayDump.romNames()
        val nm = ProcArrayDump.nmFile()
        assumeTrue(names.isNotEmpty() && nm != null, "decomp nm file not present")
        val funcs = HashMap<Int, String>()
        nm!!.forEachLine {
            val p = it.split(" ")
            if (p.size >= 4 && p[2] == "T") funcs[p[0].toLong(16).toInt() or 1] = p[3]
        }
        val capture = ref.run(ATTACK)
        val byStep = capture.dumps.entries.associate { it.value to it.key }
        val ram = (FIRST..LAST).associateWith { capture.region(byStep.getValue(it), "ewram") }
        return Setup(ram, names, funcs)
    }

    private fun Setup.procs(step: Int) = ProcArrayDump.ofEwram(ram.getValue(step), names)

    private fun Setup.gauge(step: Int, side: Int) = s16(ram.getValue(step), GAUGE_HP + 2 * side)

    /** `step:side=value` for each step where a `gEkrGaugeHp` half differs from the step before. */
    private fun Setup.gaugeChanges(first: Int, last: Int): List<String> {
        val out = ArrayList<String>()
        for (step in first..last) {
            for (side in 0..1) {
                val v = gauge(step, side)
                if (v != gauge(step - 1, side)) out += "$step:$side=$v"
            }
        }
        return out
    }

    @Test
    fun `gauge decrements are one point every second proc run`() {
        val s = load()
        val changes = s.gaugeChanges(FIRST + 1, LAST)
        // Bar 1: left 22 -> 15; bar 2: right 16 -> 7; bar 3: left 15 -> 8. Step size always -1.
        assertEquals(
            listOf(
                "1579:0=21", "1581:0=20", "1584:0=19", "1586:0=18", "1588:0=17", "1590:0=16",
                "1592:0=15",
                "1661:1=15", "1663:1=14", "1665:1=13", "1667:1=12", "1669:1=11", "1671:1=10",
                "1673:1=9", "1675:1=8", "1677:1=7",
                "1786:0=14", "1788:0=13", "1791:0=12", "1793:0=11", "1795:0=10", "1797:0=9",
                "1799:0=8",
            ),
            changes,
        )
        // Spacing is 2 steps except where the bar proc skipped a step (1582, 1789; below).
        val steps = changes.map { it.substringBefore(':').toInt() }
        val wide = steps.zipWithNext().filter { it.second - it.first == 3 }
        assertEquals(listOf(1581 to 1584, 1788 to 1791), wide)
        // HP bar values never change outside the three bars, and the final HP equals the last
        // hit result (gauge = (left, right)).
        assertEquals(15 to 16, s.gauge(1592, 0) to s.gauge(1592, 1))
        assertEquals(15 to 7, s.gauge(1677, 0) to s.gauge(1677, 1))
        assertEquals(8 to 7, s.gauge(1799, 0) to s.gauge(1799, 1))
        assertEquals(8 to 7, s.gauge(LAST, 0) to s.gauge(LAST, 1))
    }

    @Test
    fun `efxHPBar proc life and fields`() {
        val s = load()
        val bars = listOf(
            // start, finish (finished = 1), break (lutOff++), scr 3, scr 4 (count 0), side,
            // this, next, timer2 at finish
            Bar(1577, 1592, 1609, 1610, 1611, 0, 22, 15, 14),
            Bar(1659, 1677, 1690, 1691, 1692, 1, 16, 7, 18),
            Bar(1784, 1799, 1816, 1817, 1818, 0, 15, 8, 14),
        )
        for (b in bars) {
            val start = s.ram.getValue(b.start)
            val p = s.procs(b.start).find("ProcScr_efxHPBar")!!
            // Start step (`NewEfxHpBar`): init values, bar counter taken, side flagged as hit.
            assertEquals(b.cur, p.u16(0x2E))
            assertEquals(b.cur, p.u32(0x4C)) // this
            assertEquals(b.next, p.u32(0x50))
            assertEquals(-1, p.u32(0x48)) // diff: this > next
            assertEquals(0, p.u16(0x2C)) // timer
            assertEquals(0, p.u32(0x54)) // timer2
            assertEquals(0, p.u32(0x58)) // finished
            assertEquals(1, u32(start, HP_BAR_COUNT))
            assertEquals(1, s16(start, HIT_NOW + 2 * b.side))
            assertEquals(0, s16(s.ram.getValue(b.start - 1), HIT_NOW + 2 * b.side))
            assertEquals(0, u32(s.ram.getValue(b.start - 1), HP_BAR_COUNT))
            // Never the death branch: next stays above zero, lutOff only goes up by one.
            assertTrue(b.next > 0)
            // Finish step: cur reaches next, finished = 1, gauge at its final value.
            val f = s.procs(b.finish).find("ProcScr_efxHPBar")!!
            assertEquals(b.next, f.u16(0x2E))
            assertEquals(1, f.u32(0x58))
            assertEquals(b.finishT2, f.u32(0x54))
            assertEquals(0, s.procs(b.finish - 1).find("ProcScr_efxHPBar")!!.u32(0x58))
            // Break step: timer2 stuck at 0x1E, `gEfxHpLutOff` incremented, hit flag cleared.
            val brk = s.procs(b.brk).find("ProcScr_efxHPBar")!!
            assertEquals(0x1E, brk.u32(0x54))
            assertEquals(0, s16(s.ram.getValue(b.brk), HIT_NOW + 2 * b.side))
            assertEquals(
                s16(s.ram.getValue(b.brk - 1), LUT_OFF + 2 * b.side) + 1,
                s16(s.ram.getValue(b.brk), LUT_OFF + 2 * b.side),
            )
            assertEquals(1, u32(s.ram.getValue(b.brk), HP_BAR_COUNT))
            // MoveCameraOnEnd (scr 3): `cur` set to 1, so the CheckRound2 arms (16/20) are not
            // taken; WaitCameraMove (scr 4) runs once (timer 1 == cur), count drops to 0.
            val m = s.procs(b.move).find("ProcScr_efxHPBar")!!
            assertEquals(3, m.scrCur)
            assertEquals(1, m.u16(0x2E))
            val w = s.procs(b.wait).find("ProcScr_efxHPBar")!!
            assertEquals(4, w.scrCur)
            assertEquals(1, w.u16(0x2C))
            assertEquals(0, u32(s.ram.getValue(b.wait), HP_BAR_COUNT))
            assertEquals(null, s.procs(b.wait + 1).find("ProcScr_efxHPBar"))
            // Spell/BG flags that gate MoveCameraOnEnd stay clear for the bar's life.
            for (st in b.start..b.wait) {
                assertEquals(0, u32(s.ram.getValue(st), SPELL_ANIM_EXISTS), "step $st")
            }
        }
        // Only the three bars exist in the round window.
        for (st in FIRST..LAST) {
            val live = s.procs(st).find("ProcScr_efxHPBar") != null
            assertEquals(bars.any { st in it.start..it.wait }, live, "step $st")
        }
        // The proc is not advanced on one step of bars 1 and 3 (same fields as the step before).
        for (st in listOf(1582, 1789)) {
            val a = s.procs(st - 1).find("ProcScr_efxHPBar")!!
            val b = s.procs(st).find("ProcScr_efxHPBar")!!
            assertEquals(
                listOf(a.u16(0x2C), a.u32(0x54), a.u32(0x4C)),
                listOf(b.u16(0x2C), b.u32(0x54), b.u32(0x4C)),
            )
        }
    }

    private class Bar(
        val start: Int,
        val finish: Int,
        val brk: Int,
        val move: Int,
        val wait: Int,
        val side: Int,
        val cur: Int,
        val next: Int,
        val finishT2: Int,
    )

    @Test
    fun `ekrBattle idle callback sequence and round end flags`() {
        val s = load()
        val seq = ArrayList<String>()
        var prev = ""
        for (step in FIRST..LAST) {
            val p = s.procs(step).find("gProc_ekrBattle")
            val name = if (p == null) {
                "(ended)"
            } else {
                val cb = p.u32(0x0C)
                if (cb == 0) "(script)" else s.funcs[cb] ?: "%08x".format(cb)
            }
            if (name != prev) seq += "$step:$name"
            prev = name
        }
        assertEquals(
            listOf(
                "1496:ekrBattle_Init", "1497:(script)", "1499:ekrBattlePrepareDragonIntro",
                "1500:ekrBattleExecDragonIntro", "1503:ekrBattlePostEkrDragonIntro",
                "1504:ekrBattleSetFlashingEffect", "1505:ekrBattleExecTriangleAtk",
                "1506:ekrBattleTriggerNewRoundStart", "1537:ekrBattle_80503EC",
                "1538:ekrBattle_StartPromotion", "1539:ekrBattleInRoundIdle",
                "1829:ekrBattleOnBattleEnd", "1830:ekrBattle_8050600",
                "1831:ekrBattle_WaitForPostBattleAct", "1861:ekrBattleExecExpGain",
                "1862:ekrBattle_80508F0", "1875:ekrBattle_8050940",
                "1886:ekrBattleWaitExpBarIdle", "1897:ekrBattlePostExpBarIdle",
                "1928:ekrBattle_8050AB8", "1941:ekrBattleLvupHanlder", "1982:ekrNewEkrPopup",
                "1983:ekrBattle_WaitForPopup", "1984:ekrBattle_PostPopup",
                "1985:ekrBattle_TriggerDragonStatusFinished",
                "1988:ekrBattle_PostDragonStatusEffect", "1989:(ended)",
            ),
            seq,
        )
        // No new-round start between the three hits: the proc stays in ekrBattleInRoundIdle
        // for f1539..f1828 and the later rounds are started by the anim scripts (C06).
        assertEquals(31, u16Proc(s, 1537, 0x2C)) // TriggerNewRoundStart waited 0x1F counts
        assertEquals(0, u8Proc(s, 1539, 0x29)) // speedup never set (B not held)
        // gBanimDoneFlag: left done f1819, right f1828; the idle proc leaves in the next step.
        val done = (FIRST..LAST).map {
            u32(s.ram.getValue(it), DONE_FLAG) to u32(s.ram.getValue(it), DONE_FLAG + 4)
        }
        assertEquals(0 to 0, done[1818 - FIRST])
        assertEquals(1 to 0, done[1819 - FIRST])
        assertEquals(1 to 0, done[1827 - FIRST])
        assertEquals(1 to 1, done[1828 - FIRST])
        assertTrue((FIRST..1818).all { done[it - FIRST] == (0 to 0) })
    }

    private fun u16Proc(s: Setup, step: Int, off: Int) =
        s.procs(step).find("gProc_ekrBattle")!!.u16(off)

    private fun u8Proc(s: Setup, step: Int, off: Int) =
        s.procs(step).find("gProc_ekrBattle")!!.u8(off)

    private companion object {
        const val ATTACK = "ram_trace_prologue_attack"
        const val EWRAM = 0x02000000
        const val FIRST = 1496
        const val LAST = 1990
        const val GAUGE_HP = 0x0203E1AC
        const val LUT_OFF = 0x0203E152
        const val HP_BAR_COUNT = 0x02017728
        const val SPELL_ANIM_EXISTS = 0x0201772C
        const val HIT_NOW = 0x02017780
        const val DONE_FLAG = 0x0201FB04
    }
}
