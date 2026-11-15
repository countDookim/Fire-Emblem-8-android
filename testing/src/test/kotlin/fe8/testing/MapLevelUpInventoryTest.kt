package fe8.testing

import gba.testkit.RamSymbols
import gba.testkit.ReferenceData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** Inventory of the original Prologue map level-up display, not a port claim (MC-18i). */
class MapLevelUpInventoryTest {
    private val ref = ReferenceData.fromEnvironment()

    private fun capture(): ReferenceData.Run {
        assumeTrue(ref.available, "mGBA reference data not present")
        val run = ref.run("ram_trace_prologue_levelup")
        assertTrue(run.hasWatch("procs"), "required level-up capture or procs watch missing")
        assertTrue(ProcArrayDump.romNames().isNotEmpty(), "required symbol table missing")
        return run
    }

    @Test
    fun `level up display proc lifetimes and parents match original`() {
        val trace = capture().watch("procs")
        val names = ProcArrayDump.romNames()
        val relevant = setOf(
            "ProcScr_ManimLevelUp", "ProcScr_MapAnimExpBar", "ProcScr_MapAnimBattle",
            "ProcScr_ManimLevelUpStatGainLabel", "ProcScr_ManimLevelUpLabelColor",
            "ProcScr_ApProc", "sMusicProc2Script", "gProcScr_E_FACE",
        )
        val open = HashMap<Triple<Int, Int, Int>, IntArray>()
        val rows = ArrayList<Pair<IntArray, String>>()
        for (step in 6413..6746) {
            val at = ProcArrayDump.ofWatch(trace, step, names)
            for (proc in at.live.filter { it.name in relevant }) {
                val parent = proc.u32(0x14)
                val key = Triple(proc.slot, proc.script, parent)
                val previous = open[key]
                if (previous != null && previous[1] == step - 1) {
                    previous[1] = step
                    continue
                }
                val parentSlot = (parent - ProcArrayDump.ARRAY_ADDRESS) / ProcArrayDump.PROC_SIZE
                val parentName = if (parent in 0..ProcArrayDump.SLOTS) {
                    "tree $parent"
                } else {
                    at.live.firstOrNull { it.slot == parentSlot }?.name ?: "?"
                }
                val range = intArrayOf(step, step)
                open[key] = range
                rows += range to "${proc.name} <- $parentName"
            }
        }
        assertEquals(EXPECTED_ROWS.sorted(), rows.map { (range, label) ->
            "$label ${range[0]}-${range[1]}"
        }.sorted())
    }

    @Test
    fun `panel fields and cursor transitions match original`() {
        val trace = capture().watch("procs")
        val names = ProcArrayDump.romNames()
        fun panel(step: Int) = requireNotNull(
            ProcArrayDump.ofWatch(trace, step, names).find("ProcScr_ManimLevelUp"),
        )
        // Cursor ranges and field sequences are source-informed hypotheses checked against the watch.
        // include/mapanim.h: ManimLevelUpProc actor_id +0x2E, next_stat_num +0x30,
        // clock +0x31, and signed y_scroll_offset +0x32.
        val cursors = listOf(
            6413 to 2, 6414 to 5, 6431 to 7, 6501 to 9, 6502 to 11,
            6519 to 12, 6520 to 13, 6521 to 14, 6533 to 15, 6563 to 16,
            6669 to 17, 6729 to 19, 6730 to 20, 6742 to 23,
        )
        for ((index, entry) in cursors.withIndex()) {
            val end = cursors.getOrNull(index + 1)?.first?.minus(1) ?: 6745
            for (step in entry.first..end) assertEquals(entry.second, panel(step).scrCur, "f$step")
        }
        for (step in 6413..6745) assertEquals(0, panel(step).u16(0x2E), "actor f$step")
        for (step in 6519..6745) {
            val expectedY = when (step) {
                in 6519..6520 -> -144
                in 6521..6532 -> -144 + 8 * (step - 6520)
                in 6533..6729 -> -48
                in 6730..6741 -> -48 - 8 * (step - 6729)
                else -> -144
            }
            assertEquals(expectedY and 0xFFFF, panel(step).u16(0x32), "scroll f$step")
        }
        for (step in 6520..6728) {
            val at = ProcArrayDump.ofWatch(trace, step, names)
            val label = requireNotNull(at.find("ProcScr_ManimLevelUpStatGainLabel"))
            val color = requireNotNull(at.find("ProcScr_ManimLevelUpLabelColor"))
            // include/mapanim.h: label chr/pal/layer +0x2A/+0x2C/+0x2E;
            // color clock +0x54 and pal +0x64.
            assertEquals(listOf(512, 3, 1), listOf(label.u16(0x2A), label.u16(0x2C), label.u16(0x2E)))
            assertEquals(step - 6519, color.u32(0x54), "color clock f$step")
            assertEquals(3, color.u16(0x64), "color palette f$step")
        }
        for ((step, next) in listOf(6563 to 1, 6584 to 2, 6605 to 4, 6626 to 6, 6647 to 7)) {
            for (offset in 0..20) {
                assertEquals(next, panel(step + offset).u8(0x30))
                assertEquals(20 - offset, panel(step + offset).u8(0x31))
            }
        }
    }

    @Test
    fun `dump snapshots align with watched procs and expose changing display buffers`() {
        val run = capture()
        val trace = run.watch("procs")
        for (step in 6519..6745) {
            val dump = "%s%05d".format(if (step % 10 == 0) "f" else "r", step)
            assertEquals(step, run.dumps[dump], dump)
            val ewram = run.region(dump, "ewram")
            val start = ProcArrayDump.ARRAY_ADDRESS - 0x02000000
            assertTrue(trace.step(step).contentEquals(ewram.copyOfRange(start, start + trace.length)), dump)
        }
        fun bytes(step: Int, offset: Int, length: Int): List<Byte> {
            val dump = "%s%05d".format(if (step % 10 == 0) "f" else "r", step)
            val data = run.region(dump, "ewram")
            return data.copyOfRange(offset, offset + length).toList()
        }
        val symbols = RamSymbols.fromEnvironment()
        val bg0 = symbols["gBG0TilemapBuffer"].address - 0x02000000
        val bg1 = symbols["gBG1TilemapBuffer"].address - 0x02000000
        // Snapshots are post-step; these checks establish that real panel buffers change.
        assertNotEquals(bytes(6518, bg0, 0x800), bytes(6519, bg0, 0x800))
        assertNotEquals(bytes(6518, bg1, 0x800), bytes(6519, bg1, 0x800))
        assertFalse(bytes(6519, bg0, 0x800).all { it == 0.toByte() })
        for (step in listOf(6520, 6560, 6600, 6640, 6680, 6720)) {
            val dump = "f%05d".format(step)
            assertEquals(step, run.dumps[dump])
            for (region in listOf("vram", "pal", "oam", "io")) {
                assertTrue(run.region(dump, region).isNotEmpty(), "$dump $region")
            }
        }
    }

    private companion object {
        val EXPECTED_ROWS = listOf(
            "ProcScr_ManimLevelUp <- ProcScr_MapAnimExpBar 6413-6745",
            "ProcScr_MapAnimBattle <- tree 3 6413-6746",
            "ProcScr_MapAnimExpBar <- ProcScr_MapAnimBattle 6413-6746",
            "sMusicProc2Script <- ProcScr_ManimLevelUp 6414-6429",
            "ProcScr_ApProc <- tree 3 6431-6500",
            "sMusicProc2Script <- ProcScr_ManimLevelUp 6502-6517",
            "gProcScr_E_FACE <- tree 5 6519-6741",
            "ProcScr_ManimLevelUpStatGainLabel <- ProcScr_ManimLevelUp 6519-6728",
            "ProcScr_ManimLevelUpLabelColor <- ProcScr_ManimLevelUpStatGainLabel 6520-6728",
            "ProcScr_ApProc <- tree 3 6563-6584",
            "ProcScr_ApProc <- tree 3 6584-6728",
            "ProcScr_ApProc <- tree 3 6584-6605",
            "ProcScr_ApProc <- tree 3 6584-6728",
            "ProcScr_ApProc <- tree 3 6605-6728",
            "ProcScr_ApProc <- tree 3 6605-6728",
            "ProcScr_ApProc <- tree 3 6605-6626",
            "ProcScr_ApProc <- tree 3 6626-6728",
            "ProcScr_ApProc <- tree 3 6626-6647",
            "ProcScr_ApProc <- tree 3 6626-6728",
            "ProcScr_ApProc <- tree 3 6647-6728",
            "ProcScr_ApProc <- tree 3 6647-6728",
            "ProcScr_ApProc <- tree 3 6647-6668",
        )
    }
}
