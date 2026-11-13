package fe8.testing

import gba.testkit.ReferenceData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Inventory (not a port claim, EP-0) of the procs alive during the Prologue enemy phase, from the
 * `procs` watch of two captures: `ram_trace_prologue_cancel_end` (End on turn 1, banner, first AI
 * decision and walk, f1510..1650) and `ram_trace_prologue_enemy_phase` (the whole enemy phase to
 * the turn-2 player phase, f1525..2851). Each row is `script symbol <- parent`, where the parent
 * is the script symbol of the parent slot at the segment's first step, or `tree N` for a root
 * proc, followed by the first and last step of a contiguous run of one slot with one script and
 * one parent. The function lists derived from these rows and from a scratch function-entry trace
 * are in
 * docs/plans/M6C-J-breakdown.md ("EP-0 inventory").
 */
class EnemyPhaseInventoryTest {
    private val ref = ReferenceData.fromEnvironment()

    private fun rows(run: String, first: Int, last: Int): List<String> {
        val names = ProcArrayDump.romNames()
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        assumeTrue(names.isNotEmpty(), "decomp nm file not present")
        val capture = ref.run(run)
        assumeTrue(capture.hasWatch("procs"), "$run has no procs watch")
        val trace = capture.watch("procs")
        // key: slot, script, parent -> (first step, last step)
        val open = HashMap<Triple<Int, Int, Int>, IntArray>()
        val result = ArrayList<Pair<IntArray, String>>()
        for (step in first..last) {
            val at = ProcArrayDump.ofWatch(trace, step, names)
            for (p in at.live) {
                val parent = p.u32(PARENT)
                val key = Triple(p.slot, p.script, parent)
                val seg = open[key]
                if (seg != null && seg[1] == step - 1) {
                    seg[1] = step
                    continue
                }
                val parentSlot = (parent - ProcArrayDump.ARRAY_ADDRESS) / ProcArrayDump.PROC_SIZE
                val parentName = if (parent in 0..ProcArrayDump.SLOTS) {
                    "tree $parent"
                } else {
                    at.live.firstOrNull { it.slot == parentSlot }?.name ?: "?"
                }
                val created = intArrayOf(step, step)
                open[key] = created
                result += created to "${p.name} <- $parentName"
            }
        }
        return result.map { (s, label) -> "$label ${s[0]}-${s[1]}" }
    }

    private fun check(expected: List<String>, run: String, first: Int, last: Int) {
        assertEquals(expected.sorted(), rows(run, first, last).sorted(), run)
    }

    private fun enemyPhase(): ReferenceData.Run {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val run = ref.run("ram_trace_prologue_enemy_phase")
        assumeTrue(run.hasWatch("aidecision"), "no aidecision watch")
        return run
    }

    @Test
    fun `cancel end matches the procs watch from End to the first walk`() {
        check(CANCEL_END_ROWS, "ram_trace_prologue_cancel_end", 1510, 1650)
    }

    @Test
    fun `enemy phase matches the procs watch from End to the turn 2 player phase`() {
        check(ENEMY_PHASE_ROWS, "ram_trace_prologue_enemy_phase", 1525, 2851)
    }

    @Test
    fun `gAiDecision shows the two attacks and none for the unit that waits`() {
        val decision = enemyPhase().watch("aidecision")
        val zero = ByteArray(DECISION_SIZE)
        fun at(step: Int) = decision.step(step).toList()
        assertEquals(zero.toList(), at(1613))
        // action 1 (attack), unit 0x82, move (5,5), target 2 (Eirika), item slot 0, performed 1
        assertEquals(ATTACK_SOLDIER_1, at(1614))
        assertEquals(ATTACK_SOLDIER_1, at(2037))
        assertEquals(zero.toList(), at(2038))
        assertEquals(ATTACK_SOLDIER_2, at(2039))
        assertEquals(ATTACK_SOLDIER_2, at(2895))
    }

    @Test
    fun `gAiState lists the three red units and steps through them`() {
        val state = enemyPhase().watch("aistate")
        assertEquals(listOf(0, 0, 0), (0..2).map { state.u8(1611, it) })
        assertEquals(listOf(0x81, 0x82, 0x83), (0..2).map { state.u8(1612, it) })
        // unitIt (byte 0x74, pointer into the list): units[1] at f1613, units[2] at f1614 and f2039
        assertEquals(listOf(0x05, 0x06, 0x07), listOf(1613, 1614, 2039).map { state.u8(it, 0x74) })
    }

    @Test
    fun `the red units' AI scripts are the inventoried ones`() {
        val red = enemyPhase().watch("red")
        // ai1 at 0x42, a-script position 0x43, ai2 at 0x44, b-script position 0x45 (struct Unit)
        fun ai(unit: Int, step: Int) = (0x42..0x45).map { red.u8(step, unit * UNIT_SIZE + it) }
        // Red 0: ai1 6 (do nothing), ai2 3 (never move); both scripts advance one command at f1613
        assertEquals(listOf(6, 0, 3, 0), ai(0, 1612))
        assertEquals(listOf(6, 1, 3, 1), ai(0, 1613))
        // Soldier 1: ai1 0 (action in range), ai2 0x0A (move to Eirika); script A advances f1614
        assertEquals(listOf(0, 0, 0x0A, 0), ai(1, 1613))
        assertEquals(listOf(0, 1, 0x0A, 0), ai(1, 1614))
        // Soldier 2: ai1 0, ai2 0x12 (wait one turn, then move to enemy); script A advances f2039
        assertEquals(listOf(0, 0, 0x12, 0), ai(2, 2038))
        assertEquals(listOf(0, 1, 0x12, 0), ai(2, 2039))
    }

    private companion object {
        const val PARENT = 0x14
        const val DECISION_SIZE = 12
        const val UNIT_SIZE = 0x48
        val ATTACK_SOLDIER_1 = listOf(1, 0x82, 5, 5, 0, 0, 2, 0, 0, 0, 1, 0).map { it.toByte() }
        val ATTACK_SOLDIER_2 = listOf(1, 0x83, 5, 5, 0, 0, 2, 0, 0, 0, 1, 0).map { it.toByte() }

        val CANCEL_END_ROWS = listOf(
            "gProcScr_GameControl <- tree 3 1510-1650",
            "sProc_BMVSync <- tree 0 1510-1650",
            "gProc_BMapMain <- tree 2 1510-1650",
            "gProc_MapTask <- tree 4 1510-1650",
            "gProcScr_BerserkCpPhase <- gProc_BMapMain 1511-1512",
            "gProcScr_BerserkCpOrder <- gProcScr_BerserkCpPhase 1511-1511",
            "ProcScr_StdEventEngine <- tree 3 1514-1516",
            "gProcScr_ResetCursorPosition <- gProc_BMapMain 1520-1520",
            "ProcScr_PhaseIntro <- gProc_BMapMain 1520-1590",
            "gProcScr_PhaseIntroBlendBox <- ProcScr_PhaseIntro 1521-1581",
            "gProcScr_PhaseIntroSquares <- ProcScr_PhaseIntro 1521-1586",
            "gProcScr_PhaseIntroText <- ProcScr_PhaseIntro 1521-1588",
            "gProcScr_PhaseIntroUnk <- gProcScr_PhaseIntroText 1527-1588",
            "gProcScr_CpDecide <- gProcScr_CpOrder 1597-1650",
            "gProcScr_CpOrder <- gProcScr_CpPhase 1597-1650",
            "gProcScr_CpPhase <- gProc_BMapMain 1597-1650",
            "ProcScr_Mu <- tree 5 1599-1650",
            "gProcScr_CpPerform <- gProcScr_CpDecide 1599-1650",
            "ProcScr_MuStepSe <- tree 3 1600-1600",
            "ProcScr_MuStepSe <- tree 3 1607-1608",
            "ProcScr_MuStepSe <- tree 3 1615-1616",
            "ProcScr_MuStepSe <- tree 3 1623-1624",
            "ProcScr_MuStepSe <- tree 3 1631-1632",
            "gProcScr_AiTargetCursor <- gProcScr_CpPerform 1633-1650",
        )

        val ENEMY_PHASE_ROWS = listOf(
            "gProcScr_GameControl <- tree 3 1525-2851",
            "sProc_BMVSync <- tree 0 1525-2851",
            "gProc_BMapMain <- tree 2 1525-2851",
            "gProc_MapTask <- tree 4 1525-2851",
            "gProcScr_BerserkCpPhase <- gProc_BMapMain 1526-1527",
            "gProcScr_BerserkCpOrder <- gProcScr_BerserkCpPhase 1526-1526",
            "ProcScr_StdEventEngine <- tree 3 1529-1531",
            "gProcScr_ResetCursorPosition <- gProc_BMapMain 1535-1535",
            "ProcScr_PhaseIntro <- gProc_BMapMain 1535-1605",
            "gProcScr_PhaseIntroText <- ProcScr_PhaseIntro 1536-1603",
            "gProcScr_PhaseIntroSquares <- ProcScr_PhaseIntro 1536-1601",
            "gProcScr_PhaseIntroBlendBox <- ProcScr_PhaseIntro 1536-1596",
            "gProcScr_PhaseIntroUnk <- gProcScr_PhaseIntroText 1542-1603",
            "gProcScr_CpDecide <- gProcScr_CpOrder 1612-2741",
            "gProcScr_CpPhase <- gProc_BMapMain 1612-2745",
            "gProcScr_CpOrder <- gProcScr_CpPhase 1612-2744",
            "gProcScr_CpPerform <- gProcScr_CpDecide 1614-2017",
            "ProcScr_Mu <- tree 5 1614-1905",
            "ProcScr_MuStepSe <- tree 3 1615-1615",
            "ProcScr_MuStepSe <- tree 3 1622-1623",
            "ProcScr_MuStepSe <- tree 3 1630-1631",
            "ProcScr_MuStepSe <- tree 3 1638-1639",
            "ProcScr_MuStepSe <- tree 3 1646-1647",
            "gProcScr_AiTargetCursor <- gProcScr_CpPerform 1648-1694",
            "sProcScr_CombatAction <- gProcScr_CpPerform 1696-2012",
            "ProcScr_MapAnimBattle <- tree 3 1697-2010",
            "ProcScr_Mu <- tree 5 1697-2017",
            "ProcScr_MapBattleInfoBox <- tree 3 1706-1906",
            "ProcScr_CallDelayedArg <- tree 3 1723-1744",
            "ProcScr_MapAnimDefaultItemEffect <- ProcScr_MapAnimBattle 1723-1760",
            "ProcScr_ApProc <- tree 3 1729-1756",
            "ProcScr_MapAnimDefaultItemEffect <- ProcScr_MapAnimBattle 1769-1820",
            "ProcScr_CallDelayedArg <- tree 3 1769-1790",
            "ProcScr_MuCritFlash <- ProcScr_Mu 1775-1815",
            "ProcScr_MapAnimBgShaker <- tree 3 1775-1792",
            "ProcScr_CallDelayedArg <- tree 3 1775-1796",
            "ProcScr_PalFade <- ProcScr_MuCritFlash 1787-1807",
            "ProcScr_CallDelayedArg <- tree 3 1829-1850",
            "ProcScr_MapAnimDefaultItemEffect <- ProcScr_MapAnimBattle 1829-1866",
            "ProcScr_MuHitFlash <- ProcScr_Mu 1835-1851",
            "ProcScr_PalFade <- ProcScr_Mu 1835-1855",
            "ProcScr_MuDeathFade <- ProcScr_Mu 1875-1905",
            "ProcScr_MuHitFlash <- ProcScr_Mu 1875-1891",
            "ProcScr_PalFade <- ProcScr_Mu 1875-1895",
            "ProcScr_MapAnimExpBar <- ProcScr_MapAnimBattle 1909-1998",
            "ProcScr_StdEventEngine <- tree 3 2014-2016",
            "sProcScr_BMXFADE <- tree 3 2018-2033",
            "ProcScr_Mu <- tree 5 2039-2293",
            "gProcScr_CpPerform <- gProcScr_CpDecide 2039-2722",
            "ProcScr_MuStepSe <- tree 3 2039-2040",
            "ProcScr_MuStepSe <- tree 3 2047-2048",
            "ProcScr_MuStepSe <- tree 3 2055-2056",
            "ProcScr_MuStepSe <- tree 3 2063-2064",
            "ProcScr_MuStepSe <- tree 3 2071-2072",
            "ProcScr_MuStepSe <- tree 3 2079-2080",
            "gProcScr_AiTargetCursor <- gProcScr_CpPerform 2081-2127",
            "sProcScr_CombatAction <- gProcScr_CpPerform 2129-2401",
            "ProcScr_MapAnimBattle <- tree 3 2130-2399",
            "ProcScr_Mu <- tree 5 2130-2722",
            "ProcScr_MapBattleInfoBox <- tree 3 2139-2294",
            "ProcScr_MapAnimDefaultItemEffect <- ProcScr_MapAnimBattle 2156-2195",
            "ProcScr_CallDelayedArg <- tree 3 2156-2177",
            "ProcScr_MuHitFlash <- ProcScr_Mu 2162-2178",
            "ProcScr_PalFade <- ProcScr_Mu 2162-2182",
            "ProcScr_MapAnimDefaultItemEffect <- ProcScr_MapAnimBattle 2204-2254",
            "ProcScr_CallDelayedArg <- tree 3 2204-2225",
            "ProcScr_MuCritFlash <- ProcScr_Mu 2210-2250",
            "ProcScr_CallDelayedArg <- tree 3 2210-2231",
            "ProcScr_MapAnimBgShaker <- tree 3 2210-2227",
            "ProcScr_PalFade <- ProcScr_MuCritFlash 2222-2242",
            "ProcScr_PalFade <- ProcScr_Mu 2263-2283",
            "ProcScr_MuDeathFade <- ProcScr_Mu 2263-2293",
            "ProcScr_MuHitFlash <- ProcScr_Mu 2263-2279",
            "ProcScr_MapAnimExpBar <- ProcScr_MapAnimBattle 2297-2387",
            "ProcScr_EventDisplayCursor <- ProcScr_StdEventEngine 2403-2462",
            "ProcScr_StdEventEngine <- tree 3 2403-2721",
            "gProcScr_Talk <- tree 3 2465-2703",
            "gProcScr_TalkSkipListener <- tree 3 2467-2703",
            "gProcScr_FaceBlink <- gProcScr_E_FACE 2471-2714",
            "gProcScr_0859124C <- gProcScr_E_FACE 2471-2714",
            "gProcScr_E_FACE <- tree 5 2471-2714",
            "ProcScr_PalFade <- gProcScr_E_FACE 2471-2483",
            "ProcScr_TemporaryLock <- gProcScr_Talk 2471-2479",
            "gProcScr_TalkOpen <- gProcScr_Talk 2481-2493",
            "gProcScr_TalkBubbleOpen <- tree 3 2481-2492",
            "gProcScr_TalkWaitForInput <- gProcScr_Talk 2644-2701",
            "ProcScr_PalFade <- gProcScr_E_FACE 2706-2714",
            "gProcScr_FaceEndIn8Frames <- tree 3 2706-2714",
            "gUnknown_08591DE8 <- ProcScr_StdEventEngine 2706-2717",
            "sProcScr_BMXFADE <- tree 3 2723-2738",
            "gProcScr_BerserkCpPhase <- gProc_BMapMain 2747-2748",
            "gProcScr_BerserkCpOrder <- gProcScr_BerserkCpPhase 2747-2747",
            "gProcScr_CpPhase <- gProc_BMapMain 2759-2761",
            "gProcScr_CpOrder <- gProcScr_CpPhase 2759-2760",
            "gProcScr_BerserkCpPhase <- gProc_BMapMain 2763-2764",
            "gProcScr_BerserkCpOrder <- gProcScr_BerserkCpPhase 2763-2763",
            "gProcScr_UpdateTraps <- gProc_BMapMain 2766-2766",
            "ProcScr_StdEventEngine <- tree 3 2768-2770",
            "gProcScr_ResetCursorPosition <- gProc_BMapMain 2774-2774",
            "ProcScr_PhaseIntro <- gProc_BMapMain 2774-2844",
            "gProcScr_PhaseIntroText <- ProcScr_PhaseIntro 2775-2842",
            "gProcScr_PhaseIntroBlendBox <- ProcScr_PhaseIntro 2775-2835",
            "gProcScr_PhaseIntroSquares <- ProcScr_PhaseIntro 2775-2840",
            "gProcScr_PhaseIntroUnk <- gProcScr_PhaseIntroText 2781-2842",
            "gProcScr_PlayerPhase <- gProc_BMapMain 2851-2851",
        )
    }
}
