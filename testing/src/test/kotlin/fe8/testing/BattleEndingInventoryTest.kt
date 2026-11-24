package fe8.testing

import gba.testkit.ReferenceData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Inventory (not a port claim, BA-i8) of `ProcScr_ekrBattleEnding` (banim-ekrbattleending.c) in
 * `ram_trace_prologue_attack`, f1989..2038: from its creation by `ekrBattle_PostDragonStatusEffect`
 * to the map UI procs being back. There is no function-entry trace; a dump step shows the state
 * after that step's proc run. The proc layout is `ProcEkrBattleEnding` (`ekrbattle.h`: timer 0x2C,
 * terminator 0x2E, x1 0x32, x2 0x34, y1 0x3A, y2 0x3C), `repeatFunc` at 0x0C and the script
 * cursor from `ProcArrayDump.Proc.scrCur` (cursor 1 = the NAME command, so the Nth repeat command
 * shows as cursor N + 1). Callback pointers are mapped to decomp names through the nm file at
 * runtime; the ROM is not read and nothing from it is embedded. Step numbers are 1-based dump
 * steps. See docs/plans/M6C-J-breakdown.md ("BA-i8 result").
 */
class BattleEndingInventoryTest {
    private val ref = ReferenceData.fromEnvironment()

    private class Step(
        val n: Int,
        val cb: String,
        val cur: Int,
        val timer: Int,
        val term: Int,
        val ram: ByteArray,
        val procs: Set<String>,
        val box: List<Int>,
    )

    private fun u8(e: ByteArray, a: Int) = e[a - EWRAM].toInt() and 0xFF
    private fun u16(e: ByteArray, a: Int) = u8(e, a) or (u8(e, a + 1) shl 8)
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
            val p = all.find("ProcScr_ekrBattleEnding")
            val cb = when {
                p == null -> "(gone)"
                p.u32(0x0C) == 0 -> "(none)"
                else -> p.u32(0x0C).let { funcs[it] ?: "%08x".format(it) }
            }
            Step(
                n, cb, p?.scrCur ?: -1, p?.u16(0x2C) ?: -1, p?.u16(0x2E) ?: -1, e,
                all.live.map { it.name ?: "?" }.toSet(),
                if (p == null) emptyList() else listOf(0x32, 0x34, 0x3A, 0x3C).map { p.u16(it) },
            )
        }
    }

    private fun List<Step>.at(n: Int) = this[n - FIRST]

    /** Run-length encoding: `first..last:callback@cursor:steps:timerFirst>timerLast`. */
    private fun List<Step>.runs(): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < size) {
            var j = i
            while (j + 1 < size && this[j + 1].cb == this[i].cb && this[j + 1].cur == this[i].cur) j++
            out += "${this[i].n}..${this[j].n}:${this[i].cb}@${this[i].cur}:${j - i + 1}:" +
                "${this[i].timer}>${this[j].timer}"
            i = j + 1
        }
        return out
    }

    @Test
    fun `callback sequence, script cursor and timers`() {
        val s = load()
        // A repeat command leaves its callback set while it runs; after Proc_Break the callback
        // reads (none) and the cursor still names the command that just broke.
        assertEquals(
            listOf(
                "1989..1989:ekrBattleEnding_80560F0@2:1:0>0",
                "1990..1990:(none)@2:1:0>0",
                "1991..1991:(none)@3:1:0>0",
                "1992..1992:(none)@4:1:0>0",
                "1993..1993:(none)@5:1:0>0",
                "1994..2008:ekrBattleEnding_8056288@6:15:1>12",
                "2009..2009:(none)@6:1:13>13",
                "2010..2010:ekrBattleEnding_8056310@7:1:0>0",
                "2011..2011:(none)@7:1:0>0",
                "2012..2025:ekrBattleEnding_8056390@8:14:1>14",
                "2026..2026:(none)@8:1:0>0",
                "2027..2027:(none)@9:1:0>0",
                "2028..2038:(gone)@-1:11:-1>-1",
            ),
            s.runs(),
        )
        // ekrBattleEnding_8056288: timer 1 shows for four steps (1994..1997): the step where it
        // first ran and three steps in which the timer did not advance; then +1 per step.
        assertEquals(List(4) { 1 }, (1994..1997).map { s.at(it).timer })
        assertEquals((2..12).toList(), (1998..2008).map { s.at(it).timer })
        // ekrBattleEnding_8056390: timer +1 per step to terminator 15, reset to 0 on the 15th call.
        assertEquals((1..14).toList(), (2012..2025).map { s.at(it).timer })
        assertEquals(15, s.at(2012).term)
        assertEquals(0, s.at(2026).timer)
    }

    @Test
    fun `entry gates and branch inputs`() {
        val s = load()
        // 80560F0, 8056170, 80561C8 (steps 1990..1992): gBanimBG == 0, so each breaks at its
        // first statement (no timer advance, no palette or map-graphics path), one step each.
        for (n in 1989..2009) {
            assertEquals(0, u16(s.at(n).ram, BANIM_BG), "step $n")
            assertEquals(0, u32(s.at(n).ram, LINK_ARENA), "step $n")
            assertEquals(1, u32(s.at(n).ram, BATTLE_END_FLAG), "step $n")
        }
        assertEquals(listOf(0, 0, 0), (1990..1992).map { s.at(it).timer })
        // 8056228: timer 0, x1 = x2 = (loc[0] + loc[2]) * 8 + 8, y1 = y2 = (loc[1] + loc[3]) * 8 + 8
        // with gEkrBmLocation = (8, 6, 8, 5); the previous values are garbage of the reused slot.
        assertEquals(listOf(8, 6, 8, 5), (0..3).map { u16(s.at(1993).ram, BM_LOCATION + 2 * it) })
        assertEquals(listOf(136, 136, 96, 96), s.at(1993).box)
        assertEquals(false, s.at(1992).box == s.at(1993).box)
        for (n in 1993..2027) assertEquals(listOf(136, 136, 96, 96), s.at(n).box, "step $n")
        // 8056228 starts the window-appear family of procs and AnimClearAll's companions.
        val started = setOf(
            "ProcScr_EkrBaseKaiten", "ProcScr_ekrWindowAppear", "ProcScr_ekrUnitKakudai",
            "ProcScr_ekrBaseAppear",
        )
        assertEquals(started, s.at(1993).procs - s.at(1992).procs)
        assertEquals(0, u32(s.at(1992).ram, WINDOW_APPEAR_EXIST))
        assertEquals(1, u32(s.at(1993).ram, WINDOW_APPEAR_EXIST))
        // 8056288: the 13th call (timer 13 > 0xC) ends the gauge and the window-appear procs.
        assertEquals(13, s.at(2009).timer)
        assertEquals(setOf("ProcScr_ekrGauge"), s.at(2008).procs - s.at(2009).procs - started)
        assertEquals(
            started - setOf("ProcScr_ekrUnitKakudai"),
            s.at(2008).procs - s.at(2009).procs - setOf("ProcScr_ekrGauge"),
        )
        assertEquals(0, u32(s.at(2009).ram, WINDOW_APPEAR_EXIST))
        // 8056310: terminator 15, timer 0; BMapDispResume_FromBattleDelayed starts the delayed
        // resume proc (and a ProcScr_Mu); the proc exists for two dump steps.
        assertEquals(15 to 0, s.at(2010).term to s.at(2010).timer)
        assertEquals(true, s.at(2010).procs.containsAll(setOf("sProc_DelayedBMapDispResume", "ProcScr_Mu")))
        assertEquals(true, s.at(2011).procs.contains("sProc_DelayedBMapDispResume"))
        assertEquals(false, s.at(2012).procs.contains("sProc_DelayedBMapDispResume"))
        assertEquals(false, s.at(2012).procs.contains("ProcScr_ekrUnitKakudai"))
        // 8056484 (step 2027): EndEkrBattleDeamon; PROC_END removes the ending proc at 2028.
        assertEquals(true, s.at(2026).procs.contains("gProc_ekrBattleDeamon"))
        assertEquals(false, s.at(2027).procs.contains("gProc_ekrBattleDeamon"))
        assertEquals(true, s.at(2027).procs.contains("ProcScr_ekrBattleEnding"))
        assertEquals(false, s.at(2028).procs.contains("ProcScr_ekrBattleEnding"))
        // The arena-palette arm of 8056310 needs gBattleAnimArenaFlag == 1, link-arena fog needs
        // gBanimLinkArenaFlag == 1; neither is the case here (flag 0 above).
    }

    @Test
    fun `proc set changes until the map ui is back`() {
        val s = load()
        val removed = mapOf(
            1995 to setOf("ProcScr_efxStatusUnit"),
            2001 to setOf("gProc_ekrDispUP"),
            2002 to setOf("ProcScr_ekrNamewinAppear"),
            2012 to setOf("sProc_DelayedBMapDispResume", "ProcScr_ekrUnitKakudai"),
            2027 to setOf("gProc_ekrBattleDeamon"),
            2028 to setOf("ProcScr_ekrBattleEnding"),
            2029 to setOf("sProcScr_CombatAction"),
            2033 to setOf("ProcScr_StdEventEngine"),
            2034 to setOf("ProcScr_Mu"),
            2038 to setOf("gProcScr_SideWindowMaker"),
        )
        val added = mapOf(
            1993 to setOf(
                "ProcScr_EkrBaseKaiten", "ProcScr_ekrWindowAppear", "ProcScr_ekrUnitKakudai",
                "ProcScr_ekrBaseAppear",
            ),
            2010 to setOf("sProc_DelayedBMapDispResume", "ProcScr_Mu"),
            2030 to setOf("ProcScr_StdEventEngine"),
            2037 to setOf("gProcScr_SideWindowMaker"),
            2038 to setOf(
                "gProcScr_TerrainDisplay", "gProcScr_UnitDisplay_MinimugBox", "gProcScr_GoalDisplay",
                "ProcScr_GreenTextColor",
            ),
        )
        for (n in 1990..LAST) {
            if (n == 2009) continue // gauge, base-kaiten, window and base-appear: other test
            assertEquals(removed[n].orEmpty(), s.at(n - 1).procs - s.at(n).procs, "removed $n")
            assertEquals(added[n].orEmpty() - removed[n].orEmpty(), s.at(n).procs - s.at(n - 1).procs, "added $n")
        }
        // Settled set: the map procs only, the battle procs are all gone.
        val settled = setOf(
            "gProcScr_GameControl", "sProc_BMVSync", "gProc_BMapMain", "gProc_MapTask",
            "gProcScr_PlayerPhase", "gProcScr_TerrainDisplay", "gProcScr_UnitDisplay_MinimugBox",
            "gProcScr_GoalDisplay", "ProcScr_GreenTextColor",
        )
        assertEquals(settled, s.at(2038).procs)
        assertEquals(true, s.at(2038).procs.none { it.startsWith("ProcScr_ek") || it.startsWith("ProcScr_efx") })
        // gEkrBattleEndFlag stays 1 and the namewin flag 0 through the end; the BM location stays.
        for (n in 2009..LAST) {
            assertEquals(1, u32(s.at(n).ram, BATTLE_END_FLAG), "step $n")
            assertEquals(0, u32(s.at(n).ram, NAMEWIN_EXIST), "step $n")
        }
        assertEquals(1, u32(s.at(1989).ram, NAMEWIN_EXIST))
        assertEquals(true, s.at(1989).procs.contains("ProcScr_ekrNamewinAppear"))
        assertEquals(0, u32(s.at(2002).ram, NAMEWIN_EXIST))
    }

    private companion object {
        const val ATTACK = "ram_trace_prologue_attack"
        const val EWRAM = 0x02000000
        const val FIRST = 1989
        const val LAST = 2038
        const val BATTLE_END_FLAG = 0x02017724
        const val WINDOW_APPEAR_EXIST = 0x0201FACC
        const val NAMEWIN_EXIST = 0x0201FAD0
        const val LINK_ARENA = 0x0203E0F0
        const val BANIM_BG = 0x0203E0FE
        const val BM_LOCATION = 0x0203E122
    }
}
