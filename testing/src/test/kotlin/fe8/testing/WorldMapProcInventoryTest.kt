package fe8.testing

import gba.testkit.ReferenceData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Inventory (not a port claim) of the proc tree alive during the New Game opening world-map
 * stretch (steps 4898-9313 of `ram_trace_prologue`, `procs` watch). Each row is
 * `script symbol <- parent`, where the parent is the script symbol of the parent slot at the
 * segment's first step, or `tree N` for a root proc, followed by the first and last step of a
 * contiguous run of one slot with one script and one parent.
 */
class WorldMapProcInventoryTest {
    private val ref = ReferenceData.fromEnvironment()

    private class Segment(val slot: Int, val script: Int, val parent: Int, val first: Int) {
        var last: Int = first
        var label: String = ""
    }

    private fun segments(): List<Segment> {
        val names = ProcArrayDump.romNames()
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        assumeTrue(names.isNotEmpty(), "decomp nm file not present")
        val run = ref.run("ram_trace_prologue")
        assumeTrue(run.hasWatch("procs"), "ram_trace_prologue has no procs watch")
        val trace = run.watch("procs")
        val open = HashMap<Triple<Int, Int, Int>, Segment>()
        val all = ArrayList<Segment>()
        for (step in FIRST..LAST) {
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
    fun `world map proc tree matches the prologue procs watch`() {
        val rows = segments().map { "${it.label} ${it.first}-${it.last}" }.toSet()
        val missing = EXPECTED.filter { it !in rows }
        assertTrue(missing.isEmpty(), "missing rows: $missing; " + missing.flatMap { m ->
            rows.filter { it.substringBefore(" <-") == m.substringBefore(" <-") }
        })
    }

    @Test
    fun `root procs and the set of script symbols are exactly the inventoried ones`() {
        val all = segments()
        val roots = all.filter { it.parent in 0..ProcArrayDump.SLOTS }
            .map { it.label.substringBefore(" <- ") }.toSet()
        assertEquals(EXPECTED_ROOTS, roots)
        val symbols = all.map { it.label.substringBefore(" <- ") }.toSet()
        assertEquals(EXPECTED_SYMBOLS, symbols)
    }

    private companion object {
        const val FIRST = 4898
        const val LAST = 9313
        const val PARENT = 0x14

        val EXPECTED = listOf(
            "gProcScr_GameControl <- tree 3 4898-9313",
            "ProcScr_WorldMapWrapper <- gProcScr_GameControl 4898-9311",
            "ProcScr_WorldMapMain <- tree 3 4899-9309",
            "ProcScr_GMapScreen <- tree 5 4900-9309",
            "ProcScr_GmapUnit <- ProcScr_GMapScreen 4901-9309",
            "ProcScr_GMapScreenVSync <- tree 0 4901-9309",
            "gProcScr_GmapCursor <- tree 5 4901-9309",
            "ProcScr_GMapMuPrim <- ProcScr_WorldMapMain 4901-9309",
            "ProcScr_GMapMu <- ProcScr_WorldMapMain 4901-9309",
            "ProcScr_GmapUnitContainer <- ProcScr_GMapScreen 4901-9309",
            "ProcScr_GMapRoute <- ProcScr_GMapScreen 4901-9309",
            "ProcScr_WorldMapTextHandler <- ProcScr_WorldMapMain 4901-9309",
            "ProcScr_GmNodeIconDisplay <- tree 5 4901-9309",
            "ProcScr_WorldMapFaceCtrl <- ProcScr_WorldMapMain 4907-9285",
            "ProcScr_WorldMapFaceHolder <- ProcScr_WorldMapFaceCtrl 4907-9285",
            "gProcScr_GmapMuEntry2 <- tree 3 4907-9285",
            "ProcScr_StdEventEngine <- tree 3 4907-9284",
            "ProcScr_GmapRM <- tree 3 4942-9287",
            "ProcScr_BmFadeIN <- tree 4 4944-5011",
            "ProcScr_GmapLineFade <- tree 3 4944-4998",
            "ProcScr_GmapRmUpdateDirect <- ProcScr_GmapRM 4948-9285",
            "gProcScr_Talk <- tree 3 5106-9284",
            "gProcScr_TalkSkipListener <- tree 3 5108-9284",
            "gProcScr_TalkWaitForInput <- gProcScr_Talk 5131-5161",
            "ProcScr_GmapRmUpdateExt <- tree 3 5436-5497",
            "gProcScr_E_FACE_ExtraFrame <- tree 5 5496-5688",
            "ProcScr_GmapRmBorder1 <- tree 3 5528-5670",
            "ProcScr_GmapRmBaPalAnim1 <- tree 3 5554-5648",
            "ProcScr_WmPlaceDot <- tree 3 8112-9279",
            "ProcScr_WmDotPalAnim <- tree 3 8112-9280",
            "ProcScr_GmapAutoMu <- tree 3 8174-8463",
            "ProcScr_GmapUnitFade <- tree 3 8185-8215",
            "ProcScr_FadeToBlack <- ProcScr_StdEventEngine 9253-9268",
        )

        val EXPECTED_ROOTS = setOf(
            "gProcScr_GameControl",
            "ProcScr_WorldMapMain",
            "ProcScr_GMapScreen",
            "ProcScr_GMapScreenVSync",
            "gProcScr_GmapCursor",
            "ProcScr_GmNodeIconDisplay",
            "gProcScr_GmapMuEntry2",
            "ProcScr_StdEventEngine",
            "ProcScr_GmapRM",
            "ProcScr_BmFadeIN",
            "ProcScr_GmapLineFade",
            "gProcScr_Talk",
            "gProcScr_TalkSkipListener",
            "ProcScr_GmapRmUpdateExt",
            "gProcScr_E_FACE_ExtraFrame",
            "ProcScr_GmapRmBorder1",
            "ProcScr_GmapRmBaPalAnim1",
            "ProcScr_WmPlaceDot",
            "ProcScr_WmDotPalAnim",
            "ProcScr_GmapAutoMu",
            "ProcScr_GmapUnitFade",
            "ProcScr_08591624",
        )

        val EXPECTED_SYMBOLS = EXPECTED_ROOTS + setOf(
            "ProcScr_WorldMapWrapper",
            "ProcScr_GmapUnit",
            "ProcScr_GMapMuPrim",
            "ProcScr_GMapMu",
            "ProcScr_GmapUnitContainer",
            "ProcScr_GMapRoute",
            "ProcScr_WorldMapTextHandler",
            "ProcScr_WorldMapFaceCtrl",
            "ProcScr_WorldMapFaceHolder",
            "ProcScr_GmapRmUpdateDirect",
            "gProcScr_TalkWaitForInput",
            "gProcScr_TalkLock",
            "ProcScr_TalkSpriteShiftClear",
            "ProcScr_0859160C",
            "ProcScr_FadeToBlack",
        )
    }
}
