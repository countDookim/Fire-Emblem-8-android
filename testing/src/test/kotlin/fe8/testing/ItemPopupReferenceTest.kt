package fe8.testing

import gba.testkit.ReferenceData
import java.io.File
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Verifies the provenance and input/RAM equivalence of `ram_trace_item_popup` vs
 * `ram_trace_prologue`.
 */
class ItemPopupReferenceTest {
    private val ref = ReferenceData.fromEnvironment()

    @Test
    fun `item popup input timeline matches prologue prefix`() {
        assumeTrue(ref.available, "mGBA reference data not present")
        val popup = ref.run("ram_trace_item_popup")
        val prologue = ref.run("ram_trace_prologue")

        val popupInputs = popup.inputs
        val prologueInputs = prologue.inputs
        assertTrue(popupInputs.isNotEmpty(), "popup inputs empty")
        assertTrue(prologueInputs.size >= popupInputs.size, "prologue inputs shorter than popup")

        val expected = prologueInputs.copyOf(popupInputs.size)
        assertArrayEquals(expected, popupInputs, "input timeline mismatch vs prologue prefix")
    }

    @Test
    fun `item popup overlapping ram dumps match prologue byte-for-byte`() {
        assumeTrue(ref.available, "mGBA reference data not present")
        val popup = ref.run("ram_trace_item_popup")
        val prologue = ref.run("ram_trace_prologue")

        val framePairs = listOf(
            "f18630" to "r18630",
            "f18660" to "r18660",
            "f18690" to "r18690",
            "f18720" to "r18720",
            "r18750" to "r18750",
        )

        for ((popupDump, prologueDump) in framePairs) {
            val popupEwram = popup.region(popupDump, "ewram")
            val prologueEwram = prologue.region(prologueDump, "ewram")
            assertArrayEquals(prologueEwram, popupEwram, "EWRAM mismatch at $popupDump vs $prologueDump")

            val popupIwram = popup.region(popupDump, "iwram")
            val prologueIwram = prologue.region(prologueDump, "iwram")
            assertArrayEquals(prologueIwram, popupIwram, "IWRAM mismatch at $popupDump vs $prologueDump")
        }
    }

    @Test
    fun `provenance records ram_trace_item_popup script`() {
        assumeTrue(ref.available, "mGBA reference data not present")
        val provenanceFile = File(ref.root, "PROVENANCE.sha1")
        assumeTrue(provenanceFile.isFile, "PROVENANCE.sha1 missing")
        val lines = provenanceFile.readLines()
        assertTrue(
            lines.any { it.contains("reference/scripts/ram_trace_item_popup.txt") },
            "PROVENANCE.sha1 does not mention ram_trace_item_popup.txt",
        )
    }
}
