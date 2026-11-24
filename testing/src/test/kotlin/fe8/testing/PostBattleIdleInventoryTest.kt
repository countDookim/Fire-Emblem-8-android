package fe8.testing

import gba.testkit.ReferenceData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Inventory (not a port claim, BA-i4b) of the post-battle idle chain of the `ekrBattle` proc
 * (banim-ekrbattle.c) from `ekrBattle_WaitForPostBattleAct` to `ekrBattle_PostDragonStatusEffect`
 * in `ram_trace_prologue_attack`, f1829..f1990. There is no function-entry trace: a dump step
 * shows `proc_idleCb` as it stands before that step's proc run, so the number of steps a callback
 * is listed equals its number of calls. The callback pointer is mapped to a decomp name through
 * the nm file at runtime; the ROM is not read and nothing from it is embedded. Struct layouts:
 * `ProcEkrBattle` (`ekrbattle.h`: speedup 0x29, timer 0x2C, end 0x2E, side 0x44, counter 0x48).
 * Step numbers are 1-based dump steps. See docs/plans/M6C-J-breakdown.md ("BA-i4b result").
 */
class PostBattleIdleInventoryTest {
    private val ref = ReferenceData.fromEnvironment()

    private class Step(
        val n: Int,
        val cb: String,
        val timer: Int,
        val end: Int,
        val speedup: Int,
        val side: Int,
        val counter: Int,
        val ram: ByteArray,
        val procs: Set<String>,
    )

    private fun u8(e: ByteArray, a: Int) = e[a - EWRAM].toInt() and 0xFF
    private fun u16(e: ByteArray, a: Int) = u8(e, a) or (u8(e, a + 1) shl 8)
    private fun s16(e: ByteArray, a: Int) = u16(e, a).toShort().toInt()
    private fun u32(e: ByteArray, a: Int) = u16(e, a) or (u16(e, a + 2) shl 16)

    private fun load(): List<Step> {
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
        return (FIRST..LAST).map { n ->
            val e = capture.region(byStep.getValue(n), "ewram")
            val all = ProcArrayDump.ofEwram(e, names)
            val p = all.find("gProc_ekrBattle")
            val cb = if (p == null) "(ended)" else p.u32(0x0C).let { funcs[it] ?: "%08x".format(it) }
            Step(
                n, cb, p?.u16(0x2C) ?: -1, p?.u16(0x2E) ?: -1, p?.u8(0x29) ?: -1,
                p?.u32(0x44) ?: -1, p?.u32(0x48) ?: -1, e,
                all.live.map { it.name ?: "?" }.toSet(),
            )
        }
    }

    private fun List<Step>.at(n: Int) = this[n - FIRST]

    /** Run-length encoding: `first..last:callback:calls:timerFirst>timerLast`. */
    private fun List<Step>.runs(): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < size) {
            var j = i
            while (j + 1 < size && this[j + 1].cb == this[i].cb) j++
            out += "${this[i].n}..${this[j].n}:${this[i].cb}:${j - i + 1}:" +
                "${this[i].timer}>${this[j].timer}"
            i = j + 1
        }
        return out
    }

    @Test
    fun `idle chain order, call counts and timers`() {
        val s = load()
        assertEquals(
            listOf(
                "1829..1829:ekrBattleOnBattleEnd:1:31>31",
                "1830..1830:ekrBattle_8050600:1:31>31",
                "1831..1860:ekrBattle_WaitForPostBattleAct:30:0>29",
                "1861..1861:ekrBattleExecExpGain:1:30>30",
                "1862..1874:ekrBattle_80508F0:13:0>12",
                "1875..1885:ekrBattle_8050940:11:0>10",
                "1886..1896:ekrBattleWaitExpBarIdle:11:0>10",
                "1897..1927:ekrBattlePostExpBarIdle:31:0>30",
                "1928..1940:ekrBattle_8050AB8:13:0>12",
                "1941..1981:ekrBattleLvupHanlder:41:0>40",
                "1982..1982:ekrNewEkrPopup:1:41>41",
                "1983..1983:ekrBattle_WaitForPopup:1:41>41",
                "1984..1984:ekrBattle_PostPopup:1:41>41",
                "1985..1987:ekrBattle_TriggerDragonStatusFinished:3:41>41",
                "1988..1988:ekrBattle_PostDragonStatusEffect:1:41>41",
                "1989..1990:(ended):2:-1>-1",
            ),
            s.runs(),
        )
    }

    @Test
    fun `entry gates and branch inputs`() {
        val s = load()
        // ekrBattle_8050600 (one call): no bar, no dead event, namewin gone -> leaves in one call.
        for (n in 1829..1830) {
            val r = s.at(n).ram
            assertEquals(0, u32(r, HP_BAR_COUNT), "step $n")
            assertEquals(0, u32(r, DEAD_EVENT), "step $n")
            assertEquals(0, u32(r, NAMEWIN_EXIST), "step $n")
        }
        // Gain (left, right) = (0, +10), previous (-1, 0): the right unit gained, and it is the
        // initial position (pos == gEkrInitPosReal), so `speedup` stays 0 (no far-attack proc).
        for (n in FIRST..LAST - 2) {
            val r = s.at(n).ram
            assertEquals(0, s16(r, EXP_GAIN), "step $n")
            assertEquals(10, s16(r, EXP_GAIN + 2), "step $n")
            assertEquals(-1, s16(r, EXP_PREV), "step $n")
            assertEquals(0, s16(r, EXP_PREV + 2), "step $n")
            assertEquals(1, u32(r, INIT_POS_REAL), "step $n")
            assertEquals(0, s.at(n).speedup, "step $n")
            assertEquals(0, u32(r, LINK_ARENA), "step $n")
            assertEquals(0, u16(r, DEBUG_MODE), "step $n")
        }
        // WaitForPostBattleAct: not link arena, gain[L] != -gain[R] -> ExecExpGain arm (the
        // ekrNewEkrPopup arm is not taken); the 30th call sets the callback.
        assertEquals("ekrBattle_WaitForPostBattleAct", s.at(1860).cb)
        assertEquals("ekrBattleExecExpGain", s.at(1861).cb)
        // ExecExpGain: sets timer 0, WaitExpBar `end` = prev[R] + gain[R] (gain[L] == 0 arm).
        assertEquals(0, s.at(1862).timer)
        assertEquals(10, s.at(1886).end)
        assertEquals(0, s.at(1886).timer) // timer = prev[R]
        // ekrBattleLvupHanlder: exp 0 + 10 < 100 on both checks -> ekrNewEkrPopup arm; the
        // ekrBattle_ExecEkrLvup/WaitEkrLvupIdle chain never runs.
        assertEquals(41, s.at(1982).timer)
        assertEquals((FIRST..LAST).none { s.at(it).cb.startsWith("ekrBattle_ExecEkrLvup") }, true)
        // Popup: gEkrPopupEnded is set by the time of the first WaitForPopup call.
        assertEquals(0, u32(s.at(1982).ram, POPUP_ENDED))
        assertEquals(1, u32(s.at(1983).ram, POPUP_ENDED))
        // PostPopup: side = gEkrInitialHitSide (1), counter 0.
        assertEquals(1, u16(s.at(1984).ram, INITIAL_HIT_SIDE))
        assertEquals(1 to 0, s.at(1985).side to s.at(1985).counter)
        // TriggerDragonStatusFinished: three calls, default (no dragon) arms on both sides:
        // R (side 1) then L (side 0), counter 0 -> 1 -> 2, then the counter == 2 exit.
        assertEquals(0 to 1, s.at(1986).side to s.at(1986).counter)
        assertEquals(1 to 2, s.at(1987).side to s.at(1987).counter)
        assertEquals("ekrBattle_PostDragonStatusEffect", s.at(1988).cb)
        // PostDragonStatusEffect: gEkrBattleEndFlag = 1, debug mode 0 -> namewin appear started
        // and the proc leaves (end proc ProcScr_ekrBattleEnding appears the same step).
        assertEquals(0, u32(s.at(1988).ram, BATTLE_END_FLAG))
        assertEquals(1, u32(s.at(1989).ram, BATTLE_END_FLAG))
        assertEquals(0, u32(s.at(1988).ram, NAMEWIN_EXIST))
        assertEquals(1, u32(s.at(1989).ram, NAMEWIN_EXIST))
        assertEquals(1, s.at(1989).procs.count { it == "ProcScr_ekrNamewinAppear" })
        assertEquals(1, s.at(1989).procs.count { it == "ProcScr_ekrBattleEnding" })
        assertEquals(false, s.at(1989).procs.contains("gProc_ekrBattle"))
        // `nullsub_69` is never the callback of a dumped step.
        assertEquals((FIRST..LAST).none { s.at(it).cb == "nullsub_69" }, true)
    }

    @Test
    fun `side effect procs end when ekrBattle_PostPopup runs`() {
        val s = load()
        for (n in 1829..1984) {
            assertEquals(true, s.at(n).procs.contains("ProcScr_efxWeaponIcon"), "step $n")
            assertEquals(true, s.at(n).procs.contains("ProcScr_efxHPBarColorChange"), "step $n")
        }
        assertEquals(false, s.at(1985).procs.contains("ProcScr_efxWeaponIcon"))
        assertEquals(false, s.at(1985).procs.contains("ProcScr_efxHPBarColorChange"))
        // The exp-bar chain and the popup add no procs of their own in this window.
        val ended = setOf("ProcScr_efxWeaponIcon", "ProcScr_efxHPBarColorChange")
        for (n in 1830..1988) {
            val expected = if (n >= 1985) s.at(1829).procs - ended else s.at(1829).procs
            assertEquals(expected, s.at(n).procs, "step $n")
        }
    }

    private companion object {
        const val ATTACK = "ram_trace_prologue_attack"
        const val EWRAM = 0x02000000
        const val FIRST = 1829
        const val LAST = 1990
        const val BATTLE_END_FLAG = 0x02017724
        const val HP_BAR_COUNT = 0x02017728
        const val DEAD_EVENT = 0x02017738
        const val INIT_POS_REAL = 0x02017744
        const val NAMEWIN_EXIST = 0x0201FAD0
        const val POPUP_ENDED = 0x02020144
        const val LINK_ARENA = 0x0203E0F0
        const val DEBUG_MODE = 0x0203E0FC
        const val INITIAL_HIT_SIDE = 0x0203E100
        const val EXP_PREV = 0x0203E1C4
        const val EXP_GAIN = 0x0203E1C8
    }
}
