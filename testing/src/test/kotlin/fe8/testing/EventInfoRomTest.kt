package fe8.testing

import fe8.game.RomView
import fe8.game.bm.ChapterData
import fe8.game.bm.PlaySt
import fe8.game.event.EventInfo
import fe8.game.event.EventInfos
import fe8.game.event.ScriptMemory
import fe8.game.flags.Fe8EventFlags
import gba.core.VerifiesPort
import gbafe.core.flags.EventFlags
import gba.testkit.GbaMem
import gba.testkit.ReferenceData
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * [EventInfos] on the turn event list of chapter 0 (`PrologueEvents.turnBasedEvents`, ROM
 * 0x089E863C -> 0x089E858C):
 * - ground truth: `ram_trace_first_phase` frames 19300..19340. The game ran `RunPhaseSwitchEvents`
 *   around f19312/f19313 (turn 0 -> turn 1, faction 64 -> 0) and started no event: no
 *   StdEventEngine proc appears and the chapter flags stay unchanged, i.e. `SearchAvailableEvent`
 *   returned NULL. Our search over the real list with the PlaySt and flags of every dumped frame
 *   must return null too (walks four EvCheck02 rejections, then EvCheck00 as terminator).
 * - ROM golden values: script pointers decoded by hand from the raw list words for turn 1 / 2.
 * StartEventFromInfo, EvCheck01 and EvCheck0C/0D are not exercised by any trace or this list.
 */
class EventInfoRomTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    private fun infos(rom: ByteArray, ps: PlaySt, flags: EventFlags = Fe8EventFlags.create()) =
        EventInfos(
            ScriptMemory { a ->
                val o = a - 0x08000000
                (rom[o].toInt() and 0xFF) or ((rom[o + 1].toInt() and 0xFF) shl 8)
            },
            flags,
            ps,
        )

    private fun turnList(rom: ByteArray): Int {
        val chapters = ChapterData(RomView.ofRom(rom))
        val group = chapters.getChapterEventDataPointer(0)
        assertEquals(0x089E863C, group)
        val o = group - 0x08000000
        return (0 until 4).sumOf { (rom[o + it].toInt() and 0xFF) shl (8 * it) }
    }

    @Test
    @VerifiesPort("SearchAvailableEvent", "EvCheck00_Always", "EvCheck02_TURN")
    fun `turn list search finds no event at the first player phase of the trace`() {
        assumeTrue(ref.available && romFile.isFile, "ROM / mGBA reference data not present")
        val rom = romFile.readBytes()
        val list = turnList(rom)
        assertEquals(0x089E858C, list)
        val run = ref.run("ram_trace_first_phase")
        val names = ProcArrayDump.romNames()
        val ps = PlaySt()
        val flags = Fe8EventFlags.create()
        val bytes = ByteArray(EventTrace.FLAGS_SIZE)
        val search = infos(rom, ps, flags)
        var checked = 0
        var sawTurn1 = false
        for (f in 19300..19340) {
            val ew = run.region("r$f", "ewram")
            val iw = run.region("r$f", "iwram")
            ps.decode(ew, 0x0202BCF0 - GbaMem.EWRAM)
            iw.copyInto(bytes, 0, EventTrace.FLAGS_ADDRESS - GbaMem.IWRAM, 0x30 +
                EventTrace.FLAGS_ADDRESS - GbaMem.IWRAM)
            flags.copyIn(bytes)
            val engine = ProcArrayDump.ofEwram(ew, names).find(EventTrace.STD_EVENT_ENGINE)
            val info = EventInfo().also { it.listScript = list }
            val found = search.searchAvailableEvent(info)
            if (f >= 19313) {
                assertNull(engine, "engine proc at f$f: the game started an event")
                assertNull(found, "f$f turn=${ps.chapterTurnNumber} faction=${ps.faction}")
                assertEquals(0, info.script)
                assertEquals(0, info.flag)
                sawTurn1 = sawTurn1 || ps.chapterTurnNumber == 1
            }
            checked++
        }
        assertTrue(sawTurn1 && checked == 41)
    }

    @Test
    fun `turn list script pointers match the raw ROM words`() {
        assumeTrue(romFile.isFile, "ROM not present")
        val rom = romFile.readBytes()
        val list = turnList(rom)
        val ps = PlaySt()
        val flags = Fe8EventFlags.create()
        val search = infos(rom, ps, flags)
        fun walk(turn: Int, faction: Int): List<Pair<Int, Int>> {
            ps.chapterTurnNumber = turn
            ps.faction = faction
            val out = mutableListOf<Pair<Int, Int>>()
            var r = search.searchAvailableEvent(EventInfo().also { it.listScript = list })
            while (r != null) {
                out += r.script to r.flag
                r = search.searchNextAvailableEvent(r)
            }
            return out
        }
        // Entries: (turn 1, faction 0x80) 0x089EF214; (turn 2, fac 0) 0x089EF23C;
        // (turn 3, fac 0) 0x089EF254; flag 8 (turns 1..0xFF, fac 0x80) 0x089EF12C.
        assertEquals(listOf(0x089EF214 to 0, 0x089EF12C to 8), walk(1, 0x80))
        assertEquals(listOf(0x089EF23C to 0), walk(2, 0))
        assertEquals(listOf(0x089EF254 to 0), walk(3, 0))
        assertEquals(emptyList<Pair<Int, Int>>(), walk(1, 0))
        flags.setFlag(8)
        assertEquals(listOf(0x089EF214 to 0), walk(1, 0x80))
        assertEquals(emptyList<Pair<Int, Int>>(), walk(2, 0x80))
    }
}
