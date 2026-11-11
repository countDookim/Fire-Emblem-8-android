package fe8.testing

import fe8.game.RomView
import fe8.game.bm.UnitTable
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Event commands 0x2F (MOVEUNIT) and 0x30 (ENUN) with the MU walk of `MuCtrl`, against
 * `ram_trace_first_phase` r19400: the headless New Game flow runs the opening events (17 moves,
 * 23 ENUN) and the blue units must end on the tiles the original left them on. Scope: end
 * tiles only; the per-frame MU positions, the RNG draws of the path tie-breaks and the walk
 * timing are not compared.
 */
class MoveUnitDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()

    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    @VerifiesPort("Event2F_MoveUnit", "Event30_ENUN", "GetUnitStructFromEventParameter")
    fun `blue units end on the tiles of the r19400 dump after the opening moves`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val rom = RomView.ofRom(romFile.readBytes())
        val dump = ref.run("ram_trace_first_phase").region("r19400", "ewram")
        val want = UnitTable(rom).also { it.loadFrom(dump) }
        val pb = PrologueBoot(romFile)
        assertTrue(pb.reachIdle(), "Prologue idle not reached")
        val got = pb.boot.units
        fun blue(t: UnitTable) = (1..0x3E).filter { t.isValid(it) }
            .associate { t.charId(it) to (t.xPos(it) to t.yPos(it)) }
        val expected = blue(want)
        assertTrue(expected.size >= 2, "no blue units in the dump")
        for ((char, pos) in expected) assertEquals(pos, blue(got)[char], "char $char")
        assertEquals(false, pb.boot.eventEngine.eventEngineExists())
    }

    /** `index` (0x0B) gives UNIT_FACTION and so the sprite palette; it must match r19400. */
    @Test
    fun `every unit at the idle map has the index byte of the r19400 dump`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val rom = RomView.ofRom(romFile.readBytes())
        val dump = ref.run("ram_trace_first_phase").region("r19400", "ewram")
        val want = UnitTable(rom).also { it.loadFrom(dump) }
        val pb = PrologueBoot(romFile)
        assertTrue(pb.reachIdle(), "Prologue idle not reached")
        val got = pb.boot.units
        val ids = (1..0xFF).filter { want.isValid(it) }
        assertTrue(ids.any { it < 0x40 } && ids.any { it >= 0x80 }, "dump lacks blue or red")
        for (id in ids) {
            assertTrue(got.isValid(id), "unit 0x${id.toString(16)}")
            assertEquals(want.index(id), got.index(id), "index of 0x${id.toString(16)}")
        }
    }
}
