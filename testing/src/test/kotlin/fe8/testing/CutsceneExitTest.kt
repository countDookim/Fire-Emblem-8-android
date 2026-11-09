package fe8.testing

import fe8.game.RomView
import fe8.game.bm.UnitTable
import gba.testkit.GbaMem
import gba.testkit.ReferenceData
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** The upper-left exits, compared to original snapshots f13800 and f15600. */
class CutsceneExitTest {
    @Test
    fun `removed riders do not leave a map sprite on their exit tile`() {
        val assets = File(System.getenv("FE8_ASSETS") ?: ".assets")
        val romFile = File(assets, "baserom.gba")
        val ref = ReferenceData.fromEnvironment()
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val rom = RomView.ofRom(romFile.readBytes())
        val run = ref.run("ram_trace_prologue")
        for ((frame, pid) in listOf(13800 to 4, 15600 to 2)) {
            val name = "f%05d".format(frame)
            val units = UnitTable(rom).also { it.loadFrom(run.region(name, "ewram")) }
            assertTrue((1..0xff).none { units.isValid(it) && units.charId(it) == pid })
            val oam = run.region(name, "oam")
            assertEquals(0, exitSprites { GbaMem.u16(oam, 0, it * 2) }, "$name original")
        }
        val pb = PrologueBoot(romFile)
        val lastPositions = mutableMapOf<Int, Pair<Int, Int>>()
        val removed = mutableSetOf<Int>()
        val checked = mutableMapOf(2 to 0, 4 to 0)
        var observedFrames = 0
        var framesSinceRemoval = 0
        pb.onFrame = {
            val table = pb.boot.units
            for (pid in listOf(4, 2)) {
                val id = (1..0xff).firstOrNull { table.isValid(it) && table.charId(it) == pid }
                if (id != null) {
                    lastPositions[pid] = table.xPos(id) to table.yPos(id)
                } else if (lastPositions[pid] == (0 to 2)) {
                    if (removed.add(pid)) framesSinceRemoval = 0
                }
            }
            // OAM is synced separately from the event update, so allow two frames to flush.
            if (removed.isNotEmpty()) framesSinceRemoval++
            if (framesSinceRemoval > 2 && pb.boot.loop.bmSt.cameraX == 0 &&
                pb.boot.loop.bmSt.cameraY == 0 &&
                (1..0xff).none {
                    table.isValid(it) && table.xPos(it) == 0 && table.yPos(it) == 2
                }
            ) {
                val oam = pb.boot.loop.oam.oam
                assertEquals(0, exitSprites { oam[it].toInt() and 0xffff }, "removed $removed")
                observedFrames++
                for (pid in removed) {
                    if ((1..0xff).none { table.isValid(it) && table.charId(it) == pid }) {
                        checked[pid] = checked.getValue(pid) + 1
                    }
                }
            }
        }
        assertTrue(pb.reachIdle())
        assertEquals(setOf(2, 4), removed)
        assertTrue(observedFrames > 100, "only $observedFrames frames checked")
        for ((pid, count) in checked) assertTrue(count > 20, "unit $pid: $count frames checked")
    }

    private fun exitSprites(word: (Int) -> Int): Int = (0 until 128).count {
        val a0 = word(it * 4)
        val a1 = word(it * 4 + 1)
        val a2 = word(it * 4 + 2)
        a0 and 0x300 != 0x200 && a0 and 0xff == 16 && a1 and 0x1ff == 0 &&
            a2 and 0x3ff in 128..511
    }
}
