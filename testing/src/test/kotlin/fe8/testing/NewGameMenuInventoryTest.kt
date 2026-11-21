package fe8.testing

import gba.testkit.ReferenceData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** Original New Game menu snapshots; this inventories capture coverage and claims no port. */
class NewGameMenuInventoryTest {
    private val ref = ReferenceData.fromEnvironment()

    private fun requireReference() {
        if (System.getenv("FE8_ASSETS") == null) {
            assumeTrue(ref.available, "reference environment absent")
        }
        check(ref.available) { "configured reference environment is incomplete" }
    }

    @Test
    fun `dense new game capture records difficulty and slot selection through save`() {
        requireReference()
        val names = ProcArrayDump.romNames()
        check(names.isNotEmpty()) { "reference environment has no ROM symbol table" }
        val run = ref.run("ram_trace_newgame")
        for (frame in 955..1230) {
            val dump = run.dumps.entries.single { it.value == frame && it.key.first() in "rf" }.key
            assertEquals(0x40000, run.region(dump, "ewram").size)
            assertEquals(0x8000, run.region(dump, "iwram").size)
        }
        fun at(frame: Int): ProcArrayDump {
            val dump = run.dumps.entries.single { it.value == frame && it.key.first() in "rf" }.key
            return ProcArrayDump.ofEwram(run.region(dump, "ewram"), names)
        }
        assertNull(at(981).find("ProcScr_NewGameDifficultySelect"))
        for (frame in 982..1087) {
            val tree = at(frame)
            val menu = requireNotNull(tree.find("ProcScr_SaveMenu"))
            val difficulty = requireNotNull(tree.find("ProcScr_NewGameDifficultySelect"))
            assertEquals(menu.address, difficulty.u32(0x14))
            if (frame >= 984) {
                val sprites = requireNotNull(tree.find("gProcScr_DrawDifficultyMenuSprites"))
                assertEquals(sprites.address, difficulty.u32(0x34))
            }
        }
        assertNull(at(1088).find("ProcScr_NewGameDifficultySelect"))
        assertEquals(0, at(1029).find("ProcScr_NewGameDifficultySelect")!!.u8(0x30))
        assertEquals(1, at(1030).find("ProcScr_NewGameDifficultySelect")!!.u8(0x30))
        assertEquals(1, at(1060).find("ProcScr_SaveMenu")!!.u8(0x2a))
        assertEquals(0, at(1129).find("ProcScr_SaveMenu")!!.u8(0x2c))
        assertEquals(0x80, run.inputs[1029])
        assertEquals(1, run.inputs[1059])
        assertEquals(1, run.inputs[1129])
        assertNotEquals(run.sram("s01128").toList(), run.sram("s01132").toList())
        assertEquals(32768, run.sram("s01132").size)
    }

    @Test
    fun `sparse menu screen snapshots include difficulty and restored slot menu`() {
        requireReference()
        val names = ProcArrayDump.romNames()
        check(names.isNotEmpty()) { "reference environment has no ROM symbol table" }
        val run = ref.run("menu_screens")
        assertEquals(setOf("m0962", "m1024", "m1086", "m1087"), run.dumps.keys)
        for (dump in run.dumps.keys) {
            for (region in listOf("ewram", "iwram", "vram", "pal", "oam", "io")) {
                assertFalse(run.region(dump, region).isEmpty(), "$dump $region")
            }
            val tree = ProcArrayDump.ofEwram(run.region(dump, "ewram"), names)
            assertNotNull(tree.find("ProcScr_SaveMenu"))
            assertNotNull(tree.find("ProcScr_savedraw"))
            if (dump == "m1024") {
                assertEquals(0, tree.find("ProcScr_NewGameDifficultySelect")!!.u8(0x30))
                assertNotNull(tree.find("gProcScr_DrawDifficultyMenuSprites"))
            } else {
                assertNull(tree.find("ProcScr_NewGameDifficultySelect"))
            }
        }
    }
}
