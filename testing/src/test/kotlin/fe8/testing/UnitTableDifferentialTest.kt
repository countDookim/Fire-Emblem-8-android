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
    @VerifiesPort("GetUnit")
    fun `unitOffset matches gUnitLookup table in ROM for all 256 unit ids`() {
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        val romView = RomView.ofRom(romBytes())
        val t = UnitTable(romView)
        for (id in 0..255) {
            val ptr = romView.u32(0x0859A5D0 + 4 * id)
            val expectedOffset = if (ptr == 0) -1 else (ptr - UnitTable.BLUE_ADDRESS)
            assertEquals(expectedOffset, t.unitOffset(id), "id $id")
        }
        assertEquals(t.unitOffset(1), t.unitOffset(0x101))
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

        // Several characters across the runs, the expected ids derived from the dumped structs.
        val seen = HashSet<Int>()
        var missing = 0
        forEachUnitDump { name, ewram, table ->
            val oracle = Oracle(romBytes(), ewram)
            for (charId in oracle.charIds() + listOf(0, 0x7F, 0xFF)) {
                val expected = oracle.firstUnitWithChar(charId)
                assertEquals(expected, table.getUnitFromCharId(charId), "$name char $charId")
                if (expected < 0) missing++ else seen += charId
            }
        }
        assertTrue(seen.size >= 3, "characters looked up: $seen")
        assertTrue(missing > 0, "no missing character looked up")
    }

    @Test
    fun `GetPlayerLeaderPid is Eirika on the common route story map`() {
        val f = frames().first { it.playerPhaseLive && it.playSt.faction == 0 }
        assertEquals(1, f.playSt.chapterModeIndex)
        assertEquals(1, f.table.getPlayerLeaderPid(f.playSt, noNodes))
    }

    // Not @VerifiesPort: no dumped unit is asleep, berserk, unselectable or in a NOT_ABLE state, so
    // the exclusion branches of GetPhaseAbleUnitCount are never exercised (breaking them passes).
    @Test
    fun `GetPhaseAbleUnitCount matches counts from the dumped unit structs`() {
        val fs = frames()
        val start = fs.first { it.playerPhaseLive && it.playSt.faction == 0 }
        // Player phase: a non-zero count lets ProcFun_ResetCursorPosition run (Seth and Eirika).
        assertEquals(2, start.table.getPhaseAbleUnitCount(0x00), start.name)
        // The player phase stays alive in every later dump: the game never found 0 able units.
        for (f in fs.dropWhile { it !== start }) {
            assertTrue(f.playerPhaseLive, f.name)
            assertTrue(f.table.getPhaseAbleUnitCount(0x00) > 0, f.name)
        }
        // All three factions in many dumps against counts computed from the raw structs per
        // src/bmphase.c (gUnitLookup and the unit fields read from the dump, not from UnitTable).
        val counts = HashMap<Int, MutableSet<Int>>()
        forEachUnitDump { name, ewram, table ->
            val oracle = Oracle(romBytes(), ewram)
            for (faction in intArrayOf(0x00, 0x40, 0x80)) {
                val expected = oracle.phaseAbleCount(faction)
                assertEquals(expected, table.getPhaseAbleUnitCount(faction), "$name $faction")
                counts.getOrPut(faction) { HashSet() } += expected
            }
        }
        assertTrue(counts.getValue(0x00).any { it > 0 }, "no player units: $counts")
        assertTrue(counts.getValue(0x80).any { it > 0 }, "no enemy units: $counts")
        assertTrue(counts.getValue(0x00).size > 1, "player count never changes: $counts")
    }

    private fun romBytes(): ByteArray = cachedRom ?: romFile.readBytes().also { cachedRom = it }
    private var cachedRom: ByteArray? = null

    /** Runs [block] on every EWRAM dump of the runs that have units on the map. */
    private fun forEachUnitDump(block: (String, ByteArray, UnitTable) -> Unit) {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        val rom = RomView.ofRom(romBytes())
        var dumps = 0
        val runs = listOf("ram_trace_first_phase", "ram_trace_newgame_bm", "ram_trace_prologue")
        for (name in runs) {
            val r = ref.run(name)
            for (dump in r.dumps.keys) {
                if (!File(ref.root, "$name/$dump.ewram.bin").isFile) continue
                val ewram = r.region(dump, "ewram")
                if (ewram.size < UnitTable.BLUE_ADDRESS - EWRAM + UnitTable.TOTAL_SIZE) continue
                block("$name/$dump", ewram, UnitTable(rom).also { it.loadFrom(ewram) })
                dumps++
            }
        }
        assertTrue(dumps > 100, "dumps checked: $dumps")
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

/**
 * Independent reading of the dumped unit structs and the ROM-resident `gUnitLookup`
 * (0x0859A5D0, 256 pointers) following src/bmunit.c and src/bmphase.c.
 */
private class Oracle(private val rom: ByteArray, private val ewram: ByteArray) {
    private fun romU8(at: Int) = rom[at - 0x08000000].toInt() and 0xFF
    private fun romU32(at: Int) =
        romU8(at) or (romU8(at + 1) shl 8) or (romU8(at + 2) shl 16) or (romU8(at + 3) shl 24)
    private fun u8(at: Int) = ewram[at - 0x02000000].toInt() and 0xFF
    private fun u32(at: Int) =
        u8(at) or (u8(at + 1) shl 8) or (u8(at + 2) shl 16) or (u8(at + 3) shl 24)

    /** The address of the unit struct with id [id], or 0. */
    private fun unit(id: Int) = romU32(0x0859A5D0 + 4 * (id and 0xFF))

    private fun valid(id: Int) = unit(id) != 0 && u32(unit(id)) != 0

    /** `pCharacterData->number` of unit [id]. */
    private fun charOf(id: Int) = romU8(u32(unit(id)) + 4)

    /** Every character number of a valid unit. */
    fun charIds(): Set<Int> = (1 until 0x100).filter { valid(it) }.map { charOf(it) }.toSet()

    fun firstUnitWithChar(charId: Int): Int =
        (1 until 0x100).firstOrNull { valid(it) && charOf(it) == charId } ?: -1

    fun phaseAbleCount(faction: Int): Int {
        var count = 0
        for (id in faction + 1 until faction + 0x40) {
            if (!valid(id)) continue
            val u = unit(id)
            val state = u32(u + 0x0C)
            // US_UNSELECTABLE | US_DEAD | US_NOT_DEPLOYED | US_RESCUED | US_UNDER_A_ROOF | US_BIT16
            if (state and (0x2 or 0x4 or 0x8 or 0x20 or 0x80 or 0x10000) != 0) continue
            val status = u8(u + 0x30) and 0xF
            if (status == 2 || status == 4) continue
            val attributes = romU32(u32(u) + 0x28) or romU32(u32(u + 4) + 0x28)
            if (attributes and (1 shl 20) != 0) continue
            count++
        }
        return count
    }
}
