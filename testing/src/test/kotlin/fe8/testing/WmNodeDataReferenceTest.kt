package fe8.testing

import fe8.game.RomView
import fe8.game.flags.Fe8EventFlags
import fe8.game.worldmap.WmNodeData
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Verifies [WmNodeData.chapterId] and [WmNodeData.nextLocId]: against the `ram_trace_prologue`
 * world-map state, and (second test) for every node against the decomp source
 * `src/worldmap_node_data.c` (parsed at test time, not ROM-derived).
 */
class WmNodeDataReferenceTest {
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )
    private val ref = ReferenceData.fromEnvironment()
    private val decomp = File(System.getenv("FE8U_DECOMP") ?: "/home/claude/fe8u")

    private class Node(val eirika: Int, val ephraim: Int, val flag: Int, val next: List<Int>)

    @Test
    @VerifiesPort("WMLoc_GetChapterId", "WMLoc_GetNextLocId")
    fun `prologue world map next node and chapter match the trace`() {
        assumeTrue(ref.available && romFile.isFile, "ROM / reference data not present")
        val run = ref.run("ram_trace_prologue")
        val playSt = run.watch("gplayst")
        val flagTrace = run.watch("flags")
        val data = WmNodeData(RomView.ofRom(romFile.readBytes()))
        // Dumps either side of the opening world map (steps 4898..9313): the intro node is
        // gGMData.current_node (IWRAM 0x03005280 + 0xC8) with nodes[] (+0x30) state bit 2 set.
        for (step in listOf(4860, 4890)) {
            val iwram = run.region("r%05d".format(step), "iwram")
            val current = iwram[GM_DATA + CURRENT_NODE].toInt() and 0xFF
            val mode = playSt.u8(step, PLAY_ST_MODE)
            val flags = Fe8EventFlags.create().also { it.copyIn(flagTrace.step(step)) }
            val next = data.nextLocId(current, mode) { flags.checkFlag(it) }
            assertEquals(current, next, "next node of the current node, step $step")
            assertEquals(2, iwram[GM_DATA + NODES + next].toInt() and 2, "node state, step $step")
            assertEquals(
                playSt.u8(step, PLAY_ST_CHAPTER),
                data.chapterId(next, mode),
                "chapter of the node, step $step",
            )
        }
    }

    @Test
    fun `chapter ids and next locations match decomp node table for all nodes`() {
        val source = File(decomp, "src/worldmap_node_data.c")
        val chapters = File(decomp, "include/constants/chapters.h")
        assumeTrue(romFile.isFile && source.isFile && chapters.isFile, "ROM / decomp not present")
        val ids = Regex("""(CHAPTER_\w+)\s*=\s*(0x[0-9A-Fa-f]+|\d+)""")
            .findAll(chapters.readText())
            .associate { it.groupValues[1] to java.lang.Integer.decode(it.groupValues[2]) }
        val nodes = parse(source.readText(), ids)
        assertEquals(WmNodeData.NODE_COUNT, nodes.size)
        val data = WmNodeData(RomView.ofRom(romFile.readBytes()))

        nodes.forEachIndexed { idx, n ->
            assertEquals(n.eirika, data.chapterId(idx, 2), "eirika chapter $idx")
            assertEquals(n.eirika, data.chapterId(idx, 1), "default chapter $idx")
            assertEquals(n.ephraim, data.chapterId(idx, 3), "ephraim chapter $idx")
            for (set in listOf(false, true)) {
                val shift = if (set) 2 else 0
                var seen = -1
                val eirika = data.nextLocId(idx, 2) { seen = it; set }
                assertEquals(n.flag, seen, "flag id $idx")
                assertEquals(n.next[shift].toByte().toInt(), eirika, "eirika next $idx set=$set")
                assertEquals(
                    n.next[shift + 1].toByte().toInt(),
                    data.nextLocId(idx, 3) { set },
                    "ephraim next $idx set=$set",
                )
            }
        }
        // Prologue world map uses node 0 (Border Mulan) before its clear flag is set.
        assertEquals(0, data.nextLocId(0, 2) { false })
    }

    private fun parse(text: String, ids: Map<String, Int>): List<Node> {
        val start = text.indexOf("gWMNodeData[] =")
        val body = text.substring(start)
        fun field(block: String, name: String) =
            Regex("""\.$name\s*=\s*([^,]+),""").find(block)!!.groupValues[1].trim()
        return body.split(Regex("""\[NODE_\w+\]\s*=""")).drop(1).map { block ->
            val next = Regex("""\.unk_08\s*=\s*\{([^}]*)\}""").find(block)!!.groupValues[1]
                .split(',').map { it.trim() }.filter { it.isNotEmpty() }
                .map { java.lang.Integer.decode(it) }
            Node(
                ids.getValue(field(block, "chapteridx_eirika")),
                ids.getValue(field(block, "chapteridx_ephram")),
                java.lang.Integer.decode(field(block, "unk_06")),
                next,
            )
        }
    }

    private companion object {
        const val GM_DATA = 0x5280
        const val CURRENT_NODE = 0xC8
        const val NODES = 0x30
        const val PLAY_ST_CHAPTER = 0x0E
        const val PLAY_ST_MODE = 0x1B
    }
}
