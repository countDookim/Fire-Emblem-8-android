package fe8.testing

import gba.testkit.ReferenceData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Inventory (not a port claim) of the proc tree alive during the Prologue player phase, from the
 * `procs` watch of two captures: `ram_trace_prologue_select_move` (select, move, Wait) and
 * `ram_trace_prologue_cancel_end` (cancel select and move, map menu End, enemy phase intro and
 * first AI action). Each row is `script symbol <- parent`, where the parent is the script symbol of
 * the parent slot at the segment's first step, or `tree N` for a root proc, followed by the first
 * and last step of a contiguous run of one slot with one script and one parent.
 */
class PlayerPhaseProcInventoryTest {
    private val ref = ReferenceData.fromEnvironment()

    private class Segment(val slot: Int, val script: Int, val parent: Int, val first: Int) {
        var last: Int = first
        var label: String = ""
    }

    private fun segments(run: String, first: Int, last: Int): List<Segment> {
        val names = ProcArrayDump.romNames()
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        assumeTrue(names.isNotEmpty(), "decomp nm file not present")
        val capture = ref.run(run)
        assumeTrue(capture.hasWatch("procs"), "$run has no procs watch")
        val trace = capture.watch("procs")
        val open = HashMap<Triple<Int, Int, Int>, Segment>()
        val all = ArrayList<Segment>()
        for (step in first..last) {
            val at = ProcArrayDump.ofWatch(trace, step, names)
            for (p in at.live) {
                val key = Triple(p.slot, p.script, p.u32(PARENT))
                val seg = open[key]
                if (seg != null && seg.last == step - 1) {
                    seg.last = step
                    continue
                }
                val parent = p.u32(PARENT)
                val parentSlot = (parent - ProcArrayDump.ARRAY_ADDRESS) / ProcArrayDump.PROC_SIZE
                val parentName = if (parent in 0..ProcArrayDump.SLOTS) {
                    "tree $parent"
                } else {
                    at.live.firstOrNull { it.slot == parentSlot }?.name ?: "?"
                }
                val created = Segment(p.slot, p.script, parent, step)
                created.label = "${p.name} <- $parentName"
                open[key] = created
                all += created
            }
        }
        return all
    }

    @Test
    fun `select move proc tree matches the prologue procs watch`() {
        val rows = segments(SELECT_MOVE, SELECT_MOVE_FIRST, SELECT_MOVE_LAST)
            .map { "${it.label} ${it.first}-${it.last}" }.toSet()
        val missing = SELECT_MOVE_ROWS.filter { it !in rows }
        assertTrue(missing.isEmpty(), "missing rows: $missing; " + missing.flatMap { m ->
            rows.filter { it.substringBefore(" <-") == m.substringBefore(" <-") }
        })
    }

    @Test
    fun `select move roots and script symbols are exactly the inventoried ones`() {
        val all = segments(SELECT_MOVE, SELECT_MOVE_FIRST, SELECT_MOVE_LAST)
        val roots = all.filter { it.parent in 0..ProcArrayDump.SLOTS }
            .map { it.label.substringBefore(" <- ") }.toSet()
        assertEquals(SELECT_MOVE_ROOTS, roots)
        val symbols = all.map { it.label.substringBefore(" <- ") }.toSet()
        assertEquals(SELECT_MOVE_SYMBOLS, symbols)
    }

    @Test
    fun `cancel end proc tree matches the prologue procs watch`() {
        val rows = segments(CANCEL_END, CANCEL_END_FIRST, CANCEL_END_LAST)
            .map { "${it.label} ${it.first}-${it.last}" }.toSet()
        val missing = CANCEL_END_ROWS.filter { it !in rows }
        assertTrue(missing.isEmpty(), "missing rows: $missing; " + missing.flatMap { m ->
            rows.filter { it.substringBefore(" <-") == m.substringBefore(" <-") }
        })
    }

    @Test
    fun `cancel end roots and script symbols are exactly the inventoried ones`() {
        val all = segments(CANCEL_END, CANCEL_END_FIRST, CANCEL_END_LAST)
        val roots = all.filter { it.parent in 0..ProcArrayDump.SLOTS }
            .map { it.label.substringBefore(" <- ") }.toSet()
        assertEquals(CANCEL_END_ROOTS, roots)
        val symbols = all.map { it.label.substringBefore(" <- ") }.toSet()
        assertEquals(CANCEL_END_SYMBOLS, symbols)
    }

    private companion object {
        const val PARENT = 0x14

        const val SELECT_MOVE = "ram_trace_prologue_select_move"
        const val SELECT_MOVE_FIRST = 1169
        const val SELECT_MOVE_LAST = 1420

        const val CANCEL_END = "ram_trace_prologue_cancel_end"
        const val CANCEL_END_FIRST = 1169
        const val CANCEL_END_LAST = 1650

        val SELECT_MOVE_ROWS = listOf(
            "gProcScr_GameControl <- tree 3 1169-1420",
            "sProc_BMVSync <- tree 0 1169-1420",
            "gProc_BMapMain <- tree 2 1169-1420",
            "gProc_MapTask <- tree 4 1169-1420",
            "gProcScr_PlayerPhase <- gProc_BMapMain 1169-1420",
            "ProcScr_GreenTextColor <- gProcScr_GoalDisplay 1172-1260",
            "gProcScr_GoalDisplay <- tree 3 1172-1260",
            "gProcScr_TerrainDisplay <- tree 3 1172-1260",
            "gProcScr_UnitDisplay_MinimugBox <- tree 3 1172-1260",
            "ProcScr_Mu <- tree 5 1255-1359",
            "sProcScr_MoveLimitViewChange <- sProcScr_MoveLimitView 1262-1267",
            "sProcScr_MoveLimitView <- tree 4 1262-1294",
            "ProcScr_MuStepSe <- tree 3 1296-1296",
            "ProcScr_MuStepSe <- tree 3 1303-1304",
            "ProcScr_MuStepSe <- tree 3 1311-1312",
            "sProc_MenuItem <- sProc_Menu 1314-1354",
            "sProc_Menu <- tree 3 1314-1354",
            "sProc_MenuItem <- sProc_Menu 1314-1354",
            "ProcScr_GreenTextColor <- sProc_Menu 1314-1354",
            "ProcScr_StdEventEngine <- tree 3 1356-1358",
            "gProcScr_SideWindowMaker <- tree 3 1363-1363",
            "gProcScr_TerrainDisplay <- tree 3 1364-1420",
            "gProcScr_UnitDisplay_MinimugBox <- tree 3 1364-1420",
            "gProcScr_GoalDisplay <- tree 3 1364-1420",
            "ProcScr_GreenTextColor <- gProcScr_GoalDisplay 1364-1420",
        )

        val SELECT_MOVE_ROOTS = setOf(
            "ProcScr_Mu",
            "ProcScr_MuStepSe",
            "ProcScr_StdEventEngine",
            "gProcScr_GameControl",
            "gProcScr_GoalDisplay",
            "gProcScr_SideWindowMaker",
            "gProcScr_TerrainDisplay",
            "gProcScr_UnitDisplay_MinimugBox",
            "gProc_BMapMain",
            "gProc_MapTask",
            "sProcScr_MoveLimitView",
            "sProc_BMVSync",
            "sProc_Menu",
        )

        val SELECT_MOVE_SYMBOLS = setOf(
            "ProcScr_GreenTextColor",
            "ProcScr_Mu",
            "ProcScr_MuStepSe",
            "ProcScr_StdEventEngine",
            "gProcScr_GameControl",
            "gProcScr_GoalDisplay",
            "gProcScr_PlayerPhase",
            "gProcScr_SideWindowMaker",
            "gProcScr_TerrainDisplay",
            "gProcScr_UnitDisplay_MinimugBox",
            "gProc_BMapMain",
            "gProc_MapTask",
            "sProcScr_MoveLimitView",
            "sProcScr_MoveLimitViewChange",
            "sProc_BMVSync",
            "sProc_Menu",
            "sProc_MenuItem",
        )

        val CANCEL_END_ROWS = listOf(
            "gProcScr_GameControl <- tree 3 1169-1650",
            "sProc_BMVSync <- tree 0 1169-1650",
            "gProc_BMapMain <- tree 2 1169-1650",
            "gProc_MapTask <- tree 4 1169-1650",
            "gProcScr_PlayerPhase <- gProc_BMapMain 1169-1509",
            "ProcScr_GreenTextColor <- gProcScr_GoalDisplay 1172-1260",
            "gProcScr_GoalDisplay <- tree 3 1172-1260",
            "gProcScr_TerrainDisplay <- tree 3 1172-1260",
            "gProcScr_UnitDisplay_MinimugBox <- tree 3 1172-1260",
            "ProcScr_Mu <- tree 5 1255-1289",
            "sProcScr_MoveLimitViewChange <- sProcScr_MoveLimitView 1262-1267",
            "sProcScr_MoveLimitView <- tree 4 1262-1289",
            "gProcScr_GoalDisplay <- tree 3 1291-1310",
            "ProcScr_GreenTextColor <- gProcScr_GoalDisplay 1291-1310",
            "gProcScr_UnitDisplay_MinimugBox <- tree 3 1291-1310",
            "gProcScr_TerrainDisplay <- tree 3 1291-1310",
            "ProcScr_Mu <- tree 5 1296-1424",
            "sProcScr_MoveLimitView <- tree 4 1312-1354",
            "sProcScr_MoveLimitViewChange <- sProcScr_MoveLimitView 1312-1317",
            "ProcScr_MuStepSe <- tree 3 1356-1356",
            "ProcScr_MuStepSe <- tree 3 1363-1364",
            "ProcScr_MuStepSe <- tree 3 1371-1372",
            "sProc_Menu <- tree 3 1374-1399",
            "sProc_MenuItem <- sProc_Menu 1374-1399",
            "ProcScr_GreenTextColor <- sProc_Menu 1374-1399",
            "sProc_MenuItem <- sProc_Menu 1374-1399",
            "sProcScr_MoveLimitView <- tree 4 1403-1424",
            "sProcScr_MoveLimitViewChange <- sProcScr_MoveLimitView 1403-1408",
            "ProcScr_GreenTextColor <- gProcScr_GoalDisplay 1426-1464",
            "gProcScr_GoalDisplay <- tree 3 1426-1464",
            "gProcScr_TerrainDisplay <- tree 3 1426-1464",
            "gProcScr_UnitDisplay_MinimugBox <- tree 3 1426-1464",
            "ProcScr_Mu <- tree 5 1431-1444",
            "sProc_MenuItem <- sProc_Menu 1465-1509",
            "sProc_MenuItem <- sProc_Menu 1465-1509",
            "sProc_Menu <- tree 3 1465-1509",
            "sProc_MenuItem <- sProc_Menu 1465-1509",
            "sProc_MenuItem <- sProc_Menu 1465-1509",
            "sProc_MenuItem <- sProc_Menu 1465-1509",
            "sProc_MenuItem <- sProc_Menu 1465-1509",
            "ProcScr_GreenTextColor <- sProc_Menu 1465-1509",
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

        val CANCEL_END_ROOTS = setOf(
            "ProcScr_Mu",
            "ProcScr_MuStepSe",
            "ProcScr_StdEventEngine",
            "gProcScr_GameControl",
            "gProcScr_GoalDisplay",
            "gProcScr_TerrainDisplay",
            "gProcScr_UnitDisplay_MinimugBox",
            "gProc_BMapMain",
            "gProc_MapTask",
            "sProcScr_MoveLimitView",
            "sProc_BMVSync",
            "sProc_Menu",
        )

        val CANCEL_END_SYMBOLS = setOf(
            "ProcScr_GreenTextColor",
            "ProcScr_Mu",
            "ProcScr_MuStepSe",
            "ProcScr_PhaseIntro",
            "ProcScr_StdEventEngine",
            "gProcScr_AiTargetCursor",
            "gProcScr_BerserkCpOrder",
            "gProcScr_BerserkCpPhase",
            "gProcScr_CpDecide",
            "gProcScr_CpOrder",
            "gProcScr_CpPerform",
            "gProcScr_CpPhase",
            "gProcScr_GameControl",
            "gProcScr_GoalDisplay",
            "gProcScr_PhaseIntroBlendBox",
            "gProcScr_PhaseIntroSquares",
            "gProcScr_PhaseIntroText",
            "gProcScr_PhaseIntroUnk",
            "gProcScr_PlayerPhase",
            "gProcScr_ResetCursorPosition",
            "gProcScr_TerrainDisplay",
            "gProcScr_UnitDisplay_MinimugBox",
            "gProc_BMapMain",
            "gProc_MapTask",
            "sProcScr_MoveLimitView",
            "sProcScr_MoveLimitViewChange",
            "sProc_BMVSync",
            "sProc_Menu",
            "sProc_MenuItem",
        )
    }
}
