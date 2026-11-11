package fe8.testing

import gba.testkit.ReferenceData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Inventory (not a port claim) of the proc tree alive during the Prologue chapter intro (chapter
 * title card, light effects, fade to the map) in `ram_trace_newgame_bm`, from the full EWRAM dumps
 * of frames [FIRST]-[LAST]. Each row is `script symbol <- parent`, where the parent is the script
 * symbol of the parent slot at the segment's first frame, or `tree N` for a root proc, followed by
 * the first and last frame of a contiguous run of one slot with one script and one parent.
 */
class ChapterIntroProcInventoryTest {
    private val ref = ReferenceData.fromEnvironment()

    private class Segment(val slot: Int, val script: Int, val parent: Int, val first: Int) {
        var last: Int = first
        var label: String = ""
    }

    private fun segments(): List<Segment> {
        val names = ProcArrayDump.romNames()
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        assumeTrue(names.isNotEmpty(), "decomp nm file not present")
        val run = ref.run("ram_trace_newgame_bm")
        val byFrame = HashMap<Int, String>()
        for ((dump, frame) in run.dumps) if (dump.startsWith("f")) byFrame[frame] = dump
        for ((dump, frame) in run.dumps) if (dump.startsWith("r")) byFrame[frame] = dump
        val open = HashMap<Triple<Int, Int, Int>, Segment>()
        val all = ArrayList<Segment>()
        for (frame in FIRST..LAST) {
            val dump = requireNotNull(byFrame[frame]) { "no dump for frame $frame" }
            val at = ProcArrayDump.ofEwram(run.region(dump, "ewram"), names)
            for (p in at.live) {
                val key = Triple(p.slot, p.script, p.u32(PARENT))
                val seg = open[key]
                if (seg != null && seg.last == frame - 1) {
                    seg.last = frame
                    continue
                }
                val parent = p.u32(PARENT)
                val parentSlot = (parent - ProcArrayDump.ARRAY_ADDRESS) / ProcArrayDump.PROC_SIZE
                val parentName = if (parent in 0..ProcArrayDump.SLOTS) {
                    "tree $parent"
                } else {
                    at.live.firstOrNull { it.slot == parentSlot }?.name ?: "?"
                }
                val created = Segment(p.slot, p.script, parent, frame)
                created.label = "${p.name} <- $parentName"
                open[key] = created
                all += created
            }
        }
        return all
    }

    @Test
    fun `chapter intro proc tree matches the newgame_bm dumps`() {
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
        const val FIRST = 9314
        const val LAST = 9540
        const val PARENT = 0x14

        val EXPECTED = listOf(
            "gProcScr_GameControl <- tree 3 9314-9540",
            "gProc_BMapMain <- tree 2 9319-9540",
            "sProc_BMVSync <- tree 0 9319-9540",
            "gProc_MapTask <- tree 4 9319-9512",
            "gProcScr_ChapterIntro <- gProc_BMapMain 9322-9506",
            "ProcScr_ChapterIntro_Bg1And3Scroll <- gProcScr_ChapterIntro 9325-9500",
            "ProcScr_ChapterIntro_KeyListen <- gProcScr_ChapterIntro 9385-9419",
            "ProcScr_StdEventEngine <- tree 3 9508-9540",
            "gProc_MapTask <- tree 4 9513-9540",
            "ProcScr_FadeFromBlack <- ProcScr_StdEventEngine 9523-9538",
        )

        val EXPECTED_ROOTS = setOf(
            "gProcScr_GameControl",
            "gProc_BMapMain",
            "sProc_BMVSync",
            "gProc_MapTask",
            "ProcScr_StdEventEngine",
        )

        val EXPECTED_SYMBOLS = EXPECTED_ROOTS + setOf(
            "gProcScr_ChapterIntro",
            "ProcScr_ChapterIntro_Bg1And3Scroll",
            "ProcScr_ChapterIntro_KeyListen",
            "ProcScr_FadeFromBlack",
        )
    }
}
