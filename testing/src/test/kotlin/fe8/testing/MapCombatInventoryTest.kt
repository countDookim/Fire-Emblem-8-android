package fe8.testing

import gba.testkit.ReferenceData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Inventory (not a port claim, MC-0) of the procs alive in the attack windows of three captures,
 * from the `procs` watch: `ram_trace_prologue_attack` (battle animations on, f1365..2038),
 * `ram_trace_prologue_attack_noanim` (animations off, with one B out of target select and one B
 * out of the weapon list, f1595..2124) and `ram_trace_prologue_kill` (Seth kills a Soldier, Eirika
 * hits the other, then the turn ends, f1623..2600). Each row is `script symbol <- parent`, where
 * the parent is the script symbol of the parent slot at the segment's first step, or `tree N` for
 * a root proc, followed by the first and last step of a contiguous run of one slot with one script
 * and one parent. The function lists derived from these rows are in docs/plans/M6C-J-breakdown.md.
 */
class MapCombatInventoryTest {
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

    @Test
    fun `attack with animations on matches the procs watch`() {
        check(ATTACK_ROWS, "ram_trace_prologue_attack", 1365, 2038)
    }

    @Test
    fun `attack with animations off and two cancels matches the procs watch`() {
        check(NOANIM_ROWS, "ram_trace_prologue_attack_noanim", 1595, 2124)
    }

    @Test
    fun `kill then attack with animations off matches the procs watch`() {
        check(KILL_ROWS, "ram_trace_prologue_kill", 1623, 2600)
    }

    private companion object {
        const val PARENT = 0x14

        val ATTACK_ROWS = listOf(
            "gProcScr_GameControl <- tree 3 1365-2038",
            "sProc_BMVSync <- tree 0 1365-2038",
            "gProc_BMapMain <- tree 2 1365-2038",
            "gProc_MapTask <- tree 4 1365-2038",
            "gProcScr_PlayerPhase <- gProc_BMapMain 1365-2038",
            "sProc_MenuItem <- sProc_Menu 1365-1399",
            "sProc_Menu <- tree 3 1365-1399",
            "sProc_MenuItem <- sProc_Menu 1365-1399",
            "sProc_MenuItem <- sProc_Menu 1365-1399",
            "ProcScr_Mu <- tree 5 1365-1478",
            "ProcScr_GreenTextColor <- sProc_Menu 1365-1399",
            "sProcScr_MoveLimitView <- tree 4 1365-1399",
            "sProcScr_MoveLimitViewChange <- sProcScr_MoveLimitView 1365-1371",
            "sProc_Menu <- tree 3 1400-1429",
            "sProc_MenuItem <- sProc_Menu 1400-1429",
            "gProcScr_E_FACE <- tree 5 1400-1429",
            "gProcScr_0859124C <- gProcScr_E_FACE 1400-1429",
            "gProcScr_FaceBlink <- gProcScr_E_FACE 1400-1429",
            "gProcCmd_MenuItemPanel <- sProc_Menu 1400-1429",
            "ProcScr_GreenTextColor <- sProc_Menu 1401-1429",
            "sProcScr_MoveLimitViewChange <- sProcScr_MoveLimitView 1401-1407",
            "sProcScr_MoveLimitView <- tree 4 1401-1459",
            "gProcScr_BKSEL <- tree 3 1430-1459",
            "gProcScr_TargetSelection <- tree 3 1430-1459",
            "ProcScr_ekrBattleStarting <- tree 3 1464-1495",
            "sProcScr_CombatAction <- gProcScr_PlayerPhase 1464-2028",
            "gProc_ekrBattleDeamon <- tree 3 1464-2026",
            "ProcScr_ekrGauge <- tree 1 1480-2008",
            "gProc_ekrDispUP <- tree 5 1480-2000",
            "ProcScr_ekrNamewinAppear <- tree 3 1482-1498",
            "ProcScr_ekrWindowAppear <- tree 3 1482-1496",
            "ProcScr_EkrBaseKaiten <- tree 3 1482-1496",
            "ProcScr_ekrUnitKakudai <- tree 3 1482-1499",
            "ProcScr_ekrBaseAppear <- tree 3 1482-1496",
            "gMusicProc3Script <- tree 3 1496-1529",
            "gProc_ekrBattle <- tree 3 1496-1988",
            "ProcScr_efxStatusUnit <- tree 3 1505-1994",
            "ProcScr_efxHPBarColorChange <- tree 3 1505-1984",
            "ProcScr_efxStatusUnit <- tree 3 1505-1994",
            "ProcScr_efxWeaponIcon <- tree 3 1505-1984",
            "ProcScr_efxHPBar <- tree 3 1577-1611",
            "ProcScr_efxFlashHPBar <- tree 4 1577-1583",
            "ProcScr_efxFlashUnit <- tree 4 1577-1586",
            "ProcScr_EfxHitQuake <- tree 3 1577-1596",
            "ProcScr_efxNormalEffect <- tree 3 1577-1602",
            "ProcScr_efxFlashBG <- tree 0 1578-1583",
            "ProcScr_efxNormalEffectBG <- tree 3 1581-1599",
            "ProcScr_efxHPBar <- tree 3 1659-1692",
            "ProcScr_efxNormalEffect <- tree 3 1659-1683",
            "ProcScr_efxFlashUnit <- tree 4 1659-1667",
            "ProcScr_EfxHitQuake <- tree 3 1659-1677",
            "ProcScr_efxFlashHPBar <- tree 4 1659-1664",
            "ProcScr_efxFlashBG <- tree 0 1660-1665",
            "ProcScr_efxNormalEffectBG <- tree 3 1663-1680",
            "gProc_ekrChienCHR <- tree 3 1750-1752",
            "ProcScr_efxHPBar <- tree 3 1784-1818",
            "ProcScr_EfxHitQuake <- tree 3 1784-1803",
            "ProcScr_efxNormalEffect <- tree 3 1784-1809",
            "ProcScr_efxFlashUnit <- tree 4 1784-1793",
            "ProcScr_efxFlashHPBar <- tree 4 1784-1790",
            "ProcScr_efxFlashBG <- tree 0 1785-1790",
            "ProcScr_efxNormalEffectBG <- tree 3 1788-1806",
            "ProcScr_ekrNamewinAppear <- tree 3 1989-2001",
            "ProcScr_ekrBattleEnding <- tree 3 1989-2027",
            "ProcScr_EkrBaseKaiten <- tree 3 1993-2008",
            "ProcScr_ekrWindowAppear <- tree 3 1993-2008",
            "ProcScr_ekrUnitKakudai <- tree 3 1993-2011",
            "ProcScr_ekrBaseAppear <- tree 3 1993-2008",
            "sProc_DelayedBMapDispResume <- tree 3 2010-2011",
            "ProcScr_Mu <- tree 5 2010-2033",
            "ProcScr_StdEventEngine <- tree 3 2030-2032",
            "gProcScr_SideWindowMaker <- tree 3 2037-2037",
            "gProcScr_TerrainDisplay <- tree 3 2038-2038",
            "gProcScr_UnitDisplay_MinimugBox <- tree 3 2038-2038",
            "gProcScr_GoalDisplay <- tree 3 2038-2038",
            "ProcScr_GreenTextColor <- gProcScr_GoalDisplay 2038-2038",
        )

        val NOANIM_ROWS = listOf(
            "gProcScr_GameControl <- tree 3 1595-2124",
            "sProc_BMVSync <- tree 0 1595-2124",
            "gProc_BMapMain <- tree 2 1595-2124",
            "gProc_MapTask <- tree 4 1595-2124",
            "gProcScr_PlayerPhase <- gProc_BMapMain 1595-2124",
            "sProc_Menu <- tree 3 1595-1629",
            "sProcScr_MoveLimitViewChange <- sProcScr_MoveLimitView 1595-1601",
            "sProcScr_MoveLimitView <- tree 4 1595-1629",
            "ProcScr_GreenTextColor <- sProc_Menu 1595-1629",
            "ProcScr_Mu <- tree 5 1595-2079",
            "sProc_MenuItem <- sProc_Menu 1595-1629",
            "sProc_MenuItem <- sProc_Menu 1595-1629",
            "sProc_MenuItem <- sProc_Menu 1595-1629",
            "gProcScr_E_FACE <- tree 5 1630-1659",
            "sProc_MenuItem <- sProc_Menu 1630-1659",
            "sProc_Menu <- tree 3 1630-1659",
            "gProcScr_0859124C <- gProcScr_E_FACE 1630-1659",
            "gProcScr_FaceBlink <- gProcScr_E_FACE 1630-1659",
            "gProcCmd_MenuItemPanel <- sProc_Menu 1630-1659",
            "ProcScr_GreenTextColor <- sProc_Menu 1631-1659",
            "sProcScr_MoveLimitView <- tree 4 1631-1699",
            "sProcScr_MoveLimitViewChange <- sProcScr_MoveLimitView 1631-1637",
            "gProcScr_BKSEL <- tree 3 1660-1703",
            "gProcScr_TargetSelection <- tree 3 1660-1699",
            "gProcScr_0859B630 <- tree 3 1700-1703",
            "gProcCmd_MenuItemPanel <- sProc_Menu 1704-1729",
            "gProcScr_E_FACE <- tree 5 1704-1729",
            "sProc_Menu <- tree 3 1704-1729",
            "sProc_MenuItem <- sProc_Menu 1704-1729",
            "gProcScr_FaceBlink <- gProcScr_E_FACE 1704-1729",
            "gProcScr_0859124C <- gProcScr_E_FACE 1704-1729",
            "ProcScr_GreenTextColor <- sProc_Menu 1705-1729",
            "sProcScr_MoveLimitView <- tree 4 1705-1729",
            "sProcScr_MoveLimitViewChange <- sProcScr_MoveLimitView 1705-1711",
            "sProc_MenuItem <- sProc_Menu 1730-1759",
            "sProc_MenuItem <- sProc_Menu 1730-1759",
            "sProc_MenuItem <- sProc_Menu 1730-1759",
            "sProc_Menu <- tree 3 1730-1759",
            "ProcScr_GreenTextColor <- sProc_Menu 1731-1759",
            "sProcScr_MoveLimitView <- tree 4 1731-1759",
            "sProcScr_MoveLimitViewChange <- sProcScr_MoveLimitView 1731-1736",
            "gProcScr_E_FACE <- tree 5 1760-1789",
            "gProcCmd_MenuItemPanel <- sProc_Menu 1760-1789",
            "gProcScr_0859124C <- gProcScr_E_FACE 1760-1789",
            "sProc_MenuItem <- sProc_Menu 1760-1789",
            "gProcScr_FaceBlink <- gProcScr_E_FACE 1760-1789",
            "sProc_Menu <- tree 3 1760-1789",
            "sProcScr_MoveLimitViewChange <- sProcScr_MoveLimitView 1761-1767",
            "sProcScr_MoveLimitView <- tree 4 1761-1819",
            "ProcScr_GreenTextColor <- sProc_Menu 1761-1789",
            "gProcScr_TargetSelection <- tree 3 1790-1819",
            "gProcScr_BKSEL <- tree 3 1790-1819",
            "sProcScr_CombatAction <- gProcScr_PlayerPhase 1824-2074",
            "ProcScr_Mu <- tree 5 1825-2079",
            "ProcScr_MapAnimBattle <- tree 3 1825-2072",
            "ProcScr_MapBattleInfoBox <- tree 3 1834-1990",
            "ProcScr_CallDelayedArg <- tree 3 1851-1872",
            "ProcScr_MapAnimDefaultItemEffect <- ProcScr_MapAnimBattle 1851-1888",
            "ProcScr_PalFade <- ProcScr_Mu 1857-1877",
            "ProcScr_MuHitFlash <- ProcScr_Mu 1857-1873",
            "ProcScr_MapAnimDefaultItemEffect <- ProcScr_MapAnimBattle 1897-1936",
            "ProcScr_CallDelayedArg <- tree 3 1897-1918",
            "ProcScr_MuHitFlash <- ProcScr_Mu 1903-1919",
            "ProcScr_PalFade <- ProcScr_Mu 1903-1923",
            "ProcScr_MapAnimDefaultItemEffect <- ProcScr_MapAnimBattle 1945-1982",
            "ProcScr_CallDelayedArg <- tree 3 1945-1966",
            "ProcScr_PalFade <- ProcScr_Mu 1951-1971",
            "ProcScr_MuHitFlash <- ProcScr_Mu 1951-1967",
            "ProcScr_MapAnimExpBar <- ProcScr_MapAnimBattle 1993-2060",
            "ProcScr_StdEventEngine <- tree 3 2076-2078",
            "gProcScr_SideWindowMaker <- tree 3 2083-2083",
            "gProcScr_GoalDisplay <- tree 3 2084-2124",
            "gProcScr_TerrainDisplay <- tree 3 2084-2124",
            "ProcScr_GreenTextColor <- gProcScr_GoalDisplay 2084-2124",
            "gProcScr_UnitDisplay_MinimugBox <- tree 3 2084-2124",
        )

        val KILL_ROWS = listOf(
            "gProcScr_GameControl <- tree 3 1623-2600",
            "sProc_BMVSync <- tree 0 1623-2600",
            "gProc_BMapMain <- tree 2 1623-2600",
            "gProc_MapTask <- tree 4 1623-2600",
            "gProcScr_PlayerPhase <- gProc_BMapMain 1623-2557",
            "sProc_Menu <- tree 3 1623-1649",
            "sProcScr_MoveLimitViewChange <- sProcScr_MoveLimitView 1623-1629",
            "sProcScr_MoveLimitView <- tree 4 1623-1649",
            "ProcScr_GreenTextColor <- sProc_Menu 1623-1649",
            "ProcScr_Mu <- tree 5 1623-2004",
            "sProc_MenuItem <- sProc_Menu 1623-1649",
            "sProc_MenuItem <- sProc_Menu 1623-1649",
            "sProc_MenuItem <- sProc_Menu 1623-1649",
            "sProc_MenuItem <- sProc_Menu 1650-1679",
            "sProc_MenuItem <- sProc_Menu 1650-1679",
            "sProc_Menu <- tree 3 1650-1679",
            "gProcScr_E_FACE <- tree 5 1650-1679",
            "gProcScr_0859124C <- gProcScr_E_FACE 1650-1679",
            "gProcScr_FaceBlink <- gProcScr_E_FACE 1650-1679",
            "gProcCmd_MenuItemPanel <- sProc_Menu 1650-1679",
            "ProcScr_GreenTextColor <- sProc_Menu 1651-1679",
            "sProcScr_MoveLimitView <- tree 4 1652-1709",
            "sProcScr_MoveLimitViewChange <- sProcScr_MoveLimitView 1652-1657",
            "gProcScr_BKSEL <- tree 3 1680-1709",
            "gProcScr_TargetSelection <- tree 3 1680-1709",
            "sProcScr_CombatAction <- gProcScr_PlayerPhase 1714-1999",
            "ProcScr_Mu <- tree 5 1715-1921",
            "ProcScr_MapAnimBattle <- tree 3 1715-1997",
            "ProcScr_MapBattleInfoBox <- tree 3 1725-1922",
            "ProcScr_CallDelayedArg <- tree 3 1742-1763",
            "ProcScr_MapAnimDefaultItemEffect <- ProcScr_MapAnimBattle 1742-1790",
            "ProcScr_MuHitFlash <- ProcScr_Mu 1748-1764",
            "ProcScr_PalFade <- ProcScr_Mu 1748-1768",
            "ProcScr_CallDelayedArg <- tree 3 1799-1820",
            "ProcScr_MapAnimDefaultItemEffect <- ProcScr_MapAnimBattle 1799-1836",
            "ProcScr_ApProc <- tree 3 1805-1832",
            "ProcScr_CallDelayedArg <- tree 3 1845-1866",
            "ProcScr_MapAnimDefaultItemEffect <- ProcScr_MapAnimBattle 1845-1882",
            "ProcScr_PalFade <- ProcScr_Mu 1851-1871",
            "ProcScr_MuHitFlash <- ProcScr_Mu 1851-1867",
            "ProcScr_PalFade <- ProcScr_Mu 1891-1911",
            "ProcScr_MuHitFlash <- ProcScr_Mu 1891-1907",
            "ProcScr_MuDeathFade <- ProcScr_Mu 1891-1921",
            "ProcScr_MapAnimExpBar <- ProcScr_MapAnimBattle 1925-1985",
            "ProcScr_StdEventEngine <- tree 3 2001-2003",
            "gProcScr_SideWindowMaker <- tree 3 2008-2008",
            "ProcScr_GreenTextColor <- gProcScr_GoalDisplay 2009-2070",
            "gProcScr_GoalDisplay <- tree 3 2009-2070",
            "gProcScr_TerrainDisplay <- tree 3 2009-2070",
            "gProcScr_UnitDisplay_MinimugBox <- tree 3 2009-2070",
            "ProcScr_Mu <- tree 5 2059-2554",
            "sProcScr_MoveLimitViewChange <- sProcScr_MoveLimitView 2072-2077",
            "sProcScr_MoveLimitView <- tree 4 2072-2159",
            "ProcScr_MuStepSe <- tree 3 2161-2161",
            "ProcScr_MuStepSe <- tree 3 2168-2169",
            "ProcScr_MuStepSe <- tree 3 2176-2177",
            "ProcScr_MuStepSe <- tree 3 2184-2185",
            "ProcScr_MuStepSe <- tree 3 2192-2193",
            "ProcScr_MuStepSe <- tree 3 2200-2201",
            "sProcScr_MoveLimitViewChange <- sProcScr_MoveLimitView 2203-2209",
            "ProcScr_GreenTextColor <- sProc_Menu 2203-2229",
            "sProc_MenuItem <- sProc_Menu 2203-2229",
            "sProc_Menu <- tree 3 2203-2229",
            "sProc_MenuItem <- sProc_Menu 2203-2229",
            "sProc_MenuItem <- sProc_Menu 2203-2229",
            "sProcScr_MoveLimitView <- tree 4 2203-2229",
            "sProc_MenuItem <- sProc_Menu 2203-2229",
            "gProcScr_E_FACE <- tree 5 2230-2259",
            "sProc_Menu <- tree 3 2230-2259",
            "gProcScr_0859124C <- gProcScr_E_FACE 2230-2259",
            "gProcCmd_MenuItemPanel <- sProc_Menu 2230-2259",
            "gProcScr_FaceBlink <- gProcScr_E_FACE 2230-2259",
            "sProc_MenuItem <- sProc_Menu 2230-2259",
            "sProcScr_MoveLimitView <- tree 4 2231-2289",
            "ProcScr_GreenTextColor <- sProc_Menu 2231-2259",
            "sProcScr_MoveLimitViewChange <- sProcScr_MoveLimitView 2231-2237",
            "gProcScr_BKSEL <- tree 3 2260-2289",
            "gProcScr_TargetSelection <- tree 3 2260-2289",
            "sProcScr_CombatAction <- gProcScr_PlayerPhase 2294-2549",
            "ProcScr_Mu <- tree 5 2295-2554",
            "ProcScr_MapAnimBattle <- tree 3 2296-2547",
            "ProcScr_MapBattleInfoBox <- tree 3 2305-2465",
            "ProcScr_CallDelayedArg <- tree 3 2322-2343",
            "ProcScr_MapAnimDefaultItemEffect <- ProcScr_MapAnimBattle 2322-2361",
            "ProcScr_PalFade <- ProcScr_Mu 2328-2348",
            "ProcScr_MuHitFlash <- ProcScr_Mu 2328-2344",
            "ProcScr_CallDelayedArg <- tree 3 2370-2391",
            "ProcScr_MapAnimDefaultItemEffect <- ProcScr_MapAnimBattle 2370-2409",
            "ProcScr_MuHitFlash <- ProcScr_Mu 2376-2392",
            "ProcScr_PalFade <- ProcScr_Mu 2376-2396",
            "ProcScr_CallDelayedArg <- tree 3 2418-2439",
            "ProcScr_MapAnimDefaultItemEffect <- ProcScr_MapAnimBattle 2418-2457",
            "ProcScr_MuHitFlash <- ProcScr_Mu 2424-2440",
            "ProcScr_PalFade <- ProcScr_Mu 2424-2444",
            "ProcScr_MapAnimExpBar <- ProcScr_MapAnimBattle 2468-2535",
            "ProcScr_StdEventEngine <- tree 3 2551-2553",
            "gProcScr_BerserkCpPhase <- gProc_BMapMain 2559-2560",
            "gProcScr_BerserkCpOrder <- gProcScr_BerserkCpPhase 2559-2559",
            "ProcScr_StdEventEngine <- tree 3 2562-2564",
            "gProcScr_PhaseIntroText <- ProcScr_PhaseIntro 2569-2600",
            "ProcScr_PhaseIntro <- gProc_BMapMain 2569-2600",
            "gProcScr_PhaseIntroBlendBox <- ProcScr_PhaseIntro 2569-2600",
            "gProcScr_PhaseIntroSquares <- ProcScr_PhaseIntro 2569-2600",
            "gProcScr_PhaseIntroUnk <- gProcScr_PhaseIntroText 2576-2600",
        )
    }
}
