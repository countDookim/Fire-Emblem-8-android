package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BattleMapNodes
import fe8.game.bm.BmSt
import fe8.game.bm.PlaySt
import fe8.game.bm.UnitTable
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * [UnitTable] against mGBA RAM of reference run `ram_trace_first_phase` (chapter 0, turn 1).
 *
 * `ProcFun_ResetCursorPosition` (src/bmcamadjust.c) runs at the start of the player phase:
 * with no phase-able unit it ends without touching the cursor, otherwise it puts the cursor (and
 * `gPlaySt.xCursor/yCursor` on turn 1) on `GetUnitFromCharId(GetPlayerLeaderPid())`. The trace
 * shows the cursor jumping from its old tile onto Eirika's tile there, which pins the result of
 * both lookups. `GetPlayerLeaderPid` itself is only exercised on its story-map early return and is
 * deliberately not tagged.
 */
class UnitTableDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val run = "ram_trace_first_phase"
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    private fun frames(): List<Frame> {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        val rom = RomView.ofRom(romFile.readBytes())
        val r = ref.run(run)
        return r.dumps.entries
            .filter { File(ref.root, "$run/${it.key}.ewram.bin").isFile }
            .sortedBy { it.value }
            .map { (name, _) ->
                val ewram = r.region(name, "ewram")
                Frame(name, ewram, UnitTable(rom).also { it.loadFrom(ewram) })
            }
    }

    private val noNodes = object : BattleMapNodes {
        override fun chapterId(node: Int) = error("story map must not consult nodes")
        override fun isCleared(node: Int) = error("story map must not consult nodes")
        override fun encounters(node: Int) = error("story map must not consult nodes")
        override fun placementFlag(node: Int) = error("story map must not consult nodes")
    }

    @Test
    fun `load and store round-trip the unit arrays`() {
        val fs = frames()
        for (f in fs) {
            val copy = f.ewram.copyOf()
            val from = UnitTable.BLUE_ADDRESS - EWRAM
            copy.fill(0, from, from + UnitTable.TOTAL_SIZE)
            f.table.storeTo(copy)
            assertArrayEquals(f.ewram, copy, f.name)
        }
    }

    @Test
    fun `units decode as the chapter 0 roster`() {
        val f = frames().last()
        val t = f.table
        // Blue slot 0 is Seth (pid 2), slot 1 Eirika (pid 1); both carry their own id as index.
        assertEquals(2, t.charId(1))
        assertEquals(1, t.charId(2))
        assertEquals(1, t.index(1))
        assertEquals(2, t.index(2))
        assertEquals(0x81, t.index(0x81) and 0xFF)
        assertEquals(-1, t.unitOffset(0))
        assertEquals(-1, t.unitOffset(0x3F))
        assertEquals(-1, t.unitOffset(0xB3))
    }

    @Test
    @VerifiesPort("GetUnitFromCharId")
    fun `GetUnitFromCharId finds Eirika where the player phase puts the cursor`() {
        val fs = frames()
        // First dump of the player phase: ProcFun_ResetCursorPosition ran, cursor is on the leader.
        val start = fs.first { it.playerPhaseLive && it.playSt.faction == 0 }
        val t = start.table
        val eirika = t.getUnitFromCharId(1)
        assertTrue(eirika >= 0, "Eirika found")
        assertEquals(t.charId(eirika), 1)
        assertEquals(t.xPos(eirika), start.bmSt.playerCursorX, start.name)
        assertEquals(t.yPos(eirika), start.bmSt.playerCursorY, start.name)
        assertEquals(t.xPos(eirika), start.playSt.xCursor, start.name)
        assertEquals(t.yPos(eirika), start.playSt.yCursor, start.name)
        // The cursor was elsewhere when the faction switched to the player, so it was moved.
        val first = fs.first { it.playSt.faction == 0 && it.playSt.chapterTurnNumber == 1 }
        assertTrue(
            first.bmSt.playerCursorX != start.bmSt.playerCursorX ||
                first.bmSt.playerCursorY != start.bmSt.playerCursorY,
            "cursor moved onto the leader after ${first.name}",
        )
        // Seth is a different unit on a different tile: the result is not just any blue unit.
        val seth = t.getUnitFromCharId(2)
        assertNotEquals(eirika, seth)
        assertTrue(t.xPos(seth) != t.xPos(eirika) || t.yPos(seth) != t.yPos(eirika))
        // A character that is not on the map is not found.
        assertEquals(-1, t.getUnitFromCharId(0x7F))
    }

    @Test
    fun `GetPlayerLeaderPid is Eirika on the common route story map`() {
        val f = frames().first { it.playerPhaseLive && it.playSt.faction == 0 }
        assertEquals(1, f.playSt.chapterModeIndex)
        assertEquals(1, f.table.getPlayerLeaderPid(f.playSt, noNodes))
    }

    @Test
    @VerifiesPort("GetPhaseAbleUnitCount")
    fun `GetPhaseAbleUnitCount agrees with the cursor reset of each phase`() {
        val fs = frames()
        val start = fs.first { it.playerPhaseLive && it.playSt.faction == 0 }
        // Player phase: a non-zero count lets ProcFun_ResetCursorPosition run (Seth and Eirika).
        assertEquals(2, start.table.getPhaseAbleUnitCount(0x00), start.name)
        // The NPC phase right before had no green units, so it ended without moving the cursor.
        var npcDumps = 0
        for (f in fs) {
            if (f.playSt.faction != 0x40) continue
            assertEquals(0, f.table.getPhaseAbleUnitCount(0x40), f.name)
            npcDumps++
        }
        assertTrue(npcDumps > 0, "no NPC phase dump")
        // The player phase stays alive in every later dump: the game never found 0 able units.
        for (f in fs.dropWhile { it !== start }) {
            assertTrue(f.playerPhaseLive, f.name)
            assertTrue(f.table.getPhaseAbleUnitCount(0x00) > 0, f.name)
        }
    }

    private companion object {
        const val EWRAM = 0x02000000
        const val PLAY_ST = 0x0202BCF0
        const val BM_ST = 0x0202BCB0
        const val PLAYER_PHASE = 0x0859AAD8
    }
}

/** A dump in frame order with everything decoded (below the test class for tools/progress.py). */
private class Frame(val name: String, val ewram: ByteArray, val table: UnitTable) {
    val playSt = PlaySt().also { it.decode(ewram, 0x0202BCF0 - 0x02000000) }
    val bmSt = BmSt().also { it.decode(ewram, 0x0202BCB0 - 0x02000000) }
    val playerPhaseLive = ProcArrayDump.ofEwram(ewram).find(0x0859AAD8) != null
}
